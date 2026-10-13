package com.star.wgauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * خدمة أمامية: تراقب الشبكة وتفحص DNS/MTU في الخلفية (مع الـ VPN أو بدونه)
 * وتعيد الاختيار تلقائياً عند تبدّل الشبكة.
 */
class AutoService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitor: NetMonitor? = null
    private var lastText = "مراقبة الشبكة"
    private val app get() = application as WgApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "WG Auto", NotificationManager.IMPORTANCE_LOW)
        )
        promote()
        monitor = NetMonitor(this) { kind -> app.engine.onNetworkChanged(kind) }.also { it.start() }
        scope.launch {
            combine(app.engine.status, app.engine.netInfo) { st, ni ->
                val d = ni.direct
                when {
                    st.connected -> "متصل • ${st.activeConfig} • DNS ${st.activeDns} • MTU ${st.activeMtu}"
                    st.running -> st.message.ifEmpty { "جارٍ التشغيل…" }
                    d != null && d.dnsMs >= 0 -> {
                        val vpnPart = if (ni.vpn != null) " • VPN ${ni.vpnOwner}" else ""
                        "${d.label} • أسرع DNS ${d.bestDns} • MTU مقترح ${d.suggestedMtu}$vpnPart"
                    }
                    else -> "مراقبة الشبكة"
                }
            }.collect { text ->
                lastText = text
                nm.notify(NOTIF_ID, buildNotif(text))
            }
        }
        scope.launch { periodicLoop() }
        if (!app.engine.status.value.running) app.engine.scanNetwork("بدء الخدمة")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        return START_STICKY
    }

    override fun onDestroy() {
        monitor?.stop()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * المستخدم سحب التطبيق من الأخيرة: حاول البقاء حياً فوراً.
     * (بعض الأجهزة تمنع ذلك — عندها يتكفل الإشعار/الإقلاع/اللزوجة بالعودة.)
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            ContextCompat.startForegroundService(this, Intent(this, AutoService::class.java))
        } catch (e: Exception) {
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun promote() {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotif(lastText),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    /** كل 15 ثانية: حارس التجمد + فحص صحة خفيف، وكل دقيقة منطق إعادة المحاولة والفحص الدوري. */
    private suspend fun periodicLoop() {
        var tick = 0
        var minutes = 0
        var lastOvpnRevive = 0L
        val cm = getSystemService(ConnectivityManager::class.java)
        while (true) {
            delay(15_000)
            tick++
            app.engine.watchdog()
            val st = app.engine.status.value
            val s = app.store.settings.value
            if (st.running && st.connected && !st.busy) app.engine.healthCheck()
            if (tick % 4 != 0 || st.busy) continue
            // حارس OpenVPN الداخلي: إن كان مطلوباً (لم يفصله المستخدم) ولا نفق فعلياً، أحيِه.
            // يعالج موت العملية من قاتل الذاكرة/البطارية: الخدمتان لاصقتان وتعيدان بعضهما.
            if (s.ovpnAutoReconnect && !st.running && !app.ovpn.isUp() &&
                app.store.prefGet("ovpnActive")?.isNotEmpty() == true
            ) {
                val anyVpn = try {
                    NetUtil.all(cm).any { it.isVpn }
                } catch (e: Exception) {
                    true // عند الشك لا نتدخل
                }
                if (!anyVpn && System.currentTimeMillis() - lastOvpnRevive > 60_000) {
                    lastOvpnRevive = System.currentTimeMillis()
                    val msg = app.ovpn.reconnectLast()
                    if (msg != null) app.engine.log("🛡 $msg")
                }
            }
            if (st.running && !st.connected) {
                // اليدوي: لا إعادة اتصال تلقائية إطلاقاً — المستخدم يشغّل ويفصل بيده
                if (s.autoConfig) app.engine.autoReselect("إعادة محاولة")
                continue
            }
            minutes++
            if (s.periodMin > 0 && minutes >= s.periodMin) {
                minutes = 0
                // الدوري (نصف ساعة/ساعة): آلي فقط — يعيد الفحص ويبدّل للأفضل؛ اليدوي لا يُمس
                if (st.running && s.autoConfig) app.engine.opportunisticCheck("فحص دوري")
                else if (!st.running && s.backgroundScan) app.engine.scanNetwork("فحص دوري")
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
