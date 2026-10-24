package it.mdo.booth.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fila di effetti da toccare (stile Instagram): si vedono subito nell'anteprima e valgono per foto e video. */
@Composable
fun FilterStrip(current: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Filters.shown.forEach { f ->
            val sel = f.id == current
            Text(
                Filters.label(f),
                color = if (sel) Color(0xFF14101A) else Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (sel) Color(0xFFFFE83A) else Color(0x99000000))
                    .clickable { onPick(f.id) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}
