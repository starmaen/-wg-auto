package com.star.wgauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * سكربت الإقلاع التلقائي: بعد تشغيل الهاتف يعيد خدمة المراقبة،
 * وحارسها يحيي نفق OpenVPN خلال دقيقة إن كان مطلوباً قبل الإطفاء.
 * (أندرويد 12+ يمنع بدء خدمة أمامية من الخلفية: نحاول أولاً،
 * وإن مُنعنا نترك إشعاراً بضغطة واحدة تستعيد كل شيء.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = ctx.applicationContext
        val store = try {
            Store(app)
        } catch (e: Exception) {
            return
        }
        val wantOvpn = store.prefGet("ovpnActive")?.isNotEmpty() == true
        val wantScan = try {
            store.settings.value.backgroundScan
        } catch (e: Exception) {
            true
        }
        if (!wantOvpn && !wantScan) return
        try {
            ContextCompat.startForegroundService(app, Intent(app, AutoService::class.java))
        } catch (e: Exception) {
            // أندرويد 12+: البدء من الخلفية ممنوع — إشعار استعادة بضغطة
            try {
                val nm = app.getSystemService(android.app.NotificationManager::class.java)
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        AutoService.CHANNEL, "WG Auto",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    )
                )
                val pi = android.app.PendingIntent.getActivity(
                    app, 0, Intent(app, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val n = NotificationCompat.Builder(app, AutoService.CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setContentTitle("WG Auto")
                    .setContentText(
                        if (wantOvpn) "أُعيد التشغيل — اضغط لاستعادة اتصال VPN"
                        else "أُعيد التشغيل — اضغط لفتح التطبيق"
                    )
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                nm.notify(AutoService.NOTIF_ID + 1, n)
            } catch (e2: Exception) {
            }
        }
    }
}
