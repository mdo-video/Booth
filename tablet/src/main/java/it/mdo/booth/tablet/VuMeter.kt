package it.mdo.booth.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Barra verticale del volume del microfono (0..100): verde, giallo, rosso, con il picco che resta un secondo. */
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
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("MIC", color = Color(0xCCFFFFFF), fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        Box(
            Modifier.width(14.dp).weight(1f).clip(RoundedCornerShape(7.dp)).background(Color(0x66000000)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val f = level.coerceIn(0, 100) / 100f
            val color = if (level >= 90) Color(0xFFE53935) else if (level >= 70) Color(0xFFFFC107) else Color(0xFF43A047)
            Box(Modifier.fillMaxWidth().fillMaxHeight(f).background(color))
            // trattino del picco
            val p = peak.coerceIn(0, 100) / 100f
            if (p > 0.02f) {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxHeight().fillMaxWidth()) {
                    Box(
                        Modifier.fillMaxWidth().height(3.dp)
                            .offset(y = maxHeight * (1f - p) - 3.dp)
                            .background(Color.White),
                    )
                }
            }
        }
    }
}
