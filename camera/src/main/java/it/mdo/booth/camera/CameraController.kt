package it.mdo.booth.camera

import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.UseCase
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCapture
import android.media.ExifInterface
import android.graphics.BitmapFactory
import kotlin.math.hypot
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraFilter
import androidx.camera.camera2.interop.Camera2CameraInfo
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.graphics.YuvImage
import android.graphics.Rect
import android.graphics.ImageFormat
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import android.os.BatteryManager
import android.content.IntentFilter
import android.content.Intent
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.util.zip.CRC32
import kotlin.math.abs

/**
 * Registra in un file temporaneo ("take"). Il tablet lo rivede in streaming e poi decide:
 * SAVE (copia in galleria Movies/Booth) o DISCARD (cancella). Tutte le impostazioni
 * arrivano dal tablet e vengono ricordate qui.
 */
class CameraController(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val wantsFrames: () -> Boolean,
    private val onPreviewFrame: (ByteArray) -> Unit,
    private val onState: (String) -> Unit,   // persistente: READY | RECORDING | TAKE:file | TAKE_SAVED:file:nome
    private val onEvent: (String) -> Unit,   // una tantum: SAVED:nome | ERROR:x
    private val onInfo: (String) -> Unit,    // JSON con capacità e impostazioni correnti
    private val onOrientation: (String) -> Unit = {},  // landscape | portrait
) {
    companion object {
        const val PREVIEW_FPS = 30
        const val JPEG_QUALITY = 40
        private val QUALITIES = linkedMapOf("HD" to Quality.HD, "FHD" to Quality.FHD, "UHD" to Quality.UHD)
    }

    private val main = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val io = Executors.newSingleThreadExecutor()
    private val prefs = context.getSharedPreferences("booth", Context.MODE_PRIVATE)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var recording: Recording? = null
    @Volatile private var pendingTake: File? = null
    private var savedName: String? = null
    @Volatile private var saving = false
    private var lastFrameMs = 0L
    @Volatile private var shooting = false     // foto in corso (flash + scatto)
    @Volatile private var processing = false   // cornice in applicazione
    private var transformer: Transformer? = null
    private var overlayBmp: Bitmap? = null
    @Volatile private var frameBmp: Bitmap? = null
    @Volatile private var frameId = ""
    private val autoStop = Runnable { stopRecording() }

    // impostazioni (persistenti)
    private var quality = prefs.getString("quality", "FHD") ?: "FHD"
    private var durationSec = prefs.getInt("duration", 60)
    private var zoom = prefs.getFloat("zoom", 1f)
    private var torch = prefs.getBoolean("torch", false)
    private var ev = prefs.getInt("ev", 0)
    private var prefix = prefs.getString("prefix", "dedica") ?: "dedica"
    private var counter = prefs.getInt("counter", 0)
    private var flash = prefs.getBoolean("flash", true)      // flash (doppio LED) negli scatti
    private var filter = prefs.getString("filter", "none") ?: "none"   // effetto colore su foto e video
    private var frameOn = prefs.getBoolean("frame", false)   // cornice su foto e video
    private var orientation = prefs.getString("orientation", "landscape") ?: "landscape"
    private val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var lensDesc = ""

    /** Fotocamera posteriore con il flash (usata se l'obiettivo scelto non ha la torcia). */
    private val torchCameraId: String? by lazy {
        runCatching {
            camManager.cameraIdList.firstOrNull { id ->
                val ch = camManager.getCameraCharacteristics(id)
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }

    /** Lunghezza focale equivalente (più bassa = più grandangolare) di un obiettivo posteriore. */
    private fun equivFocal(info: CameraInfo): Double? = runCatching {
        val c2 = Camera2CameraInfo.from(info)
        if (c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
            return@runCatching null
        }
        val f = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
            ?: return@runCatching null
        val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return@runCatching null
        (f * 43.27 / hypot(size.width, size.height)).toDouble()
    }.getOrNull()

    /** Sceglie tra gli obiettivi posteriori visibili il più grandangolare. */
    private fun widestSelector(p: ProcessCameraProvider): CameraSelector {
        val best = runCatching {
            p.availableCameraInfos.mapNotNull { i -> equivFocal(i)?.let { i to it } }.minByOrNull { it.second }
        }.getOrNull()
        if (best == null) {
            lensDesc = ""
            return CameraSelector.DEFAULT_BACK_CAMERA
        }
        val id = Camera2CameraInfo.from(best.first).cameraId
        lensDesc = "equiv. " + "%.0f".format(best.second) + " mm (camera " + id + ")"
        return CameraSelector.Builder()
            .addCameraFilter(CameraFilter { infos ->
                infos.filter { runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() == id }.toMutableList()
            })
            .build()
    }

    private var lock = false  // fuoco, esposizione e bianco bloccati (non persistente)

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = publishInfo()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = publishInfo()
    }

    private val takesDir: File
        get() = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir

    private val historyDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "history").apply { mkdirs() }

    private val thumbsDir: File
        get() = File(historyDir, "thumbs").apply { mkdirs() }

    private fun isMedia(f: File) = f.isFile && (f.name.endsWith(".mp4") || f.name.endsWith(".jpg"))

    /** Video e foto salvati, dal più recente. */
    private fun historyItems(): List<File> =
        (historyDir.listFiles()?.filter { isMedia(it) } ?: emptyList()).sortedByDescending { it.lastModified() }

    /** File dello storico (video .mp4 o foto .jpg) per nome. */
    fun historyFile(name: String): File? {
        val clean = File(name).name
        if (!clean.endsWith(".mp4") && !clean.endsWith(".jpg")) return null
        return File(historyDir, clean).takeIf { it.isFile }
    }

    fun thumbFile(name: String): File? = File(thumbsDir, File(name).name).takeIf { it.isFile }

    /** Elenco JSON dei salvati, dal più recente. */
    fun historyJson(): String {
        val arr = JSONArray()
        historyItems().forEach { arr.put(JSONObject().put("name", it.name).put("time", it.lastModified())) }
        return arr.toString()
    }

    /** Dalla 1.4 le miniature stavano accanto ai video: le sposta nella cartella thumbs. */
    private fun migrateThumbs() {
        runCatching {
            historyDir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }?.forEach { j ->
                if (File(historyDir, j.name.removeSuffix(".jpg") + ".mp4").isFile) {
                    j.renameTo(File(thumbsDir, j.name))
                }
            }
        }
    }

    /** Copia il file nello storico, crea la miniatura e tiene solo gli ultimi 50. */
    private fun keepInHistory(src: File, name: String) {
        runCatching {
            val dst = File(historyDir, name)
            src.copyTo(dst, overwrite = true)
            var bmp: Bitmap? = null
            if (name.endsWith(".jpg")) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                bmp = BitmapFactory.decodeFile(dst.absolutePath, opts)?.let { b ->
                    val deg = when (ExifInterface(dst.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                        6 -> 90f
                        3 -> 180f
                        8 -> 270f
                        else -> 0f
                    }
                    if (deg != 0f) Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true) else b
                }
            } else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(dst.absolutePath)
                    bmp = r.getFrameAtTime(500_000)
                } finally {
                    r.release()
                }
            }
            bmp?.let { b ->
                val w = 360
                val h = (b.height * w / b.width.toFloat()).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(b, w, h, true)
                File(thumbsDir, name.substringBeforeLast('.') + ".jpg").outputStream().use {
                    small.compress(Bitmap.CompressFormat.JPEG, 80, it)
                }
            }
            historyItems().drop(50).forEach {
                it.delete()
                File(thumbsDir, it.name.substringBeforeLast('.') + ".jpg").delete()
            }
        }
    }

    // ---------------------------------------------------------------- cornice

    private val frameFile: File get() = File(context.filesDir, "frame.png")

    private fun crc(b: ByteArray): String = CRC32().also { it.update(b) }.value.toString(16)

    private fun loadFrame() {
        try {
            val f = frameFile
            if (!f.isFile) return
            val bytes = f.readBytes()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            frameBmp = bmp
            frameId = crc(bytes)
        } catch (t: Throwable) {
            // cornice illeggibile: si ignora
        }
    }

    /** Riceve dal tablet il PNG (con trasparenza) della cornice. */
    fun saveFrame(bytes: ByteArray): Boolean {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        return try {
            frameFile.writeBytes(bytes)
            frameBmp = bmp
            frameId = crc(bytes)
            main.post { publishInfo() }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** La cornice è orizzontale: ha senso solo su immagini più larghe che alte. */
    private fun sizeFitsFrame(w: Int, h: Int): Boolean = w > 0 && h > 0 && w >= h

    private fun beginTake(file: File) {
        shooting = false
        processing = false
        main.removeCallbacks(progressTick)
        pendingTake = file
        savedName = null
        onState("TAKE:${file.name}")
    }

    /** Foto: ruota secondo EXIF, ritaglia 16:9 come l'anteprima, sovrappone la cornice. null = ok, altrimenti il motivo. */
    private fun compositePhoto(file: File, frame: Bitmap): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val deg = when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            6 -> 90f
            3 -> 180f
            8 -> 270f
            else -> 0f
        }
        val turned = deg == 90f || deg == 270f
        val dispW = if (turned) bounds.outHeight else bounds.outWidth
        val dispH = if (turned) bounds.outWidth else bounds.outHeight
        if (!sizeFitsFrame(dispW, dispH)) return "la cornice funziona solo in orizzontale"

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
        val src = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return "foto non leggibile"
        val upright = if (deg != 0f) {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(deg) }, true)
        } else src

        // stesso rapporto della cornice (come l'anteprima del tablet)
        val fa = frame.width.toFloat() / frame.height
        val ua = upright.width.toFloat() / upright.height
        val cw: Int
        val ch: Int
        if (ua > fa) {
            ch = upright.height
            cw = (ch * fa).toInt().coerceIn(1, upright.width)
        } else {
            cw = upright.width
            ch = (cw / fa).toInt().coerceIn(1, upright.height)
        }
        val cx = (upright.width - cw) / 2
        val cy = (upright.height - ch) / 2

        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(upright, Rect(cx, cy, cx + cw, cy + ch), Rect(0, 0, cw, ch), Filters.paint(filter))
        canvas.drawBitmap(frame, null, Rect(0, 0, cw, ch), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val tmp = File(file.parentFile, file.name + ".tmp")
        val ok = tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        out.recycle()
        if (upright !== src) upright.recycle()
        src.recycle()
        if (!ok || !tmp.renameTo(file)) {
            tmp.delete()
            return "salvataggio foto"
        }
        return null
    }

    /** Foto con solo l'effetto colore (senza cornice): ruota secondo EXIF e salva. null = ok, altrimenti il motivo. */
    private fun filterPhoto(file: File): String? {
        val deg = when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            6 -> 90f
            3 -> 180f
            8 -> 270f
            else -> 0f
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
        val src = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return "foto non leggibile"
        val upright = if (deg != 0f) {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(deg) }, true)
        } else src
        val out = Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(upright, 0f, 0f, Filters.paint(filter))
        val tmp = File(file.parentFile, file.name + ".tmp")
        val ok = tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        out.recycle()
        if (upright !== src) upright.recycle()
        src.recycle()
        if (!ok || !tmp.renameTo(file)) {
            tmp.delete()
            return "salvataggio foto"
        }
        return null
    }

    private fun finishPhoto(file: File) {
        val fr = frameBmp
        val useFrame = frameOn && fr != null
        if (useFrame || filter != "none") {
            processing = true
            onState("PROCESSING:0")
            io.execute {
                val err = try {
                    var e = if (useFrame && fr != null) compositePhoto(file, fr) else filterPhoto(file)
                    // la cornice non entra (foto verticale): resta almeno l'effetto
                    if (e != null && useFrame && filter != "none") e = filterPhoto(file)
                    e
                } catch (t: Throwable) {
                    "effetto o cornice non applicati"
                }
                main.post {
                    if (err != null) onEvent("ERROR:$err")
                    beginTake(file)
                }
            }
        } else {
            beginTake(file)
        }
    }

    private fun videoDisplaySize(f: File): Pair<Int, Int>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            val w0 = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h0 = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w0 == null || h0 == null) null
            else if (rot == 90 || rot == 270) Pair(h0, w0)
            else Pair(w0, h0)
        } catch (t: Throwable) {
            null
        } finally {
            r.release()
        }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            val h = ProgressHolder()
            if (t.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) onState("PROCESSING:${h.progress}")
            main.postDelayed(this, 500)
        }
    }

    /** Video: ricodifica con effetto colore e/o cornice (Media3 Transformer). Se fallisce resta il video originale. */
    @SuppressLint("UnsafeOptInUsageError")
    private fun applyVideoFrame(src: File, frameIn: Bitmap?) {
        val size = videoDisplaySize(src)
        var frame = frameIn
        if (frame != null && (size == null || !sizeFitsFrame(size.first, size.second) ||
                abs((size.first.toFloat() / size.second) / (frame.width.toFloat() / frame.height) - 1f) > 0.12f)
        ) {
            onEvent("ERROR:la cornice funziona solo in orizzontale 16:9")
            frame = null
            if (filter == "none") {
                beginTake(src)
                return
            }
        }
        val fr = frame
        processing = true
        onState("PROCESSING:0")
        try {
            val effects = ArrayList<Effect>()
            // prima l'effetto colore, poi la cornice sopra (così la cornice non cambia colore)
            val m4 = Filters.rgb4(filter)
            if (m4 != null) {
                effects.add(object : RgbMatrix {
                    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = m4
                })
            }
            if (fr != null && size != null) {
                // l'overlay Media3 occupa "dimensione bitmap / dimensione video": a misura esatta copre tutto il fotogramma
                val scaled = Bitmap.createScaledBitmap(fr, size.first, size.second, true)
                overlayBmp = scaled
                effects.add(OverlayEffect(ImmutableList.of<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(scaled))))
            }
            val out = File(takesDir, "take_${System.currentTimeMillis()}f.mp4")
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src)))
                .setEffects(Effects(emptyList<AudioProcessor>(), effects))
                .build()
            val t = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        endVideoFrame(src, out, true)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        endVideoFrame(src, out, false)
                    }
                })
                .build()
            transformer = t
            t.start(edited, out.absolutePath)
            main.postDelayed(progressTick, 500)
        } catch (t: Throwable) {
            endVideoFrame(src, null, false)
        }
    }

    private fun endVideoFrame(src: File, out: File?, ok: Boolean) {
        main.removeCallbacks(progressTick)
        transformer = null
        overlayBmp = null
        if (ok && out != null && out.length() > 0L) {
            src.delete()
            beginTake(out)
        } else {
            out?.delete()
            onEvent("ERROR:cornice non applicata al video")
            beginTake(src)
        }
    }

    fun start() {
        io.execute {
            migrateThumbs()
            loadFrame()
            main.post { publishInfo() }
        }
        takesDir.listFiles()?.filter { it.name.startsWith("take_") }?.forEach { it.delete() }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            provider = future.get()
            if (orientation != "landscape") {
                onOrientation(orientation)
                main.postDelayed({ bind() }, 700)
            } else {
                bind()
            }
            audio.registerAudioDeviceCallback(audioCallback, main)
            onState("READY")
        }, ContextCompat.getMainExecutor(context))
    }

    private fun tryBind(p: ProcessCameraProvider, sel: CameraSelector, vararg uses: UseCase): Camera? =
        try {
            p.unbindAll()
            p.bindToLifecycle(owner, sel, *uses)
        } catch (e: Exception) {
            null
        }

    private fun bind() {
        val p = provider ?: return
        val q = QUALITIES[quality] ?: Quality.FHD
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q)))
            .build()
        val video = VideoCapture.withOutput(recorder)

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 360),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        analysis.setAnalyzer(analysisExecutor, ::analyze)

        p.unbindAll()
        val photo = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .build()
        val wide = widestSelector(p)
        var cam: Camera? = tryBind(p, wide, video, analysis, photo)
        imageCapture = if (cam != null) photo else null
        if (cam == null) cam = tryBind(p, wide, video, analysis)
        if (cam == null) {
            lensDesc = ""
            cam = tryBind(p, CameraSelector.DEFAULT_BACK_CAMERA, video, analysis, photo)
            imageCapture = if (cam != null) photo else null
        }
        if (cam == null) cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, video, analysis)
        camera = cam
        videoCapture = video
        cam.cameraInfo.zoomState.observe(owner) { publishInfo() }
        applyControls()
        if (lock) applyLock()
        publishInfo()
    }

    private fun applyLock() {
        val cam = camera ?: return
        if (lock) {
            val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
            val flags = FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
            cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point, flags).disableAutoCancel().build())
        } else {
            cam.cameraControl.cancelFocusAndMetering()
        }
    }

    private fun applyControls() {
        val cam = camera ?: return
        val info = cam.cameraInfo
        info.zoomState.value?.let { zs ->
            zoom = zs.minZoomRatio  // sempre la visione più ampia
            cam.cameraControl.setZoomRatio(zoom)
        }
        if (info.hasFlashUnit()) {
            cam.cameraControl.enableTorch(torch)
        } else {
            torchCameraId?.let { id -> runCatching { camManager.setTorchMode(id, torch) } }
        }
        val es = info.exposureState
        if (es.isExposureCompensationSupported) {
            ev = ev.coerceIn(es.exposureCompensationRange.lower, es.exposureCompensationRange.upper)
            cam.cameraControl.setExposureCompensationIndex(ev)
        }
    }

    private fun lenses(): List<Double> {
        val zs = camera?.cameraInfo?.zoomState?.value
        val lo = zs?.minZoomRatio ?: 1f
        val hi = zs?.maxZoomRatio ?: 1f
        val out = mutableListOf<Double>()
        if (lo < 0.95f) out += Math.ceil(lo * 10.0) / 10.0
        out += 1.0
        for (c in listOf(2.0, 3.0, 5.0, 10.0)) if (c <= hi + 0.01) out += c
        return out
    }

    private fun usbMicName(): String {
        val d = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        return d?.productName?.toString() ?: ""
    }

    private fun publishInfo() {
        val cam = camera ?: return
        val info = cam.cameraInfo
        val supported = runCatching { QualitySelector.getSupportedQualities(info) }.getOrDefault(emptyList())
        val qs = QUALITIES.filter { (_, q) -> q in supported }.keys.toList()
        val es = info.exposureState
        val j = JSONObject()
        j.put("quality", quality)
        j.put("qualities", JSONArray(qs))
        j.put("duration", durationSec)
        j.put("zoom", (info.zoomState.value?.zoomRatio ?: zoom).toDouble())
        j.put("lenses", JSONArray(lenses()))
        j.put("hasTorch", info.hasFlashUnit() || torchCameraId != null)
        j.put("lens", lensDesc)
        j.put("hasPhoto", imageCapture != null)
        j.put("torch", torch)
        j.put("evSupported", es.isExposureCompensationSupported)
        j.put("ev", ev)
        j.put("evMin", es.exposureCompensationRange.lower)
        j.put("evMax", es.exposureCompensationRange.upper)
        j.put("prefix", prefix)
        j.put("counter", counter)
        j.put("mic", usbMicName())
        j.put("lock", lock)
        j.put("zoomMin", (info.zoomState.value?.minZoomRatio ?: 1f).toDouble())
        j.put("zoomMax", (info.zoomState.value?.maxZoomRatio ?: 1f).toDouble())
        j.put("orientation", orientation)
        j.put("hasFlash", info.hasFlashUnit() || torchCameraId != null)
        j.put("flash", flash)
        j.put("hasFrame", frameBmp != null)
        j.put("frame", frameOn && frameBmp != null)
        j.put("frameId", frameId)
        j.put("filter", filter)
        runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            j.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            val st = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            j.put("charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
            j.put("freeMb", takesDir.usableSpace / (1024L * 1024L))
        }
        onInfo(j.toString())
    }

    fun refreshInfo() = publishInfo()

    fun applySettings(j: JSONObject) {
        var rebind = false
        if (j.has("quality") && recording == null) {
            val q = j.getString("quality")
            if (q != quality && QUALITIES.containsKey(q)) { quality = q; rebind = true }
        }
        if (j.has("duration")) durationSec = j.getInt("duration").coerceIn(5, 600)
        if (j.has("zoom")) zoom = j.getDouble("zoom").toFloat()
        if (j.has("torch")) torch = j.getBoolean("torch")
        if (j.has("ev")) ev = j.getInt("ev")
        if (j.has("flash")) flash = j.getBoolean("flash")
        if (j.has("frame")) frameOn = j.getBoolean("frame")
        if (j.has("filter")) filter = j.getString("filter").let { if (Filters.has(it)) it else "none" }
        if (j.has("prefix")) {
            prefix = j.getString("prefix").filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(30)
            if (prefix.isEmpty()) prefix = "dedica"
        }
        if (j.has("counter")) counter = j.getInt("counter").coerceAtLeast(0)
        if (j.has("lock")) {
            val n = j.getBoolean("lock")
            if (n != lock) { lock = n; applyLock() }
        }
        if (j.has("orientation") && recording == null) {
            val o = j.getString("orientation")
            if ((o == "landscape" || o == "portrait") && o != orientation) {
                orientation = o
                prefs.edit().putString("orientation", o).apply()
                onOrientation(o)
                main.postDelayed({ bind() }, 700)
            }
        }
        prefs.edit()
            .putString("quality", quality).putInt("duration", durationSec)
            .putFloat("zoom", zoom).putBoolean("torch", torch).putInt("ev", ev)
            .putString("prefix", prefix).putInt("counter", counter)
            .putBoolean("flash", flash).putBoolean("frame", frameOn).putString("filter", filter)
            .apply()
        if (rebind) bind() else { applyControls(); publishInfo() }
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            val now = System.currentTimeMillis()
            if (!wantsFrames() || now - lastFrameMs < 1000 / PREVIEW_FPS - 8) return
            lastFrameMs = now
            val rot = it.imageInfo.rotationDegrees
            val bos = ByteArrayOutputStream(64 * 1024)
            if (rot == 0 && it.format == ImageFormat.YUV_420_888 && it.planes.size == 3) {
                // percorso veloce: niente bitmap intermedia
                val w = it.width
                val h = it.height
                YuvImage(yuvToNv21(it), ImageFormat.NV21, w, h, null)
                    .compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, bos)
            } else {
                val bmp = it.toBitmap()
                val out = if (rot != 0) {
                    Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                } else bmp
                out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
            }
            onPreviewFrame(bos.toByteArray())
        }
    }

    private fun yuvToNv21(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val nv21 = ByteArray(w * h * 3 / 2)
        val yBuf = yP.buffer
        val yRow = yP.rowStride
        var pos = 0
        if (yRow == w) {
            yBuf.rewind()
            yBuf.get(nv21, 0, w * h)
            pos = w * h
        } else {
            for (r in 0 until h) {
                yBuf.position(r * yRow)
                yBuf.get(nv21, pos, w)
                pos += w
            }
        }
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        val uvRow = uP.rowStride
        val uvPix = uP.pixelStride
        for (r in 0 until h / 2) {
            for (c in 0 until w / 2) {
                val i = r * uvRow + c * uvPix
                nv21[pos++] = vBuf.get(i)
                nv21[pos++] = uBuf.get(i)
            }
        }
        return nv21
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val video = videoCapture ?: return
        if (recording != null || pendingTake != null || processing || shooting) return
        takesDir.listFiles()?.filter { it.name.startsWith("take_") }?.forEach { it.delete() }
        val file = File(takesDir, "take_${System.currentTimeMillis()}.mp4")

        recording = video.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { e ->
                when (e) {
                    is VideoRecordEvent.Start -> onState("RECORDING")
                    is VideoRecordEvent.Finalize -> {
                        main.removeCallbacks(autoStop)
                        recording = null
                        if (e.hasError()) {
                            file.delete()
                            onEvent("ERROR:${e.error}")
                            onState("READY")
                        } else {
                            val fr = frameBmp
                            if ((frameOn && fr != null) || filter != "none") applyVideoFrame(file, if (frameOn) fr else null) else beginTake(file)
                        }
                    }
                    else -> Unit
                }
            }
        main.postDelayed(autoStop, durationSec * 1000L)
    }

    fun stopRecording() {
        recording?.stop()
    }

    /**
     * Scatta una foto: stesso percorso del video (anteprima, poi salva o rifai).
     * Flash: se l'obiettivo in uso ha il flash lo usa (FLASH_MODE_ON, entrambi i LED); l'ultragrandangolare
     * dei Xiaomi non lo ha, allora accende la torcia dell'obiettivo principale un attimo prima dello scatto.
     */
    fun takePhoto() {
        val ic = imageCapture ?: return
        if (recording != null || pendingTake != null || saving || processing || shooting) return
        shooting = true
        takesDir.listFiles()?.filter { it.name.startsWith("take_") }?.forEach { it.delete() }
        val file = File(takesDir, "take_${System.currentTimeMillis()}.jpg")

        val nativeFlash = camera?.cameraInfo?.hasFlashUnit() == true
        val pulseId: String? = if (flash && !torch && !nativeFlash) torchCameraId else null
        ic.flashMode = if (flash && nativeFlash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        if (pulseId != null) runCatching { camManager.setTorchMode(pulseId, true) }

        val shoot = Runnable {
            ic.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (pulseId != null) runCatching { camManager.setTorchMode(pulseId, false) }
                        finishPhoto(file)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (pulseId != null) runCatching { camManager.setTorchMode(pulseId, false) }
                        shooting = false
                        file.delete()
                        onEvent("ERROR:photo")
                    }
                },
            )
        }
        // con la torcia l'esposizione automatica ha bisogno di qualche fotogramma per adattarsi
        if (pulseId != null) main.postDelayed(shoot, 500) else shoot.run()
    }

    fun takeFile(name: String): File? = pendingTake?.takeIf { it.name == name }

    /** Copia il take in galleria (Movies/Booth) con numerazione progressiva. */
    fun saveTake() {
        val src = pendingTake ?: return
        if (savedName != null || saving) return
        saving = true
        io.execute {
            try {
                val n = counter + 1
                val isPhoto = src.name.endsWith(".jpg")
                val name = "%s_%03d.%s".format(Locale.ROOT, prefix, n, if (isPhoto) "jpg" else "mp4")
                val collection = if (isPhoto) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (isPhoto) "image/jpeg" else "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, if (isPhoto) "Pictures/Booth" else "Movies/Booth")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(collection, values)
                    ?: throw IllegalStateException("insert")
                resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                keepInHistory(src, name)
                main.post {
                    counter = n
                    savedName = name
                    saving = false
                    prefs.edit().putInt("counter", counter).apply()
                    onEvent("SAVED:$name")
                    onState("TAKE_SAVED:${src.name}:$name")
                    publishInfo()
                }
            } catch (t: Throwable) {
                main.post {
                    saving = false
                    onEvent("ERROR:save")
                }
            }
        }
    }

    /** Elimina un video dallo storico (la copia in galleria resta). */
    fun deleteHistory(name: String) {
        val clean = File(name).name
        File(historyDir, clean).delete()
        File(thumbsDir, clean.substringBeforeLast('.') + ".jpg").delete()
        onEvent("DELETED:$clean")
    }

    /** Rifai (take non salvato) oppure chiudi l'anteprima dopo il salvataggio. */
    fun discardTake() {
        if (recording != null || saving || processing) return
        pendingTake?.delete()
        pendingTake = null
        savedName = null
        onState("READY")
    }

    fun release() {
        main.removeCallbacks(autoStop)
        main.removeCallbacks(progressTick)
        runCatching { transformer?.cancel() }
        recording?.stop()
        runCatching { audio.unregisterAudioDeviceCallback(audioCallback) }
        analysisExecutor.shutdown()
        io.shutdown()
    }
}
