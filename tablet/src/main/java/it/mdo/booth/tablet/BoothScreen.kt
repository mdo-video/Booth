package it.mdo.booth.tablet

import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import android.media.ToneGenerator
import android.media.AudioManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import org.json.JSONObject
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.graphics.BitmapFactory
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun BoothScreen(
    client: BoothClient,
    local: LocalSettings,
    frames: FrameStore,
    onKiosk: (Boolean) -> Unit,
    onShare: (String, String, (Float) -> Unit, (Boolean) -> Unit) -> Unit,
    onPickFrame: () -> Unit,
    dashCfg: DashConfig,
) {
    val connected by client.connected.collectAsState()
    val state by client.state.collectAsState()
    val info by client.info.collectAsState()
    val history by client.history.collectAsState()

    var starting by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var remaining by remember { mutableIntStateOf(60) }
    var banner by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showLegend by remember { mutableStateOf(false) }
    val remoteConnected by RemoteState.connected.collectAsState()
    val ctx = LocalContext.current
    // icona del telecomando: PNG scontornato in assets/remote_icon.png
    val remoteIcon = remember {
        runCatching { ctx.assets.open("remote_icon.png").use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull()
    }
    LaunchedEffect(local.remoteDevice) { RemoteState.refresh(local) }
    var viewing by remember { mutableStateOf<HistoryItem?>(null) }
    var qr by remember { mutableStateOf<QrRequest?>(null) }
    var shotKind by remember { mutableStateOf("video") }
    var shareProgress by remember { mutableStateOf<Float?>(null) }
    var shareResult by remember { mutableStateOf<Boolean?>(null) }
    val sharing = shareProgress != null

    val parts = state.split(":")
    val recording = state == "RECORDING"
    val processing = parts[0] == "PROCESSING"
    val processingPct = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val frameActive = info?.optBoolean("frame") == true
    val frameOverlay = remember(frames.bitmap, frameActive) { if (frameActive) frames.bitmap?.asImageBitmap() else null }
    val phoneFrameId = info?.optString("frameId") ?: ""
    val hasInfo = info != null
    val hasTake = (parts[0] == "TAKE" || parts[0] == "TAKE_SAVED") && parts.size > 1
    val takeSaved = parts[0] == "TAKE_SAVED"
    val takeFile = parts.getOrNull(1) ?: ""
    val savedName = parts.getOrNull(2)
    val maxSec = info?.optInt("duration", 60) ?: 60
    val base = client.baseUrl
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }

    LaunchedEffect(starting) {
        if (starting) {
            for (i in local.countdown downTo 1) {
                countdown = i
                if (local.sound) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                delay(1000)
            }
            countdown = 0
            if (local.sound) tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            EventLog.add(if (shotKind == "photo") "FOTO" else "REC_START")
            client.send(if (shotKind == "photo") "PHOTO" else "START")
            starting = false
        }
    }
    LaunchedEffect(recording) { if (recording) { viewing = null; qr = null } }
    // la cornice del tablet viene mandata al telefono se questo non ha la stessa
    LaunchedEffect(connected, hasInfo, phoneFrameId, frames.id) {
        val data = frames.bytes
        if (connected && hasInfo && data != null && frames.id.isNotEmpty() && phoneFrameId != frames.id) {
            client.uploadFrame(data) { }
        }
    }
    // ...e quando il telefono ce l'ha, dopo un'importazione si accende da sola
    LaunchedEffect(connected, phoneFrameId, frames.id, frames.enableOnSync) {
        if (connected && frames.enableOnSync && frames.id.isNotEmpty() && phoneFrameId == frames.id) {
            frames.enableOnSync = false
            client.set(JSONObject().put("frame", true))
        }
    }
    LaunchedEffect(takeSaved, savedName) {
        if (takeSaved && savedName != null && local.autoQr && base != null) qr = QrRequest("$base/h/$savedName")
    }
    LaunchedEffect(connected) {
        if (connected) while (true) { client.send("INFO?"); delay(30_000) }
    }
    LaunchedEffect(recording) {
        if (recording) {
            remaining = maxSec
            while (remaining > 0) { delay(1000); remaining-- }
        }
    }
    LaunchedEffect(Unit) {
        client.events.collect { ev ->
            if (ev.startsWith("MOTION:") && dashCfg.motion) {
                EventLog.add(if (ev == "MOTION:FALL") "CAMERA_CADUTA" else "CAMERA_SPOSTATA")
            }
            banner = when {
                ev == "MOTION:FALL" -> "⚠ Il telefono camera è caduto!"
                ev == "MOTION:MOVED" -> "⚠ Il telefono camera è stato spostato"
                ev.startsWith("SAVED:") -> "Salvata ✓  " + ev.removePrefix("SAVED:")
                ev.startsWith("ERROR") -> "Errore: " + ev.removePrefix("ERROR:")
                else -> null
            }
        }
    }
    LaunchedEffect(shareResult) {
        if (shareResult != null) { delay(3000); shareResult = null }
    }
    LaunchedEffect(banner) {
        if (banner != null) { delay(3000); banner = null }
    }
    // registro eventi e stato per l'app di controllo
    LaunchedEffect(connected) { DashState.phoneConnected = connected; EventLog.phone(connected) }
    LaunchedEffect(info) { DashState.phoneInfo = info?.toString() ?: "" }
    LaunchedEffect(state) { EventLog.stateChange(state) }
    LaunchedEffect(qr) { if (qr != null) EventLog.add("QR") }
    LaunchedEffect(recording, starting) { if (recording || starting) showLegend = false }

    // telecomando Bluetooth a un tasto (singola / doppia / tripla / quadrupla / lunga)
    val remoteHandler: (RemoteGesture) -> Unit = handler@{ g ->
        if (showSettings || processing || sharing) return@handler
        fun ack() { if (local.sound) tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120) }
        // QR a schermo: qualsiasi pressione lo chiude
        if (qr != null) { qr = null; return@handler }
        // conto alla rovescia in corso: annulla
        if (starting || countdown > 0) {
            starting = false; countdown = 0; banner = "Annullato"; ack(); return@handler
        }
        // registrazione in corso: STOP
        if (recording) { client.send("STOP"); return@handler }
        // anteprima del video/foto appena fatti
        if (hasTake) {
            if (takeSaved) {
                if (g == RemoteGesture.SINGLE) { client.send("DISCARD"); ack() }            // Nuova
                else if (g != RemoteGesture.DOUBLE && savedName != null && base != null) { // QR
                    qr = QrRequest("$base/h/$savedName"); ack()
                }
            } else {
                if (g == RemoteGesture.SINGLE) { client.send("SAVE"); ack() }                // Salva
                else if (g == RemoteGesture.DOUBLE) {                                       // Rifai
                    EventLog.add("DEDICA_SCARTATA"); client.send("DISCARD"); ack()
                }
            }
            return@handler
        }
        if (!connected) return@handler
        val i = info ?: return@handler
        when (g) {
            RemoteGesture.SINGLE -> { shotKind = "video"; starting = true }
            RemoteGesture.DOUBLE -> if (i.optBoolean("hasPhoto")) { shotKind = "photo"; starting = true }
            RemoteGesture.TRIPLE -> if (i.optBoolean("hasFlash")) {
                val on = i.optBoolean("flash")
                client.set(JSONObject().put("flash", !on))
                banner = if (on) "Flash foto spento" else "Flash foto acceso"; ack()
            }
            RemoteGesture.LONG, RemoteGesture.QUAD -> if (i.optBoolean("hasTorch")) {
                val on = i.optBoolean("torch")
                client.set(JSONObject().put("torch", !on))
                banner = if (on) "Torcia spenta" else "Torcia accesa"; ack()
            }
        }
    }
    val onRemote by rememberUpdatedState(remoteHandler)
    LaunchedEffect(Unit) { RemoteBus.gestures.collect { onRemote(it) } }

    val borderBg = Color(local.borderColor)
    val onBorder = if (borderBg.luminance() > 0.5f) Color.Black else Color.White
    val wide = local.borderMode == 2            // bordo largo: i tasti stanno fuori dall'immagine
    val zoneTop = if (wide) 96.dp else 96.dp
    val zoneBottom = if (wide) 232.dp else 96.dp
    Box(Modifier.fillMaxSize().background(borderBg)) {
        LivePreview(client, local, frameOverlay)
        if (!connected) {
            Text("Cerco il telefono…", color = onBorder, fontSize = 28.sp, modifier = Modifier.align(Alignment.Center))
        }
        if (countdown > 0) {
            Text("$countdown", color = Color.White, fontSize = 220.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
        if (recording) {
            Text("${remaining}s", color = onBorder, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
        }
        if (processing) {
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(28.dp)).background(Color(0xCC000000)).padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Applico la cornice…", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("$processingPct%", color = Color(0xFFFFC107), fontSize = 56.sp, fontWeight = FontWeight.Bold)
            }
        }
        banner?.let {
            Text(it, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
                    .clip(RoundedCornerShape(28.dp)).background(Color(0xCC1B5E20))
                    .padding(horizontal = 28.dp, vertical = 10.dp))
        }

        // tasto impostazioni
        if (!recording && !starting && !processing && countdown == 0) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(20.dp).size(56.dp).clip(CircleShape)
                    .background(UiDark)
                    .clickable { showLegend = false; showSettings = true; client.send("INFO?") },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_gear), null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 92.dp).size(56.dp).clip(CircleShape)
                    .background(UiDark)
                    .clickable { qr = QrRequest(null) },
                contentAlignment = Alignment.Center,
            ) {
                Text("QR", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            // telecomando: compare solo se associato; anello verde = collegato, rosso/sbiadito = non collegato.
            // Tocca per mostrare la legenda dei gesti, tocca di nuovo per nasconderla.
            if (local.remoteDevice.isNotEmpty()) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 164.dp).size(56.dp).clip(CircleShape)
                        .background(
                            when {
                                showLegend -> Color(0xFFFFE082)
                                remoteConnected -> Color(0xF2FFFFFF)
                                else -> Color(0x99FFFFFF)
                            }
                        )
                        .border(3.dp, if (remoteConnected) Color(0xFF43A047) else Color(0xFFE53935), CircleShape)
                        .clickable { showLegend = !showLegend },
                    contentAlignment = Alignment.Center,
                ) {
                    remoteIcon?.let { icon ->
                        Image(
                            bitmap = icon,
                            contentDescription = null,
                            modifier = Modifier.size(38.dp),
                            contentScale = ContentScale.Fit,
                            alpha = if (remoteConnected) 1f else 0.4f,
                        )
                    }
                }
            }
            if (showLegend && local.remoteDevice.isNotEmpty()) {
                val legendLines = buildList {
                    if (hasTake) {
                        if (takeSaved) {
                            add("1 pressione" to "Nuova")
                            add("Lunga · 3 · 4" to "QR")
                        } else {
                            add("1 pressione" to "Salva")
                            add("2 pressioni" to "Rifai")
                        }
                    } else {
                        add("1 pressione" to "REC video")
                        if (info?.optBoolean("hasPhoto") == true) add("2 pressioni" to "FOTO")
                        if (info?.optBoolean("hasFlash") == true) add("3 pressioni" to "Flash foto")
                        if (info?.optBoolean("hasTorch") == true) add("Lunga · 4 pressioni" to "Torcia")
                    }
                }
                Column(
                    Modifier.align(Alignment.TopEnd).padding(top = 90.dp, end = 20.dp)
                        .clip(RoundedCornerShape(18.dp)).background(Color(0xE61B1D22)).padding(20.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (remoteConnected) "Telecomando · collegato ✓" else "Telecomando · non collegato",
                        color = if (remoteConnected) Color(0xFF66BB6A) else Color(0xFFEF5350),
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    )
                    if (!remoteConnected) {
                        Text("Premi il tasto per risvegliarlo", color = Color(0xFF9AA0A6), fontSize = 14.sp)
                    }
                    legendLines.forEach { (gesto, azione) ->
                        Text("$gesto  →  $azione", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (!hasTake) {
                        Text("Conto alla rovescia: annulla · REC: STOP", color = Color(0xFF9AA0A6), fontSize = 14.sp)
                    }
                }
            }
        }

        // tasto REC (nascosto mentre c'è un'anteprima da valutare)
        if (!hasTake && !processing) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp), contentAlignment = Alignment.Center) {
                if (recording) {
                    CircularProgressIndicator(
                        progress = { remaining / maxSec.toFloat() },
                        modifier = Modifier.size(200.dp),
                        color = Color(0xFFE53935),
                        strokeWidth = 8.dp,
                    )
                }
                val enabled = connected && !starting
                if (!recording) {
                    Box(Modifier.size(168.dp).border(4.dp, Color.White.copy(alpha = 0.9f), CircleShape))
                }
                Box(
                    Modifier.size(if (recording) 160.dp else 136.dp).clip(CircleShape)
                        .background(if (enabled) Color(0xFFFF2D3D) else Color.Gray)
                        .clickable(enabled = enabled) {
                            if (recording) client.send("STOP") else { shotKind = "video"; starting = true }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (recording) "STOP" else "REC", color = Color.White, fontSize = if (recording) 36.sp else 32.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // tasto FOTO
        if (!hasTake && !processing && connected && !recording && !starting && countdown == 0 && info?.optBoolean("hasPhoto") == true) {
            Column(
                Modifier.align(Alignment.BottomCenter).offset(x = 190.dp).padding(bottom = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(84.dp).clip(CircleShape).background(Color.White)
                        .clickable { shotKind = "photo"; starting = true },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_camera), null,
                        tint = Color(0xFF3A0A33), modifier = Modifier.size(38.dp))
                }
                Text("FOTO", color = onBorder, fontSize = 13.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }

        // anteprima del video appena registrato
        if (hasTake && base != null) {
            TakeCard(
                client = client,
                path = "/take/$takeFile",
                cacheKey = takeFile,
                saved = takeSaved,
                savedName = savedName,
                sharing = sharing,
                onRedo = { EventLog.add("DEDICA_SCARTATA"); client.send("DISCARD") },
                onQr = { if (savedName != null) qr = QrRequest("$base/h/$savedName") },
                onSave = { client.send("SAVE") },
                onShare = {
                    shareResult = null
                    shareProgress = 0f
                    onShare(
                        "/take/$takeFile", savedName ?: (if (takeFile.endsWith(".jpg")) "dedica.jpg" else "dedica.mp4"),
                        { p -> shareProgress = p },
                        { ok -> shareProgress = null; shareResult = ok; EventLog.add(if (ok) "CONDIVISA" else "CONDIVISIONE_FALLITA") },
                    )
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            )
        }

        // torcia e focali
        if (!hasTake && !processing && connected && viewing == null) {
            info?.let { CameraControls(client, it, Modifier.align(Alignment.CenterEnd).padding(top = zoneTop, bottom = zoneBottom, end = 24.dp)) }
        }

        // batteria e spazio del telefono
        if (connected) {
            info?.let { StatusBadge(it, Modifier.align(Alignment.TopStart).padding(20.dp)) }
        }

        // messaggio d'attesa
        if (connected && state == "READY" && !starting && countdown == 0 && !hasTake && viewing == null &&
            local.welcome.isNotBlank()
        ) {
            Text(
                local.welcome,
                color = if (wide) onBorder else Color.White,
                fontSize = if (wide) 28.sp else 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = if (wide) {
                    // bordo largo: il messaggio sta nella fascia in alto, fuori dall'immagine
                    Modifier.align(Alignment.TopCenter).padding(top = 24.dp, start = 260.dp, end = 260.dp)
                } else {
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 240.dp)
                        .clip(RoundedCornerShape(24.dp)).background(Color(0x99000000))
                        .padding(horizontal = 28.dp, vertical = 14.dp)
                },
            )
        }

        // storico: pellicola verticale a sinistra
        if (!recording && !processing && countdown == 0 && history.isNotEmpty()) {
            HistoryStrip(
                client = client,
                list = history,
                selected = viewing?.name,
                onSelect = { viewing = it },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp, top = if (wide) zoneTop else 24.dp, bottom = if (wide) zoneBottom else 24.dp),
            )
        }
        viewing?.let { v ->
            if (!hasTake && base != null) {
                HistoryCard(
                    client = client,
                    path = "/h/${v.name}",
                    cacheKey = "h_${v.time}_${v.name}",
                    name = v.name,
                    sharing = sharing,
                    onClose = { viewing = null },
                    onQr = { qr = QrRequest("$base/h/${v.name}") },
                    onDelete = { EventLog.add("DEDICA_ELIMINATA", v.name); client.send("DEL:${v.name}"); viewing = null },
                    onShare = {
                        shareResult = null
                        shareProgress = 0f
                        onShare(
                            "/h/${v.name}", v.name,
                            { p -> shareProgress = p },
                            { ok -> shareProgress = null; shareResult = ok; EventLog.add(if (ok) "CONDIVISA" else "CONDIVISIONE_FALLITA") },
                        )
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                )
            }
        }

        // stato del trasferimento verso il tablet
        if (shareProgress != null || shareResult != null) {
            ShareStatus(shareProgress, shareResult, Modifier.align(Alignment.TopCenter).padding(top = 96.dp))
        }

        if (showSettings) {
            SettingsPanel(client, info, local, frames, onKiosk, onPickFrame, dashCfg) { showSettings = false }
        }

        qr?.let { r -> QrOverlay(wifiQrPayload(local.wifiSsid, local.wifiPass), r.videoUrl) { qr = null } }
    }
}
