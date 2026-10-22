package it.mdo.booth.control

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlin.math.roundToInt
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var api: Api

    private var updRef: UpdateManager? = null

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        updRef?.handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Api(this).also { it.start() }
        val upd = UpdateManager(this, "booth-control.apk", BuildConfig.UPDATE_TOKEN).also { it.check(); it.startAutoCheck() }
        updRef = upd
        upd.handleIntent(intent)
        window.decorView.post { Changelog.showIfNew(this, upd.installedCode()) }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
                .launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ControlApp(api, upd)
            }
        }
    }

    override fun onDestroy() {
        api.stop()
        super.onDestroy()
    }
}

private val BG = Color(0xFF101216)
private val CARD = Color(0xFF1B1E25)
private val GREEN = Color(0xFF2E7D32)
private val AMBER = Color(0xFFB26A00)
private val RED = Color(0xFFB71C1C)

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.ITALY)
private val dateFmt = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY)

private fun ago(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s fa"
        s < 3600 -> "${s / 60} min fa"
        else -> "${s / 3600} h ${(s % 3600) / 60} min fa"
    }
}

private fun eventText(e: String): String = when (e) {
    "REC_START" -> "🎬 Registrazione dedica avviata"
    "FOTO" -> "📸 Foto scattata"
    "DEDICA_PRONTA" -> "👀 Anteprima pronta"
    "DEDICA_SALVATA" -> "✅ Dedica salvata"
    "DEDICA_SCARTATA" -> "🔁 Dedica scartata"
    "DEDICA_ELIMINATA" -> "🗑 Dedica eliminata"
    "CONDIVISA" -> "📤 Dedica condivisa"
    "CONDIVISIONE_FALLITA" -> "⚠ Condivisione fallita"
    "QR" -> "🔳 QR mostrato"
    "PHONE_ON" -> "📱 Telefono camera collegato"
    "PHONE_OFF" -> "⚠ Telefono camera scollegato"
    "BATT_BASSA" -> "🔋 Batteria del tablet bassa"
    "SPAZIO_BASSO" -> "💾 Spazio del tablet quasi finito"
    "DASH_AVVIO" -> "🟢 Dashcam avviata"
    "DASH_STOP" -> "⏹ Dashcam fermata"
    "DASH_ERRORE" -> "⚠ Errore dashcam"
    "DASH_CONFIG" -> "⚙ Impostazioni dashcam cambiate"
    "SBLOCCO_REMOTO" -> "🔓 Tablet sbloccato da Controllo"
    "IMPOSTAZIONI" -> "⚙ Impostazioni cambiate da remoto"
    "TABLET_CADUTO" -> "🚨 Il tablet è caduto"
    "TABLET_URTO" -> "💥 Il tablet ha preso un urto forte"
    "TABLET_SPOSTATO" -> "🚨 Il tablet è stato spostato"
    "CAMERA_CADUTA" -> "🚨 Il telefono camera è caduto"
    "CAMERA_SPOSTATA" -> "⚠ Il telefono camera è stato spostato"
    "FOTO_ALLARME" -> "📷 Foto antifurto scattata"
    "MOMENTO_PROTETTO" -> "🔒 Video protetti dalla sovrascrittura"
    "APP_AVVIATA" -> "🔄 App del tablet avviata"
    else -> e
}

private class Health(val level: Int, val title: String, val reasons: List<String>)

