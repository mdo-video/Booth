package it.mdo.booth.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * Coda di caricamento sulla galleria web. Ogni dedica salvata entra in coda (se il caricamento automatico è acceso);
 * si carica una alla volta, e se manca la rete o c'è un errore riprova da sola dopo un minuto.
 * La coda è salvata: sopravvive alla chiusura dell'app.
 */
class GalleryUploader(
    private val ctx: Context,
    val client: GalleryClient,
    private val fileFor: (String) -> File?,
    private val thumbFor: (String) -> File?,
    private val onChange: () -> Unit,
) {
    private val prefs = ctx.getSharedPreferences("gallery", Context.MODE_PRIVATE)
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

    // si ricorda "nome|data del file": se un nuovo evento riusa lo stesso nome, la dedica nuova si carica comunque
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
                if ((n.endsWith(".mp4") || n.endsWith(".jpg")) && queue.add(n)) added = true
            }
        }
        if (added) saveQueue()
        kick()
        onChange()
    }

    fun queuedCount(): Int = synchronized(lock) { queue.size }

    fun doneCount(): Int = done.size

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
            if (!client.configured) { lastError = "Galleria non impostata"; return }
            val f = fileFor(name)
            if (f == null || !f.isFile) {
                // il file non c'è più (eliminato): si toglie dalla coda
                synchronized(lock) { queue.remove(name) }
                saveQueue()
                continue
            }
            val key = "${f.name}|${f.lastModified()}"
            if (key in done) {
                synchronized(lock) { queue.remove(name) }
                saveQueue()
                continue
            }
            if (!networkOk()) {
                lastError = if (wifiOnly) "In attesa del Wi-Fi" else "In attesa di internet"
                retryLater()
                return
            }
            current = name
            pct = 0
            main.post { onChange() }
            try {
                client.upload(f, thumbFor(name)) { p -> pct = p }
                done = done + key
                synchronized(lock) { queue.remove(name) }
                saveQueue()
                lastError = ""
            } catch (e: GalleryHttpException) {
                if (e.code == 401) {
                    lastError = "Chiave non valida: controllala nelle impostazioni della galleria"
                    return
                }
                lastError = e.message ?: "errore"
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
