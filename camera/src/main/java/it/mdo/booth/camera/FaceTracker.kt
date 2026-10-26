package it.mdo.booth.camera

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.TimeUnit

/** Riconoscimento volti con ML Kit (modello incluso nell'app, funziona senza internet). Chiamare da un thread secondario. */
class FaceTracker {
    private val fast by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.06f)
                .build()
        )
    }
    private val accurate by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.04f)
                .build()
        )
    }

    /** Volti di un'immagine dritta, per l'anteprima e il video (veloce). */
    fun detectLive(bmp: Bitmap): List<ArFace> = detect(bmp, false)

    /** Volti di una foto (più preciso; l'immagine viene rimpicciolita per andare più in fretta). */
    fun detectStill(bmp: Bitmap): List<ArFace> {
        val max = 1280
        val side = maxOf(bmp.width, bmp.height)
        if (side <= max) return detect(bmp, true)
        val s = max.toFloat() / side
        val small = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        return try {
            detect(small, true)
        } finally {
            small.recycle()
        }
    }

    private fun detect(bmp: Bitmap, precise: Boolean): List<ArFace> = try {
        val det = if (precise) accurate else fast
        val faces = Tasks.await(det.process(InputImage.fromBitmap(bmp, 0)), 4, TimeUnit.SECONDS)
        faces.map { toFace(it, bmp.width.toFloat(), bmp.height.toFloat()) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun toFace(f: Face, w: Float, h: Float): ArFace {
        val b = f.boundingBox
        val bw = b.width().toFloat()
        val bh = b.height().toFloat()
        val ex = f.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val ey = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        var ax = ex?.x ?: (b.exactCenterX() - 0.20f * bw)
        var ay = ex?.y ?: (b.top + 0.40f * bh)
        var bx = ey?.x ?: (b.exactCenterX() + 0.20f * bw)
        var by = ey?.y ?: (b.top + 0.40f * bh)
        if (ax > bx) { val tx = ax; ax = bx; bx = tx; val ty = ay; ay = by; by = ty }
        return ArFace(b.exactCenterX() / w, b.exactCenterY() / h, bw / w, bh / h, ax / w, ay / h, bx / w, by / h)
    }
}
