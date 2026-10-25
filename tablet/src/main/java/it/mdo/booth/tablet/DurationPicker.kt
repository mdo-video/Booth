package it.mdo.booth.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Slider sullo schermo principale: l'ospite sceglie la durata del video (30, 60, 90 o 120 secondi). */
@Composable
fun DurationPicker(current: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(30, 60, 90, 120)
    var idx by remember(current) {
        mutableFloatStateOf(options.indices.minByOrNull { abs(options[it] - current) }!!.toFloat())
    }
    val shown = options[idx.roundToInt().coerceIn(0, options.lastIndex)]
    Column(
        modifier.width(280.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xB3000000))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            Lang.t("Durata massima: $shown s", "Max length: $shown s"),
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
        Slider(
            value = idx,
            onValueChange = { idx = it },
            onValueChangeFinished = { onPick(options[idx.roundToInt().coerceIn(0, options.lastIndex)]) },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = options.size - 2,
            colors = SliderDefaults.colors(thumbColor = Color(0xFFFF2D55), activeTrackColor = Color(0xFFFF2D55)),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            options.forEach { Text("$it", color = Color(0xCCFFFFFF), fontSize = 12.sp) }
        }
    }
}
