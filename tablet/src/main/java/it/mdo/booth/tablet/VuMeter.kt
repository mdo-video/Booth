package it.mdo.booth.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Larghezza della barra del volume. */
val VuBarWidth: Dp = 28.dp

/** Scala della barra: 0% = -60 dB, 100% = 0 dB (fondo scala). Giallo da -18 dB, rosso da -6 dB. */
private const val VU_MIN_DB = -60
private const val VU_YELLOW_DB = -18
private const val VU_RED_DB = -6

private fun dbFraction(db: Int): Float = ((db - VU_MIN_DB) / (-VU_MIN_DB).toFloat()).coerceIn(0f, 1f)

/** Barra verticale del volume del microfono (0..100 = da -60 a 0 dB): verde, giallo, rosso, con il picco che resta un secondo. */
@Composable
fun VuMeter(level: Int, modifier: Modifier = Modifier) {
    var peak by remember { mutableIntStateOf(0) }
    LaunchedEffect(level) {
        if (level >= peak) peak = level
        else {
            delay(1000)
            if (peak > level) peak = level
        }
    }
    val yellowAt = (dbFraction(VU_YELLOW_DB) * 100).toInt()
    val redAt = (dbFraction(VU_RED_DB) * 100).toInt()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("MIC", color = Color(0xCCFFFFFF), fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        BoxWithConstraints(Modifier.width(VuBarWidth).weight(1f)) {
            val h = maxHeight
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(VuBarWidth / 2)).background(Color(0x66000000))) {
                val f = level.coerceIn(0, 100) / 100f
                val color = if (level >= redAt) Color(0xFFE53935) else if (level >= yellowAt) Color(0xFFFFC107) else Color(0xFF43A047)
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(f).background(color))
                // tacche: giallo e rosso
                for (db in listOf(VU_YELLOW_DB, VU_RED_DB)) {
                    Box(
                        Modifier.align(Alignment.TopStart).fillMaxWidth().height(2.dp)
                            .offset(y = h * (1f - dbFraction(db)) - 1.dp)
                            .background(Color(0xB3FFFFFF)),
                    )
                }
                // trattino del picco
                val p = peak.coerceIn(0, 100) / 100f
                if (p > 0.02f) {
                    Box(
                        Modifier.align(Alignment.TopStart).fillMaxWidth().height(3.dp)
                            .offset(y = h * (1f - p) - 3.dp)
                            .background(Color.White),
                    )
                }
            }
            // valori in dB a sinistra della barra
            for ((db, label) in listOf(0 to "0", VU_RED_DB to "$VU_RED_DB", VU_YELLOW_DB to "$VU_YELLOW_DB")) {
                Text(
                    label, color = Color(0xCCFFFFFF), fontSize = 10.sp, textAlign = TextAlign.End,
                    modifier = Modifier.align(Alignment.TopStart).width(26.dp)
                        .offset(x = (-30).dp, y = h * (1f - dbFraction(db)) - 7.dp),
                )
            }
        }
    }
}

