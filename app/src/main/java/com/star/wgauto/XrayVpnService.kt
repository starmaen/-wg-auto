package com.star.wgauto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * نفق Xray (vmess/vless/trojan/ss) عبر libv2ray.
 * واصف tun يُنشأ هنا ويُمرَّر للنواة، والتطبيق نفسه مستثنى من الـ VPN
 * كي لا تدور حزم الاتصال بالسيرفر داخل النفق (حلقة لا نهائية).
 * تعمل في عملية :xray لأن WireGuard يجلب Go runtime آخر.
 */
class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.star.wgauto.XRAY_START"
        const val ACTION_STOP = "com.star.wgauto.XRAY_STOP"
        const val EXTRA_CONFIG = "xray_config"
        const val EXTRA_TITLE = "xray_title"
        const val EXTRA_TEST = "xray_test"

        private const val CHANNEL = "wgauto_xray"
        private const val NOTIF_ID = 21
        private const val ADDR4 = "10.10.14.1"
        private const val ADDR6 = "fc00::10:10:14:1"
    }

    private var core: CoreController? = null
    private var tun: ParcelFileDescriptor? = null
    private var running = false
    private var lastTitle = "Xray"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown(notify = true)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val cfg = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                val test = intent.getBooleanExtra(EXTRA_TEST, false)
                lastTitle = intent.getStringExtra(EXTRA_TITLE)?.ifBlank { "Xray" } ?: "Xray"
                ensureChannel()
                promote(lastTitle, "جارٍ الاتصال…")
                if (cfg.isBlank()) {
                    fail("إعداد فارغ")
                    return START_NOT_STICKY
                }
                return if (startCore(cfg, test)) START_STICKY else START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        shutdown(notify = true)
        super.onRevoke()
    }

    override fun onDestroy() {
        if (running) shutdown(notify = true)
        super.onDestroy()
    }

    private fun startCore(cfg: String, test: Boolean = false): Boolean {
        // تبديل سيرفر: أوقف النواة السابقة وأعد استخدام الخدمة نفسها
        if (running) {
            try { core?.stopLoop() } catch (e: Exception) { }
            try { tun?.close() } catch (e: Exception) { }
            core = null
            tun = null
            running = false
        }
        return try {
            val pfd = buildTun(test) ?: run {
                fail("تعذّر إنشاء واجهة VPN (تحقق من الإذن)")
                return false
            }
            Seq.setContext(applicationContext)
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            val cc = CoreController(object : CoreCallbackHandler {
                override fun startup(): Long = 0L
                override fun shutdown(): Long = 0L
                override fun onEmitStatus(l: Long, s: String?): Long = 0L
            })
            cc.startLoop(cfg, pfd.fd)
            if (!cc.isRunning) {
                try { pfd.close() } catch (e: Exception) { }
                fail("رفضت النواة تشغيل الإعداد")
                return false
            }
            core = cc
            tun = pfd
            running = true
            promote(lastTitle, "متصل")
            sendState(running = true, connecting = false, title = lastTitle, error = "")
            true
        } catch (e: Throwable) {
            try { core?.stopLoop() } catch (x: Exception) { }
            try { tun?.close() } catch (x: Exception) { }
            core = null
            tun = null
            running = false
            fail(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun buildTun(test: Boolean = false): ParcelFileDescriptor? {
        val b = Builder()
            .setSession(if (test) "WG Auto — فحص" else "WG Auto — Xray")
            .setMtu(1500)
            .addAddress(ADDR4, 30)
        if (test) {
            // فحص عميق: نوجّه عنوان الفحص فقط (1.1.1.1) داخل النفق
            // مع السماح للتطبيق — كي تخرج حزم النواة إلى السيرفر مباشرة (بلا حلقة)
            b.addRoute("1.1.1.1", 32)
            try {
                b.addAllowedApplication(packageName)
            } catch (e: Exception) {
            }
        } else {
            b.addRoute("0.0.0.0", 0)
                .addAddress(ADDR6, 126)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
            try {
                b.addDisallowedApplication(packageName)
            } catch (e: Exception) {
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            b.setMetered(false)
        }
        return try {
            b.establish()
        } catch (e: Exception) {
            null
        }
    }

    private fun shutdown(notify: Boolean) {
        try { core?.stopLoop() } catch (e: Exception) { }
        try { tun?.close() } catch (e: Exception) { }
        core = null
        tun = null
        running = false
        removeForeground()
        if (notify) sendState(running = false, connecting = false, title = "", error = "")
        stopSelf()
    }

    private fun fail(msg: String) {
        sendState(running = false, connecting = false, title = lastTitle, error = msg)
        removeForeground()
        stopSelf()
    }

    private fun removeForeground() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
        }
    }

    private fun sendState(running: Boolean, connecting: Boolean, title: String, error: String) {
        try {
            val i = Intent(XrayEngine.ACTION_STATE).setPackage(packageName)
                .putExtra(XrayEngine.EXTRA_RUNNING, running)
                .putExtra(XrayEngine.EXTRA_CONNECTING, connecting)
                .putExtra(XrayEngine.EXTRA_TITLE, title)
                .putExtra(XrayEngine.EXTRA_ERROR, error)
            sendBroadcast(i)
        } catch (e: Exception) {
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "WG Auto — Xray", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun promote(title: String, text: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, XrayVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("WG Auto — Xray")
            .setContentText("$title • $text")
            .setContentIntent(open)
            .addAction(0, "فصل", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }
}
