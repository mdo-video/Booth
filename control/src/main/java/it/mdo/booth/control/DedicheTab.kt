package it.mdo.booth.control

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Una dedica salvata sul telefono camera: video (.mp4) o foto (.jpg). */
data class Dedica(val name: String, val time: Long) {
    val isPhoto: Boolean get() = name.endsWith(".jpg", ignoreCase = true)

    /** La camera salva la miniatura come <nome senza estensione>.jpg, sia per i video sia per le foto. */
    val thumbName: String get() = name.substringBeforeLast('.') + ".jpg"
}

/**
 * Parla direttamente col telefono camera (stessa Wi-Fi): lo trova via mDNS (_boothcam._tcp.) o con IP a mano,
 * legge l'elenco delle dediche salvate (/list), le miniature (/t/<nome>) e scarica i file scelti (/h/<nome>).
 */
@Suppress("DEPRECATION")
class DedicheApi(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("control", Context.MODE_PRIVATE)
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val thumbs = HashMap<String, Bitmap>()

    var manualHost by mutableStateOf(prefs.getString("camHost", "") ?: "")
    var foundHost by mutableStateOf<String?>(null)
    var items by mutableStateOf<List<Dedica>>(emptyList())
    var loading by mutableStateOf(false)
    var message by mutableStateOf("")
    var downloaded by mutableStateOf((prefs.getStringSet("dedicheScaricate", emptySet()) ?: emptySet()).toSet())

    fun saveHost() {
        prefs.edit().putString("camHost", manualHost).apply()
    }

    val base: String?
        get() {
            val h = manualHost.trim().ifEmpty { foundHost } ?: return null
            return "http://" + (if (h.contains(':')) h else "$h:8080")
        }

    // ---- ricerca del telefono camera in rete ----
    private var resolving = false
    private var started = false
    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            if (info.serviceType.contains("_boothcam")) resolve(info, 0)
        }
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    private fun resolve(info: NsdServiceInfo, attempt: Int) {
        if (resolving) {
            // un'altra risoluzione (es. quella del tablet) è in corso: riprova tra poco
            if (attempt < 8) Handler(Looper.getMainLooper()).postDelayed({ resolve(info, attempt + 1) }, 1500)
            return
        }
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {
                resolving = false
                if (attempt < 8) Handler(Looper.getMainLooper()).postDelayed({ resolve(info, attempt + 1) }, 1500)
            }
            override fun onServiceResolved(i: NsdServiceInfo) {
                resolving = false
                foundHost = "${i.host.hostAddress}:${i.port}"
            }
        })
    }

    fun start() {
        if (started) return
        started = true
        runCatching { nsd.discoverServices("_boothcam._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery) }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { nsd.stopServiceDiscovery(discovery) }
    }

    // ---- richieste ----
    private fun url(first: String, name: String): HttpUrl? =
        base?.let { runCatching { it.toHttpUrl().newBuilder().addPathSegment(first).addPathSegment(name).build() }.getOrNull() }

    /** Indirizzo del file sul telefono camera, per aprirlo in anteprima. */
    fun fileUrl(d: Dedica): String? = url("h", d.name)?.toString()

    private fun parseList(s: String): List<Dedica> {
        val arr = JSONArray(s)
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Dedica(o.getString("name"), o.optLong("time"))
        }
    }

    suspend fun refresh() {
        val b = base
        if (b == null) {
            message = "Telefono camera non trovato. Sei sulla stessa Wi-Fi? Puoi scrivere l'IP con il tasto IP."
            return
        }
        loading = true
        val result: List<Dedica>? = withContext(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url("$b/list").build()).execute().use { r ->
                    if (r.isSuccessful) parseList(r.body?.string() ?: "[]") else null
                }
            } catch (e: Exception) {
                null
            }
        }
        loading = false
        if (result == null) {
            message = "Il telefono camera non risponde"
        } else {
            items = result
            message = if (result.isEmpty()) "Nessuna dedica salvata ancora." else ""
        }
    }

    suspend fun thumb(d: Dedica): Bitmap? {
        thumbs[d.thumbName]?.let { return it }
        val u = url("t", d.thumbName) ?: return null
        val bmp: Bitmap? = withContext(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url(u).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } else null
                }
            } catch (e: Exception) {
                null
            }
        }
        if (bmp != null) thumbs[d.thumbName] = bmp
        return bmp
    }

    /** Scarica il file e lo mette nella Galleria: video in Movies/Booth, foto in Pictures/Booth. */
    suspend fun saveToGallery(d: Dedica): Boolean {
        val u = url("h", d.name) ?: return false
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url(u).build()).execute().use { r ->
                    val body = r.body
                    if (!r.isSuccessful || body == null) {
                        false
                    } else {
                        val video = !d.isPhoto
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, d.name)
                            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/Booth" else "Pictures/Booth")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val collection =
                            if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        val uri = ctx.contentResolver.insert(collection, values)
                        if (uri == null) {
                            false
                        } else {
                            try {
                                val out = ctx.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("no stream")
                                out.use { body.byteStream().copyTo(it) }
                                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                                ctx.contentResolver.update(uri, done, null, null)
                                true
                            } catch (e: Exception) {
                                ctx.contentResolver.delete(uri, null, null)
                                false
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    fun markDownloaded(name: String) {
        downloaded = downloaded + name
        prefs.edit().putStringSet("dedicheScaricate", downloaded).apply()
    }
}

private val dedicheDate = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY)

@Composable
fun DedicheTab(api: DedicheApi) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var showIp by remember { mutableStateOf(false) }

    // all'apertura e quando il telefono camera viene trovato (o cambia l'IP)
    LaunchedEffect(api.base) { api.refresh() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("↻ Aggiorna", false) { scope.launch { api.refresh() } }
            Chip("Nuove", false) { selected = api.items.filter { it.name !in api.downloaded }.map { it.name }.toSet() }
            Chip("Tutte", false) { selected = api.items.map { it.name }.toSet() }
            Chip("Nessuna", false) { selected = emptySet() }
            Chip("IP", showIp) { showIp = !showIp }
        }
        if (showIp) {
            OutlinedTextField(
                value = api.manualHost,
                onValueChange = { api.manualHost = it; api.saveHost() },
                label = { Text("IP telefono camera (vuoto = automatico)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (api.message.isNotEmpty()) Text(api.message, color = Color.Gray, fontSize = 15.sp)
        if (api.loading) Text("Carico l'elenco…", color = Color.Gray, fontSize = 15.sp)
        if (result.isNotEmpty()) Text(result, color = Color(0xFF81C784), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val groups = api.items.groupBy { it.name.substringBeforeLast('_', "") }
            groups.forEach { (ev, list) ->
            if (ev.isNotEmpty() && groups.size > 1) {
                item(key = "ev_$ev", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text(ev, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                }
            }
            items(list, key = { it.name }) { d ->
                DedicaCell(
                    api = api,
                    d = d,
                    selected = d.name in selected,
                    onToggle = { selected = if (d.name in selected) selected - d.name else selected + d.name },
                    onOpen = {
                        val u = api.fileUrl(d)
                        if (u != null) {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(Uri.parse(u), if (d.isPhoto) "image/jpeg" else "video/mp4")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    },
                )
            }
            }
        }

        Box(Modifier.padding(bottom = 10.dp)) {
            Chip(if (busy) status else "⬇ Scarica in Galleria (${selected.size})", selected.isNotEmpty() || busy) {
                if (busy || selected.isEmpty()) return@Chip
                scope.launch {
                    busy = true
                    result = ""
                    val todo = api.items.filter { it.name in selected }
                    var ok = 0
                    todo.forEachIndexed { i, d ->
                        status = "Scarico ${i + 1}/${todo.size}…"
                        if (api.saveToGallery(d)) {
                            ok++
                            api.markDownloaded(d.name)
                        }
                    }
                    busy = false
                    status = ""
                    if (ok == todo.size) {
                        result = "✓ $ok scaricati in Galleria (cartella Booth)"
                        selected = emptySet()
                    } else {
                        result = "Scaricati $ok su ${todo.size}: riprova per gli altri"
                    }
                }
            }
        }
    }
}

@Composable
private fun DedicaCell(api: DedicheApi, d: Dedica, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, d.name, api.base) { value = api.thumb(d) }
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B1E25)).clickable { onToggle() }
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (selected) Box(Modifier.fillMaxSize().background(Color(0x883D5AFE)))
        if (selected) Text("✓", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        Text(
            if (d.isPhoto) "📷" else "▶",
            fontSize = 14.sp,
            color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
        )
        Text(
            "👁",
            fontSize = 16.sp,
            modifier = Modifier.align(Alignment.TopEnd).clickable { onOpen() }.padding(8.dp),
        )
        Text(
            dedicheDate.format(Date(d.time)),
            fontSize = 11.sp,
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
        )
        if (d.name in api.downloaded) {
            Text(
                "scaricata",
                fontSize = 11.sp,
                color = Color(0xFF81C784),
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
            )
        }
    }
}