private fun health(api: Api): Health {
    val st: JSONObject? = api.status
    if (api.base == null) return Health(2, "Tablet non trovato", listOf("Sei sulla stessa rete Wi-Fi del tablet? Oppure scrivi l'IP in Impostazioni."))
    if (api.authError) return Health(2, "Codice errato", listOf("Scrivi in Impostazioni il codice mostrato sul tablet."))
    val age = System.currentTimeMillis() - api.lastOk
    if (st == null || age > 15_000) {
        return Health(2, "Tablet non raggiungibile", listOf(if (st == null) "Nessuna risposta finora" else "Ultima risposta ${ago(age)}"))
    }
    val bad = mutableListOf<String>()
    val warn = mutableListOf<String>()
    val d = st.getJSONObject("dash")
    val bat = st.getJSONObject("battery")
    val sto = st.getJSONObject("storage")
    val ph = st.getJSONObject("phone")
    val phInfo = ph.optJSONObject("info") ?: JSONObject()
    if (d.optBoolean("enabled") && !d.optBoolean("recording")) bad.add("La dashcam non sta registrando")
    if (d.optString("lastError").isNotEmpty()) warn.add("Dashcam: " + d.optString("lastError"))
    val last = d.optLong("lastSegmentAt")
    val seg = d.optInt("segMin", 5)
    if (d.optBoolean("recording") && last > 0 && System.currentTimeMillis() - last > (seg * 60_000L * 3 / 2 + 60_000L)) {
        bad.add("Nessun nuovo file da " + ago(System.currentTimeMillis() - last).removeSuffix(" fa"))
    }
    if (bat.optInt("pct", -1) in 0..19 && !bat.optBoolean("charging")) warn.add("Batteria del tablet bassa (${bat.optInt("pct")}%)")
    if (sto.optLong("freeMb", 99999) < 3000) warn.add("Spazio del tablet quasi finito")
    if (!ph.optBoolean("connected")) warn.add("Telefono camera non collegato al tablet")
    if (phInfo.optInt("battery", 100) in 0..19 && !phInfo.optBoolean("charging")) warn.add("Batteria del telefono camera bassa")
    if (phInfo.has("freeMb") && phInfo.optLong("freeMb") in 0..1999) warn.add("Spazio del telefono camera quasi finito")
    val now = System.currentTimeMillis()
    val security = setOf("TABLET_CADUTO", "TABLET_URTO", "TABLET_SPOSTATO", "CAMERA_CADUTA", "CAMERA_SPOSTATA")
    api.log.filter { it.optString("e") in security && now - it.optLong("t") < 15 * 60_000L }
        .distinctBy { it.optString("e") }
        .forEach { warn.add(eventText(it.optString("e")) + " (" + ago(now - it.optLong("t")) + ")") }
    val all = bad + warn
    return when {
        bad.isNotEmpty() -> Health(2, "ATTENZIONE", all)
        warn.isNotEmpty() -> Health(1, "Da controllare", all)
        else -> Health(0, "Tutto OK", emptyList())
    }
}

@Composable
internal fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (selected) Color(0xFF3D5AFE) else Color(0xFF2A2E38))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) { Text(text, color = Color.White, fontSize = 15.sp) }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CARD).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = Color(0xFF9AA0AC), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Color(0xFFB0B5C0), fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun openUrl(ctx: Context, url: String, mime: String = "video/mp4") {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun ControlApp(api: Api, upd: UpdateManager) {
    var tab by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val dediche = remember { DedicheApi(ctx) }
    DisposableEffect(Unit) {
        dediche.start()
        onDispose { dediche.stop() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            api.refresh()
            delay(4000)
        }
    }
    Column(Modifier.fillMaxSize().background(BG).padding(top = 28.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("Stato", "Diretta", "Log", "Video", "Dediche", "Impost.").forEachIndexed { i, t -> Chip(t, tab == i) { tab = i } }
        }
        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            when (tab) {
                0 -> StatusTab(api)
                1 -> LiveTab(api)
                2 -> LogTab(api)
                3 -> ClipsTab(api)
                4 -> DedicheTab(dediche)
                else -> SettingsTab(api, upd)
            }
        }
    }
}

