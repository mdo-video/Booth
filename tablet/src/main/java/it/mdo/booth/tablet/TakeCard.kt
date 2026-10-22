package it.mdo.booth.tablet

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.viewinterop.AndroidView

/** Piccola anteprima in basso a destra: guarda il video, poi Rifai / Salva / Condividi (QR). */
@Composable
fun TakeCard(
    client: BoothClient,
    path: String,
    cacheKey: String,
    saved: Boolean,
    savedName: String?,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(320.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xEE1B1D22)).padding(10.dp)
    ) {
        DownloadedMedia(client, path, cacheKey, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.height(8.dp))
        if (saved) {
            Text("Salvata ✓ ${savedName ?: ""}", color = Color(0xFF66BB6A), fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!saved) {
                CardButton("Rifai", Color(0xFF3A3F47), Modifier.weight(1f), onRedo)
                CardButton("Salva", Color(0xFF2E7D32), Modifier.weight(1f), onSave)
            } else {
                CardButton("Nuova", Color(0xFFE53935), Modifier.weight(1f), onRedo)
            }
            // Condividi salva (se serve) e mostra il QR: non si esce mai dal kiosk
            CardButton("Condividi", Color(0xFF1565C0), Modifier.weight(1f), onShare)
        }
    }
}

@Composable
internal fun CardButton(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(color).clickable(onClick = onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun VideoPlayer(url: String, modifier: Modifier) {
    var paused by remember(url) { mutableStateOf(false) }
    val holder = remember(url) { arrayOfNulls<VideoView>(1) }
    DisposableEffect(url) { onDispose { holder[0]?.stopPlayback() } }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).also { v ->
                    holder[0] = v
                    v.setOnPreparedListener { mp -> mp.isLooping = true; v.start() }
                    v.setVideoURI(Uri.parse(url))
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // tocca per mettere in pausa / riprendere
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                holder[0]?.let { v ->
                    if (v.isPlaying) { v.pause(); paused = true } else { v.start(); paused = false }
                }
            }
        )
        if (paused) {
            Text("▶", color = Color.White, fontSize = 48.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}
