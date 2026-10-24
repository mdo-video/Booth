package it.mdo.booth.tablet

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

private fun JSONObject.strList(key: String): List<String> =
    optJSONArray(key)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()

internal fun JSONObject.dblList(key: String): List<Double> =
    optJSONArray(key)?.let { a -> List(a.length()) { a.getDouble(it) } } ?: emptyList()

@Composable
fun SettingsPanel(
    client: BoothClient,
    info: JSONObject?,
    local: LocalSettings,
    frames: FrameStore,
    onKiosk: (Boolean) -> Unit,
    onPickFrame: () -> Unit,
    dashCfg: DashConfig,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color(0x99000000)).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClose() }
        )
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(440.dp)
                .background(Color(0xFF16181D))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Impostazioni", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Chip("Chiudi", false, onClose)
            }

            if (info == null) {
                Text("Telefono non connesso", color = Color.LightGray, fontSize = 16.sp)
            } else {
                PhoneSettings(client, info)
            }

            FrameSettings(client, info, frames, onPickFrame)

            BorderSettings(local)

            DashSettings(dashCfg)

            RemoteSettings(local)

            Section("TABLET")
            SwitchRow("Anteprima a specchio", local.mirror) { local.mirror = it; local.save() }
            Label("Conto alla rovescia")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 3, 5).forEach { s ->
                    Chip(if (s == 0) "Nessuno" else "${s}s", local.countdown == s) { local.countdown = s; local.save() }
                }
            }
            SwitchRow("Suoni del conto alla rovescia", local.sound) { local.sound = it; local.save() }
            Label("Lingua per gli ospiti")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Italiano", local.lang == "it") { local.lang = "it"; local.save() }
                Chip("English", local.lang == "en") { local.lang = "en"; local.save() }
            }
            SwitchRow("Mostra gli effetti agli ospiti", local.filtersUi) { local.filtersUi = it; local.save() }
            Label("Effetti personalizzati (testo)")
            OutlinedTextField(
                value = local.customFilters,
                onValueChange = { local.customFilters = it; Filters.setCustom(it); local.save() },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            )
            val parsedCustom = Filters.parseOrNull(local.customFilters)
            Text(
                when {
                    local.customFilters.isBlank() -> "Nessun effetto personalizzato"
                    parsedCustom == null -> "Testo non valido: controlla virgole, virgolette e parentesi"
                    else -> "${parsedCustom.size} effetti personalizzati pronti"
                },
                color = if (parsedCustom == null) Color(0xFFFF8A80) else Color(0xFFB8AEC4),
                fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Inserisci esempio", false) { local.customFilters = Filters.EXAMPLE; Filters.setCustom(Filters.EXAMPLE); local.save() }
                Chip("Cancella", false) { local.customFilters = ""; Filters.setCustom(""); local.save() }
            }
            Label("Messaggio di benvenuto")
            OutlinedTextField(
                value = local.welcome,
                onValueChange = { local.welcome = it.take(60); local.save() },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
            )
            SwitchRow("Mostra il QR dopo il salvataggio", local.autoQr) { local.autoQr = it; local.save() }
            Label("Wi-Fi del booth (per il QR di collegamento)")
            OutlinedTextField(
                value = local.wifiSsid,
                onValueChange = { local.wifiSsid = it.take(60); local.save() },
                singleLine = true,
                label = { Text("Nome rete") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
            )
            OutlinedTextField(
                value = local.wifiPass,
                onValueChange = { local.wifiPass = it.take(63); local.save() },
                singleLine = true,
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
            )
            SwitchRow("Kiosk (blocca le altre app)", local.kiosk) { local.kiosk = it; local.save(); onKiosk(it) }
            Text("Le impostazioni si aprono con pressione lunga sull'angolo in alto a destra e il codice a 6 cifre (lo stesso dell'app Controllo).", color = Color.Gray, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PhoneSettings(client: BoothClient, info: JSONObject) {
    fun send(key: String, value: Any) = client.set(JSONObject().put(key, value))

    val qualities = info.strList("qualities")
    if (qualities.isNotEmpty()) {
        Section("QUALITÀ VIDEO")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            qualities.forEach { q ->
                val label = when (q) {
                    "UHD" -> "4K"
                    "FHD" -> "Full HD"
                    else -> "HD"
                }
                Chip(label, info.optString("quality") == q) { send("quality", q) }
            }
        }
    }

    Section("DURATA MASSIMA")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(15, 30, 60, 90, 120).forEach { s ->
            Chip("${s}s", info.optInt("duration") == s) { send("duration", s) }
        }
    }

    Section("ORIENTAMENTO VIDEO")
    val orient = info.optString("orientation", "landscape")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Orizzontale", orient == "landscape") { send("orientation", "landscape") }
        Chip("Verticale", orient == "portrait") { send("orientation", "portrait") }
    }
    Text("Ruota il telefono nella posizione scelta.", color = Color.Gray, fontSize = 14.sp)

    Section("OBIETTIVO")
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
        SwitchRow("Torcia (luce continua)", info.optBoolean("torch")) { send("torch", it) }
    }
    if (info.optBoolean("hasFlash")) {
        SwitchRow("Flash nelle foto (doppio LED)", info.optBoolean("flash")) { send("flash", it) }
    }
    SwitchRow("Blocco FISSO (esposizione e fuoco)", info.optBoolean("lock")) { send("lock", it) }

    if (info.optBoolean("evSupported") && info.optInt("evMax") > info.optInt("evMin")) {
        val lo = info.optInt("evMin")
        val hi = info.optInt("evMax")
        var ev by remember(info.optInt("ev")) { mutableFloatStateOf(info.optInt("ev").toFloat()) }
        Label("Esposizione: ${ev.roundToInt()}")
        Slider(
            value = ev,
            onValueChange = { ev = it },
            onValueChangeFinished = { send("ev", ev.roundToInt()) },
            valueRange = lo.toFloat()..hi.toFloat(),
            steps = hi - lo - 1,
        )
    }

    Section("NOME FILE")
    var prefix by remember(info.optString("prefix")) { mutableStateOf(info.optString("prefix", "dedica")) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = prefix,
            onValueChange = { prefix = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }.take(30) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
        )
        Chip("Applica", false) { send("prefix", prefix) }
    }
    val next = info.optInt("counter") + 1
    Label("Prossimo file: ${info.optString("prefix")}_${"%03d".format(next)}.mp4")
    Row { Chip("Azzera numerazione", false) { send("counter", 0) } }

    Section("MICROFONO")
    val mic = info.optString("mic")
    Label(if (mic.isNotEmpty()) "USB collegato: $mic" else "Microfono del telefono (nessun USB rilevato)")
}

