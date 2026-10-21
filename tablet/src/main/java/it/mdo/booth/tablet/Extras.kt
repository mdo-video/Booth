package it.mdo.booth.tablet

import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject

/** Batteria e spazio libero del telefono; diventa rosso quando serve attenzione. */
@Composable
fun StatusBadge(info: JSONObject, modifier: Modifier = Modifier) {
    val bat = info.optInt("battery", -1)
    val charging = info.optBoolean("charging")
    val freeMb = info.optLong("freeMb", -1L)
    if (bat < 0 && freeMb < 0) return
    val lowBat = bat in 0..19 && !charging
    val lowSpace = freeMb in 0..1999
    val warn = lowBat || lowSpace
    Column(
        modifier.clip(RoundedCornerShape(28.dp))
            .background(if (warn) Color(0xEEB71C1C) else Color(0x80000000))
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        val battery = if (bat >= 0) "🔋 $bat%" + (if (charging) " ⚡" else "") else ""
        val space = if (freeMb >= 0) "💾 " + "%.1f".format(freeMb / 1024.0) + " GB" else ""
        Text("$battery   $space".trim(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (lowBat) Text("Batteria del telefono bassa!", color = Color.White, fontSize = 14.sp)
        if (lowSpace) Text("Spazio del telefono quasi finito!", color = Color.White, fontSize = 14.sp)
    }
}

/**
 * Anteprima dal vivo: è l'unico pezzo che si ridisegna a ogni frame.
 * La cornice (se attiva) sta sopra, non specchiata, nello stesso rettangolo dell'immagine.
 */
@Composable
fun LivePreview(client: BoothClient, local: LocalSettings, overlay: ImageBitmap? = null) {
    val frame by client.frame.collectAsState()
    frame?.let { bmp ->
        val img = remember(bmp) { bmp.asImageBitmap() }
        val ar = bmp.width.toFloat() / bmp.height.toFloat()
        val (top, bottom, side) = when (local.borderMode) {
            0 -> Triple(0.dp, 0.dp, 0.dp)
            1 -> Triple(24.dp, 24.dp, 24.dp)
            else -> Triple(96.dp, 260.dp, 0.dp)
        }
        Box(Modifier.fillMaxSize().padding(start = side, end = side, top = top, bottom = bottom), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(ar)) {
                Image(
                    bitmap = img,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = if (local.mirror) -1f else 1f },
                    contentScale = ContentScale.FillBounds,
                )
                if (overlay != null && ar >= 1f) {
                    Image(
                        bitmap = overlay,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                }
            }
        }
    }
}
