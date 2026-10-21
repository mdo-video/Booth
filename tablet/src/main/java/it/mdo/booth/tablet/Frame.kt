package it.mdo.booth.tablet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/**
 * La cornice sul tablet: un PNG con trasparenza salvato in files/frame.png.
 * Serve in due punti: sovrapposta all'anteprima dal vivo e inviata al telefono (POST /frame),
 * che la applica a foto e video.
 */
class FrameStore(private val context: Context) {
    var bitmap by mutableStateOf<Bitmap?>(null)
        private set
    var id by mutableStateOf("")
        private set
    var bytes: ByteArray? = null
        private set

    /** Dopo l'importazione la cornice si accende da sola appena il telefono l'ha ricevuta. */
    var enableOnSync by mutableStateOf(false)

    private val file: File get() = File(context.filesDir, "frame.png")

    fun load() {
        try {
            val f = file
            if (!f.isFile) return
            val data = f.readBytes()
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return
            bytes = data
            bitmap = bmp
            id = crc(data)
        } catch (t: Throwable) {
            // file rovinato: si riparte senza cornice
        }
    }

    fun clear() {
        file.delete()
        bytes = null
        bitmap = null
        id = ""
        enableOnSync = false
    }

    fun importAsset(name: String): Boolean = try {
        context.assets.open(name).use { input ->
            val bmp = BitmapFactory.decodeStream(input, null, decodeOptions())
            bmp != null && store(bmp)
        }
    } catch (t: Throwable) {
        false
    }

    fun importUri(uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bmp = BitmapFactory.decodeStream(input, null, decodeOptions())
            bmp != null && store(bmp)
        } ?: false
    } catch (t: Throwable) {
        false
    }

    private fun decodeOptions() = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inMutable = true
    }

    private fun crc(b: ByteArray): String = CRC32().also { it.update(b) }.value.toString(16)

    private fun store(src: Bitmap): Boolean {
        var bmp = src
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / longest
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        }
        if (bmp.config != Bitmap.Config.ARGB_8888 || !bmp.isMutable) {
            bmp = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return false
        }
        // una cornice senza trasparenza (es. un JPG con il centro bianco) viene resa trasparente
        val centerAlpha = Color.alpha(bmp.getPixel(bmp.width / 2, bmp.height / 2))
        if (!bmp.hasAlpha() || centerAlpha > 250) whiteToTransparent(bmp)

        val out = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) return false
        val data = out.toByteArray()
        file.writeBytes(data)
        bytes = data
        bitmap = bmp
        id = crc(data)
        return true
    }

    companion object {
        const val MAX_SIDE = 3840
        private const val WHITE = 240

        private fun isWhite(p: Int): Boolean =
            ((p shr 16) and 255) >= WHITE && ((p shr 8) and 255) >= WHITE && (p and 255) >= WHITE

        /**
         * Rende trasparente il bianco collegato al centro o ai bordi dell'immagine (riempimento a macchia).
         * I petali bianchi, chiusi da un contorno, restano pieni. Il bordo viene ammorbidito di 1 pixel.
         */
        fun whiteToTransparent(bmp: Bitmap) {
            val w = bmp.width
            val h = bmp.height
            val n = w * h
            val px = IntArray(n)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val bg = BooleanArray(n)
            val stack = IntArray(n)
            var sp = 0

            // semi: il centro e tutto il perimetro
            val seeds = IntArray(2 * w + 2 * h + 1)
            var k = 0
            seeds[k++] = (h / 2) * w + w / 2
            for (x in 0 until w) { seeds[k++] = x; seeds[k++] = (h - 1) * w + x }
            for (y in 0 until h) { seeds[k++] = y * w; seeds[k++] = y * w + (w - 1) }
            for (s in seeds) {
                if (!bg[s] && isWhite(px[s])) { bg[s] = true; stack[sp++] = s }
            }
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                if (x > 0 && !bg[i - 1] && isWhite(px[i - 1])) { bg[i - 1] = true; stack[sp++] = i - 1 }
                if (x < w - 1 && !bg[i + 1] && isWhite(px[i + 1])) { bg[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && !bg[i - w] && isWhite(px[i - w])) { bg[i - w] = true; stack[sp++] = i - w }
                if (y < h - 1 && !bg[i + w] && isWhite(px[i + w])) { bg[i + w] = true; stack[sp++] = i + w }
            }

            for (i in 0 until n) {
                if (bg[i]) { px[i] = 0; continue }
                val x = i % w
                val y = i / w
                val edge = (x > 0 && bg[i - 1]) || (x < w - 1 && bg[i + 1]) ||
                    (y > 0 && bg[i - w]) || (y < h - 1 && bg[i + w])
                if (!edge) { px[i] = px[i] or (0xFF shl 24); continue }
                val p = px[i]
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                val a = ((255 - minOf(r, g, b)) * 255 / (255 - WHITE)).coerceIn(0, 255)
                if (a == 0) { px[i] = 0; continue }
                val back = 255 * (255 - a) / 255
                val r2 = ((r - back) * 255 / a).coerceIn(0, 255)
                val g2 = ((g - back) * 255 / a).coerceIn(0, 255)
                val b2 = ((b - back) * 255 / a).coerceIn(0, 255)
                px[i] = (a shl 24) or (r2 shl 16) or (g2 shl 8) or b2
            }
            bmp.setHasAlpha(true)
            bmp.setPixels(px, 0, w, 0, 0, w, h)
        }
    }
}