@Composable
private fun FrameSettings(client: BoothClient, info: JSONObject?, frames: FrameStore, onPick: () -> Unit) {
    Section("CORNICE")
    val bmp = frames.bitmap
    if (bmp == null) {
        Text("Nessuna cornice caricata.", color = Color.Gray, fontSize = 14.sp)
    } else {
        Box(Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).background(Color(0xFF2B2F36))) {
            Image(
                bitmap = remember(bmp) { bmp.asImageBitmap() },
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
        if (info != null) {
            SwitchRow("Applica a foto e video", info.optBoolean("frame")) {
                client.set(JSONObject().put("frame", it))
            }
            val synced = info.optString("frameId") == frames.id
            Text(
                if (synced) "Cornice presente sul telefono ✓" else "Invio della cornice al telefono…",
                color = Color.Gray, fontSize = 14.sp,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Gaia +30", false) { if (frames.importAsset("frame_gaia30.png")) frames.enableOnSync = true }
        Chip("Scegli…", false, onPick)
        if (bmp != null) Chip("Rimuovi", false) {
            frames.clear()
            client.set(JSONObject().put("frame", false))
        }
    }
    Text(
        "Serve un PNG trasparente, oppure un'immagine con il centro bianco (viene resa trasparente). " +
            "Funziona con foto e video orizzontali 16:9. Sui video l'elaborazione richiede qualche secondo dopo lo stop.",
        color = Color.Gray, fontSize = 13.sp,
    )
}

private fun formatZoom(z: Double): String =
    if (z == z.toInt().toDouble()) "${z.toInt()}x" else "${z}x"

@Composable
internal fun Section(title: String) =
    Text(title, color = Color(0xFF9AA0A6), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

@Composable
internal fun Label(text: String) = Text(text, color = Color.White, fontSize = 18.sp)

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (selected) Color(0xFFE53935) else Color(0xFF2B2F36))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text, color = Color.White, fontSize = 16.sp)
    }
}

