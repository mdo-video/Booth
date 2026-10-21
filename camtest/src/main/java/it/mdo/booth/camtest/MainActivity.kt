package it.mdo.booth.camtest

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var out: TextView
    private lateinit var cm: CameraManager
    private val ui = Handler(Looper.getMainLooper())
    private val opened = mutableListOf<CameraDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cm = getSystemService(CAMERA_SERVICE) as CameraManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        val btn = Button(this).apply {
            text = "Prova apertura simultanea (anteriore + posteriore)"
            setOnClickListener { runOpenTest() }
        }
        out = TextView(this).apply { textSize = 14f; typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.gravity = Gravity.CENTER })
        root.addView(ScrollView(this).apply { addView(out) })
        setContentView(root)

        out.text = report()
    }

    private fun facing(id: String): String {
        val f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
        return when (f) {
            CameraCharacteristics.LENS_FACING_FRONT -> "ANTERIORE"
            CameraCharacteristics.LENS_FACING_BACK -> "POSTERIORE"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "ESTERNA"
            else -> "?"
        }
    }

    private fun report(): String {
        val sb = StringBuilder()
        sb.appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()
        val feat = packageManager.hasSystemFeature("android.hardware.camera.concurrent")
        sb.appendLine("FEATURE_CAMERA_CONCURRENT: ${if (feat) "SI" else "NO"}")
        sb.appendLine()
        sb.appendLine("Fotocamere visibili:")
        try {
            for (id in cm.cameraIdList) sb.appendLine("  id $id -> ${facing(id)}")
        } catch (e: Exception) {
            sb.appendLine("  errore: $e")
        }
        sb.appendLine()
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val sets = cm.concurrentCameraIds
                if (sets.isEmpty()) {
                    sb.appendLine("Combinazioni concorrenti dichiarate: NESSUNA")
                } else {
                    sb.appendLine("Combinazioni concorrenti dichiarate:")
                    for (s in sets) sb.appendLine("  " + s.joinToString(" + ") { "id $it (${facing(it)})" })
                }
            } catch (e: Exception) {
                sb.appendLine("getConcurrentCameraIds errore: $e")
            }
        } else {
            sb.appendLine("API < 30: concurrent camera non disponibile")
        }
        sb.appendLine()
        sb.appendLine("Il pulsante sopra prova ad aprire davvero due fotocamere insieme (anche se non dichiarate).")
        return sb.toString()
    }

    private fun closeAll() {
        opened.forEach { try { it.close() } catch (_: Exception) {} }
        opened.clear()
    }

    private fun runOpenTest() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        closeAll()
        val ids = cm.cameraIdList.toList()
        val front = ids.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
        val back = ids.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        if (front == null || back == null) {
            out.text = report() + "\nTEST: manca una delle due fotocamere (anteriore=$front, posteriore=$back)"
            return
        }
        val log = StringBuilder(report()).appendLine().appendLine("TEST apertura simultanea:")
        out.text = log.toString() + "in corso..."

        var pending = 2
        var okCount = 0
        fun done() {
            pending--
            if (pending == 0) {
                log.appendLine()
                log.appendLine(
                    if (okCount == 2) "RISULTATO: entrambe aperte insieme -> OK"
                    else "RISULTATO: apertura simultanea NON riuscita"
                )
                out.text = log.toString()
                ui.postDelayed({ closeAll() }, 3000)
            }
        }
        fun open(id: String, name: String) {
            try {
                cm.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        opened.add(camera)
                        log.appendLine("  $name (id $id): aperta")
                        okCount++
                        done()
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        log.appendLine("  $name (id $id): disconnessa")
                        camera.close()
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        log.appendLine("  $name (id $id): errore $error")
                        camera.close()
                        done()
                    }
                }, ui)
            } catch (e: CameraAccessException) {
                log.appendLine("  $name (id $id): eccezione $e")
                done()
            } catch (e: SecurityException) {
                log.appendLine("  $name (id $id): permesso negato")
                done()
            }
        }
        open(front, "anteriore")
        open(back, "posteriore")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) runOpenTest()
    }

    override fun onDestroy() {
        closeAll()
        super.onDestroy()
    }
}
