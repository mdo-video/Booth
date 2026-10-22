package it.mdo.booth.tablet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Sezioni "DASHCAM" e "AGGIORNAMENTI" del pannello impostazioni. */
@Composable
fun DashSettings(cfg: DashConfig) {
    var enabled by remember { mutableStateOf(cfg.enabled) }
    var seg by remember { mutableStateOf(cfg.segMin) }
    var keep by remember { mutableStateOf(cfg.keepMin) }
    var back by remember { mutableStateOf(cfg.useBack) }
    var motion by remember { mutableStateOf(cfg.motion) }
    var deg by remember { mutableStateOf(cfg.motionDeg) }

    Section("DASHCAM")
    SwitchRow("Registra video e audio", enabled) { enabled = it; cfg.enabled = it }
    SwitchRow("Usa anche la fotocamera posteriore", back) { back = it; cfg.useBack = it; if (it) cfg.backFailed = false }
    SwitchRow("Rileva spostamenti e cadute", motion) { motion = it; cfg.motion = it }
    if (motion) {
        Label("Sensibilità")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5 to "Alta", 10 to "Media", 20 to "Bassa").forEach { (g, name) ->
                Chip(name, deg == g) { deg = g; cfg.motionDeg = g }
            }
        }
    }
    Text(
        "Ora: " + (if (DashState.recording) "registra con ${DashState.cams}" else "ferma") +
            (if (DashState.lastError.isNotEmpty()) " — ${DashState.lastError}" else ""),
        color = Color.Gray, fontSize = 14.sp,
    )
    Label("Durata di ogni file")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 5, 10, 15).forEach { m -> Chip("${m} min", seg == m) { seg = m; cfg.segMin = m } }
    }
    Label("Conserva gli ultimi")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(30, 60, 120, 240, 480).forEach { m ->
            Chip(if (m < 60) "$m min" else "${m / 60} h", keep == m) { keep = m; cfg.keepMin = m }
        }
    }
    Text("Le modifiche valgono dal riavvio della registrazione (entro 30 secondi).", color = Color.Gray, fontSize = 13.sp)
    Label("Codice per l'app di controllo")
    Text(cfg.key, color = Color.White, fontSize = 28.sp)

    Section("PIN DEL TABLET")
    var pinState by remember { mutableStateOf(cfg.adminPin) }
    Text(
        when (pinState) {
            "" -> "Da scegliere: lo chiede al prossimo tocco lungo sull'ingranaggio"
            "-" -> "Nessun PIN: le impostazioni si aprono subito"
            else -> "PIN attivo (${pinState.length} cifre)"
        },
        color = Color.LightGray, fontSize = 16.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Cambia PIN", false) { cfg.adminPin = ""; pinState = "" }
        if (pinState != "-") Chip("Togli PIN", false) { cfg.adminPin = "-"; pinState = "-" }
    }

    Section("AGGIORNAMENTI")
    val upd = DashState.updates
    if (upd != null) {
        val st by upd.status.collectAsState()
        Text(st.text, color = Color.LightGray, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val actx = androidx.compose.ui.platform.LocalContext.current
            Chip("Controlla", false) { upd.check() }
            if (st.canInstall) Chip("Installa", true) { upd.installNow() }
            Chip("Novità", false) { (actx as? android.app.Activity)?.let { Changelog.show(it) } }
        }
    }
}
