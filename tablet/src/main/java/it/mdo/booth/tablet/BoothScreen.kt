package it.mdo.booth.tablet

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
    onPickFrame: () -> Unit,
    dashCfg: DashConfig,
) {
    val connected by client.connected.collectAsState()
    val state by client.state.collectAsState()
    val info by client.info.collectAsState()
    val allHistory by client.history.collectAsState()
    val history = allHistory.filter { it.name !in local.hidden }

    var starting by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var remaining by remember { mutableIntStateOf(60) }
    var banner by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showLegend by remember { mutableStateOf(false) }
    val remoteConnected by RemoteState.connected.collectAsState()
    // messaggio animato quando il telecomando si connette o si disconnette
    var remoteToast by remember { mutableStateOf(false) }
    var remoteToastOn by remember { mutableStateOf(false) }
    var prevRemote by remember { mutableStateOf<Boolean?>(null) }
    val openedAt = remember { System.currentTimeMillis() }
    LaunchedEffect(remoteConnected) {
        val was = prevRemote
        prevRemote = remoteConnected
        // niente messaggio nei primi secondi dopo l'apertura (il primo controllo non è un cambio vero)
        if (was != null && was != remoteConnected && local.remoteDevice.isNotEmpty() &&
            System.currentTimeMillis() - openedAt > 3000
        ) {
            remoteToastOn = remoteConnected
            remoteToast = true
            delay(3000)
            remoteToast = false
        }
    }
    val ctx = LocalContext.current
    // icona del telecomando: PNG scontornato in assets/remote_icon.png
    val remoteIcon = remember {
        runCatching { ctx.assets.open("remote_icon.png").use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull()
    }
    LaunchedEffect(local.remoteDevice) { RemoteState.refresh(local) }
    var viewing by remember { mutableStateOf<HistoryItem?>(null) }
    var qr by remember { mutableStateOf<QrRequest?>(null) }
    var shotKind by remember { mutableStateOf("video") }
    var showPin by remember { mutableStateOf(false) }
    // Condividi su un video non ancora salvato: salva, poi mostra il QR appena il telefono conferma
    var pendingQr by remember { mutableStateOf(false) }

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
    // salvaschermo: dopo X secondi senza tocchi riappare la splash (se caricata e se il timer non è "mai")
    val idleBusy by rememberUpdatedState(recording || starting || countdown > 0 || processing || hasTake || showSettings || showPin || qr != null || viewing != null)
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val idle = local.splashIdleSec
            val busy = idleBusy
            if (idle > 0 && SplashStore.bitmap != null && !SplashStore.visible && !busy &&
                System.currentTimeMillis() - SplashStore.lastActivity >= idle * 1000L
            ) SplashStore.show(true)
        }
    }
    // effetti personalizzati: si mandano al telefono quando ci si collega e dopo ogni modifica
    LaunchedEffect(connected, hasInfo, local.customFilters) {
        delay(600)
        if (connected && hasInfo) client.set(JSONObject().put("customFilters", local.customFilters))
    }
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
        if (takeSaved && savedName != null && (local.autoQr || pendingQr) && base != null) {
            qr = QrRequest("$base/p/$savedName")
            pendingQr = false
        }
    }
    LaunchedEffect(hasTake) { if (!hasTake) pendingQr = false }
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
                ev.startsWith("SAVED:") -> Lang.t("Salvata ✓  ", "Saved ✓  ") + ev.removePrefix("SAVED:")
                ev.startsWith("ERROR") -> "Errore: " + ev.removePrefix("ERROR:")
                else -> null
            }
        }
    }
    LaunchedEffect(banner) {
        if (banner != null) { delay(3000); banner = null }
    }
    // registro eventi e stato per l'app di controllo
    LaunchedEffect(connected) { DashState.phoneConnected = connected; EventLog.phone(connected) }
    LaunchedEffect(info) { DashState.phoneInfo = info?.toString() ?: "" }
    LaunchedEffect(state) { EventLog.stateChange(state) }
    LaunchedEffect(qr) { if (qr != null) EventLog.add("QR") }
    // sblocco remoto dall'app Controllo: apre le impostazioni senza codice (solo se arriva adesso)
    val unlockAt by DashState.unlockAt.collectAsState()
    LaunchedEffect(unlockAt) {
        if (unlockAt > 0 && System.currentTimeMillis() - unlockAt < 5000 && !processing) {
            starting = false; viewing = null; qr = null; showLegend = false
            showPin = false; showSettings = true; client.send("INFO?")
        }
    }
    LaunchedEffect(recording, starting) { if (recording || starting) showLegend = false }
    // ritorno automatico alla schermata iniziale se nessuno tocca nulla
    LaunchedEffect(qr, showSettings, showPin) {
        if (qr != null && !showSettings && !showPin) { delay(45_000); qr = null }
    }
    LaunchedEffect(viewing, showSettings, showPin) {
        if (viewing != null && !showSettings && !showPin) { delay(60_000); viewing = null }
    }
    LaunchedEffect(hasTake, takeSaved, takeFile, qr, showSettings, showPin) {
        if (hasTake && qr == null && !showSettings && !showPin) {
            delay(if (takeSaved) 30_000L else 60_000L)
            // non salvato: si salva da solo, così la dedica dell'ospite non va persa
            client.send(if (takeSaved) "DISCARD" else "SAVE")
        }
    }

    // telecomando Bluetooth a un tasto (singola / doppia / tripla / quadrupla / lunga)
    val remoteHandler: (RemoteGesture) -> Unit = handler@{ g ->
        if (SplashStore.visible) { SplashStore.dismiss(); return@handler }
        if (showSettings || showPin || processing) return@handler
        fun ack() { if (local.sound) tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120) }
        // QR a schermo: qualsiasi pressione lo chiude
        if (qr != null) { qr = null; return@handler }
        // conto alla rovescia in corso: come il tocco sul tablet, non fa nulla
        if (starting || countdown > 0) return@handler
        // registrazione in corso: STOP
        if (recording) { client.send("STOP"); return@handler }
        // anteprima del video/foto appena fatti
        if (hasTake) {
            if (takeSaved) {
                if (g == RemoteGesture.SINGLE) { client.send("DISCARD"); ack() }            // Nuova
                else if (g != RemoteGesture.DOUBLE && savedName != null && base != null) { // QR
                    qr = QrRequest("$base/p/$savedName"); ack()
                }
            } else {
                if (g == RemoteGesture.SINGLE) { client.send("SAVE"); ack() }                // Salva
                else if (g == RemoteGesture.DOUBLE) {                                       // Rifai
                    EventLog.add("DEDICA_SCARTATA"); client.send("DISCARD"); ack()
                } else { pendingQr = true; client.send("SAVE"); ack() }                      // Condividi (salva + QR)
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

    val borderBg = if (local.borderMode == 2) Color(0xFF14101A) else Color(local.borderColor)
    val onBorder = if (borderBg.luminance() > 0.5f) Color.Black else Color.White
    val wide = local.borderMode == 2            // bordo largo: i tasti stanno fuori dall'immagine
    val zoneTop = if (wide) 104.dp else 96.dp
    val zoneBottom = if (wide) 176.dp else 96.dp
    var photoMode by remember { mutableStateOf(false) }
    var filterToast by remember { mutableStateOf<String?>(null) }
    val shownFilter = FilterState.override ?: (info?.optString("filter", "none") ?: "none")
    val shownFilterState by rememberUpdatedState(shownFilter)
    LaunchedEffect(filterToast) { if (filterToast != null) { delay(1400); filterToast = null } }
    LaunchedEffect(FilterState.override) { if (FilterState.override != null) { delay(2500); FilterState.override = null } }
    Box(Modifier.fillMaxSize().background(borderBg)) {
        // impostazioni nascoste: pressione lunga sul bordo fuori dall'anteprima, poi il PIN
        if (wide && !recording && !starting && !processing && countdown == 0) {
            SettingsHotZones(previewPadding(local.borderMode), {
                showLegend = false
                if (dashCfg.adminPin == "-") { showSettings = true; client.send("INFO?") } else showPin = true
            })
        }
        LivePreview(client, local, frameOverlay, dim = hasTake || viewing != null)
        // scorrendo il dito sull'anteprima si cambia effetto, come le storie di Instagram
        val swipeOn = local.filtersUi && connected && state == "READY" && !starting && countdown == 0 &&
            !hasTake && !processing && viewing == null && !recording && !showSettings && !showPin && qr == null
        if (swipeOn) {
            Box(
                Modifier.fillMaxSize().padding(previewPadding(local.borderMode))
                    .pointerInput(Unit) {
                        var acc = Offset.Zero
                        detectDragGestures(
                            onDragStart = { acc = Offset.Zero },
                            onDragEnd = {
                                // si scorre in verticale o in orizzontale: conta l'asse con più movimento
                                val d = if (kotlin.math.abs(acc.x) > kotlin.math.abs(acc.y)) acc.x else acc.y
                                if (kotlin.math.abs(d) > 100f) {
                                    val ids = Filters.shown.map { it.id }
                                    val i = ids.indexOf(shownFilterState).coerceAtLeast(0)
                                    val n = if (d < 0) (i + 1) % ids.size else (i - 1 + ids.size) % ids.size
                                    FilterState.override = ids[n]
                                    filterToast = Filters.label(Filters.get(ids[n]))
                                    client.set(JSONObject().put("filter", ids[n]))
                                }
                            },
                            onDrag = { change, dragAmount -> change.consume(); acc += dragAmount },
                        )
                    },
            )
            // sul bordo superiore: solo i puntini degli effetti
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(if (wide) 104.dp else 56.dp).padding(bottom = 10.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                FilterHint(shownFilter)
            }
        }
        filterToast?.let { FilterToast(it, Modifier.align(Alignment.Center)) }
        if (!connected) {
            Text(Lang.t("Cerco il telefono…", "Looking for the phone…"), color = onBorder, fontSize = 28.sp, modifier = Modifier.align(Alignment.Center))
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
                Text(Lang.t("Elaboro…", "Processing…"), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("$processingPct%", color = Color(0xFFFFC107), fontSize = 56.sp, fontWeight = FontWeight.Bold)
            }
        }
        // telecomando collegato / scollegato: compare dall'alto e sparisce dopo 3 secondi
        androidx.compose.animation.AnimatedVisibility(
            visible = remoteToast,
            enter = androidx.compose.animation.slideInVertically { -it } + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically { -it } + androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
        ) {
            val tone = if (remoteToastOn) Color(0xFF43A047) else Color(0xFFE53935)
            Row(
                Modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xF21D1026))
                    .border(2.dp, tone, RoundedCornerShape(28.dp))
                    .padding(horizontal = 26.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(tone))
                Text(
                    if (remoteToastOn) Lang.t("Telecomando collegato", "Remote connected")
                    else Lang.t("Telecomando scollegato", "Remote disconnected"),
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        banner?.let {
            Text(it, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
                    .clip(RoundedCornerShape(28.dp)).background(Color(0xCC1B5E20))
                    .padding(horizontal = 28.dp, vertical = 10.dp))
        }

        // in alto a destra: telecomando e Wi-Fi/QR
        if (wide && !recording && !starting && !processing && countdown == 0) {
            TopPills(
                remotePaired = local.remoteDevice.isNotEmpty(),
                remoteConnected = remoteConnected,
                onRemote = { showLegend = !showLegend },
                onWifi = { qr = QrRequest(null) },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 160.dp),
            )
        }
        // altri bordi: nessun tasto visibile. Pressione lunga sull'angolo in alto a destra + PIN.
        if (!wide && !recording && !starting && !processing && countdown == 0) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(20.dp).size(72.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = {
                            showLegend = false
                            if (dashCfg.adminPin == "-") { showSettings = true; client.send("INFO?") } else showPin = true
                        })
                    },
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 104.dp).size(56.dp).clip(CircleShape)
                    .background(UiDark)
                    .clickable { qr = QrRequest(null) },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(R.drawable.ic_wifi), null,
                    tint = Color.White, modifier = Modifier.size(30.dp),
                )
            }
            // telecomando: compare solo se associato; anello verde = collegato, rosso/sbiadito = non collegato.
            // Tocca per mostrare la legenda dei gesti, tocca di nuovo per nasconderla.
            if (local.remoteDevice.isNotEmpty()) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 176.dp).size(56.dp).clip(CircleShape)
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
        }
        // legenda del telecomando (vale per tutti i layout)
        if (!recording && !starting && !processing && countdown == 0) {
            if (showLegend && local.remoteDevice.isNotEmpty()) {
                val legendLines = buildList {
                    if (hasTake) {
                        if (takeSaved) {
                            add("1 pressione" to "Nuova")
                            add("Lunga · 3 · 4" to "Condividi")
                        } else {
                            add("1 pressione" to "Salva")
                            add("2 pressioni" to "Rifai")
                            add("Lunga · 3 · 4" to "Condividi")
                        }
                    } else {
                        add("1 pressione" to "REC video")
                        if (info?.optBoolean("hasPhoto") == true) add("2 pressioni" to "FOTO")
                        if (info?.optBoolean("hasFlash") == true) add("3 pressioni" to "Flash foto")
                        if (info?.optBoolean("hasTorch") == true) add("Lunga · 4 pressioni" to "Torcia")
                    }
                }
                Column(
                    // a sinistra della colonna di tasti (96dp + 24dp di margine + 16dp di respiro) per non coprirli
                    Modifier.align(Alignment.TopEnd).padding(top = 90.dp, end = 136.dp)
                        .widthIn(min = 360.dp, max = 460.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xF21D1026))
                        .border(2.dp, Color(0x66FF2BFF), RoundedCornerShape(28.dp))
                        .padding(22.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.size(14.dp).clip(CircleShape)
                                .background(if (remoteConnected) Color(0xFF43A047) else Color(0xFFE53935))
                        )
                        Column {
                            Text(
                                if (remoteConnected) "Telecomando · collegato" else "Telecomando · non collegato",
                                color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            )
                            if (!remoteConnected) {
                                Text("Premi il tasto per risvegliarlo", color = Color(0xFFC9A4E6), fontSize = 13.sp)
                            }
                        }
                    }
                    legendLines.forEach { (gesto, azione) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF2A1B38))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                        ) {
                            Text(gesto, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                azione, color = Color(0xFF14101A), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFFFE83A))
                                    .padding(horizontal = 14.dp, vertical = 5.dp),
                            )
                        }
                    }
                    if (!hasTake) {
                        Text("REC: STOP", color = Color(0xFFC9A4E6), fontSize = 13.sp)
                    }
                }
            }
        }

        // comandi in basso: interruttore VIDEO/FOTO + tasto REC
        if (!hasTake && !processing) {
            val photoOk = info?.optBoolean("hasPhoto") == true
            val isPhoto = photoMode && photoOk
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp),
            ) {
                if (!recording && !starting && countdown == 0 && photoOk) {
                    ModeToggle(isPhoto, { photoMode = it })
                }
                Box(contentAlignment = Alignment.Center) {
                    if (recording) {
                        CircularProgressIndicator(
                            progress = { remaining / maxSec.toFloat() },
                            modifier = Modifier.size(124.dp),
                            color = Color(0xFFE53935),
                            strokeWidth = 6.dp,
                            trackColor = Color(0xFF3D3A41),
                        )
                    }
                    val enabled = connected && !starting
                    if (!recording) {
                        Box(Modifier.size(108.dp).border(4.dp, Color.White.copy(alpha = 0.9f), CircleShape))
                    }
                    Box(
                        Modifier.size(if (recording) 100.dp else 88.dp).clip(CircleShape)
                            .background(if (enabled) Color(0xFFFF2D55) else Color.Gray)
                            .clickable(enabled = enabled) {
                                if (recording) client.send("STOP") else { shotKind = if (isPhoto) "photo" else "video"; starting = true }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (recording) "STOP" else if (isPhoto) Lang.t("FOTO", "PHOTO") else "REC",
                            color = Color.White, fontSize = if (recording) 26.sp else 22.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
                // tasto luce accanto a REC: torcia in modalità VIDEO, flash in modalità FOTO
                val hasLight = if (isPhoto) info?.optBoolean("hasFlash") == true else info?.optBoolean("hasTorch") == true
                if (wide && !recording && !starting && countdown == 0 && hasLight) {
                    val key = if (isPhoto) "flash" else "torch"
                    val lightOn = info?.optBoolean(key) == true
                    LightButton(isPhoto, lightOn, { client.set(JSONObject().put(key, !lightOn)) })
                }
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
                onRedo = { EventLog.add("DEDICA_SCARTATA"); client.send("DISCARD") },
                onSave = { client.send("SAVE") },
                onShare = {
                    // Condividi = QR, senza uscire dal kiosk
                    EventLog.add("CONDIVISA")
                    if (takeSaved && savedName != null) qr = QrRequest("$base/p/$savedName")
                    else { pendingQr = true; client.send("SAVE") }
                },
                modifier = Modifier.align(Alignment.Center).padding(bottom = 32.dp),
            )
        }

        // torcia e focali
        if (!wide && !hasTake && !processing && connected && viewing == null) {
            info?.let { CameraControls(client, it, Modifier.align(Alignment.CenterEnd).padding(top = zoneTop, bottom = zoneBottom, end = 24.dp)) }
        }

        // batteria e spazio del telefono
        if (connected) {
            // batteria e spazio: nel layout nuovo non servono agli ospiti (si vedono nelle impostazioni)
            if (!wide) info?.let { StatusBadge(it, Modifier.align(Alignment.TopStart).padding(20.dp)) }
        }

        // messaggio d'attesa
        if (connected && state == "READY" && !starting && countdown == 0 && !hasTake && viewing == null &&
            local.welcome.isNotBlank()
        ) {
            Text(
                if (Lang.en && local.welcome == "Lascia una dedica!") "Leave us a message!" else local.welcome,
                color = if (wide) onBorder else Color.White,
                fontSize = if (wide) 28.sp else 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = if (wide) TextAlign.Start else TextAlign.Center,
                modifier = if (wide) {
                    // titolo in alto a sinistra, allineato all'anteprima
                    Modifier.align(Alignment.TopStart).padding(top = 40.dp, start = 168.dp)
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
                selected = viewing?.name ?: history.firstOrNull()?.name,
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
                    onClose = { viewing = null },
                    onShare = { EventLog.add("CONDIVISA", v.name); qr = QrRequest("$base/p/${v.name}") },
                    modifier = Modifier.align(Alignment.Center).padding(bottom = 32.dp),
                )
            }
        }

        if (showPin) {
            PinOverlay(
                expected = dashCfg.adminPin.let { if (it == "-") "" else it },
                onOk = { showPin = false; showSettings = true; client.send("INFO?") },
                onCreate = { dashCfg.adminPin = it; showPin = false; showSettings = true; client.send("INFO?") },
                onNoPin = { dashCfg.adminPin = "-"; showPin = false; showSettings = true; client.send("INFO?") },
                onCancel = { showPin = false },
            )
        }

        if (showSettings) {
            SettingsPanel(client, info, local, frames, onKiosk, onPickFrame, dashCfg) { showSettings = false }
        }

        SplashOverlay()
        qr?.let { r -> QrOverlay(wifiQrPayload(local.wifiSsid, local.wifiPass), r.videoUrl) { qr = null } }
    }
}
