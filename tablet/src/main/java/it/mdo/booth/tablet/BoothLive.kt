package it.mdo.booth.tablet

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Diretta del booth per l'app Controllo: l'anteprima del telefono camera, ridotta a
 * massimo 640x480 e a 15 fotogrammi al secondo (poca banda). Si prepara solo se Controllo la chiede.
 */
object BoothLive {
    const val MAX_W = 640
    const val MAX_H = 480
    const val FPS = 15

    @Volatile var jpeg: ByteArray? = null
    @Volatile var seq = 0L
    @Volatile var at = 0L
    @Volatile var lastRequest = 0L

    /** Volume del microfono del telefono camera (0..100 = da -60 a 0 dB), per la barra in Controllo. */
    @Volatile var level = 0
    @Volatile var levelAt = 0L
    val levelNow: Int get() = if (System.currentTimeMillis() - levelAt < 2000) level else 0
    fun updateLevel(v: Int) { level = v; levelAt = System.currentTimeMillis() }

    val wanted: Boolean get() = System.currentTimeMillis() - lastRequest < 4000
    val fresh: Boolean get() = jpeg != null && System.currentTimeMillis() - at < 3000

    /** Chiamata per ogni anteprima decodificata: salta i fotogrammi in più per restare a 15 fps. */
    fun offer(src: Bitmap) {
        if (!wanted) return
        val now = System.currentTimeMillis()
        if (now - at < 1000L / FPS - 4) return
        runCatching {
            val scale = minOf(MAX_W.toFloat() / src.width, MAX_H.toFloat() / src.height, 1f)
            val w = (src.width * scale).toInt().coerceAtLeast(2)
            val h = (src.height * scale).toInt().coerceAtLeast(2)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(src, w, h, true) else src
            val out = ByteArrayOutputStream(24_000)
            small.compress(Bitmap.CompressFormat.JPEG, 60, out)
            if (small !== src) small.recycle()
            jpeg = out.toByteArray()
            at = now
            seq++
        }
    }
}
