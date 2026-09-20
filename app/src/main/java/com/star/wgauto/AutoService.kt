package com.star.wgauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** خدمة أمامية تُبقي التطبيق حياً وتراقب الشبكة وتجري الفحص الدوري. */
class AutoService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitor: NetMonitor? = null
    private val app get() = application as WgApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "WG Auto", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotif("جارٍ التشغيل…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        monitor = NetMonitor(this) { app.engine.onNetworkChanged() }.also { it.start() }
        scope.launch {
            app.engine.status.collect { st ->
                val text = if (st.connected)
                    "متصل • ${st.activeConfig} • DNS ${st.activeDns} • MTU ${st.activeMtu}"
                else st.message.ifEmpty { "متوقف" }
                nm.notify(NOTIF_ID, buildNotif(text))
            }
        }
        scope.launch { periodicLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        monitor?.stop()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun periodicLoop() {
        var minutes = 0
        while (true) {
            delay(60_000)
            val st = app.engine.status.value
            val s = app.store.settings.value
            if (!st.running || st.busy) {
                minutes = 0
                continue
            }
            if (!st.connected) {
                minutes = 0
                app.engine.reselect("إعادة محاولة")
                continue
            }
            minutes++
            if (s.periodMin > 0 && minutes >= s.periodMin) {
                minutes = 0
                app.engine.reselect("فحص دوري")
            }
        }
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("WG Auto")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL = "wgauto"
        const val NOTIF_ID = 1
    }
}
