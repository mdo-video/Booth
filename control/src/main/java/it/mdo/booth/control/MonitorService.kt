package it.mdo.booth.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Controlla il booth anche con lo schermo spento: ogni 5 secondi legge lo stato dal tablet e,
 * se qualcosa non va (camera scollegata, batteria bassa, poco spazio...), manda una notifica.
 * Un problema viene segnalato solo se resta per due controlli di fila, per evitare falsi allarmi.
 */
class MonitorService : Service() {
    companion object {
        private const val CH_MON = "monitor"
        private const val CH_ALERT = "alert2"
        private const val ID_MON = 91
        private const val ID_ALERT = 92

        fun enabled(ctx: Context): Boolean =
            ctx.getSharedPreferences("control", Context.MODE_PRIVATE).getBoolean("monitor", true)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences("control", Context.MODE_PRIVATE).edit().putBoolean("monitor", on).apply()
            if (on) start(ctx) else stop(ctx)
        }

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, MonitorService::class.java)) }
        }

        /** Toglie numeri e tempi dal motivo, così "Ultima risposta 12 s fa" resta lo stesso problema. */
        private fun norm(r: String): String =
            r.replace(Regex("\\(.*?\\)"), "").replace(Regex("[0-9]+"), "#").trim()
    }

    private var scope: CoroutineScope? = null
    private var api: Api? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MON, "Monitoraggio booth", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Avvisi booth", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) },
        )
        val notif = Notification.Builder(this, CH_MON)
            .setContentTitle("Booth Controllo")
            .setContentText("Controllo il booth anche a schermo spento")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openApp())
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, ID_MON, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID_MON, notif)
            }
        } catch (e: Exception) {
            stopSelf()
            return
        }
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "booth:monitor").apply { acquire() }
        wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "booth:monitor").apply { acquire() }
        val a = Api(this).also { it.start() }
        api = a
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s -> s.launch { monitor(a) } }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private suspend fun monitor(a: Api) {
        val count = HashMap<String, Int>() // motivo -> controlli consecutivi
        var alerted = emptySet<String>()
        while (true) {
            a.refreshLight()
            val h = health(a)
            val current = h.reasons.associateBy { norm(it) }
            count.keys.retainAll(current.keys)
            for (k in current.keys) count[k] = (count[k] ?: 0) + 1
            val stable = current.filterKeys { (count[it] ?: 0) >= 2 }
            if (stable.isEmpty()) {
                if (alerted.isNotEmpty()) getSystemService(NotificationManager::class.java).cancel(ID_ALERT)
            } else if ((stable.keys - alerted).isNotEmpty()) {
                notifyAlert(h.level, stable.values.toList())
            }
            alerted = stable.keys
            delay(5000)
        }
    }

    private fun notifyAlert(level: Int, reasons: List<String>) {
        val n = Notification.Builder(this, CH_ALERT)
            .setContentTitle(if (level >= 2) "ATTENZIONE: booth" else "Booth: da controllare")
            .setContentText(reasons.first())
            .setStyle(Notification.BigTextStyle().bigText(reasons.joinToString("\n") { "• $it" }))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_ALERT, n)
    }

    override fun onDestroy() {
        scope?.cancel()
        api?.stop()
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }
}