@Composable
private fun StatusTab(api: Api) {
    val h = health(api)
    val st: JSONObject? = api.status
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(when (h.level) { 0 -> GREEN; 1 -> AMBER; else -> RED }).padding(18.dp)
        ) {
            Text(h.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            h.reasons.forEach { Text("• $it", color = Color.White, fontSize = 16.sp) }
        }
        if (st == null) return@Column
        val d = st.getJSONObject("dash")
        val bat = st.getJSONObject("battery")
        val sto = st.getJSONObject("storage")
        val ph = st.getJSONObject("phone")
        val phInfo = ph.optJSONObject("info") ?: JSONObject()
        val today = st.optJSONObject("today") ?: JSONObject()

        Card("DASHCAM") {
            Line("Stato", if (d.optBoolean("recording")) "registra" else "ferma")
            Line("Fotocamere", d.optString("cams"))
            Line("Due fotocamere insieme", if (d.optBoolean("concurrent")) "supportato" else "non supportato")
            val last = d.optLong("lastSegmentAt")
            if (last > 0) Line("Ultimo file iniziato", ago(System.currentTimeMillis() - last))
            Line("File ogni / conservati", "${d.optInt("segMin")} min / ${d.optInt("keepMin")} min")
        }
        Card("TABLET") {
            Line("Batteria", "${bat.optInt("pct")}%" + if (bat.optBoolean("charging")) " ⚡" else "")
            Line("Spazio libero", "%.1f GB".format(sto.optLong("freeMb") / 1024.0))
            Line("Dashcam occupa", "%.1f GB (${sto.optInt("clips")} file)".format(sto.optLong("dashMb") / 1024.0))
            Line("Versione app", st.optString("version"))
        }
        Card("TELEFONO CAMERA") {
            Line("Collegato al tablet", if (ph.optBoolean("connected")) "sì" else "NO")
            if (phInfo.has("battery")) {
                Line("Batteria", "${phInfo.optInt("battery")}%" + if (phInfo.optBoolean("charging")) " ⚡" else "")
            }
            if (phInfo.has("freeMb")) Line("Spazio libero", "%.1f GB".format(phInfo.optLong("freeMb") / 1024.0))
        }
        Card("OGGI") {
            Line("Dediche salvate", today.optInt("DEDICA_SALVATA").toString())
            Line("Dediche scartate", today.optInt("DEDICA_SCARTATA").toString())
            Line("Registrazioni avviate", today.optInt("REC_START").toString())
            Line("Foto", today.optInt("FOTO").toString())
            Line("Condivisioni", today.optInt("CONDIVISA").toString())
            Line("QR mostrati", today.optInt("QR").toString())
        }
    }
}

