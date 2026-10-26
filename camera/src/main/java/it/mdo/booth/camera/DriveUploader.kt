package it.mdo.booth.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Coda di caricamento su Google Drive. Ogni dedica salvata entra in coda (se il caricamento automatico è acceso);
 * si carica una alla volta, e se manca la rete o c'è un errore riprova da sola dopo un minuto.
 * La coda è salvata: sopravvive alla chiusura dell'app.
 */
class DriveUploader(
    private val ctx: Context,
    val client: DriveClient,
    private val fileFor: (String) -> File?,
    private val onChange: () -> Unit,
) {
    private val prefs = ctx.getSharedPreferences("drive", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val queue = LinkedHashSet<String>()
    private var running = false

    @Volatile var current: String = ""
    @Volatile var pct: Int = 0
    @Volatile var lastError: String = ""

    var auto: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) { prefs.edit().putBoolean("auto", v).apply() }

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", false)
        set(v) { prefs.edit().putBoolean("wifiOnly", v).apply() }

    private var done: Set<String>
        get() = prefs.getStringSet("done", emptySet())!!.toSet()
        set(v) { prefs.edit().putStringSet("done", v).apply() }

    init {
        synchronized(lock) { queue.addAll(prefs.getStringSet("queue", emptySet())!!) }
    }

    /** Da chiamare quando la camera è pronta: riprende la coda rimasta in sospeso. */
    fun start() {
        if (synchronized(lock) { queue.isNotEmpty() }) kick()
    }

    private fun saveQueue() {
        prefs.edit().putStringSet("queue", synchronized(lock) { queue.toSet() }).apply()
    }

    fun enqueue(names: Collection<String>) {
        var added = false
        synchronized(lock) {
            names.forEach { n ->
                if ((n.endsWith(".mp4") || n.endsWith(".jpg")) && n !in done && queue.add(n)) added = true
            }
        }
        if (added) saveQueue()
        kick()
        onChange()
    }

    fun doneNames(): Set<String> = done

    fun statusJson(): String {
        val o = JSONObject()
        o.put("connected", client.connected)
        o.put("auto", auto)
        o.put("wifiOnly", wifiOnly)
        o.put("queued", synchronized(lock) { queue.size })
        o.put("current", current)
        o.put("pct", pct)
        o.put("error", lastError)
        o.put("done", JSONArray(done.toList()))
        return o.toString()
    }

    private fun networkOk(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        return if (wifiOnly) caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) else true
    }

    private fun retryLater() {
        main.postDelayed({ kick() }, 60_000)
    }

    fun kick() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        io.execute {
            try {
                drain()
            } finally {
                synchronized(lock) { running = false }
                current = ""
                pct = 0
                main.post { onChange() }
            }
        }
    }

    private fun drain() {
        while (true) {
            val name = synchronized(lock) { queue.firstOrNull() } ?: return
            if (!client.connected) { lastError = "Drive non collegato"; return }
            if (!networkOk()) {
                lastError = if (wifiOnly) "In attesa del Wi-Fi" else "In attesa di internet"
                retryLater()
                return
            }
            val f = fileFor(name)
            if (f == null || !f.isFile) {
                // il file non c'è più (eliminato): si toglie dalla coda
                synchronized(lock) { queue.remove(name) }
                saveQueue()
                continue
            }
            val token = client.tokenOrNull()
            if (token == null) {
                lastError = "Serve di nuovo il consenso a Drive"
                client.connected = false
                return
            }
            current = name
            pct = 0
            main.post { onChange() }
            try {
                client.upload(f, token) { p -> pct = p }
                done = done + name
                synchronized(lock) { queue.remove(name) }
                saveQueue()
                lastError = ""
            } catch (e: DriveHttpException) {
                if (e.code == 404) client.forgetFolders()
                lastError = if (e.code == 403 && e.message?.contains("storageQuota") == true) "Drive pieno" else e.message ?: "errore"
                retryLater()
                return
            } catch (e: Exception) {
                lastError = e.message ?: "errore di rete"
                retryLater()
                return
            }
            main.post { onChange() }
        }
    }
}
