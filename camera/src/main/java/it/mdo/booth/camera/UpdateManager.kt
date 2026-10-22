package it.mdo.booth.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Aggiornamento OTA: legge l'ultima release GitHub (tag "v<versionCode>"),
 * scarica l'APK di questa app e apre l'installazione.
 */
class UpdateManager(
    private val ctx: Context,
    private val assetName: String,
    private val token: String = "",
    private val beforeInstall: () -> Unit = {},
) {
    data class Status(val text: String, val canInstall: Boolean = false, val busy: Boolean = false)

    private data class Found(val code: Int, val name: String, val url: String)

    private fun HttpURLConnection.auth() {
        if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
    }

    val status = MutableStateFlow(Status("Versione ${installedName()} (${installedCode()})"))
    @Volatile var listener: ((Status) -> Unit)? = null
    @Volatile private var found: Found? = null

    private fun set(s: Status) {
        status.value = s
        listener?.invoke(s)
    }

    fun installedCode(): Int {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else @Suppress("DEPRECATION") p.versionCode
    }

    fun installedName(): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    fun check() {
        if (status.value.busy) return
        set(Status("Controllo aggiornamenti…", busy = true))
        thread {
            val f = try { fetch() } catch (e: Exception) { null }
            if (f == null) {
                set(Status("Impossibile controllare gli aggiornamenti"))
            } else if (f.code > installedCode()) {
                found = f
                set(Status("Disponibile: ${f.name} (installata ${installedName()})", canInstall = true))
                notifyAvailable(f)
            } else {
                set(Status("Aggiornata: versione ${installedName()}"))
            }
        }
    }

    /** Controlla da solo ogni 6 ore (la prima volta dopo 1 minuto), così la notifica arriva anche a app aperta a lungo. */
    fun startAutoCheck() {
        if (autoStarted) return
        autoStarted = true
        thread(isDaemon = true) {
            Thread.sleep(60_000)
            while (true) {
                runCatching { check() }
                Thread.sleep(6 * 60 * 60 * 1000L)
            }
        }
    }

    @Volatile private var autoStarted = false

    /** Notifica "aggiornamento disponibile": una sola volta per versione, solo se le notifiche sono consentite. */
    private fun notifyAvailable(f: Found) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val prefs = ctx.getSharedPreferences("update_notify", Context.MODE_PRIVATE)
            if (prefs.getInt("last", 0) >= f.code) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("update", "Aggiornamenti", NotificationManager.IMPORTANCE_DEFAULT))
            val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            val pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, "update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Aggiornamento disponibile")
                .setContentText("${f.name} · apri l'app e tocca Installa")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(4242, n)
            prefs.edit().putInt("last", f.code).apply()
        } catch (e: Exception) {
        }
    }

    fun installNow() {
        val f = found ?: return
        if (status.value.busy) return
        set(Status("Scarico… 0%", busy = true))
        thread {
            val dir = File(ctx.cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val apk = File(dir, assetName)
            val ok = try { download(f.url, apk) } catch (e: Exception) { false }
            if (!ok) {
                set(Status("Download non riuscito", canInstall = true))
            } else {
                install(apk)
            }
        }
    }

    private fun fetch(): Found? {
        val c = URL("https://api.github.com/repos/mdo-video/Booth/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.auth()
        try {
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val code = j.getString("tag_name").removePrefix("v").toIntOrNull() ?: return null
            val assets = j.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == assetName) {
                    val url = if (token.isNotEmpty()) a.getString("url") else a.getString("browser_download_url")
                    return Found(code, j.optString("name", "v$code"), url)
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, dest: File): Boolean {
        var c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Accept", "application/octet-stream")
        c.auth()
        // repo privato: GitHub risponde con un redirect verso un indirizzo firmato, che va aperto senza token
        if (c.responseCode in 301..303 || c.responseCode == 307 || c.responseCode == 308) {
            val next = c.getHeaderField("Location") ?: return false
            c.disconnect()
            c = URL(next).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
        }
        try {
            if (c.responseCode != 200) return false
            val total = c.contentLengthLong
            var done = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != lastPct) {
                                lastPct = p
                                set(Status("Scarico… $p%", busy = true))
                            }
                        }
                    }
                }
            }
            return total <= 0 || done == total
        } finally {
            c.disconnect()
        }
    }

    private fun install(apk: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            set(Status("Consenti a questa app di installare aggiornamenti, poi riprova", canInstall = true))
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        set(Status("Pronto: conferma l'installazione sullo schermo"))
        beforeInstall()
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
