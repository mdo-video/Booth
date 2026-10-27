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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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
    /** Codice a 6 cifre del telefono camera (mostrato nell'app Camera): serve se lì la protezione è accesa. */
    var camKey by mutableStateOf(prefs.getString("camKey", "") ?: "")
    var foundHost by mutableStateOf<String?>(null)
    var items by mutableStateOf<List<Dedica>>(emptyList())
    var loading by mutableStateOf(false)
    var message by mutableStateOf("")
    var downloaded by mutableStateOf((prefs.getStringSet("dedicheScaricate", emptySet()) ?: emptySet()).toSet())

    fun saveHost() {
        prefs.edit().putString("camHost", manualHost).putString("camKey", camKey.trim()).apply()
    }

    /** Richiesta già col codice della camera nell'intestazione X-Key. */
    private fun rb(): Request.Builder =
        Request.Builder().apply { camKey.trim().takeIf { it.isNotEmpty() }?.let { header("X-Key", it) } }

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
        base?.let { runCatching {
            it.toHttpUrl().newBuilder().addPathSegment(first).addPathSegment(name).apply {
                camKey.trim().takeIf { k -> k.isNotEmpty() }?.let { k -> addQueryParameter("k", k) }
            }.build()
        }.getOrNull() }

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
        var code = 0
        var err = ""
        val result: List<Dedica>? = withContext(Dispatchers.IO) {
            try {
                http.newCall(rb().url("$b/list").build()).execute().use { r ->
                    code = r.code
                    if (r.isSuccessful) parseList(r.body?.string() ?: "[]") else null
                }
            } catch (e: Exception) {
                err = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                null
            }
        }
        loading = false
        if (result == null) {
            message = when {
                code == 401 -> "Il telefono camera vuole il codice: tocca IP e scrivi il codice a 6 cifre che vedi nell'app Camera"
                code != 0 -> "Il telefono camera ha risposto con errore $code"
                else -> "Il telefono camera non risponde ($err)"
            }
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
                http.newCall(rb().url(u).build()).execute().use { r ->
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
                http.newCall(rb().url(u).build()).execute().use { r ->
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

    /** Elimina una dedica dal telefono camera (la copia già salvata in Galleria sul telefono camera resta). */
    suspend fun delete(d: Dedica): Boolean {
        val u = url("del", d.name) ?: return false
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(rb().url(u).post("".toRequestBody()).build()).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                false
            }
        }
    }

    // ---- caricamento su Google Drive (lo fa il telefono camera; qui si comanda e si vede lo stato) ----
    var drive by mutableStateOf<JSONObject?>(null)

    val uploaded: Set<String>
        get() = drive?.optJSONArray("done")?.let { a -> List(a.length()) { a.getString(it) }.toSet() } ?: emptySet()

    suspend fun refreshDrive() {
        val b = base
        if (b == null) { drive = null; return }
        drive = withContext(Dispatchers.IO) {
            try {
                http.newCall(rb().url("$b/drive").build()).execute().use { r ->
                    if (r.isSuccessful) JSONObject(r.body?.string() ?: "{}") else null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** names = elenco, oppure "*" per tutte le non ancora caricate. */
    suspend fun driveUpload(names: String): Boolean {
        val b = base ?: return false
        val ok = withContext(Dispatchers.IO) {
            try {
                http.newCall(rb().url("$b/drive/up").post(names.toRequestBody()).build()).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                false
            }
        }
        refreshDrive()
        return ok
    }

    suspend fun driveAuto(on: Boolean): Boolean {
        val b = base ?: return false
        val ok = withContext(Dispatchers.IO) {
            try {
                http.newCall(rb().url("$b/drive/auto?on=${if (on) 1 else 0}").post("".toRequestBody()).build()).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                false
            }
        }
        refreshDrive()
        return ok
    }

    fun markDownloaded(name: String) {
        downloaded = downloaded + name
        prefs.edit().putStringSet("dedicheScaricate", downloaded).apply()
    }
}

private val dedicheDate = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DedicheTab(api: DedicheApi, tablet: Api) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var showIp by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    // dediche nascoste sul tablet (le manda il tablet nello stato)
    val hidden: Set<String> = tablet.status?.optJSONArray("hidden")?.let { a -> List(a.length()) { a.getString(it) }.toSet() } ?: emptySet()

    // all'apertura e quando il telefono camera viene trovato (o cambia l'IP)
    LaunchedEffect(api.base, api.camKey) { api.refresh() }
    // stato del caricamento su Drive, ogni 3 secondi
    LaunchedEffect(api.base, api.camKey) {
        while (true) {
            api.refreshDrive()
            delay(3000)
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("↻ Aggiorna", false) { scope.launch { api.refresh() } }
            Chip("Nuove", false) { selected = api.items.filter { it.name !in api.downloaded }.map { it.name }.toSet() }
            Chip("Seleziona tutto", false) { selected = api.items.map { it.name }.toSet() }
            Chip("Nessuna", false) { selected = emptySet() }
            Chip("IP e codice", showIp) { showIp = !showIp }
        }
        if (showIp) {
            OutlinedTextField(
                value = api.manualHost,
                onValueChange = { api.manualHost = it; api.saveHost() },
                label = { Text("IP telefono camera (vuoto = automatico)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = api.camKey,
                onValueChange = { api.camKey = it.filter { c -> c.isDigit() }.take(6); api.saveHost() },
                label = { Text("Codice telefono camera (6 cifre, se la protezione è accesa)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (api.message.isNotEmpty()) Text(api.message, color = Color.Gray, fontSize = 15.sp)
        if (api.loading) Text("Carico l'elenco…", color = Color.Gray, fontSize = 15.sp)
        api.drive?.let { dv ->
            val queued = dv.optInt("queued")
            val cur = dv.optString("current")
            val err = dv.optString("error")
            val line = when {
                !dv.optBoolean("connected") -> "☁ Drive non collegato: collegalo dall'app Camera"
                cur.isNotEmpty() -> "☁ Carico $cur ${dv.optInt("pct")}%" + if (queued > 1) " · in coda ${queued - 1}" else ""
                queued > 0 -> "☁ In coda su Drive: $queued"
                else -> "☁ Tutto caricato su Drive (${api.uploaded.size})"
            }
            Text(line, color = Color(0xFF90CAF9), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (err.isNotEmpty() && dv.optBoolean("connected")) Text("⚠ $err", color = Color(0xFFFFB74D), fontSize = 14.sp)
        }
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
                    Text(
                        "$ev  ·  ${list.size}  (tocca per selezionare l'evento)",
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp).clickable { selected = selected + list.map { it.name } },
                    )
                }
            }
            items(list, key = { it.name }) { d ->
                DedicaCell(
                    api = api,
                    d = d,
                    hidden = d.name in hidden,
                    selected = d.name in selected,
                    onToggle = { selected = if (d.name in selected) selected - d.name else selected + d.name },
                    selecting = selected.isNotEmpty(),
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

        FlowRow(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selected.isNotEmpty() && !busy) {
                Chip("🙈 Nascondi (${selected.size})", false) {
                    val names = selected.toList()
                    selected = emptySet()
                    result = "✓ ${names.size} nascoste dalla galleria del tablet (restano sul telefono camera)"
                    scope.launch {
                        tablet.postSettings(JSONObject().put("tablet", JSONObject().put("hide", JSONArray(names))))
                    }
                }
            }
            if (hidden.isNotEmpty() && !busy) {
                Chip("👁 Mostra nascoste (${hidden.size})", false) {
                    result = "✓ Nascoste di nuovo visibili"
                    scope.launch {
                        tablet.postSettings(JSONObject().put("tablet", JSONObject().put("unhide", JSONArray(listOf("*")))))
                    }
                }
            }
            if (selected.isNotEmpty() && !busy) {
                Chip(if (confirmDel) "Conferma: elimina ${selected.size}" else "🗑 Elimina (${selected.size})", confirmDel) {
                    if (!confirmDel) { confirmDel = true; return@Chip }
                    confirmDel = false
                    val todo = api.items.filter { it.name in selected }
                    selected = emptySet()
                    scope.launch {
                        busy = true
                        status = "Elimino…"
                        var ok = 0
                        todo.forEach { if (api.delete(it)) ok++ }
                        busy = false
                        status = ""
                        result = "✓ $ok eliminate dal telefono camera"
                        api.refresh()
                    }
                }
                if (confirmDel) Chip("Annulla", false) { confirmDel = false }
            }
            if (api.drive?.optBoolean("connected") == true && !busy) {
                Chip(if (selected.isNotEmpty()) "☁ Carica su Drive (${selected.size})" else "☁ Carica tutte le nuove", false) {
                    val names = if (selected.isEmpty()) "*" else selected.joinToString(",")
                    val n = selected.size
                    selected = emptySet()
                    scope.launch {
                        val ok = api.driveUpload(names)
                        result = if (ok) (if (n == 0) "☁ Caricamento avviato (le non ancora caricate)" else "☁ $n messe in coda su Drive") else "Non riesco a contattare il telefono camera"
                    }
                }
                val autoOn = api.drive?.optBoolean("auto") == true
                Chip(if (autoOn) "☁ Automatico: acceso" else "☁ Automatico: spento", autoOn) {
                    scope.launch { api.driveAuto(!autoOn) }
                }
            }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DedicaCell(api: DedicheApi, d: Dedica, hidden: Boolean, selected: Boolean, onToggle: () -> Unit, selecting: Boolean, onOpen: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, d.name, api.base) { value = api.thumb(d) }
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B1E25))
            .combinedClickable(onClick = { if (selecting) onToggle() else onOpen() }, onLongClick = { onToggle() })
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
        if (hidden) {
            Text(
                "nascosta",
                fontSize = 11.sp,
                color = Color(0xFFFFB74D),
                modifier = Modifier.align(Alignment.CenterStart).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
            )
        }
        if (d.name in api.uploaded) {
            Text(
                "☁",
                fontSize = 13.sp,
                color = Color(0xFF90CAF9),
                modifier = Modifier.align(Alignment.CenterEnd).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
            )
        }
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
