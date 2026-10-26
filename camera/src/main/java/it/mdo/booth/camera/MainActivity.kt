package it.mdo.booth.camera

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.Manifest
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    // stessi colori dell'app Controllo
    private val cBg = 0xFF101216.toInt()
    private val cCard = 0xFF1B1E25.toInt()
    private val cChip = 0xFF2A2E38.toInt()
    private val cBlue = 0xFF3D5AFE.toInt()
    private val cTitle = 0xFF9AA0AC.toInt()
    private val cSub = 0xFFB0B5C0.toInt()
    private val cGreen = 0xFF66BB6A.toInt()
    private val cGreenTxt = 0xFF81C784.toInt()
    private val cAmber = 0xFFFFB300.toInt()
    private val cRed = 0xFFFF5252.toInt()

    private lateinit var updText: TextView
    private lateinit var updBtn: Button
    private lateinit var updates: UpdateManager
    private var camera: CameraController? = null
    private var server: StreamServer? = null
    private var nsd: NsdAdvertiser? = null
    private var motion: MotionGuard? = null

    // schede dell'interfaccia
    private lateinit var body: LinearLayout
    private lateinit var left: LinearLayout
    private lateinit var right: LinearLayout
    private lateinit var stateCard: LinearLayout
    private lateinit var screenCard: LinearLayout
    private lateinit var screenSpacer: View
    private lateinit var pillBox: LinearLayout
    private lateinit var pillDot: View
    private lateinit var pillText: TextView
    private lateinit var stateDot: View
    private lateinit var stateLabel: TextView
    private lateinit var stateSub: TextView
    private lateinit var progTrack: LinearLayout
    private lateinit var progFill: View
    private lateinit var valTablet: TextView
    private lateinit var valQuality: TextView
    private lateinit var valOrient: TextView
    private lateinit var valMic: TextView
    private lateinit var secCode: TextView
    private lateinit var secBtn: Button
    private lateinit var driveText: TextView
    private lateinit var driveConnBtn: Button
    private lateinit var driveAutoBtn: Button
    private lateinit var driveWifiBtn: Button
    private lateinit var driveNowBtn: Button
    private val driveClient by lazy { DriveClient(this) }
    private var drive: DriveUploader? = null
    private var driveMsg = ""

    // stato mostrato
    private var curState = "READY"
    private var permError = false
    private var recStart = 0L
    private var info = JSONObject()

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
    // ogni secondo: collegamento al tablet e tempo di registrazione
    private val uiTick = object : Runnable {
        override fun run() {
            render()
            renderDrive()
            ui.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun roundBg(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun ovalBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun tv(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(cCard, 16)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(tv(title, 13f, cTitle, true).apply { letterSpacing = 0.04f })
    }

    private fun chipBtn(text: String, bg: Int, textSp: Float = 15f) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        background = roundBg(bg, 24)
        stateListAnimator = null
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(14), 0, dp(14), 0)
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, top: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    /** Riga "etichetta ... valore" dentro la scheda Stato. */
    private fun line(label: String): TextView {
        val value = tv("—", 14f, 0xFFFFFFFF.toInt(), true)
        stateCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tv(label, 14f, cSub), lp(0, -2, 1f))
            addView(value, lp(-2, -2))
        }, lp(-1, -2, top = 6))
        return value
    }

    private fun buildUi(): View {
        val wrap = -2
        val match = -1

        left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // intestazione: logo, nome, versione e stato del tablet
        val logo = FrameLayout(this).apply {
            background = roundBg(cBlue, 14)
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_booth_logo) },
                FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("Booth Camera", 22f, 0xFFFFFFFF.toInt(), true))
            addView(tv("Telefono camera · v${BuildConfig.VERSION_NAME}", 13f, cTitle))
        }
        pillDot = View(this)
        pillText = tv("", 14f, 0xFFFFFFFF.toInt(), true)
        pillBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            addView(pillDot, lp(dp(10), dp(10)).apply { rightMargin = dp(8) })
            addView(pillText)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(logo, lp(dp(44), dp(44)).apply { rightMargin = dp(12) })
            addView(titles, lp(0, wrap, 1f))
            addView(pillBox, lp(wrap, wrap))
        }
        left.addView(header, lp(match, wrap))

        // scheda STATO
        stateCard = card("STATO")
        stateDot = View(this)
        stateLabel = tv("", 28f, 0xFFFFFFFF.toInt(), true)
        stateCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stateDot, lp(dp(16), dp(16)).apply { rightMargin = dp(12) })
            addView(stateLabel)
        }, lp(match, wrap, top = 8))
        stateSub = tv("", 14f, cSub)
        stateCard.addView(stateSub, lp(match, wrap, top = 6))
        progFill = View(this).apply { background = roundBg(cRed, 4) }
        progTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 100f
            background = roundBg(cChip, 4)
            addView(progFill, lp(0, match, 0f))
            visibility = View.GONE
        }
        stateCard.addView(progTrack, lp(match, dp(8), top = 8))
        stateCard.addView(View(this).apply { setBackgroundColor(cChip) }, lp(match, dp(1), top = 10))
        valTablet = line("Collegato al tablet")
        valQuality = line("Qualità video")
        valOrient = line("Orientamento")
        valMic = line("Microfono")
        left.addView(stateCard, lp(match, wrap, 1f, top = 12))

        // scheda AGGIORNAMENTI
        val updCard = card("AGGIORNAMENTI")
        updText = tv("", 14f, cSub)
        updCard.addView(updText, lp(match, wrap, top = 8))
        updBtn = chipBtn("Controlla", cChip)
        val newsBtn = chipBtn("Novità", cChip).apply { setOnClickListener { Changelog.show(this@MainActivity) } }
        updCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(updBtn, lp(0, wrap, 1f).apply { rightMargin = dp(4) })
            addView(newsBtn, lp(0, wrap, 1f).apply { leftMargin = dp(4) })
        }, lp(match, wrap, top = 10))
        right.addView(updCard, lp(match, wrap))

        // scheda CODICE DI ABBINAMENTO
        val secCard = card("CODICE DI ABBINAMENTO")
        secCode = tv(Pairing.key(this), 30f, 0xFFFFFFFF.toInt(), true).apply {
            letterSpacing = 0.15f
            // tenuto premuto: nuovo codice (poi va riscritto sul tablet)
            setOnLongClickListener { Pairing.regenerate(this@MainActivity); renderSec(); true }
        }
        secCard.addView(secCode, lp(match, wrap, top = 8))
        secCard.addView(
            tv("Scrivilo nelle impostazioni del tablet (Telefono camera), poi accendi la protezione. Tieni premuto il codice per cambiarlo.", 13f, cSub),
            lp(match, wrap, top = 4),
        )
        secBtn = chipBtn("", cChip).apply {
            setOnClickListener {
                Pairing.setRequired(this@MainActivity, !Pairing.required(this@MainActivity))
                renderSec()
            }
        }
        secCard.addView(secBtn, lp(match, wrap, top = 10))
        right.addView(secCard, lp(match, wrap, top = 12))
        renderSec()

        // scheda GOOGLE DRIVE
        val driveCard = card("GOOGLE DRIVE")
        driveText = tv("", 14f, cSub)
        driveCard.addView(driveText, lp(match, wrap, top = 8))
        driveConnBtn = chipBtn("Collega Drive", cBlue).apply { setOnClickListener { onDriveConnect() } }
        driveCard.addView(driveConnBtn, lp(match, wrap, top = 10))
        driveAutoBtn = chipBtn("", cChip).apply {
            setOnClickListener { drive?.let { it.auto = !it.auto }; renderDrive() }
        }
        driveCard.addView(driveAutoBtn, lp(match, wrap, top = 8))
        driveWifiBtn = chipBtn("", cChip).apply {
            setOnClickListener { drive?.let { it.wifiOnly = !it.wifiOnly }; renderDrive() }
        }
        driveCard.addView(driveWifiBtn, lp(match, wrap, top = 8))
        driveNowBtn = chipBtn("Carica ora quelle non ancora caricate", cChip).apply {
            setOnClickListener { drive?.enqueue(camera?.historyNames().orEmpty()) }
        }
        driveCard.addView(driveNowBtn, lp(match, wrap, top = 8))
        right.addView(driveCard, lp(match, wrap, top = 12))
        renderDrive()

        // scheda SCHERMO
        screenCard = card("SCHERMO")
        screenCard.addView(
            tv("Si spegne da solo dopo 1 minuto senza tocchi. Doppio tocco per riaccenderlo.", 14f, cSub),
            lp(match, wrap, top = 8),
        )
        screenSpacer = View(this)
        screenCard.addView(screenSpacer, lp(match, 0, 1f))
        val blackBtn = chipBtn("Schermo nero", cBlue, 16f).apply {
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dp(48)
            minimumHeight = dp(48)
            setOnClickListener { enterBlack() }
        }
        screenCard.addView(blackBtn, lp(match, wrap, top = 10))
        right.addView(screenCard, lp(match, wrap, 1f, top = 12))

        body = LinearLayout(this)
        body.addView(left)
        body.addView(right)
        applyLayout()

        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(cBg)
            setPadding(dp(24), dp(20), dp(24), dp(20))
            clipToPadding = false
            addView(body, FrameLayout.LayoutParams(match, match))
        }
    }

    /** Orizzontale: due colonne. Verticale: una colonna sola. */
    private fun applyLayout() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            body.orientation = LinearLayout.HORIZONTAL
            left.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            right.layoutParams = LinearLayout.LayoutParams(dp(340), -1).apply { leftMargin = dp(16) }
            stateCard.layoutParams = lp(-1, 0, 1f, top = 12)
            screenCard.layoutParams = lp(-1, 0, 1f, top = 12)
            screenSpacer.visibility = View.VISIBLE
        } else {
            body.orientation = LinearLayout.VERTICAL
            left.layoutParams = LinearLayout.LayoutParams(-1, -2)
            right.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            stateCard.layoutParams = lp(-1, -2, top = 12)
            screenCard.layoutParams = lp(-1, -2, top = 12)
            screenSpacer.visibility = View.GONE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    private fun onDriveConnect() {
        driveMsg = ""
        renderDrive()
        // prima si sceglie l'account Google, poi si dà il consenso a Drive
        driveClient.chooseAccount(this)
    }

    private fun onDriveAccount(name: String) {
        driveClient.accountName = name
        driveClient.connect(this) { ok, err ->
            runOnUiThread {
                driveMsg = if (ok || err.isEmpty()) "" else err
                renderDrive()
                if (ok) drive?.kick()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DriveClient.REQ_ACCOUNT) {
            val name = if (resultCode == RESULT_OK) driveClient.accountFrom(data) else null
            if (name == null) {
                driveMsg = "Scelta dell'account annullata"
                renderDrive()
            } else {
                onDriveAccount(name)
            }
        }
        if (requestCode == DriveClient.REQ_AUTH) {
            val ok = resultCode == RESULT_OK && driveClient.handleResult(data)
            driveMsg = if (ok) "" else "Collegamento a Drive non riuscito" +
                (driveClient.lastError.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "")
            renderDrive()
            if (ok) drive?.kick()
        }
    }

    private fun renderDrive() {
        if (!::driveText.isInitialized) return
        val d = drive
        val on = driveClient.connected
        val lines = ArrayList<String>()
        if (!on) {
            lines.add("Non collegato. Collega un account Google: le dediche andranno in Drive, cartella \"Booth Dediche\".")
        } else if (d == null) {
            lines.add("Collegato a Google Drive" + (driveClient.accountName?.let { " ($it)" } ?: "") + ".")
        } else {
            val queued = runCatching { org.json.JSONObject(d.statusJson()).optInt("queued") }.getOrDefault(0)
            lines.add(
                when {
                    d.current.isNotEmpty() -> "Carico ${d.current}  ${d.pct}%" + if (queued > 1) "  ·  in coda ${queued - 1}" else ""
                    queued > 0 -> "In coda: $queued"
                    else -> "✓ Tutto caricato (${d.doneNames().size})"
                }
            )
            if (d.lastError.isNotEmpty()) lines.add("⚠ " + d.lastError)
        }
        if (driveMsg.isNotEmpty()) lines.add("⚠ $driveMsg")
        driveText.text = lines.joinToString("\n")
        driveConnBtn.text = if (on) "Cambia account / rinnova consenso" else "Collega Drive"
        driveConnBtn.background = roundBg(if (on) cChip else cBlue, 24)
        val auto = d?.auto ?: true
        val wifi = d?.wifiOnly ?: false
        driveAutoBtn.text = if (auto) "Caricamento automatico: acceso" else "Caricamento automatico: spento"
        driveAutoBtn.background = roundBg(if (auto) 0xFF2E7D32.toInt() else cChip, 24)
        driveWifiBtn.text = if (wifi) "Solo con Wi-Fi: acceso" else "Solo con Wi-Fi: spento (usa anche i dati)"
        driveWifiBtn.background = roundBg(if (wifi) 0xFF2E7D32.toInt() else cChip, 24)
        val vis = if (on) View.VISIBLE else View.GONE
        driveAutoBtn.visibility = vis
        driveWifiBtn.visibility = vis
        driveNowBtn.visibility = vis
    }

    private fun renderSec() {
        val on = Pairing.required(this)
        secCode.text = Pairing.key(this)
        secBtn.text = if (on) "Protezione accesa (tocca per spegnere)" else "Protezione spenta (tocca per accendere)"
        secBtn.background = roundBg(if (on) 0xFF2E7D32.toInt() else cChip, 24)
    }

    /** Una sola volta chiede di escludere l'app dal risparmio batteria (su Xiaomi evita che venga chiusa). */
    private fun askBatteryExemption() {
        val p = getSharedPreferences("booth_service", MODE_PRIVATE)
        if (p.getBoolean("battAsked", false)) return
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        p.edit().putBoolean("battAsked", true).apply()
        runCatching {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun mmss(sec: Long): String = "%02d:%02d".format(sec / 60, sec % 60)

    /** Aggiorna testi e colori in base a stato, collegamento al tablet e info del telefono. */
    private fun render() {
        val connected = server?.hasClients() == true
        val rec = curState == "RECORDING"
        val label: String
        var sub: String
        val color: Int
        when {
            permError -> { label = "Servono i permessi"; sub = "Concedi fotocamera e microfono dalle impostazioni dell'app."; color = cRed }
            !booted -> { label = "Avvio…"; sub = "Controllo permessi e fotocamera."; color = cAmber }
            rec -> { label = "In registrazione"; sub = ""; color = cRed }
            curState.startsWith("PROCESSING") -> {
                label = "Elaboro il video"; sub = "${curState.substringAfter(':', "0")}%"; color = cBlue
            }
            !connected -> {
                label = "In attesa del tablet"
                sub = "Apri l'app Tablet: si collega da sola se siete sulla stessa rete Wi-Fi."
                color = cAmber
            }
            curState == "TAKE_SAVED" || curState.startsWith("TAKE_SAVED") -> { label = "Salvato"; sub = "La dedica è nella galleria del telefono."; color = cGreen }
            curState.startsWith("TAKE") -> { label = "Video pronto"; sub = "Il tablet decide se salvarlo o rifarlo."; color = cGreen }
            else -> { label = "Pronto"; sub = "In attesa di un comando dal tablet."; color = cGreen }
        }
        if (rec && booted) {
            val max = info.optInt("duration", 60).coerceAtLeast(1)
            val el = ((System.currentTimeMillis() - recStart) / 1000).coerceAtLeast(0)
            sub = "${mmss(el)} su ${max} s. Il tablet comanda la registrazione."
            val pct = (el * 100f / max).coerceIn(0f, 100f)
            (progFill.layoutParams as LinearLayout.LayoutParams).weight = pct
            progFill.requestLayout()
            progTrack.visibility = View.VISIBLE
        } else {
            progTrack.visibility = View.GONE
        }
        stateLabel.text = label
        stateSub.text = sub
        stateDot.background = ovalBg(color)

        pillDot.background = ovalBg(if (connected) cGreen else cAmber)
        pillText.text = if (connected) "Tablet collegato" else "Tablet non collegato"
        pillBox.background = roundBg(if (connected) 0xFF1E3A22.toInt() else 0xFF3A2B0F.toInt(), 20)

        valTablet.text = if (connected) "sì" else "NO"
        valTablet.setTextColor(if (connected) cGreenTxt else cAmber)
        valQuality.text = when (info.optString("quality")) {
            "UHD" -> "4K"
            "FHD" -> "Full HD"
            "HD" -> "HD"
            else -> "—"
        }
        valOrient.text = when (info.optString("orientation")) {
            "portrait" -> "Verticale"
            "landscape" -> "Orizzontale"
            else -> "—"
        }
        val mic = info.optString("mic")
        valMic.text = if (mic.isNotEmpty()) mic else "Telefono"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.setBackgroundColor(cBg)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = cBg
            window.navigationBarColor = cBg
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val root = buildUi()
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
        render()
        ui.postDelayed(idleTick, 5000)
        ui.postDelayed(uiTick, 1000)

        updates = UpdateManager(this, "booth-camera.apk", BuildConfig.UPDATE_TOKEN)
        updates.listener = { st ->
            runOnUiThread {
                updText.text = st.text
                updBtn.isEnabled = !st.busy
                updBtn.text = if (st.canInstall) "Installa" else "Controlla"
            }
        }
        updBtn.setOnClickListener {
            if (updates.status.value.canInstall) updates.installNow() else updates.check()
        }
        updText.text = updates.status.value.text
        updates.check()
        updates.startAutoCheck()
        updates.handleIntent(intent)
        updText.setOnClickListener { Changelog.show(this) }   // tocca la scritta della versione per le novità
        window.decorView.post { Changelog.showIfNew(this, updates.installedCode()) }

        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true && result[Manifest.permission.RECORD_AUDIO] == true) boot()
            else { permError = true; render() }
        }.launch(buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())
    }

    private fun boot() {
        val up = DriveUploader(this, driveClient, { n -> camera?.historyFile(n) }, { runOnUiThread { renderDrive() } })
        drive = up
        val srv = StreamServer(
            takeFile = { name -> camera?.takeFile(name) },
            onCommand = { cmd -> runOnUiThread { handle(cmd) } },
            historyFile = { name -> camera?.historyFile(name) },
            historyList = { camera?.historyJson() ?: "[]" },
            thumbFile = { name -> camera?.thumbFile(name) },
            onFrame = { bytes -> camera?.saveFrame(bytes) ?: false },
            pairKey = { if (Pairing.required(this@MainActivity)) Pairing.key(this@MainActivity) else null },
            driveStatus = { up.statusJson() },
        )
        val cam = CameraController(
            context = this,
            owner = this,
            wantsFrames = srv::hasClients,
            onPreviewFrame = srv::sendFrame,
            onState = { s ->
                srv.sendState(s)
                runOnUiThread {
                    if (s == "RECORDING" && curState != "RECORDING") recStart = System.currentTimeMillis()
                    curState = s
                    render()
                }
            },
            onEvent = { e ->
                srv.sendEvent(e)
                if (e.startsWith("SAVED:")) {
                    runOnUiThread { if (up.auto && driveClient.connected) up.enqueue(listOf(e.removePrefix("SAVED:"))) }
                }
            },
            onLevel = srv::sendLevel,
            onInfo = { j ->
                srv.sendInfo(j)
                runOnUiThread {
                    runCatching { info = JSONObject(j) }
                    render()
                }
            },
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
        up.start()
        CameraService.start(this)
        askBatteryExemption()
        booted = true
        render()
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
            cmd.startsWith("DRIVE_UP:") -> {
                val b = cmd.removePrefix("DRIVE_UP:").trim()
                val names = if (b == "*") cam.historyNames() else b.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (driveClient.connected) drive?.enqueue(names)
            }
            cmd.startsWith("DRIVE_AUTO:") -> { drive?.auto = cmd.endsWith("1"); renderDrive() }
            cmd.startsWith("DEL:") -> cam.deleteHistory(cmd.removePrefix("DEL:"))
            cmd.startsWith("SET:") -> runCatching { cam.applySettings(JSONObject(cmd.removePrefix("SET:"))) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        updates.handleIntent(intent)
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
        CameraService.stop(this)
        super.onDestroy()
    }
}
