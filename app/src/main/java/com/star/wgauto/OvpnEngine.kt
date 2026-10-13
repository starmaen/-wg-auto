package com.star.wgauto

import android.content.Context
import android.content.Intent
import android.os.Build
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

    private val listener = object : VpnStatus.StateListener {
        override fun updateState(
            state: String, logmessage: String, localizedResId: Int,
            level: ConnectionStatus, intent: Intent
        ) {
            scope.launch { applyLevel(level, logmessage.ifEmpty { state }) }
        }

        override fun setConnectedVPN(uuid: String) {}
    }

    init {
        VpnStatus.addStateListener(listener)
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
        return try {
            val i = Intent(appCtx, OpenVPNService::class.java)
                .setAction(OpenVPNService.DISCONNECT_VPN)
            if (Build.VERSION.SDK_INT >= 26) appCtx.startForegroundService(i)
            else appCtx.startService(i)
            state.value = OvpnState(statusText = "تم الفصل")
            store.prefPut("ovpnActive", null)
            "تم فصل OpenVPN"
        } catch (e: Exception) {
            state.value = OvpnState()
            "تم الفصل"
        }
    }

    fun isUp(): Boolean = state.value.active || state.value.connecting
}
