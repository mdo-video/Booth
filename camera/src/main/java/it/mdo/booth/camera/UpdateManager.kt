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

    fun check(onDone: () -> Unit = {}) {
        if (status.value.busy) { onDone(); return }
        set(Status("Controllo aggiornamenti…", busy = true))
        thread {
            val f = try { fetch() } catch (e: Exception) { null }
            if (f == null) {
                set(Status("Impossibile controllare gli aggiornamenti"))
            } else if (f.code > installedCode()) {
                found = f
                // scarica subito l'APK in background: a quel punto basta toccare Installa
                set(Status("Scarico ${f.name}… 0%", busy = true))
                if (readyFor(f.code) || prepare(f)) {
                    set(Status("Pronto: ${f.name} · tocca Installa", canInstall = true))
                    notifyUser("Aggiornamento pronto", "${f.name} è già scaricato · tocca per installare", "last_ready", f.code)
                } else {
                    set(Status("Disponibile: ${f.name} (download non riuscito, riprova)", canInstall = true))
                    notifyUser("Aggiornamento disponibile", "${f.name} · apri l'app e tocca Installa", "last_avail", f.code)
                }
            } else {
                cleanup()
                set(Status("Aggiornata: versione ${installedName()}"))
            }
            onDone()
        }
    }

    private fun updateDir(): File = File(ctx.cacheDir, "update").apply { mkdirs() }
    private fun apkFile() = File(updateDir(), assetName)
    private fun codeFile() = File(updateDir(), "$assetName.code")

    private fun readyFor(code: Int): Boolean = try {
        apkFile().exists() && codeFile().exists() && codeFile().readText().trim() == code.toString()
    } catch (e: Exception) { false }

    private fun cleanup() {
        runCatching { updateDir().listFiles()?.forEach { it.delete() } }
    }

    /** scarica l'APK in un file temporaneo e lo rende "pronto" solo a download completo */
    private fun prepare(f: Found): Boolean {
        cleanup()
        val part = File(updateDir(), "$assetName.part")
        val ok = try { download(f.url, part) } catch (e: Exception) { false }
        if (!ok) { part.delete(); return false }
        val apk = apkFile()
        if (!part.renameTo(apk)) { part.delete(); return false }
        codeFile().writeText(f.code.toString())
        return true
    }

    /** chiamato da MainActivity: se l'app è stata aperta toccando la notifica, avvia l'installazione */
    fun handleIntent(i: Intent?) {
        if (i == null || !i.getBooleanExtra("install_update", false)) return
        i.removeExtra("install_update")
        thread {
            val code = try { codeFile().readText().trim().toInt() } catch (e: Exception) { null }
            if (code != null && code > installedCode() && apkFile().exists()) install(apkFile())
            else check()
        }
    }

    /** Controlla da solo ogni 6 ore (la prima volta dopo 1 minuto), così la notifica arriva anche a app aperta a lungo. */
    fun startAutoCheck() {
        UpdateJobService.schedule(ctx.applicationContext)
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

    /** Notifica: una sola volta per versione (e per tipo), solo se le notifiche sono consentite. */
    private fun notifyUser(title: String, text: String, prefKey: String, code: Int) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val prefs = ctx.getSharedPreferences("update_notify", Context.MODE_PRIVATE)
            if (prefs.getInt(prefKey, 0) >= code) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("update2", "Aggiornamenti", NotificationManager.IMPORTANCE_HIGH))
            val open = (ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent())
                .putExtra("install_update", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, "update2")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(4242, n)
            prefs.edit().putInt(prefKey, code).apply()
        } catch (e: Exception) {
        }
    }

    fun installNow() {
        val f = found ?: return
        if (status.value.busy) return
        thread {
            if (!readyFor(f.code)) {
                set(Status("Scarico… 0%", busy = true))
                if (!prepare(f)) {
                    set(Status("Download non riuscito", canInstall = true))
                    return@thread
                }
            }
            install(apkFile())
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
