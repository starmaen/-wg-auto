package com.star.wgauto

import android.content.Context
import android.content.Intent
import de.blinkt.openvpn.OpenVpnApi
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** حالة نفق OpenVPN الداخلي (يُبث من خدمة ics-openvpn المدمجة). */
data class OvpnState(
    val active: Boolean = false,
    val connecting: Boolean = false,
    val serverKey: String = "",
    val serverLabel: String = "",
    val statusText: String = "",
    val startedAt: Long = 0L
)

/**
 * محرك OpenVPN المدمج داخل التطبيق (بلا أي تطبيق خارجي).
 * التشغيل عبر OpenVpnApi بكونفيج .ovpn نصاً، والفصل بأمر DISCONNECT للخدمة نفسها.
 * التعايش مع WireGuard: نفق واحد فقط — المتصل يوقف الآخر قبل التشغيل (يتم من MainActivity).
 */
class OvpnEngine(ctx: Context, private val store: Store) {
    private val appCtx = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val state = MutableStateFlow(OvpnState())
    /** آخر 30 سطراً من سجل OpenVPN — للتشخيص داخل التطبيق. */
    val logLines = MutableStateFlow<List<String>>(emptyList())

    private val listener = object : VpnStatus.StateListener {
        // ملاحظة: مكتبة ics-openvpn تمرر intent=null عند أول تسجيل (mLastIntent)،
        // لذلك يجب أن تكون المعاملة nullable وإلا سقط التطبيق فور فتحه.
        override fun updateState(
            state: String, logmessage: String, localizedResId: Int,
            level: ConnectionStatus, intent: Intent?
        ) {
            scope.launch { applyLevel(level, logmessage.ifEmpty { state }) }
        }

        override fun setConnectedVPN(uuid: String) {}
    }

    init {
        // حماية إقلاع: أي عطل في مكتبة الـ VPN يجب ألا يمنع فتح التطبيق أبداً
        try {
            VpnStatus.addStateListener(listener)
            VpnStatus.addLogListener { item ->
                scope.launch {
                    val line = try {
                        item.getString(appCtx)
                    } catch (e: Exception) {
                        item.toString()
                    }
                    if (line.isNotBlank()) logLines.value = (logLines.value + line).takeLast(30)
                }
            }
        } catch (e: Exception) {
            state.value = OvpnState(statusText = "تعذّر تهيئة OpenVPN")
        }
    }

    private fun applyLevel(level: ConnectionStatus, msg: String) {
        val cur = state.value
        state.value = when (level) {
            ConnectionStatus.LEVEL_CONNECTED ->
                cur.copy(active = true, connecting = false, statusText = "متصل")
            ConnectionStatus.LEVEL_NOTCONNECTED ->
                OvpnState(statusText = if (cur.serverKey.isNotEmpty()) "انقطع الاتصال" else "")
            ConnectionStatus.LEVEL_AUTH_FAILED ->
                OvpnState(statusText = "فشل الاتصال — جرّب سيرفراً آخر")
            ConnectionStatus.LEVEL_NONETWORK ->
                cur.copy(statusText = "لا توجد شبكة")
            else ->
                cur.copy(
                    connecting = !cur.active, active = cur.active,
                    statusText = msg.ifEmpty { "جارٍ الاتصال…" }.take(90)
                )
        }
        if (!state.value.active && !state.value.connecting && state.value.serverKey.isEmpty()) {
            store.prefPut("ovpnActive", null)
        }
    }

    /** يشغّل سيرفراً بكونفيجه المفلتر. يعيد رسالة عرض. */
    fun start(server: OvpnServer, configText: String): String {
        return try {
            OpenVpnApi.startVpn(
                appCtx, configText, "WG Auto • ${server.host}",
                null, null, emptyList()
            )
            val label = "${Country.flag(server.country)} ${server.host}"
            state.value = OvpnState(
                connecting = true, serverKey = server.key, serverLabel = label,
                statusText = "جارٍ الاتصال…", startedAt = System.currentTimeMillis()
            )
            store.prefPut("ovpnActive", server.key)
            "جارٍ الاتصال عبر ${server.host} داخل التطبيق…"
        } catch (e: Exception) {
            "تعذّر تشغيل OpenVPN: ${(e.message ?: "خطأ غير معروف").take(90)}"
        }
    }

    /** يفصل النفق الداخلي مباشرة (بلا إعدادات نظام وبلا تطبيق خارجي). */
    fun stop(): String {
        val hadKey = store.prefGet("ovpnActive")?.isNotEmpty() == true
        store.prefPut("ovpnActive", null)
        val wasUp = isUp()
        state.value = OvpnState(statusText = if (wasUp || hadKey) "تم الفصل" else "")
        // إن لم يكن يعمل أصلاً: لا نوقظ الخدمة عبثاً.
        if (!wasUp && !hadKey) return "متوقف أصلاً"
        return try {
            // startService وليس startForegroundService: أمر الفصل لا يستدعي
            // startForeground لاحقاً، وطلب Foreground دون لحاقه يسقط التطبيق
            // كله بخطأ ForegroundServiceDidNotStartInTime — وهذا ما كان
            // يُخرج الواجهة عند كل تشغيل/فحص لـ WireGuard.
            appCtx.startService(
                Intent(appCtx, OpenVPNService::class.java)
                    .setAction(OpenVPNService.DISCONNECT_VPN)
            )
            "تم فصل OpenVPN"
        } catch (e: Exception) {
            "تم الفصل"
        }
    }

    fun isUp(): Boolean = state.value.active || state.value.connecting

    /**
     * إحياء تلقائي: إن كان هناك سيرفر مطلوب (لم يفصله المستخدم) ولا يوجد نفق،
     * يعيد تشغيله. يُستدعى من حارس AutoService. يعيد رسالة أو null إن لا شيء لفعله.
     */
    fun reconnectLast(): String? {
        val key = store.prefGet("ovpnActive") ?: return null
        if (key.isEmpty() || isUp()) return null
        val server = store.ovpn.value.firstOrNull { it.key == key } ?: return null
        val b64 = store.ovpnConfigB64(key) ?: return null
        val cfg = VpnGate.decodeConfig(b64) ?: return null
        val prefer = store.settings.value.ovpnProto
        return try {
            OpenVpnApi.startVpn(
                appCtx, VpnGate.filterConfig(cfg, prefer), "WG Auto • ${server.host}",
                null, null, emptyList()
            )
            val label = "${Country.flag(server.country)} ${server.host}"
            state.value = OvpnState(
                connecting = true, serverKey = server.key, serverLabel = label,
                statusText = "إعادة وصل تلقائية…", startedAt = System.currentTimeMillis()
            )
            "أُعيد وصل ${server.host} تلقائياً"
        } catch (e: Exception) {
            null
        }
    }
}
