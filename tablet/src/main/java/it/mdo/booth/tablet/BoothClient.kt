package it.mdo.booth.tablet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class HistoryItem(val name: String, val time: Long) {
    val isPhoto: Boolean get() = name.endsWith(".jpg")
}

/** Trova il telefono via mDNS, si connette in WebSocket e riconnette da solo se cade. */
@Suppress("DEPRECATION")
class BoothClient(context: Context) {
    val connected = MutableStateFlow(false)
    val frame = MutableStateFlow<Bitmap?>(null)
    /** READY | RECORDING | TAKE:file | TAKE_SAVED:file:nome | OFFLINE */
    val state = MutableStateFlow("OFFLINE")
    /** capacità e impostazioni correnti del telefono */
    val info = MutableStateFlow<JSONObject?>(null)
    val level = MutableStateFlow(0)
    /** eventi una tantum: SAVED:nome | ERROR:x */
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** video salvati sul telefono, dal più recente */
    val history = MutableStateFlow<List<HistoryItem>>(emptyList())
    private val thumbs = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    @Volatile
    var baseUrl: String? = null
        private set

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val http = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var target: NsdServiceInfo? = null
    private var resolving = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            if (info.serviceType.contains("_boothcam") && !connected.value) resolve(info)
        }
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    fun start() {
        nsd.discoverServices("_boothcam._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        runCatching { nsd.stopServiceDiscovery(discovery) }
        socket?.close(1000, null)
    }

    private val previewOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
    private val latestFrame = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
    private val decoding = java.util.concurrent.atomic.AtomicBoolean(false)
    private val decoder = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Decodifica sempre e solo l'ultimo frame ricevuto: niente code, niente ritardo accumulato. */
    private fun onFrameBytes(b: ByteArray) {
        latestFrame.set(b)
        if (decoding.compareAndSet(false, true)) {
            decoder.execute {
                try {
                    while (true) {
                        val f = latestFrame.getAndSet(null) ?: break
                        BitmapFactory.decodeByteArray(f, 0, f.size, previewOpts)?.let { frame.value = it }
                    }
                } finally {
                    decoding.set(false)
                }
            }
        }
    }

    fun refreshHistory() {
        val url = baseUrl?.let { "$it/list" } ?: return
        http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) return
                    val text = r.body?.string() ?: return
                    runCatching {
                        val a = JSONArray(text)
                        history.value = List(a.length()) { i ->
                            a.getJSONObject(i).let { HistoryItem(it.getString("name"), it.optLong("time")) }
                        }
                    }
                }
            }
        })
    }

    suspend fun thumb(name: String): Bitmap? {
        thumbs[name]?.let { return it }
        val url = baseUrl?.let { "$it/t/${name.substringBeforeLast('.')}.jpg" } ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    val bytes = if (r.isSuccessful) r.body?.bytes() else null
                    if (bytes == null) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }.getOrNull()?.also { thumbs[name] = it }
        }
    }

    fun send(cmd: String) {
        socket?.send(cmd)
    }

    /** Invia al telefono una o più impostazioni, es. {"zoom":0.5} */
    fun set(j: JSONObject) {
        send("SET:$j")
    }

    /** Invia al telefono il PNG della cornice (il telefono la applica a foto e video). */
    fun uploadFrame(png: ByteArray, onDone: (Boolean) -> Unit) {
        val url = baseUrl?.let { "$it/frame" } ?: return onDone(false)
        val req = Request.Builder().url(url).post(png.toRequestBody("image/png".toMediaType())).build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onDone(false)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { onDone(it.isSuccessful) }
            }
        })
    }

    /** Scarica il video del take dal telefono (per la condivisione). */
    fun download(path: String, dest: File, onProgress: (Float) -> Unit = {}, onDone: (Boolean) -> Unit) {
        val url = baseUrl?.let { "$it$path" } ?: return onDone(false)
        http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onDone(false)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val body = r.body
                    if (!r.isSuccessful || body == null) {
                        onDone(false)
                        return
                    }
                    try {
                        val total = body.contentLength()
                        var copied = 0L
                        var lastPct = -1
                        if (total <= 0L) onProgress(-1f)
                        body.byteStream().use { input ->
                            dest.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    copied += n
                                    if (total > 0L) {
                                        val pct = (copied * 100 / total).toInt()
                                        if (pct != lastPct) {
                                            lastPct = pct
                                            onProgress(copied / total.toFloat())
                                        }
                                    }
                                }
                            }
                        }
                        onDone(total <= 0L || copied == total)
                    } catch (e: Exception) {
                        onDone(false)
                    }
                }
            }
        })
    }

    private fun resolve(info: NsdServiceInfo) {
        if (resolving) return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) { resolving = false }
            override fun onServiceResolved(i: NsdServiceInfo) {
                resolving = false
                target = i
                main.post { connect() }
            }
        })
    }

    private fun connect() {
        val t = target ?: return
        baseUrl = "http://${t.host.hostAddress}:${t.port}"
        val req = Request.Builder().url("ws://${t.host.hostAddress}:${t.port}/ws").build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.value = true
                refreshHistory()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                when {
                    text.startsWith("STATE:") -> state.value = text.removePrefix("STATE:")
                    text.startsWith("EVENT:") -> {
                        events.tryEmit(text.removePrefix("EVENT:"))
                        if (text.startsWith("EVENT:SAVED:") || text.startsWith("EVENT:DELETED:")) refreshHistory()
                    }
                    text.startsWith("LEVEL:") -> level.value = text.removePrefix("LEVEL:").toIntOrNull() ?: 0
                    text.startsWith("INFO:") -> runCatching { info.value = JSONObject(text.removePrefix("INFO:")) }
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onFrameBytes(bytes.toByteArray())
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
        })
    }

    private fun lost(ws: WebSocket) {
        if (ws !== socket) return
        connected.value = false
        state.value = "OFFLINE"
        info.value = null
        main.postDelayed({ connect() }, 2000)
    }
}
