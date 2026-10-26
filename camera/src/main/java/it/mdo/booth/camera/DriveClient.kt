package it.mdo.booth.camera

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class DriveHttpException(val code: Int, msg: String) : Exception("HTTP $code $msg")

/**
 * Google Drive con lo scope "drive.file": l'app vede solo i file e le cartelle che crea lei.
 * Cartelle: "Booth Dediche" / <nome evento> / file. Il consenso si dà una volta sola dal telefono camera.
 */
class DriveClient(private val ctx: Context) {
    companion object {
        const val REQ_AUTH = 7411
        const val REQ_ACCOUNT = 7412
        private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val ROOT = "Booth Dediche"
    }

    private val prefs = ctx.getSharedPreferences("drive", Context.MODE_PRIVATE)

    var connected: Boolean
        get() = prefs.getBoolean("connected", false)
        set(v) { prefs.edit().putBoolean("connected", v).apply() }

    /** Account Google scelto una volta: serve a non far riapparire ogni volta la scelta dell'account. */
    var accountName: String?
        get() = prefs.getString("account", null)
        set(v) { prefs.edit().putString("account", v).apply() }

    /** Ultimo errore, da mostrare nella scheda. */
    @Volatile
    var lastError: String = ""

    private fun request(): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
        accountName?.let { b.setAccount(Account(it, "com.google")) }
        return b.build()
    }

    /** Da un thread secondario: il token se il consenso c'è già, altrimenti null. */
    fun tokenOrNull(): String? = try {
        val r = Tasks.await(Identity.getAuthorizationClient(ctx.applicationContext).authorize(request()))
        if (r.hasResolution()) null else r.accessToken
    } catch (e: Exception) {
        null
    }

    /** Primo passo del tasto "Collega Drive": la scelta dell'account Google tra quelli del telefono. */
    @Suppress("DEPRECATION")
    fun chooseAccount(activity: Activity) {
        val intent = AccountManager.newChooseAccountIntent(
            null, null, arrayOf("com.google"), null, null, null, null,
        )
        activity.startActivityForResult(intent, REQ_ACCOUNT)
    }

    /** Nome dell'account scelto (dal risultato di chooseAccount), oppure null. */
    fun accountFrom(data: Intent?): String? = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)

    /** Secondo passo: consenso a Drive per l'account già scelto. */
    fun connect(activity: Activity, onDone: (Boolean, String) -> Unit) {
        Identity.getAuthorizationClient(activity).authorize(request())
            .addOnSuccessListener { r ->
                if (r.hasResolution()) {
                    try {
                        activity.startIntentSenderForResult(r.pendingIntent!!.intentSender, REQ_AUTH, null, 0, 0, 0)
                    } catch (e: Exception) {
                        onDone(false, e.message ?: "errore")
                    }
                } else {
                    connected = r.accessToken != null
                    onDone(connected, "")
                }
            }
            .addOnFailureListener { e ->
                lastError = e.message ?: "errore"
                onDone(false, lastError)
            }
    }

    /** Risultato della schermata di consenso (da onActivityResult). */
    fun handleResult(data: Intent?): Boolean = try {
        val r = Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data)
        connected = r.accessToken != null
        if (!connected) lastError = "Google non ha dato il permesso"
        connected
    } catch (e: Exception) {
        lastError = e.message ?: "errore"
        false
    }

    fun forgetFolders() {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("f:") }.forEach { e.remove(it) }
        e.apply()
    }

    private fun call(method: String, url: String, token: String, json: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("Authorization", "Bearer $token")
        if (json != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.outputStream.use { it.write(json.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw DriveHttpException(code, text.take(200))
        return text
    }

    private fun folder(name: String, parent: String?, token: String): String {
        val key = "f:${parent ?: "root"}/$name"
        prefs.getString(key, null)?.let { return it }
        val q = "name='${name.replace("'", "\\'")}' and mimeType='application/vnd.google-apps.folder' and trashed=false" +
            (if (parent != null) " and '$parent' in parents" else "")
        val found = JSONObject(
            call("GET", "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id)&pageSize=1", token)
        ).getJSONArray("files")
        val id = if (found.length() > 0) {
            found.getJSONObject(0).getString("id")
        } else {
            val meta = JSONObject().put("name", name).put("mimeType", "application/vnd.google-apps.folder")
            if (parent != null) meta.put("parents", JSONArray().put(parent))
            JSONObject(call("POST", "https://www.googleapis.com/drive/v3/files?fields=id", token, meta.toString())).getString("id")
        }
        prefs.edit().putString(key, id).apply()
        return id
    }

    /** Carica il file in "Booth Dediche/<evento>/". Restituisce il link Drive (o l'id). */
    fun upload(file: File, token: String, onProgress: (Int) -> Unit): String {
        val event = file.name.substringBeforeLast('_', "")
        val root = folder(ROOT, null, token)
        val parent = if (event.isNotEmpty()) folder(event, root, token) else root
        val mime = if (file.name.endsWith(".jpg", true)) "image/jpeg" else "video/mp4"
        val len = file.length()

        val meta = JSONObject().put("name", file.name).put("parents", JSONArray().put(parent))
        val init = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,webViewLink")
            .openConnection() as HttpURLConnection
        init.requestMethod = "POST"
        init.connectTimeout = 15000
        init.readTimeout = 30000
        init.doOutput = true
        init.setRequestProperty("Authorization", "Bearer $token")
        init.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        init.setRequestProperty("X-Upload-Content-Type", mime)
        init.setRequestProperty("X-Upload-Content-Length", len.toString())
        init.outputStream.use { it.write(meta.toString().toByteArray()) }
        if (init.responseCode != 200) {
            val t = init.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw DriveHttpException(init.responseCode, t.take(200))
        }
        val loc = init.getHeaderField("Location") ?: throw DriveHttpException(0, "nessun indirizzo di caricamento")

        val put = URL(loc).openConnection() as HttpURLConnection
        put.requestMethod = "PUT"
        put.connectTimeout = 15000
        put.readTimeout = 60000
        put.doOutput = true
        put.setFixedLengthStreamingMode(len)
        put.setRequestProperty("Content-Type", mime)
        file.inputStream().use { inp ->
            put.outputStream.use { out ->
                val buf = ByteArray(256 * 1024)
                var sent = 0L
                var last = -1
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    val pct = if (len > 0) (sent * 100 / len).toInt() else 100
                    if (pct != last) { last = pct; onProgress(pct) }
                }
            }
        }
        val code = put.responseCode
        val text = (if (code in 200..299) put.inputStream else put.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw DriveHttpException(code, text.take(200))
        val o = JSONObject(text)
        return o.optString("webViewLink").ifEmpty { o.optString("id") }
    }
}
