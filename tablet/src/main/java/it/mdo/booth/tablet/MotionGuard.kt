package it.mdo.booth.tablet

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Accelerometro: riconosce cadute (assenza di peso + urto), urti forti e spostamenti/inclinazioni
 * (la direzione della gravità cambia più di [degrees] gradi per qualche secondo).
 * onEvent riceve "FALL", "HIT" o "MOVED".
 */
class MotionGuard(
    ctx: Context,
    private val enabled: () -> Boolean,
    private val degrees: () -> Int,
    private val onEvent: (String) -> Unit,
) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val acc: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var gx = 0f; private var gy = 0f; private var gz = 0f
    private var haveG = false
    private var rx = 0f; private var ry = 0f; private var rz = 0f
    private var haveRef = false
    private var startedAt = 0L
    private var freeSince = 0L
    private var freeAt = 0L
    private var offSince = 0L
    private var lastEvent = 0L

    fun start(): Boolean {
        val s = acc ?: return false
        startedAt = System.currentTimeMillis()
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        return true
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun fire(kind: String, now: Long) {
        if (now - lastEvent < 10_000) return
        lastEvent = now
        onEvent(kind)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!enabled()) {
            haveRef = false
            offSince = 0L
            return
        }
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val now = System.currentTimeMillis()
        val mag = sqrt(x * x + y * y + z * z)

        // caduta: almeno 100 ms di assenza di peso, poi un urto entro 1 secondo
        if (mag < 2.0f) {
            if (freeSince == 0L) freeSince = now
        } else {
            if (freeSince != 0L) {
                if (now - freeSince >= 100) freeAt = now
                freeSince = 0L
            }
            if (mag > 25f && freeAt != 0L && now - freeAt < 1000) {
                freeAt = 0L
                fire("FALL", now)
            } else if (mag > 40f) {
                fire("HIT", now)
            }
        }

        // direzione della gravità (media lenta, circa 1 secondo)
        if (!haveG) {
            gx = x; gy = y; gz = z; haveG = true
        } else {
            gx += 0.02f * (x - gx); gy += 0.02f * (y - gy); gz += 0.02f * (z - gz)
        }
        if (now - startedAt < 3000) return
        if (!haveRef) {
            rx = gx; ry = gy; rz = gz; haveRef = true
            return
        }
        val gn = sqrt(gx * gx + gy * gy + gz * gz)
        val rn = sqrt(rx * rx + ry * ry + rz * rz)
        if (gn < 1f || rn < 1f) return
        val cos = ((gx * rx + gy * ry + gz * rz) / (gn * rn)).coerceIn(-1f, 1f)
        val angle = Math.toDegrees(acos(cos).toDouble())
        if (angle > degrees()) {
            if (offSince == 0L) {
                offSince = now
            } else if (now - offSince > 2500) {
                fire("MOVED", now)
                rx = gx; ry = gy; rz = gz
                offSince = 0L
            }
        } else {
            offSince = 0L
        }
    }
}
