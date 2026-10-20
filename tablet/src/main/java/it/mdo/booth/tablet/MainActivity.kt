package it.mdo.booth.tablet

import android.Manifest
import android.content.Intent
import android.hardware.input.InputManager
import android.content.pm.PackageManager
import android.os.Build
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

/** Impostazioni che restano sul tablet. */
class LocalSettings(private val prefs: SharedPreferences) {
    var mirror by mutableStateOf(prefs.getBoolean("mirror", true))
    var countdown by mutableIntStateOf(prefs.getInt("countdown", 3))
    var kiosk by mutableStateOf(prefs.getBoolean("kiosk", false))
    var sound by mutableStateOf(prefs.getBoolean("sound", true))
    var autoQr by mutableStateOf(prefs.getBoolean("autoQr", true))
    var wifiSsid by mutableStateOf(prefs.getString("wifiSsid", "") ?: "")
    var wifiPass by mutableStateOf(prefs.getString("wifiPass", "") ?: "")
    var welcome by mutableStateOf(prefs.getString("welcome", "Lascia una dedica!") ?: "")
    var frameDefaultDone by mutableStateOf(prefs.getBoolean("frameDefaultDone", false))
    /** telecomando Bluetooth associato (descrittore del dispositivo di input + nome da mostrare) */
    var remoteDevice by mutableStateOf(prefs.getString("remoteDevice", "") ?: "")
    var remoteName by mutableStateOf(prefs.getString("remoteName", "") ?: "")

    fun save() {
        prefs.edit()
            .putBoolean("mirror", mirror)
            .putInt("countdown", countdown)
            .putBoolean("kiosk", kiosk)
            .putBoolean("sound", sound)
            .putBoolean("autoQr", autoQr)
            .putString("wifiSsid", wifiSsid)
            .putString("wifiPass", wifiPass)
            .putString("welcome", welcome)
            .putBoolean("frameDefaultDone", frameDefaultDone)
            .putString("remoteDevice", remoteDevice)
            .putString("remoteName", remoteName)
            .apply()
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var client: BoothClient
    private lateinit var local: LocalSettings
    private lateinit var frames: FrameStore
    private lateinit var dashCfg: DashConfig

    private val pickFrame = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            Thread {
                val ok = frames.importUri(uri)
                runOnUiThread {
                    if (ok) frames.enableOnSync = true
                    else Toast.makeText(this, "Immagine non valida", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
    }

    /** tiene aggiornata l'icona del telecomando (collegato / non collegato) */
    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) { RemoteState.refresh(local) }
        override fun onInputDeviceRemoved(deviceId: Int) { RemoteState.refresh(local) }
        override fun onInputDeviceChanged(deviceId: Int) { RemoteState.refresh(local) }
    }

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startDash() }

    private fun startDash() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) DashcamService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        dashCfg = DashConfig(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        local = LocalSettings(getSharedPreferences("booth_tablet", MODE_PRIVATE))
        getSystemService(InputManager::class.java)?.registerInputDeviceListener(inputListener, null)
        RemoteState.refresh(local)
        frames = FrameStore(this).also { it.load() }
        if (frames.bitmap == null && !local.frameDefaultDone) {
            // prima apertura: cornice "Gaia +30" già pronta e attiva
            local.frameDefaultDone = true
            local.save()
            if (frames.importAsset("frame_gaia30.png")) frames.enableOnSync = true
        }
        client = BoothClient(this).also { it.start() }
        DashState.local = local
        DashState.frames = frames
        DashState.client = client
        DashState.kioskHook = { on -> runOnUiThread { setKiosk(on) } }
        DashState.updates = UpdateManager(this, "booth-tablet.apk", BuildConfig.UPDATE_TOKEN) {
            // il kiosk impedisce di aprire l'installazione: si toglie prima
            local.kiosk = false
            local.save()
            runOnUiThread { runCatching { stopLockTask() } }
        }.also { it.check() }
        val need = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) startDash() else askPerms.launch(need.toTypedArray())
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                BoothScreen(client, local, frames, onKiosk = ::setKiosk, onShare = ::shareTake, onPickFrame = ::chooseFrame, dashCfg = dashCfg)
            }
        }
        if (local.kiosk) setKiosk(true)
    }

    /** Apre la galleria per scegliere la cornice (il kiosk blocca le altre app: si disattiva). */
    private fun chooseFrame() {
        runCatching { stopLockTask() }
        local.kiosk = false
        local.save()
        pickFrame.launch("image/*")
    }

    private fun setKiosk(on: Boolean) {
        runCatching { if (on) startLockTask() else stopLockTask() }
    }

    /** Scarica il video dal telefono e apre il menu Condividi (WhatsApp, email...). */
    private fun shareTake(path: String, fileName: String, onProgress: (Float) -> Unit, done: (Boolean) -> Unit) {
        // il blocco kiosk impedisce di aprire altre app: lo togliamo
        runCatching { stopLockTask() }
        local.kiosk = false
        local.save()

        val dir = File(cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val dest = File(dir, fileName)
        client.download(path, dest, { p -> runOnUiThread { onProgress(p) } }) { ok ->
            runOnUiThread {
                done(ok)
                if (ok) {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", dest)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = if (fileName.endsWith(".jpg")) "image/jpeg" else "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Condividi dedica"))
                }
            }
        }
    }

    /** Telecomando Bluetooth: i suoi tasti diventano gesti (singola/doppia/tripla/lunga). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::local.isInitialized && RemoteInput.handle(event, local)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(inputListener)
        DashState.kioskHook = null
        client.stop()
        super.onDestroy()
    }
}