@Composable
private fun LogTab(api: Api) {
    val ctx = LocalContext.current
    val log = api.log
    val clips = api.clips
    if (log.isEmpty()) {
        Text("Nessun evento ancora.", color = Color.Gray, fontSize = 16.sp)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(log) { ev ->
            val t = ev.optLong("t")
            val type = ev.optString("e")
            val detail = ev.optString("d")
            val covering = clips.filter { !it.optBoolean("live") && t >= it.optLong("start") && t <= it.optLong("end") + 5000 }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CARD).padding(12.dp)) {
                Row {
                    Text(dateFmt.format(Date(t)), color = Color(0xFF9AA0AC), fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
                Text(eventText(type), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (detail.isNotEmpty()) Text(detail, color = Color(0xFFB0B5C0), fontSize = 13.sp)
                if (type == "FOTO_ALLARME" && detail.isNotEmpty()) {
                    Row(Modifier.padding(top = 6.dp)) {
                        Chip("🖼 Vedi foto", false) { openUrl(ctx, api.clipUrl(detail), "image/jpeg") }
                    }
                }
                if (covering.isNotEmpty()) {
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        covering.forEach { c ->
                            val off = (t - c.optLong("start")) / 1000
                            Chip("▶ " + (if (c.optString("cam") == "front") "fronte" else "retro") + " @%d:%02d".format(off / 60, off % 60), false) {
                                openUrl(ctx, api.clipUrl(c.optString("name")))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipsTab(api: Api) {
    val ctx = LocalContext.current
    val clips = api.clips
    if (clips.isEmpty()) {
        Text("Nessun video sul tablet.", color = Color.Gray, fontSize = 16.sp)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(clips) { c ->
            val live = c.optBoolean("live")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CARD)
                    .clickable(enabled = !live) { openUrl(ctx, api.clipUrl(c.optString("name"))) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (c.optBoolean("kept")) "🔒 " else "") +
                            (if (c.optString("cam") == "front") "Frontale" else "Posteriore") + "  " + dateFmt.format(Date(c.optLong("start"))),
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (live) "● in registrazione" else "fino alle " + timeFmt.format(Date(c.optLong("end"))),
                        color = if (live) Color(0xFFFF5252) else Color(0xFFB0B5C0), fontSize = 13.sp,
                    )
                }
                Text("%.0f MB".format(c.optLong("size") / 1_048_576.0), color = Color(0xFFB0B5C0), fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun LiveTab(api: Api) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var msg by remember { mutableStateOf("Connessione al tablet…") }
    LaunchedEffect(api.base, api.key) {
        var seq = -1L
        while (true) {
            val r = api.snap(seq)
            when {
                r == null -> { msg = "Il tablet non risponde"; delay(1000) }
                r.bmp == null -> { msg = "Nessun fotogramma: la dashcam è ferma?"; delay(700) }
                r.seq == seq -> { msg = "Nessun nuovo fotogramma dal tablet"; delay(400) }
                else -> { msg = ""; seq = r.seq; bmp = r.bmp }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val b = bmp
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black)
                .aspectRatio(if (b != null) b.width.toFloat() / b.height else 16f / 9f),
            contentAlignment = Alignment.Center,
        ) {
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                )
            }
            if (msg.isNotEmpty()) Text(msg, color = Color(0xFFB0B5C0), fontSize = 15.sp)
        }
        Text(
            "Diretta della fotocamera frontale del tablet (circa 640 px, qualche fotogramma al secondo). " +
                "La registrazione a segmenti continua normalmente.",
            color = Color.Gray, fontSize = 13.sp,
        )
    }
}

private fun JSONObject.strList(key: String): List<String> =
    optJSONArray(key)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Sub(text: String) = Text(text, color = Color(0xFF9AA0AC), fontSize = 13.sp)

@Composable
private fun Note(text: String) = Text(text, color = Color.Gray, fontSize = 12.sp)

/** Copia delle impostazioni del tablet: tutto quello che c'è nel pannello Impostazioni si cambia da qui. */
@Composable
private fun SettingsTab(api: Api, upd: UpdateManager) {
    val scope = rememberCoroutineScope()
    val st: JSONObject? = api.status
    val d = st?.optJSONObject("dash")
    val tab = st?.optJSONObject("tablet")
    val frame = st?.optJSONObject("frame")
    val phone = st?.optJSONObject("phone")
    val info = phone?.optJSONObject("info")
    val tabletUpd = st?.optJSONObject("update")
    val mine by upd.status.collectAsState()
    val field = TextStyle(color = Color.White, fontSize = 18.sp)

    var unlockSent by remember { mutableStateOf(0L) }
    fun send(j: JSONObject) = scope.launch { api.postSettings(j) }
    fun sendPhone(k: String, v: Any) = send(JSONObject().put("phone", JSONObject().put(k, v)))
    fun sendTablet(k: String, v: Any) = send(JSONObject().put("tablet", JSONObject().put(k, v)))
    fun sendDash(k: String, v: Any) = send(JSONObject().put("dash", JSONObject().put(k, v)))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (st != null) {
            // ---- come nel pannello del tablet: prima il telefono camera ----
            if (info == null || phone?.optBoolean("connected") != true) {
                Card("TELEFONO CAMERA") { Text("Telefono non connesso al tablet", color = Color.LightGray, fontSize = 16.sp) }
            } else {
                val qualities = info.strList("qualities")
                if (qualities.isNotEmpty()) {
                    Card("QUALITÀ VIDEO") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            qualities.forEach { q ->
                                val label = when (q) { "UHD" -> "4K"; "FHD" -> "Full HD"; else -> "HD" }
                                Chip(label, info.optString("quality") == q) { sendPhone("quality", q) }
                            }
                        }
                    }
                }
                Card("DURATA MASSIMA") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60, 90, 120).forEach { sec ->
                            Chip("${sec}s", info.optInt("duration") == sec) { sendPhone("duration", sec) }
                        }
                    }
                }
                Card("ORIENTAMENTO VIDEO") {
                    val orient = info.optString("orientation", "landscape")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Orizzontale", orient == "landscape") { sendPhone("orientation", "landscape") }
                        Chip("Verticale", orient == "portrait") { sendPhone("orientation", "portrait") }
                    }
                    Note("Ruota il telefono nella posizione scelta.")
                }
                Card("OBIETTIVO") {
                    Text(
                        "Il telefono dichiara zoom da " + "%.1f".format(info.optDouble("zoomMin", 1.0)) +
                            "x a " + "%.1f".format(info.optDouble("zoomMax", 1.0)) + "x",
                        color = Color.Gray, fontSize = 14.sp,
                    )
                    val lensTxt = info.optString("lens")
                    Text(
                        if (lensTxt.isNotEmpty()) "Obiettivo più grandangolare: $lensTxt" else "Obiettivo: quello predefinito del telefono",
                        color = Color.Gray, fontSize = 14.sp,
                    )
                    if (info.optBoolean("hasTorch")) {
                        SwitchLine("Torcia (luce continua)", info.optBoolean("torch")) { sendPhone("torch", it) }
                    }
                    if (info.optBoolean("hasFlash")) {
                        SwitchLine("Flash nelle foto (doppio LED)", info.optBoolean("flash")) { sendPhone("flash", it) }
                    }
                    SwitchLine("Blocco FISSO (esposizione e fuoco)", info.optBoolean("lock")) { sendPhone("lock", it) }
                    if (info.optBoolean("evSupported") && info.optInt("evMax") > info.optInt("evMin")) {
                        val lo = info.optInt("evMin")
                        val hi = info.optInt("evMax")
                        var ev by remember(info.optInt("ev")) { mutableFloatStateOf(info.optInt("ev").toFloat()) }
                        Text("Esposizione: ${ev.roundToInt()}", color = Color.White, fontSize = 17.sp)
                        Slider(
                            value = ev,
                            onValueChange = { ev = it },
                            onValueChangeFinished = { sendPhone("ev", ev.roundToInt()) },
                            valueRange = lo.toFloat()..hi.toFloat(),
                            steps = hi - lo - 1,
                        )
                    }
                }
                Card("NOME FILE") {
                    var prefix by remember(info.optString("prefix")) { mutableStateOf(info.optString("prefix", "dedica")) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = prefix,
                            onValueChange = { prefix = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }.take(30) },
                            singleLine = true, modifier = Modifier.weight(1f), textStyle = field,
                        )
                        Chip("Applica", false) { sendPhone("prefix", prefix) }
                    }
                    val next = info.optInt("counter") + 1
                    Text("Prossimo file: ${info.optString("prefix")}_${"%03d".format(next)}.mp4", color = Color.White, fontSize = 16.sp)
                    Row { Chip("Azzera numerazione", false) { sendPhone("counter", 0) } }
                }
                Card("MICROFONO") {
                    val mic = info.optString("mic")
                    Text(
                        if (mic.isNotEmpty()) "USB collegato: $mic" else "Microfono del telefono (nessun USB rilevato)",
                        color = Color.White, fontSize = 16.sp,
                    )
                }
            }

            // ---- cornice ----
            Card("CORNICE") {
                val present = frame?.optBoolean("present") == true
                if (!present) {
                    Text("Nessuna cornice caricata sul tablet.", color = Color.Gray, fontSize = 14.sp)
                } else if (info != null) {
                    SwitchLine("Applica a foto e video", info.optBoolean("frame")) { sendPhone("frame", it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Gaia +30", false) { send(JSONObject().put("frame", "gaia")) }
                    if (present) Chip("Rimuovi", false) { send(JSONObject().put("frame", "remove")) }
                }
                Note("Per scegliere un'altra immagine dalla galleria usa le impostazioni sul tablet.")
            }

            // ---- dashcam ----
            if (d != null) {
                Card("DASHCAM") {
                    SwitchLine("Registra video e audio", d.optBoolean("enabled")) { sendDash("enabled", it) }
                    SwitchLine("Usa anche la fotocamera posteriore", d.optBoolean("useBack")) { sendDash("useBack", it) }
                    Text(
                        "Ora: " + (if (d.optBoolean("recording")) "registra con ${d.optString("cams")}" else "ferma") +
                            (if (d.optString("lastError").isNotEmpty()) " — ${d.optString("lastError")}" else ""),
                        color = Color.Gray, fontSize = 14.sp,
                    )
                    SwitchLine("Rileva spostamenti e cadute", d.optBoolean("motion")) { sendDash("motion", it) }
                    if (d.optBoolean("motion")) {
                        Sub("Sensibilità")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5 to "Alta", 10 to "Media", 20 to "Bassa").forEach { (g, name) ->
                                Chip(name, d.optInt("motionDeg") == g) { sendDash("motionDeg", g) }
                            }
                        }
                    }
                    Sub("Durata di ogni file")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 5, 10, 15).forEach { m -> Chip("$m min", d.optInt("segMin") == m) { sendDash("segMin", m) } }
                    }
                    Sub("Conserva gli ultimi")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60, 120, 240, 480).forEach { m ->
                            Chip(if (m < 60) "$m min" else "${m / 60} h", d.optInt("keepMin") == m) { sendDash("keepMin", m) }
                        }
                    }
                    Note("Le modifiche valgono dal riavvio della registrazione (entro 30 secondi).")
                }
            }

            // ---- tablet ----
            if (tab != null && tab.length() > 0) {
                Card("TABLET") {
                    SwitchLine("Anteprima a specchio", tab.optBoolean("mirror")) { sendTablet("mirror", it) }
                    Sub("Conto alla rovescia")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 3, 5).forEach { sec ->
                            Chip(if (sec == 0) "Nessuno" else "${sec}s", tab.optInt("countdown") == sec) { sendTablet("countdown", sec) }
                        }
                    }
                    SwitchLine("Suoni del conto alla rovescia", tab.optBoolean("sound")) { sendTablet("sound", it) }
                    var welcome by remember(tab.optString("welcome")) { mutableStateOf(tab.optString("welcome")) }
                    Sub("Messaggio di benvenuto")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = welcome, onValueChange = { welcome = it.take(60) },
                            singleLine = true, modifier = Modifier.weight(1f), textStyle = field,
                        )
                        Chip("Applica", false) { sendTablet("welcome", welcome) }
                    }
                    SwitchLine("Mostra il QR dopo il salvataggio", tab.optBoolean("autoQr")) { sendTablet("autoQr", it) }
                    var ssid by remember(tab.optString("wifiSsid")) { mutableStateOf(tab.optString("wifiSsid")) }
                    var pass by remember(tab.optString("wifiPass")) { mutableStateOf(tab.optString("wifiPass")) }
                    Sub("Wi-Fi del booth (per il QR di collegamento)")
                    OutlinedTextField(
                        value = ssid, onValueChange = { ssid = it.take(60) }, singleLine = true,
                        label = { Text("Nome rete") }, modifier = Modifier.fillMaxWidth(), textStyle = field,
                    )
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it.take(63) }, singleLine = true,
                        label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), textStyle = field,
                    )
                    Row {
                        Chip("Applica Wi-Fi", false) {
                            send(JSONObject().put("tablet", JSONObject().put("wifiSsid", ssid).put("wifiPass", pass)))
                        }
                    }
                    SwitchLine("Kiosk (blocca le altre app)", tab.optBoolean("kiosk")) { sendTablet("kiosk", it) }
                    Row {
                        Chip("Sblocca il tablet (apri impostazioni)", false) {
                            scope.launch { api.post("/unlock"); unlockSent = System.currentTimeMillis() }
                        }
                    }
                    if (System.currentTimeMillis() - unlockSent < 4000) Note("Richiesta inviata: sul tablet si aprono le impostazioni.")
                    Note("Sul tablet le impostazioni si aprono con pressione lunga sull'angolo in alto a destra e il PIN scelto sul tablet (o con il pulsante qui sopra).")
                }
            }
        } else {
            Card("TABLET") { Text("Nessuna risposta dal tablet: controlla il collegamento qui sotto.", color = Color.LightGray, fontSize = 16.sp) }
        }

        Card("COLLEGAMENTO AL TABLET") {
            OutlinedTextField(
                value = api.key, onValueChange = { api.key = it.filter { c -> c.isDigit() }.take(6); api.saveConn() },
                singleLine = true, label = { Text("Codice (6 cifre, dal tablet)") },
                modifier = Modifier.fillMaxWidth(), textStyle = field,
            )
            OutlinedTextField(
                value = api.manualHost, onValueChange = { api.manualHost = it.trim(); api.saveConn() },
                singleLine = true, label = { Text("IP del tablet (solo se non lo trova da solo)") },
                modifier = Modifier.fillMaxWidth(), textStyle = field,
            )
            Text(
                "Trovato: " + (api.foundHost ?: "no") + "  •  telefono e tablet devono stare sulla stessa rete.",
                color = Color.Gray, fontSize = 13.sp,
            )
        }
        Card("AGGIORNAMENTI") {
            Text("App del tablet", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(tabletUpd?.optString("text").orEmpty().ifEmpty { "—" }, color = Color(0xFFB0B5C0), fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Controlla", false) { scope.launch { api.post("/update/check"); delay(2500); api.refresh() } }
                if (tabletUpd?.optBoolean("canInstall") == true) {
                    Chip("Installa sul tablet", true) { scope.launch { api.post("/update/install") } }
                }
            }
            Note("Sul tablet serve un tocco per confermare l'installazione.")
            Text("Questa app", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Text(mine.text, color = Color(0xFFB0B5C0), fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val actx = androidx.compose.ui.platform.LocalContext.current
                Chip("Controlla", false) { upd.check() }
                if (mine.canInstall) Chip("Installa", true) { upd.installNow() }
                Chip("Novità", false) { (actx as? android.app.Activity)?.let { Changelog.show(it) } }
            }
        }
    }
}
