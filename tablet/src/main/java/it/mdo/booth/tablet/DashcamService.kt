package it.mdo.booth.tablet

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Dashcam: registra a segmenti (fotocamera frontale, e la posteriore insieme se il tablet
 * lo permette), cancella i più vecchi e tiene un piccolo server per l'app di controllo.
 */
class DashcamService : Service() {
    companion object {
        const val PORT = 8090
        private const val CHANNEL = "dashcam"
        private const val NOTIF_ID = 77
        private const val MIN_FREE = 2L * 1024 * 1024 * 1024

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DashcamService::class.java))
        }
    }

    private lateinit var cfg: DashConfig
    private lateinit var cm: CameraManager
    private lateinit var dir: File
    private val worker = HandlerThread("dashcam").also { it.start() }
    private val handler by lazy { Handler(worker.looper) }
    private var server: DashServer? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var wake: PowerManager.WakeLock? = null
    private var recs: List<CamRec> = emptyList()
    private var generation = 0
    private var lastTry = 0L
    private var lowBatt = false
    private var lowSpace = false
    private var liveDisabled = false
    private var motion: MotionGuard? = null
    private val main = Handler(Looper.getMainLooper())
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        cfg = DashConfig(this)
        cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        dir = (getExternalFilesDir("dashcam") ?: File(filesDir, "dashcam")).apply { mkdirs() }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Dashcam", NotificationManager.IMPORTANCE_LOW))
        val notif = Notification.Builder(this, CHANNEL)
            .setContentTitle("Dashcam attiva")
            .setContentText("Registrazione video e audio in corso")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            DashState.lastError = "servizio non avviabile: $e"
            stopSelf()
            return
        }
        DashState.serviceUp = true
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "booth:dashcam").apply { acquire() }

        server = DashServer(
            key = { cfg.key },
            status = ::statusJson,
            clips = ::clipsJson,
            clipFile = ::clipFile,
            applyConfig = ::applyConfig,
            applySettings = ::applySettings,
        ).also { it.start() }
        registerNsd()
        motion = MotionGuard(this, { cfg.motion }, { cfg.motionDeg }, ::onMotion).also { it.start() }
        EventLog.add("APP_AVVIATA", "v" + (DashState.updates?.installedName() ?: ""))
        handler.post(tick)
    }

    override fun onDestroy() {
        DashState.serviceUp = false
        handler.removeCallbacksAndMessages(null)
        handler.post { stopRecording() }
        motion?.stop()
        server?.stop()
        runCatching { nsdListener?.let { nsd?.unregisterService(it) } }
        runCatching { wake?.release() }
        worker.quitSafely()
        super.onDestroy()
    }

    // ---- controllo periodico ----

    private val tick = object : Runnable {
        override fun run() {
            try { monitor() } catch (e: Exception) { DashState.lastError = e.toString() }
            handler.postDelayed(this, 30_000)
        }
    }

    private fun monitor() {
        val (pct, charging) = battery()
        if (pct in 0..19 && !charging) {
            if (!lowBatt) { lowBatt = true; EventLog.add("BATT_BASSA", "$pct%") }
        } else if (pct >= 30 || charging) lowBatt = false

        val free = dir.usableSpace
        if (free < MIN_FREE) {
            if (!lowSpace) { lowSpace = true; EventLog.add("SPAZIO_BASSO", "${free / 1_048_576} MB") }
        } else if (free > MIN_FREE * 2) lowSpace = false

        cleanup()
        if (cfg.enabled && !DashState.recording && System.currentTimeMillis() - lastTry > 30_000) startRecording()
        if (!cfg.enabled && DashState.recording) stopRecording()
    }

    private fun battery(): Pair<Int, Boolean> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1 to false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return (if (level >= 0 && scale > 0) level * 100 / scale else -1) to plugged
    }

    // ---- registrazione ----

    private fun facing(id: String): Int? = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)

    /** (frontale, posteriore): la posteriore solo se il tablet dichiara l'uso insieme alla frontale. */
    private fun pickCameras(): Pair<String?, String?> {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                for (set in cm.concurrentCameraIds) {
                    val f = set.firstOrNull { facing(it) == CameraCharacteristics.LENS_FACING_FRONT }
                    val b = set.firstOrNull { facing(it) == CameraCharacteristics.LENS_FACING_BACK }
                    if (f != null && b != null) {
                        DashState.concurrent = true
                        return f to b
                    }
                }
            } catch (_: Exception) {
            }
        }
        DashState.concurrent = false
        return cm.cameraIdList.firstOrNull { facing(it) == CameraCharacteristics.LENS_FACING_FRONT } to null
    }

    private fun startRecording() {
        lastTry = System.currentTimeMillis()
        val ids = pickCameras()
        val front = ids.first
        if (front == null) {
            DashState.lastError = "Nessuna fotocamera anteriore"
            return
        }
        val back = ids.second
        val list = mutableListOf(CamRec(front, "front", true))
        if (cfg.useBack && back != null && !cfg.backFailed) list.add(CamRec(back, "back", false))
        recs = list
        DashState.cams = if (list.size > 1) "frontale+posteriore" else "frontale"
        val gen = ++generation
        var pending = list.size
        var failed = 0
        for (r in list) {
            r.open { ok ->
                if (gen != generation) {
                    r.close()
                } else {
                    if (!ok) failed++
                    pending--
                    if (pending == 0) {
                        if (failed > 0) openFailed(list) else beginSegments(list)
                    }
                }
            }
        }
    }

    private fun openFailed(list: List<CamRec>) {
        list.forEach { it.close() }
        recs = emptyList()
        if (list.size > 1) {
            cfg.backFailed = true
            EventLog.add("DASH_ERRORE", "le due fotocamere insieme non si aprono: uso solo la frontale")
            startRecording()
        } else {
            DashState.lastError = "Impossibile aprire la fotocamera"
            DashState.cams = "nessuna"
            EventLog.add("DASH_ERRORE", DashState.lastError)
        }
    }

    private fun beginSegments(list: List<CamRec>) {
        DashState.lastError = ""
        list.forEach { it.startSegment() }
        if (recs.isEmpty()) return
        DashState.recording = true
        DashState.lastSegmentAt = System.currentTimeMillis()
        EventLog.add("DASH_AVVIO", DashState.cams)
        handler.postDelayed(rotate, cfg.segMin * 60_000L)
    }

    private val rotate: Runnable = object : Runnable {
        override fun run() {
            recs.forEach { it.stopSegment() }
            cleanup()
            if (DashState.recording) {
                recs.forEach { it.startSegment() }
                if (recs.isNotEmpty()) {
                    DashState.lastSegmentAt = System.currentTimeMillis()
                    handler.postDelayed(this, cfg.segMin * 60_000L)
                }
            }
        }
    }

    private fun stopRecording() {
        handler.removeCallbacks(rotate)
        generation++
        val wasRecording = DashState.recording
        recs.forEach { it.stopSegment(); it.close() }
        recs = emptyList()
        DashState.recording = false
        DashState.cams = "nessuna"
        if (wasRecording) EventLog.add("DASH_STOP")
    }

    private fun lost(msg: String) {
        DashState.lastError = msg
        EventLog.add("DASH_ERRORE", msg)
        stopRecording()
    }

    private fun pickSize(id: String): Size {
        val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
        return sizes.filter { it.width * 9 == it.height * 16 && it.width <= 1920 }.minByOrNull { abs(it.width - 1280) }
            ?: sizes.minByOrNull { abs(it.width * it.height - 1280 * 720) }
            ?: Size(1280, 720)
    }

    private fun orientationHint(id: String): Int {
        val ch = cm.getCameraCharacteristics(id)
        val sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val front = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val rot = when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (front) (sensor + rot) % 360 else (sensor - rot + 360) % 360
    }

    /** Una fotocamera che registra un file alla volta. */
    private inner class CamRec(val id: String, val tag: String, val audio: Boolean) {
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var rec: MediaRecorder? = null
        var file: File? = null
        var reader: ImageReader? = null
        private var lastLive = 0L
        private val size = pickSize(id)
        private val liveSize: Size = run {
            val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            sizes.filter { it.width <= 640 && it.width * 9 == it.height * 16 }.maxByOrNull { it.width }
                ?: sizes.filter { it.width <= 640 }.maxByOrNull { it.width * it.height }
                ?: Size(640, 480)
        }

        @SuppressLint("MissingPermission")
        fun open(cb: (Boolean) -> Unit) {
            var answered = false
            try {
                cm.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(c: CameraDevice) {
                        device = c
                        if (!answered) { answered = true; cb(true) }
                    }
                    override fun onDisconnected(c: CameraDevice) {
                        c.close()
                        device = null
                        if (!answered) { answered = true; cb(false) } else lost("$tag disconnessa")
                    }
                    override fun onError(c: CameraDevice, error: Int) {
                        c.close()
                        device = null
                        if (!answered) { answered = true; cb(false) } else lost("$tag errore $error")
                    }
                }, handler)
            } catch (e: Exception) {
                if (!answered) { answered = true; cb(false) }
            }
        }

        fun startSegment() {
            val d = device ?: return
            val f = File(dir, "${tag}_${stamp.format(Date())}.mp4")
            try {
                val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this@DashcamService)
                else @Suppress("DEPRECATION") MediaRecorder()
                if (audio) r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setOutputFile(f.absolutePath)
                r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                r.setVideoSize(size.width, size.height)
                r.setVideoFrameRate(30)
                r.setVideoEncodingBitRate(2_500_000)
                if (audio) {
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    r.setAudioEncodingBitRate(96_000)
                    r.setAudioSamplingRate(44_100)
                }
                r.setOrientationHint(orientationHint(id))
                r.prepare()
                val surface = r.surface
                rec = r
                file = f
                openSession(d, r, surface, tag == "front" && !liveDisabled)
            } catch (e: Exception) {
                lost("$tag: $e")
            }
        }

        /** Apre la sessione: registrazione su file + (solo frontale) un piccolo flusso per la diretta. */
        private fun openSession(d: CameraDevice, r: MediaRecorder, surface: Surface, withLive: Boolean) {
            try {
                val surfaces = mutableListOf(surface)
                var ir: ImageReader? = null
                if (withLive) {
                    ir = ImageReader.newInstance(liveSize.width, liveSize.height, ImageFormat.YUV_420_888, 3)
                    ir.setOnImageAvailableListener({ rd -> onLiveFrame(rd) }, handler)
                    surfaces.add(ir.surface)
                }
                val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { surfaces.forEach { addTarget(it) } }
                d.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (rec !== r) { s.close(); ir?.close(); return }
                        session = s
                        reader = ir
                        try {
                            s.setRepeatingRequest(req.build(), null, handler)
                            r.start()
                        } catch (e: Exception) {
                            lost("$tag avvio fallito: $e")
                        }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        ir?.close()
                        if (withLive && rec === r) {
                            // la diretta insieme alla registrazione non è supportata: si registra senza
                            liveDisabled = true
                            EventLog.add("DASH_ERRORE", "diretta non supportata da questo tablet: registro senza")
                            openSession(d, r, surface, false)
                        } else {
                            lost("$tag configurazione fallita")
                        }
                    }
                }, handler)
            } catch (e: Exception) {
                lost("$tag: $e")
            }
        }

        private fun onLiveFrame(rd: ImageReader) {
            val img = (try { rd.acquireLatestImage() } catch (_: Exception) { null }) ?: return
            try {
                val now = System.currentTimeMillis()
                val photo = LiveHub.photoRequested
                val live = LiveHub.wanted && now - lastLive >= 150
                if (!photo && !live) return
                val jpeg = yuvToJpeg(img) ?: return
                val rot = orientationHint(id)
                if (live) {
                    lastLive = now
                    LiveHub.publish(jpeg, rot)
                }
                if (photo) {
                    LiveHub.photoRequested = false
                    saveAlarmPhoto(jpeg, rot)
                }
            } catch (_: Throwable) {
            } finally {
                img.close()
            }
        }

        private fun yuvToJpeg(img: Image): ByteArray? {
            val w = img.width
            val h = img.height
            val planes = img.planes
            if (planes.size < 3) return null
            val yBuf = planes[0].buffer
            val yRow = planes[0].rowStride
            val uBuf = planes[1].buffer
            val vBuf = planes[2].buffer
            val uvRow = planes[1].rowStride
            val uvPix = planes[1].pixelStride
            val nv21 = ByteArray(w * h * 3 / 2)
            var pos = 0
            for (row in 0 until h) {
                yBuf.position(row * yRow)
                yBuf.get(nv21, pos, w)
                pos += w
            }
            for (row in 0 until h / 2) {
                for (col in 0 until w / 2) {
                    val i = row * uvRow + col * uvPix
                    nv21[pos++] = vBuf.get(i)
                    nv21[pos++] = uBuf.get(i)
                }
            }
            val out = ByteArrayOutputStream(48 * 1024)
            YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 60, out)
            return out.toByteArray()
        }

        fun stopSegment() {
            val s = session
            val r = rec
            val f = file
            session = null
            rec = null
            file = null
            try { s?.stopRepeating() } catch (_: Exception) {}
            try { s?.abortCaptures() } catch (_: Exception) {}
            try { s?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            reader = null
            if (r != null) {
                try { r.stop() } catch (_: Exception) { f?.delete() }
                try { r.release() } catch (_: Exception) {}
            }
        }

        fun close() {
            stopSegment()
            try { device?.close() } catch (_: Exception) {}
            device = null
        }
    }

    // ---- file e stato ----

    private fun activeFiles(): Set<File> = recs.mapNotNull { it.file }.toSet()

    private fun keepFile(f: File) = File(f.parentFile, f.name + ".keep")

    private fun isKept(f: File) = keepFile(f).exists()

    private fun deleteClip(f: File) {
        f.delete()
        keepFile(f).delete()
    }

    private fun cleanup() {
        val files = dir.listFiles { f -> f.extension == "mp4" }?.sortedBy { it.lastModified() } ?: return
        val active = activeFiles()
        val now = System.currentTimeMillis()
        val cutoff = now - cfg.keepMin * 60_000L
        val keptCutoff = now - 24 * 60 * 60_000L
        for (f in files) {
            if (f in active) continue
            if (f.lastModified() < (if (isKept(f)) keptCutoff else cutoff)) deleteClip(f)
        }
        dir.listFiles { f -> f.extension == "jpg" }?.forEach { if (it.lastModified() < now - 3 * 24 * 60 * 60_000L) it.delete() }
        var free = dir.usableSpace
        val rest = dir.listFiles { f -> f.extension == "mp4" }?.sortedBy { it.lastModified() } ?: return
        // se manca spazio si cancellano prima i video non protetti, poi quelli protetti
        for (f in rest.filter { !isKept(it) } + rest.filter { isKept(it) }) {
            if (free >= MIN_FREE) break
            if (f in active) continue
            free += f.length()
            deleteClip(f)
        }
    }

    /** Protegge dalla sovrascrittura (per 24 ore) i file in corso e quelli appena chiusi. */
    private fun protectNow() {
        val now = System.currentTimeMillis()
        val recent = dir.listFiles { f -> f.extension == "mp4" && now - f.lastModified() < 90_000 }?.toList().orEmpty()
        var n = 0
        for (f in (recent + activeFiles()).toSet()) {
            if (runCatching { keepFile(f).createNewFile() }.getOrDefault(false)) n++
        }
        if (n > 0) EventLog.add("MOMENTO_PROTETTO", "$n file")
    }

    private fun onMotion(kind: String) {
        val (type, text) = when (kind) {
            "FALL" -> "TABLET_CADUTO" to "caduta e urto"
            "HIT" -> "TABLET_URTO" to "urto forte"
            else -> "TABLET_SPOSTATO" to "inclinato o spostato (oltre ${cfg.motionDeg}°)"
        }
        EventLog.add(type, text)
        handler.post { protectNow() }
        // antifurto: con il kiosk attivo, uno spostamento o una caduta fa scattare una foto con la frontale
        if (kind != "HIT" && DashState.local?.kiosk == true) LiveHub.photoRequested = true
    }

    private fun saveAlarmPhoto(jpeg: ByteArray, rot: Int) {
        try {
            val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
            val bmp = if (rot == 0) raw else {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            }
            val f = File(dir, "allarme_${stamp.format(Date())}.jpg")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            EventLog.add("FOTO_ALLARME", f.name)
        } catch (_: Exception) {
        }
    }

    private fun clipFile(name: String): File? {
        if (name.contains('/') || name.contains('\\') || !(name.endsWith(".mp4") || name.endsWith(".jpg"))) return null
        return File(dir, name).takeIf { it.exists() }
    }

    private fun clipsJson(): JSONArray {
        val arr = JSONArray()
        val active = activeFiles().map { it.name }.toSet()
        val files = dir.listFiles { f -> f.extension == "mp4" }?.sortedByDescending { it.lastModified() }.orEmpty()
        for (f in files) {
            val parts = f.nameWithoutExtension.split("_")
            val start = runCatching { stamp.parse(parts.getOrElse(1) { "" })?.time }.getOrNull() ?: f.lastModified()
            arr.put(
                JSONObject().put("name", f.name).put("cam", parts.getOrElse(0) { "?" })
                    .put("start", start).put("end", f.lastModified()).put("size", f.length())
                    .put("live", f.name in active).put("kept", isKept(f))
            )
        }
        return arr
    }

    private fun applyConfig(j: JSONObject) {
        if (j.has("segMin")) cfg.segMin = j.getInt("segMin")
        if (j.has("keepMin")) cfg.keepMin = j.getInt("keepMin")
        if (j.has("useBack")) {
            cfg.useBack = j.getBoolean("useBack")
            if (cfg.useBack) cfg.backFailed = false
        }
        if (j.has("enabled")) cfg.enabled = j.getBoolean("enabled")
        if (j.has("motion")) cfg.motion = j.getBoolean("motion")
        if (j.has("motionDeg")) cfg.motionDeg = j.getInt("motionDeg")
        EventLog.add("DASH_CONFIG", j.toString())
        // le nuove impostazioni valgono dal prossimo avvio: riparte la registrazione
        handler.post {
            if (DashState.recording) stopRecording()
            lastTry = 0L
            monitor()
        }
    }

    /** Impostazioni inviate dall'app di controllo: telefono camera, tablet, cornice, dashcam. */
    private fun applySettings(j: JSONObject) {
        j.optJSONObject("dash")?.let { applyConfig(it) }
        j.optJSONObject("phone")?.let { DashState.client?.set(it) }
        j.optJSONObject("tablet")?.let { t -> main.post { applyTablet(t) } }
        j.optString("frame").takeIf { it.isNotEmpty() }?.let { a -> applyFrame(a) }
        if (j.has("phone") || j.has("tablet") || j.has("frame")) {
            EventLog.add("IMPOSTAZIONI", j.keys().asSequence().joinToString(","))
        }
    }

    private fun applyTablet(t: JSONObject) {
        val l = DashState.local ?: return
        if (t.has("mirror")) l.mirror = t.getBoolean("mirror")
        if (t.has("countdown")) l.countdown = t.getInt("countdown").coerceIn(0, 10)
        if (t.has("sound")) l.sound = t.getBoolean("sound")
        if (t.has("autoQr")) l.autoQr = t.getBoolean("autoQr")
        if (t.has("welcome")) l.welcome = t.getString("welcome").take(60)
        if (t.has("wifiSsid")) l.wifiSsid = t.getString("wifiSsid").take(60)
        if (t.has("wifiPass")) l.wifiPass = t.getString("wifiPass").take(63)
        if (t.has("kiosk")) {
            l.kiosk = t.getBoolean("kiosk")
            DashState.kioskHook?.invoke(l.kiosk)
        }
        l.save()
    }

    private fun applyFrame(action: String) {
        val fr = DashState.frames ?: return
        when (action) {
            "gaia" -> Thread {
                val ok = fr.importAsset("frame_gaia30.png")
                if (ok) main.post { fr.enableOnSync = true }
            }.start()
            "remove" -> main.post {
                fr.clear()
                DashState.client?.set(JSONObject().put("frame", false))
            }
        }
    }

    private fun statusJson(): JSONObject {
        val (pct, charging) = battery()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val files = dir.listFiles { f -> f.extension == "mp4" }.orEmpty()
        val upd = DashState.updates?.status?.value
        return JSONObject()
            .put("app", "tablet")
            .put("version", DashState.updates?.installedName() ?: "")
            .put("code", DashState.updates?.installedCode() ?: 0)
            .put("now", System.currentTimeMillis())
            .put("uptime", System.currentTimeMillis() - DashState.startedAt)
            .put("dash", JSONObject()
                .put("enabled", cfg.enabled)
                .put("recording", DashState.recording)
                .put("cams", DashState.cams)
                .put("concurrent", DashState.concurrent)
                .put("useBack", cfg.useBack)
                .put("backFailed", cfg.backFailed)
                .put("segMin", cfg.segMin)
                .put("keepMin", cfg.keepMin)
                .put("motion", cfg.motion)
                .put("motionDeg", cfg.motionDeg)
                .put("lastSegmentAt", DashState.lastSegmentAt)
                .put("lastError", DashState.lastError))
            .put("battery", JSONObject().put("pct", pct).put("charging", charging))
            .put("storage", JSONObject()
                .put("freeMb", dir.usableSpace / 1_048_576)
                .put("dashMb", files.sumOf { it.length() } / 1_048_576)
                .put("clips", files.size))
            .put("phone", JSONObject()
                .put("connected", DashState.phoneConnected)
                .put("info", runCatching { JSONObject(DashState.phoneInfo) }.getOrNull() ?: JSONObject()))
            .put("live", JSONObject().put("available", !liveDisabled && DashState.recording))
            .put("tablet", tabletJson())
            .put("frame", JSONObject().put("present", DashState.frames?.bitmap != null))
            .put("today", EventLog.counts(cal.timeInMillis))
            .put("update", JSONObject().put("text", upd?.text ?: "").put("canInstall", upd?.canInstall ?: false))
    }

    private fun tabletJson(): JSONObject {
        val l = DashState.local ?: return JSONObject()
        return JSONObject()
            .put("mirror", l.mirror)
            .put("countdown", l.countdown)
            .put("sound", l.sound)
            .put("welcome", l.welcome)
            .put("autoQr", l.autoQr)
            .put("wifiSsid", l.wifiSsid)
            .put("wifiPass", l.wifiPass)
            .put("kiosk", l.kiosk)
    }

    private fun registerNsd() {
        val m = getSystemService(Context.NSD_SERVICE) as NsdManager
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        val info = NsdServiceInfo().apply {
            serviceName = "BoothDash"
            serviceType = "_boothdash._tcp."
            setPort(PORT)
        }
        nsd = m
        nsdListener = l
        runCatching { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }
}
