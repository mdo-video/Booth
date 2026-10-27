package it.mdo.booth.tablet

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Misure di un'anteprima che rispetta la proporzione `ar` (larghezza/altezza) dentro un riquadro massimo:
 * orizzontale -> larga, verticale -> alta e stretta.
 */
internal fun fitMedia(ar: Float, maxW: Dp, maxH: Dp): Pair<Dp, Dp> {
    val a = ar.coerceIn(0.4f, 3f)
    val w = minOf(maxW, maxH * a)
    return w to (w / a)
}

/** Altezza massima dell'anteprima dentro una scheda: lascia posto ai tasti e ai margini, anche per i video verticali. */
@Composable
internal fun cardMediaMaxHeight(): Dp {
    val h = LocalConfiguration.current.screenHeightDp
    return (h - 330).coerceIn(220, 560).dp
}

/** Scheda al centro dell'anteprima: guarda il video, poi Salva / Rifai / Condividi (QR). */
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
    remoteHints: Boolean = false,
    /** proporzione di partenza (quella dell'anteprima dal vivo); poi si usa quella vera del file */
    aspect: Float = 16f / 9f,
) {
    val hint = { n: Int -> if (remoteHints) "  ·  $n " + Lang.t("clic", if (n == 1) "click" else "clicks") else "" }
    var realAr by remember(cacheKey) { mutableStateOf<Float?>(null) }
    val (mw, mh) = fitMedia(realAr ?: aspect, 528.dp, cardMediaMaxHeight())
    Column(
        modifier.width(maxOf(mw, 360.dp) + 32.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xF21C1D22)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DownloadedMedia(client, path, cacheKey, Modifier.size(mw, mh).clip(RoundedCornerShape(14.dp)), onAspect = { realAr = it })
        }
        if (saved) {
            Text(
                Lang.t("Salvata ✓", "Saved ✓") + " ${savedName ?: ""}",
                color = Color(0xFF66BB6A), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
            BigButton(Lang.t("Nuova", "New"), Color(0xFFFF2D55), onRedo)
            OutlineButton(Lang.t("Condividi", "Share"), Modifier.fillMaxWidth(), onShare)
        } else {
            BigButton(Lang.t("Salva", "Save") + hint(1), Color(0xFF2E7D32), onSave)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlineButton(Lang.t("Rifai", "Retake") + hint(2), Modifier.weight(1f), onRedo)
                // Condividi salva (se serve) e mostra il QR: non si esce mai dal kiosk
                OutlineButton(Lang.t("Condividi", "Share") + hint(3), Modifier.weight(1f), onShare)
            }
        }
    }
}

/** Tasto grande a tutta larghezza (Salva). */
@Composable
internal fun BigButton(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** Tasto con bordo sottile (Rifai, Condividi, Chiudi). */
@Composable
internal fun OutlineButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.height(50.dp).clip(shape).background(Color(0xFF1F2025)).border(1.dp, Color(0xFF343539), shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
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
