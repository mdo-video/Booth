package it.mdo.booth.control

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Un fotogramma della diretta (già ruotato) e il suo numero progressivo. */
class Snap(val bmp: Bitmap?, val seq: Long, val level: Int = -1)

/** Parla con la dashcam del tablet: la trova via mDNS (o IP a mano) e legge stato, log e video. */
@Suppress("DEPRECATION")
class Api(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("control", Context.MODE_PRIVATE)
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val liveHttp = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    var key by mutableStateOf(prefs.getString("key", "") ?: "")
    var manualHost by mutableStateOf(prefs.getString("host", "") ?: "")
    var foundHost by mutableStateOf<String?>(null)
    var status by mutableStateOf<JSONObject?>(null)
    var lastOk by mutableLongStateOf(0L)
    var authError by mutableStateOf(false)
    var log by mutableStateOf<List<JSONObject>>(emptyList())
    var clips by mutableStateOf<List<JSONObject>>(emptyList())

    fun saveConn() {
        prefs.edit().putString("key", key).putString("host", manualHost).apply()
    }

    val base: String?
        get() {
            val h = manualHost.trim().ifEmpty { foundHost } ?: return null
            return "http://" + (if (h.contains(':')) h else "$h:8090")
        }

    private var resolving = false
    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            if (!info.serviceType.contains("_boothdash") || resolving) return
            resolving = true
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) { resolving = false }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    resolving = false
                    foundHost = "${i.host.hostAddress}:${i.port}"
                }
            })
        }
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    fun start() {
        runCatching { nsd.discoverServices("_boothdash._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery) }
    }

    fun stop() {
        runCatching { nsd.stopServiceDiscovery(discovery) }
    }

    fun clipUrl(name: String): String = "$base/clip/$name?k=$key"

    private fun get(url: String): Pair<Int, String> =
        http.newCall(Request.Builder().url(url).build()).execute().use { it.code to (it.body?.string() ?: "") }

    suspend fun post(path: String, body: String = "") = withContext(Dispatchers.IO) {
        val b = base ?: return@withContext
        runCatching {
            http.newCall(
                Request.Builder().url("$b$path?k=$key")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
            ).execute().close()
        }
    }

    /** Manda al tablet un gesto del telecomando (SINGLE, DOUBLE, TRIPLE, QUAD, LONG); true se il tablet l'ha ricevuto. */
    suspend fun remote(gesture: String): Boolean = withContext(Dispatchers.IO) {
        val b = base ?: return@withContext false
        runCatching {
            http.newCall(
                Request.Builder().url("$b/remote?g=$gesture&k=$key")
                    .post("".toRequestBody("text/plain".toMediaType())).build()
            ).execute().use { it.isSuccessful && it.body?.string() == "OK" }
        }.getOrDefault(false)
    }

    /** Invia un file grezzo al tablet (es. l'immagine della splash). */
    suspend fun postBytes(path: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val b = base ?: return@withContext false
        runCatching {
            http.newCall(
                Request.Builder().url("$b$path?k=$key")
                    .post(bytes.toRequestBody("application/octet-stream".toMediaType())).build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Come refresh() ma senza l'elenco dei video: serve al servizio di avvisi. */
    suspend fun refreshLight() = withContext(Dispatchers.IO) {
        val b = base ?: return@withContext
        try {
            val st = get("$b/status?k=$key")
            if (st.first == 401) {
                authError = true
                return@withContext
            }
            if (st.first != 200) return@withContext
            authError = false
            status = JSONObject(st.second)
            lastOk = System.currentTimeMillis()
            val lg = JSONArray(get("$b/log?n=100&k=$key").second)
            log = List(lg.length()) { lg.getJSONObject(it) }.reversed()
        } catch (_: Exception) {
        }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val b = base ?: return@withContext
        try {
            val st = get("$b/status?k=$key")
            if (st.first == 401) {
                authError = true
                return@withContext
            }
            if (st.first != 200) return@withContext
            authError = false
            status = JSONObject(st.second)
            lastOk = System.currentTimeMillis()
            val lg = JSONArray(get("$b/log?n=400&k=$key").second)
            log = List(lg.length()) { lg.getJSONObject(it) }.reversed()
            val cl = JSONArray(get("$b/clips?k=$key").second)
            clips = List(cl.length()) { cl.getJSONObject(it) }
        } catch (_: Exception) {
        }
    }

    /** Invia impostazioni al tablet, es. {"phone":{"torch":true}} oppure {"tablet":{"countdown":5}}. */
    suspend fun postSettings(j: JSONObject) {
        post("/settings", j.toString())
        delay(700)
        refresh()
    }

    /** Prossimo fotogramma della diretta (dashcam, oppure booth se booth=true); null se il tablet non risponde. */
    suspend fun snap(after: Long, booth: Boolean = false): Snap? = withContext(Dispatchers.IO) {
        val b = base ?: return@withContext null
        try {
            liveHttp.newCall(Request.Builder().url("$b/${if (booth) "boothsnap" else "snap"}?after=$after&k=$key").build()).execute().use { r ->
                val lvl = r.header("X-Level")?.toIntOrNull() ?: -1
                if (r.code == 204) return@use Snap(null, after, lvl)
                if (!r.isSuccessful) return@use null
                val seq = r.header("X-Seq")?.toLongOrNull() ?: 0L
                val rot = r.header("X-Rot")?.toIntOrNull() ?: 0
                val bytes = r.body?.bytes() ?: return@use null
                val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use null
                val bmp = if (rot == 0) raw else {
                    val m = Matrix().apply { postRotate(rot.toFloat()) }
                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                }
                Snap(bmp, seq, lvl)
            }
        } catch (_: Exception) {
            null
        }
    }
}
