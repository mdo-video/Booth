package it.mdo.booth.tablet

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

private val Accent = Color(0xFFFF2D55)
private val AccentSoft = Color(0xFFFF7A59)
private val TextShadow = Shadow(Color(0xCC000000), Offset(0f, 1f), 8f)

/**
 * Slider sullo schermo principale: l'ospite sceglie la durata del video (30, 60, 90 o 120 secondi).
 * Senza sfondo: valori sopra la barra, "Durata massima" sotto, tutto con ombra per leggersi sull'anteprima.
 */
@Composable
fun DurationPicker(current: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(30, 60, 90, 120)
    val last = options.lastIndex
    var pos by remember(current) {
        mutableFloatStateOf(options.indices.minByOrNull { abs(options[it] - current) }!!.toFloat())
    }
    var dragging by remember { mutableStateOf(false) }
    val shownPos by animateFloatAsState(
        targetValue = pos,
        animationSpec = if (dragging) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "durPos",
    )
    val sel = pos.roundToInt().coerceIn(0, last)

    Column(modifier.width(196.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val inset = 16.dp
            val inner = maxWidth - inset * 2
            val labelW = 44.dp
            val innerPx = with(androidx.compose.ui.platform.LocalDensity.current) { inner.toPx() }
            val insetPx = with(androidx.compose.ui.platform.LocalDensity.current) { inset.toPx() }

            fun posAt(x: Float) = (((x - insetPx) / innerPx) * last).coerceIn(0f, last.toFloat())

            Column {
                // valori, subito sopra la barra
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(22.dp)) {
                    options.forEachIndexed { i, v ->
                        val on = i == sel
                        Text(
                            "$v",
                            textAlign = TextAlign.Center,
                            color = if (on) Color.White else Color(0xB3FFFFFF),
                            fontSize = if (on) 17.sp else 13.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            style = TextStyle(shadow = TextShadow),
                            modifier = Modifier
                                .width(labelW)
                                .offset(x = inset + inner * (i.toFloat() / last) - labelW / 2)
                                .align(Alignment.CenterStart),
                        )
                    }
                }
                // barra moderna: linea sottile, tacche, cursore con alone
                Canvas(
                    Modifier.fillMaxWidth().height(34.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { o ->
                                pos = posAt(o.x).roundToInt().toFloat()
                                onPick(options[pos.roundToInt().coerceIn(0, last)])
                            }
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { o -> dragging = true; pos = posAt(o.x) },
                                onHorizontalDrag = { change, _ -> change.consume(); pos = posAt(change.position.x) },
                                onDragEnd = {
                                    dragging = false
                                    pos = pos.roundToInt().coerceIn(0, last).toFloat()
                                    onPick(options[pos.roundToInt().coerceIn(0, last)])
                                },
                                onDragCancel = { dragging = false; pos = pos.roundToInt().toFloat() },
                            )
                        },
                ) {
                    val cy = size.height / 2
                    val x0 = insetPx
                    val x1 = insetPx + innerPx
                    val tx = x0 + innerPx * (shownPos / last)
                    val stroke = 5.dp.toPx()
                    // binario con un'ombra leggera per staccarsi dall'anteprima
                    drawLine(Color(0x66000000), Offset(x0, cy + 1.5f), Offset(x1, cy + 1.5f), stroke + 2f, StrokeCap.Round)
                    drawLine(Color(0x73FFFFFF), Offset(x0, cy), Offset(x1, cy), stroke, StrokeCap.Round)
                    // parte attiva col gradiente
                    if (tx > x0) {
                        drawLine(
                            Brush.horizontalGradient(listOf(AccentSoft, Accent), startX = x0, endX = x1),
                            Offset(x0, cy), Offset(tx, cy), stroke, StrokeCap.Round,
                        )
                    }
                    // tacche
                    for (i in 0..last) {
                        val x = x0 + innerPx * (i.toFloat() / last)
                        val passed = x <= tx + 1f
                        drawCircle(if (passed) Color.White else Color(0xB3FFFFFF), 3.dp.toPx(), Offset(x, cy))
                    }
                    // cursore: alone + bianco + centro rosso
                    drawCircle(Accent.copy(alpha = if (dragging) 0.40f else 0.26f), if (dragging) 17.dp.toPx() else 14.dp.toPx(), Offset(tx, cy))
                    drawCircle(Color.White, 10.dp.toPx(), Offset(tx, cy))
                    drawCircle(Accent, 5.dp.toPx(), Offset(tx, cy))
                }
            }
        }
        Text(
            Lang.t("Durata massima: ${options[sel]} s", "Max length: ${options[sel]} s"),
            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            style = TextStyle(shadow = TextShadow),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
