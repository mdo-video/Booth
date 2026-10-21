package it.mdo.booth.tablet

import android.content.Context
import java.security.SecureRandom

/** Stato condiviso tra schermata, servizio dashcam e server. */
object DashState {
    @Volatile var phoneConnected = false
    @Volatile var phoneInfo = ""
    @Volatile var recording = false
    @Volatile var cams = "nessuna"
    @Volatile var concurrent = false
    @Volatile var lastSegmentAt = 0L
    @Volatile var lastError = ""
    @Volatile var serviceUp = false
    val startedAt = System.currentTimeMillis()
    @Volatile var updates: UpdateManager? = null

    /** Riferimenti per il controllo remoto delle impostazioni (li imposta la schermata principale). */
    @Volatile var local: LocalSettings? = null
    @Volatile var client: BoothClient? = null
    @Volatile var frames: FrameStore? = null
    @Volatile var kioskHook: ((Boolean) -> Unit)? = null
}

/** Impostazioni della dashcam (salvate sul tablet, modificabili anche dall'app di controllo). */
class DashConfig(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("booth_dash", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) { p.edit().putBoolean("enabled", v).apply() }
    var segMin: Int
        get() = p.getInt("segMin", 5)
        set(v) { p.edit().putInt("segMin", v.coerceIn(1, 30)).apply() }
    var keepMin: Int
        get() = p.getInt("keepMin", 120)
        set(v) { p.edit().putInt("keepMin", v.coerceIn(10, 2880)).apply() }
    var useBack: Boolean
        get() = p.getBoolean("useBack", true)
        set(v) { p.edit().putBoolean("useBack", v).apply() }
    /** rileva cadute e spostamenti del tablet */
    var motion: Boolean
        get() = p.getBoolean("motion", true)
        set(v) { p.edit().putBoolean("motion", v).apply() }
    /** inclinazione (gradi) oltre la quale il tablet risulta spostato */
    var motionDeg: Int
        get() = p.getInt("motionDeg", 10)
        set(v) { p.edit().putInt("motionDeg", v.coerceIn(3, 45)).apply() }
    /** la posteriore insieme alla frontale non si è aperta: si usa solo la frontale */
    var backFailed: Boolean
        get() = p.getBoolean("backFailed", false)
        set(v) { p.edit().putBoolean("backFailed", v).apply() }
    /** codice a 6 cifre che l'app di controllo deve conoscere */
    val key: String
        get() {
            var k = p.getString("key", null)
            if (k == null) {
                k = (100000 + SecureRandom().nextInt(900000)).toString()
                p.edit().putString("key", k).apply()
            }
            return k
        }
}
