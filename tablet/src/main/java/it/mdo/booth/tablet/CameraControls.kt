package it.mdo.booth.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.abs

private val OnColor = Color(0xFFFFD54A)
private val OffColor = Color(0x99000000)

/** Colonna di tasti grandi: torcia (ON/OFF) e focali. Il tasto acceso è giallo pieno. */
@Composable
fun CameraControls(client: BoothClient, info: JSONObject, modifier: Modifier = Modifier) {
    val lenses = info.dblList("lenses")
    val cur = info.optDouble("zoom", 1.0)
    val shown = lenses.filter { it < 0.95 || it == 1.0 || it == 2.0 || it == 5.0 }.take(4)
    val sel = shown.minByOrNull { abs(it - cur) }
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (info.optBoolean("hasTorch")) {
            val on = info.optBoolean("torch")
            ControlButton("🔦", "TORCIA", on) {
                client.set(JSONObject().put("torch", !on))
            }
        }
        if (info.optBoolean("hasFlash")) {
            val f = info.optBoolean("flash")
            ControlButton("⚡", "FLASH", f) {
                client.set(JSONObject().put("flash", !f))
            }
        }
        if (info.optBoolean("hasFrame")) {
            val fr = info.optBoolean("frame")
            ControlButton("🖼", "CORNICE", fr) {
                client.set(JSONObject().put("frame", !fr))
            }
        }
        val lockOn = info.optBoolean("lock")
        ControlButton(if (lockOn) "🔒" else "🔓", if (lockOn) "FISSO" else "AUTO", lockOn) {
            client.set(JSONObject().put("lock", !lockOn))
        }
    }
}

private fun lensLabel(z: Double): String =
    if (z == z.toInt().toDouble()) "${z.toInt()}x" else "${z}x"

@Composable
private fun ControlButton(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.size(width = 88.dp, height = 96.dp).clip(shape)
            .background(if (active) OnColor else OffColor)
            .border(2.dp, if (active) Color.White else Color(0x55FFFFFF), shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(icon, fontSize = 32.sp, textAlign = TextAlign.Center)
        Text(
            label,
            color = if (active) Color(0xFF2B1B00) else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Barra di avanzamento del trasferimento + esito (verde ok / rosso errore). */
@Composable
fun ShareStatus(progress: Float?, result: Boolean?, modifier: Modifier = Modifier) {
    Column(
        modifier.width(420.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xE61B1D22)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            progress != null -> {
                val label = if (progress >= 0f) "Trasferimento al tablet… ${(progress * 100).toInt()}%"
                else "Trasferimento al tablet…"
                Text(label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = Color(0xFF42A5F5),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = Color(0xFF42A5F5),
                    )
                }
            }
            result == true ->
                Text("Trasferimento completato ✓", color = Color(0xFF66BB6A), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            result == false ->
                Text("Trasferimento fallito ✗ — riprova", color = Color(0xFFEF5350), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}
