package it.mdo.booth.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Tastierino per aprire le impostazioni dal tablet: stesso codice a 6 cifre dell'app Controllo.
 * Dopo 5 errori si blocca per 30 secondi.
 */
@Composable
fun PinOverlay(expected: String, onOk: () -> Unit, onCancel: () -> Unit) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var fails by remember { mutableIntStateOf(0) }
    var lockedFor by remember { mutableIntStateOf(0) }

    LaunchedEffect(lockedFor) {
        if (lockedFor > 0) { delay(1000); lockedFor-- }
    }
    LaunchedEffect(wrong) {
        if (wrong) { delay(700); wrong = false }
    }
    // si chiude da solo se nessuno tocca niente
    LaunchedEffect(entered, wrong) { delay(30_000); onCancel() }

    fun press(d: String) {
        if (lockedFor > 0 || wrong) return
        val next = (entered + d).take(6)
        entered = next
        if (next.length == 6) {
            if (next == expected) onOk()
            else {
                fails++
                entered = ""
                wrong = true
                if (fails >= 5) { fails = 0; lockedFor = 30 }
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xE6000000)).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
        ) { onCancel() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(340.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF1D1026))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Codice", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(6) { i ->
                    Box(
                        Modifier.size(18.dp).clip(CircleShape).background(
                            when {
                                wrong -> Color(0xFFE53935)
                                i < entered.length -> Color.White
                                else -> Color(0xFF4A3A5A)
                            }
                        )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    lockedFor > 0 -> "Troppi tentativi: attendi ${lockedFor}s"
                    wrong -> "Codice errato"
                    else -> " "
                },
                color = Color(0xFFEF9A9A), fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        r.forEach { d -> PinKey(d) { press(d) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PinKey("⌫") { if (lockedFor == 0) entered = entered.dropLast(1) }
                    PinKey("0") { press("0") }
                    PinKey("✕") { onCancel() }
                }
            }
        }
    }
}

@Composable
private fun PinKey(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 92.dp, height = 64.dp).clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF2A1B38)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}