@Composable
private fun RemoteSettings(local: LocalSettings) {
    val learning by RemoteLog.learning.collectAsState()
    val lines by RemoteLog.lines.collectAsState()
    Section("TELECOMANDO BLUETOOTH")
    Label(if (local.remoteName.isNotEmpty()) "Associato: ${local.remoteName}" else "Nessun telecomando associato")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(if (learning) "Premi il tasto…" else "Associa", learning) {
            RemoteLog.learning.value = !learning
            if (!learning) RemoteLog.add("Premi il tasto del telecomando")
        }
        if (local.remoteName.isNotEmpty()) Chip("Scollega", false) {
            local.remoteDevice = ""; local.remoteName = ""; local.save()
        }
    }
    Text(
        "Abbina prima il telecomando dalle impostazioni Bluetooth di Android, poi premi Associa e il suo tasto.\n" +
            "Pronto: singola = REC, doppia = FOTO, tripla = flash foto, lunga o quadrupla = torcia.\n" +
            "Conto alla rovescia: qualsiasi pressione annulla. Registrazione: qualsiasi pressione = STOP.\n" +
            "Anteprima: singola = Salva (o Nuova), doppia = Rifai, lunga = QR.",
        color = Color.Gray, fontSize = 13.sp,
    )
    if (lines.isNotEmpty()) {
        Text(lines.joinToString("\n"), color = Color(0xFF9AA0A6), fontSize = 13.sp)
    }
}

@Composable
private fun BorderSettings(local: LocalSettings) {
    Section("BORDO ANTEPRIMA")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Pieno schermo", local.borderMode == 0) { local.borderMode = 0; local.save() }
        Chip("Sottile", local.borderMode == 1) { local.borderMode = 1; local.save() }
        Chip("Largo", local.borderMode == 2) { local.borderMode = 2; local.save() }
    }
    Text("Largo: l'immagine si rimpicciolisce e i tasti stanno sul bordo, non sopra la cornice.",
        color = Color.Gray, fontSize = 13.sp)
    Label("Colore del bordo")
    val presets = listOf(
        "Fucsia" to 0xFFD61F9E, "Nero" to 0xFF000000, "Bianco" to 0xFFFFFFFF, "Oro" to 0xFFD4AF37, "Rosa" to 0xFFF4C2C2,
        "Salvia" to 0xFF9CAF88, "Blu notte" to 0xFF0B1F3A, "Bordeaux" to 0xFF5A1224,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { (name, c) ->
            val argb = c.toInt()
            Box(
                Modifier.clip(RoundedCornerShape(20.dp))
                    .background(Color(argb))
                    .border(if (local.borderColor == argb) 4.dp else 1.dp,
                        if (local.borderColor == argb) Color(0xFFE53935) else Color(0xFF5F6368), RoundedCornerShape(20.dp))
                    .clickable { local.borderColor = argb; local.save() }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(name, color = if (Color(argb).luminance() > 0.5f) Color.Black else Color.White, fontSize = 16.sp)
            }
        }
    }
    var hex by remember(local.borderColor) { mutableStateOf("#%06X".format(local.borderColor and 0xFFFFFF)) }
    OutlinedTextField(
        value = hex,
        onValueChange = { v ->
            hex = v.take(7)
            val clean = hex.removePrefix("#")
            if (clean.length == 6) clean.toIntOrNull(16)?.let { local.borderColor = (0xFF000000.toInt() or it); local.save() }
        },
        singleLine = true,
        label = { Text("Altro colore (#RRGGBB)") },
        modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
    )
}
