package it.mdo.booth.camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.media3.effect.BitmapOverlay

/**
 * Livello trasparente a misura di video con i filtri AR, per il passaggio finale (Media3 Transformer).
 * Il disegno si rifà ogni 50 ms usando i volti registrati durante la ripresa.
 */
@SuppressLint("UnsafeOptInUsageError")
class ArOverlay(
    private val w: Int,
    private val h: Int,
    private val id: String,
    private val track: List<ArTrackPt>,
) : BitmapOverlay() {
    private val bmps = arrayOf(
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
    )
    private var idx = 0
    private var lastBucket = -1L

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val bucket = presentationTimeUs / 50_000L
        if (bucket != lastBucket) {
            lastBucket = bucket
            idx = 1 - idx          // due bitmap alternate: Media3 ricarica la texture solo se cambia l'istanza
            val b = bmps[idx]
            b.eraseColor(0)
            val faces = ArFilters.at(track, presentationTimeUs / 1000L)
            if (faces.isNotEmpty()) ArFilters.draw(Canvas(b), id, faces, w, h)
        }
        return bmps[idx]
    }

    fun free() {
        bmps.forEach { runCatching { it.recycle() } }
    }
}
