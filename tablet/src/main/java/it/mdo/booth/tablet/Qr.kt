package it.mdo.booth.tablet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

/** Richiesta di mostrare i QR: con videoUrl anche quello per scaricare il file. */
data class QrRequest(val videoUrl: String?)

/** Testo del QR che fa collegare un telefono a una rete Wi-Fi. */
fun wifiQrPayload(ssid: String, pass: String): String? {
    if (ssid.isBlank()) return null
    fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
        .replace(":", "\\:").replace("\"", "\\\"")
    return if (pass.isBlank()) "WIFI:T:nopass;S:${esc(ssid)};;"
    else "WIFI:T:WPA;S:${esc(ssid)};P:${esc(pass)};;"
}

/** QR a tutto schermo: 1) collegati al Wi-Fi, 2) scarica il file. Tocca per chiudere. */
@Composable
fun QrOverlay(wifi: String?, videoUrl: String?, onClose: () -> Unit) {
    val wifiBmp = remember(wifi) { wifi?.let { makeQr(it, 640) } }
    val videoBmp = remember(videoUrl) { videoUrl?.let { makeQr(it, 640) } }
    Box(
        Modifier.fillMaxSize().background(Color(0xE6000000)).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClose,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
            QrCard(
                Lang.t("1 · Collegati al Wi-Fi", "1 · Join the Wi-Fi"), Lang.t("Inquadra per entrare nella rete del booth", "Scan to join the booth network"), wifiBmp,
                Lang.t("Imposta il nome e la password del Wi-Fi nelle impostazioni del tablet", "Set the Wi-Fi name and password in the tablet settings"),
            )
            if (videoUrl != null) {
                QrCard(Lang.t("2 · Scarica il tuo file", "2 · Download your file"), Lang.t("Inquadra dopo esserti collegato al Wi-Fi", "Scan after joining the Wi-Fi"), videoBmp, "")
            }
        }
        Text(
            Lang.t("Tocca per chiudere", "Tap to close"), color = Color(0xB3FFFFFF), fontSize = 16.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun QrCard(title: String, subtitle: String, bmp: Bitmap?, missing: String) {
    Column(
        Modifier.width(320.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        if (bmp != null) {
            Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(280.dp))
        } else {
            Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                Text(missing, color = Color.DarkGray, fontSize = 16.sp, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(subtitle, color = Color.DarkGray, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

private fun makeQr(text: String, size: Int): Bitmap? = runCatching {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}.getOrNull()

/**
 * Scarica il file dal telefono nella cache del tablet (con barra di avanzamento) e poi lo mostra:
 * video con il player, foto come immagine. Così l'anteprima si vede sempre.
 */
@Composable
fun DownloadedMedia(client: BoothClient, path: String, cacheKey: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var file by remember(cacheKey) { mutableStateOf<File?>(null) }
    var progress by remember(cacheKey) { mutableStateOf(0f) }
    var failed by remember(cacheKey) { mutableStateOf(false) }
    var attempt by remember(cacheKey) { mutableIntStateOf(0) }

    LaunchedEffect(cacheKey, attempt) {
        failed = false
        progress = 0f
        val dir = File(ctx.cacheDir, "previews").apply { mkdirs() }
        val dest = File(dir, cacheKey)
        if (dest.isFile && dest.length() > 0) {
            file = dest
            return@LaunchedEffect
        }
        val part = File(dir, "$cacheKey.part")
        client.download(path, part, { p -> progress = p }) { ok ->
            if (ok && part.renameTo(dest)) {
                dir.listFiles { f -> f.name.endsWith(".mp4") || f.name.endsWith(".jpg") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(12)
                    ?.forEach { it.delete() }
                file = dest
            } else {
                part.delete()
                failed = true
            }
        }
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val f = file
        when {
            f != null && cacheKey.endsWith(".jpg") -> {
                val bmp = remember(f) { decodeUpright(f.absolutePath) }
                bmp?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            f != null -> VideoPlayer(Uri.fromFile(f).toString(), Modifier.fillMaxSize())
            failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Lang.t("Anteprima non disponibile", "Preview unavailable"), color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                CardButton("Riprova", Color(0xFF3A3F47), Modifier.width(120.dp)) { attempt++ }
            }
            else -> Column(Modifier.padding(16.dp)) {
                val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
                Text(Lang.t("Scarico l'anteprima…", "Loading preview…") + " $pct%", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun decodeUpright(path: String): Bitmap? = runCatching {
    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
    val b = BitmapFactory.decodeFile(path, opts) ?: return@runCatching null
    val deg = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
}.getOrNull()
