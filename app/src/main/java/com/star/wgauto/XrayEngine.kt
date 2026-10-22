package com.star.wgauto

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

data class XrayState(
    val connecting: Boolean = false,
    val running: Boolean = false,
    val id: String = "",
    val title: String = "",
    val error: String = ""
)

/**
 * واجهة محرّك Xray من جهة الواجهة.
 * الخدمة تعمل في عملية منفصلة (:xray) لتفادي تحميل نسختَي Go runtime
 * (WireGuard و libv2ray) في عملية واحدة، فتُرسل حالتها ببثّ محلي.
 */
object XrayEngine {
    const val ACTION_STATE = "com.star.wgauto.XRAY_STATE"
    const val EXTRA_RUNNING = "running"
    const val EXTRA_CONNECTING = "connecting"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ERROR = "error"

    val state = MutableStateFlow(XrayState())

    /** يجهّز الإعداد ويشغّل الخدمة. يعيد رسالة خطأ أو نصاً فارغاً عند النجاح. */
    fun start(ctx: Context, s: ExtraServer): String {
        val cfg = try {
            XrayConfig.build(s)
        } catch (e: Exception) {
            val msg = e.message ?: "إعداد غير صالح"
            state.value = XrayState(error = msg)
            return msg
        }
        state.value = XrayState(connecting = true, id = s.id, title = s.title)
        return try {
            val i = Intent(ctx, XrayVpnService::class.java).apply {
                action = XrayVpnService.ACTION_START
                putExtra(XrayVpnService.EXTRA_CONFIG, cfg)
                putExtra(XrayVpnService.EXTRA_TITLE, s.title)
            }
            ContextCompat.startForegroundService(ctx, i)
            ""
        } catch (e: Exception) {
            val msg = e.message ?: "تعذّر تشغيل خدمة Xray"
            state.value = XrayState(error = msg)
            msg
        }
    }

    fun stop(ctx: Context) {
        try {
            ctx.startService(
                Intent(ctx, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP)
            )
        } catch (e: Exception) {
        }
        state.value = XrayState()
    }

    fun isUp(): Boolean = state.value.running || state.value.connecting

    /** تُستدعى من مستقبل البثّ في الواجهة. */
    fun apply(running: Boolean, connecting: Boolean, title: String, error: String) {
        state.value = XrayState(
            running = running, connecting = connecting, title = title, error = error
        )
    }
}
