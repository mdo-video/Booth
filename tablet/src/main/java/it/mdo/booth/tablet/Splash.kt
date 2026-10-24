package it.mdo.booth.tablet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File

/** Immagine di apertura (PNG o JPG) scelta dalle impostazioni. Si vede all'avvio per qualche secondo, o con un tocco la si chiude. */
object SplashStore {
    var bitmap by mutableStateOf<Bitmap?>(null)
        private set
    /** vero finché la splash è a schermo */
    var visible by mutableStateOf(false)
    /** vero se è comparsa per inattività: resta finché non si tocca lo schermo */
    var hold by mutableStateOf(false)
    /** ultimo tocco o tasto (serve al timer di inattività) */
    @Volatile var lastActivity = System.currentTimeMillis()

    fun touch() { lastActivity = System.currentTimeMillis() }

    fun show(hold: Boolean) { this.hold = hold; visible = true }

    fun dismiss() { visible = false; hold = false; touch() }

    /** Apre la galleria per scegliere l'immagine (lo imposta MainActivity). */
    var pick: (() -> Unit)? = null

    private const val MAX_SIDE = 2560

    private fun file(ctx: Context) = File(ctx.filesDir, "splash.png")

    fun load(ctx: Context) {
        runCatching {
            val f = file(ctx)
            if (f.isFile) {
                bitmap = BitmapFactory.decodeFile(f.absolutePath)
                if (bitmap != null) show(false)
            }
        }
    }

    fun importUri(ctx: Context, uri: Uri): Boolean = try {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        bytes != null && importBytes(ctx, bytes)
    } catch (t: Throwable) {
        false
    }

    /** Decodifica un PNG/JPG (rimpicciolito se enorme) e lo salva come splash. */
    fun importBytes(ctx: Context, data: ByteArray): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        if (bmp == null) {
            false
        } else {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            file(ctx).writeBytes(out.toByteArray())
            bitmap = bmp
            true
        }
    } catch (t: Throwable) {
        false
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
        bitmap = null
        visible = false
    }
}

/** Splash a tutto schermo sopra a tutto: all'apertura sparisce da sola dopo 4 secondi; se è comparsa per inattività resta finché non si tocca. */
@Composable
fun SplashOverlay() {
    val bmp = SplashStore.bitmap
    if (!SplashStore.visible || bmp == null) return
    LaunchedEffect(SplashStore.hold) { if (!SplashStore.hold) { delay(4000); SplashStore.dismiss() } }
    Box(
        Modifier.fillMaxSize().background(Color.Black).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { SplashStore.dismiss() },
    ) {
        Image(
            bitmap = remember(bmp) { bmp.asImageBitmap() },
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}
