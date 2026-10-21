package it.mdo.booth.camera

import android.content.pm.ActivityInfo
import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var updText: TextView
    private lateinit var updBtn: Button
    private lateinit var updates: UpdateManager
    private var camera: CameraController? = null
    private var server: StreamServer? = null
    private var nsd: NsdAdvertiser? = null
    private var motion: MotionGuard? = null

    // schermo nero (AMOLED): resta solo un puntino di funzionamento, doppio tocco per uscire
    private lateinit var overlay: FrameLayout
    private lateinit var dot: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var lastTouch = System.currentTimeMillis()
    private var lastDotMove = 0L
    private var booted = false
    private val idleTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (overlay.visibility != View.VISIBLE) {
                if (booted && now - lastTouch > 60_000) enterBlack()
            } else {
                // verde: tablet collegato, arancione: tablet non collegato
                dot.setTextColor(if (server?.hasClients() == true) 0xFF2E7D32.toInt() else 0xFFB26A00.toInt())
                if (now - lastDotMove > 60_000) moveDot()
            }
            ui.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply {
            textSize = 22f
            setPadding(48, 48, 48, 48)
            text = "Booth Camera"
        }
        updText = TextView(this).apply { textSize = 16f; setPadding(48, 0, 48, 16) }
        updBtn = Button(this).apply { text = "Controlla aggiornamenti" }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(updText)
            addView(updBtn)
        }
        updBtn.isEnabled = true
        val blackBtn = Button(this).apply { text = "Schermo nero"; setOnClickListener { enterBlack() } }
        root.addView(blackBtn)
        overlay = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            visibility = View.GONE
        }
        dot = TextView(this).apply { text = "●"; textSize = 20f; setTextColor(0xFFB26A00.toInt()) }
        overlay.addView(dot, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean { exitBlack(); return true }
        })
        overlay.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev); true }
        val container = FrameLayout(this)
        container.addView(root)
        container.addView(overlay)
        setContentView(container)
        ui.postDelayed(idleTick, 5000)

        updates = UpdateManager(this, "booth-camera.apk", BuildConfig.UPDATE_TOKEN)
        updates.listener = { st ->
            runOnUiThread {
                updText.text = st.text
                updBtn.isEnabled = !st.busy
                updBtn.text = if (st.canInstall) "Installa aggiornamento" else "Controlla aggiornamenti"
            }
        }
        updBtn.setOnClickListener {
            if (updates.status.value.canInstall) updates.installNow() else updates.check()
        }
        updText.text = updates.status.value.text
        updates.check()

        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) boot() else status.text = "Servono permessi camera e microfono"
        }.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun boot() {
        val srv = StreamServer(
            takeFile = { name -> camera?.takeFile(name) },
            onCommand = { cmd -> runOnUiThread { handle(cmd) } },
            historyFile = { name -> camera?.historyFile(name) },
            historyList = { camera?.historyJson() ?: "[]" },
            thumbFile = { name -> camera?.thumbFile(name) },
            onFrame = { bytes -> camera?.saveFrame(bytes) ?: false },
        )
        val cam = CameraController(
            context = this,
            owner = this,
            wantsFrames = srv::hasClients,
            onPreviewFrame = srv::sendFrame,
            onState = { s ->
                srv.sendState(s)
                runOnUiThread { status.text = "Booth Camera\nStato: $s" }
            },
            onEvent = srv::sendEvent,
            onInfo = srv::sendInfo,
            onOrientation = { o ->
                runOnUiThread {
                    requestedOrientation = if (o == "portrait") ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            },
        )
        server = srv
        camera = cam
        srv.start()
        cam.start()
        nsd = NsdAdvertiser(this).also { it.register(StreamServer.PORT) }
        booted = true
        motion = MotionGuard(this, { true }, { 10 }) { kind -> if (kind != "HIT") srv.sendEvent("MOTION:$kind") }.also { it.start() }
    }

    private fun handle(cmd: String) {
        val cam = camera ?: return
        when {
            cmd == "START" -> cam.startRecording()
            cmd == "PHOTO" -> cam.takePhoto()
            cmd == "STOP" -> cam.stopRecording()
            cmd == "SAVE" -> cam.saveTake()
            cmd == "DISCARD" -> cam.discardTake()
            cmd == "INFO?" -> cam.refreshInfo()
            cmd.startsWith("DEL:") -> cam.deleteHistory(cmd.removePrefix("DEL:"))
            cmd.startsWith("SET:") -> runCatching { cam.applySettings(JSONObject(cmd.removePrefix("SET:"))) }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastTouch = System.currentTimeMillis()
    }

    private fun enterBlack() {
        overlay.visibility = View.VISIBLE
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        moveDot()
    }

    private fun exitBlack() {
        overlay.visibility = View.GONE
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        lastTouch = System.currentTimeMillis()
    }

    /** Il puntino cambia posizione ogni minuto per non lasciare segni sullo schermo AMOLED. */
    private fun moveDot() {
        lastDotMove = System.currentTimeMillis()
        val dm = resources.displayMetrics
        val lp = dot.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = (dm.widthPixels * (0.1 + Math.random() * 0.75)).toInt()
        lp.topMargin = (dm.heightPixels * (0.1 + Math.random() * 0.75)).toInt()
        dot.layoutParams = lp
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (overlay.visibility == View.VISIBLE) exitBlack() else super.onBackPressed()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        motion?.stop()
        camera?.release()
        server?.stop()
        nsd?.unregister()
        super.onDestroy()
    }
}
