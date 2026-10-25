package it.mdo.booth.control

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Barra orizzontale del volume del microfono del telefono camera (stessa scala del tablet):
 * 0..100 = da -60 dB a 0 dB, verde, giallo da -18 dB, rosso da -6 dB, con il picco che resta un secondo.
 */
@Composable
fun LevelBar(level: Int, modifier: Modifier = Modifier) {
    var peak by remember { mutableIntStateOf(0) }
    LaunchedEffect(level) {
        if (level >= peak) {
            peak = level
        } else {
            delay(1000)
            if (peak > level) peak = level
        }
    }
    val yellowAt = 70 // -18 dB
    val redAt = 90 // -6 dB
    val barColor = if (level >= redAt) Color(0xFFE53935) else if (level >= yellowAt) Color(0xFFFFC107) else Color(0xFF43A047)
    val sub = Color(0xFF9AA0AC)
    Column(modifier) {
        Text("Volume microfono", color = sub, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp)) {
            val w = maxWidth
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)).background(Color(0xFF1B1E25))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0, 100) / 100f).background(barColor))
                for (pct in listOf(yellowAt, redAt)) {
                    Box(Modifier.fillMaxHeight().width(2.dp).offset(x = w * (pct / 100f) - 1.dp).background(Color(0xB3FFFFFF)))
                }
                val p = peak.coerceIn(0, 100)
                if (p > 2) {
                    Box(Modifier.fillMaxHeight().width(3.dp).offset(x = w * (p / 100f) - 3.dp).background(Color.White))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
            val w = maxWidth
            Text("-60", color = sub, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart))
            Text("-18", color = sub, fontSize = 10.sp, modifier = Modifier.offset(x = w * 0.70f - 8.dp))
            Text("-6", color = sub, fontSize = 10.sp, modifier = Modifier.offset(x = w * 0.90f - 5.dp))
            Text("0 dB", color = sub, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}
