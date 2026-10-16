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
        // فشل سريع (نفق لم يعش دقيقتين): السيرفر لا يصلح — نخفضه ونمسح الطلب
        // حتى لا يعيد التلقائي والحارس اختياره. (السقوط بعد جلسة طويلة لا يُخفض.)
        if (level == ConnectionStatus.LEVEL_NOTCONNECTED && cur.serverKey.isNotEmpty() &&
            System.currentTimeMillis() - cur.startedAt < 120_000 &&
            !(msg.contains("AUTH_FAILED", true) || msg.contains("auth-failure", true))
        ) {
            val now = System.currentTimeMillis()
            store.updateOvpn { l ->
                l.map {
                    if (it.key == cur.serverKey) it.copy(loss = 100, via = "فشل الاتصال", testedAt = now)
                    else it
                }
            }
            state.value = OvpnState(statusText = "تعذّر هذا السيرفر — جرّب التلقائي مجدداً للذي بعده")
            store.prefPut("ovpnActive", null)
            return
        }
        // رفض الدخول من السيرفر: نخفضه فوراً (فقد 100%) فلا يعود للأول تلقائياً
        if ((msg.contains("AUTH_FAILED", true) || msg.contains("auth-failure", true)) &&
            cur.serverKey.isNotEmpty()
        ) {
            val now = System.currentTimeMillis()
            store.updateOvpn { l ->
                l.map {
                    if (it.key == cur.serverKey) it.copy(loss = 100, via = "مرفوض الدخول", testedAt = now)
                    else it
                }
            }
            // كلمة سر VPNBook أسبوعية: رفض الدخول غالباً يعني انتهاءها —
            // نجلب الجديدة في الخلفية ليعيد المستخدم الاتصال فوراً بلا توليد جديد.
            if (cur.serverKey.startsWith("vbk-")) {
                state.value = OvpnState(statusText = "انتهت كلمة سر VPNBook — جارٍ جلب الجديدة تلقائياً…")
                store.prefPut("ovpnActive", null)
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val pwd = VpnBook.fetchPassword()
                    if (!pwd.isNullOrEmpty()) store.prefPut("vpnbookPass", pwd)
                    scope.launch {
                        state.value = OvpnState(
                            statusText = if (!pwd.isNullOrEmpty()) "حُدّثت كلمة السر — اضغط اتصال مجدداً"
                            else "تعذّر تحديث كلمة السر — أعد توليد السيرفر من زر VPNBook"
                        )
                    }
                }
                return
            }
            state.value = OvpnState(statusText = "السيرفر رفض الدخول — اختر سيرفراً آخر (الأول الجديد مقترح بالأعلى)")
            store.prefPut("ovpnActive", null)
            return
        }
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

    /** يشغّل سيرفراً بكونفيجه المفلتر. الاعتماد يُمرَّر لواجهة النواة مباشرة (ملف auth-user-pass الخارجي تتجاهله النواة في وضع API). */
    fun start(
        server: OvpnServer, configText: String,
        authUser: String? = null, authPass: String? = null
    ): String {
        return try {
            OpenVpnApi.startVpn(
                appCtx, configText, "WG Auto • ${server.host}",
                authUser, authPass, emptyList()
            )
            val label = "${Country.flag(server.country)} ${server.host}"
            state.value = OvpnState(
                connecting = true, serverKey = server.key, serverLabel = label,
                statusText = "جارٍ الاتصال…", startedAt = System.currentTimeMillis()
            )
            store.prefPut("ovpnActive", server.key)
            // حارس المهلة: التعليق على «جارٍ الاتصال» أكثر من 60 ثانية يعني
            // سيرفراً ميتاً — نفصل ونوجّه للبديل بدل الانتظار الأبدي.
            scope.launch {
                kotlinx.coroutines.delay(60_000)
                val st = state.value
                if (st.serverKey == server.key && st.connecting && !st.active) {
                    stop()
                    state.value = OvpnState(
                        statusText = "انتهت المهلة (60 ثانية) — ${server.host} لا يرد، جرّب سيرفراً آخر"
                    )
                }
            }
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
        if (server.stale) {
            store.prefPut("ovpnActive", null)
            return null
        }
        val prefer = store.settings.value.ovpnProto
        val authPath = java.io.File(appCtx.cacheDir, "ovpn/auth.txt").absolutePath
        val isVbk = key.startsWith("vbk-")
        val user = if (isVbk) VpnBook.OVPN_USER else "vpn"
        val pass = if (isVbk) (store.prefGet("vpnbookPass") ?: "") else "vpn"
        if (isVbk && pass.isEmpty()) {
            store.prefPut("ovpnActive", null)
            return null
        }
        val ready = VpnGate.buildConnectConfig(store, key, prefer, authPath, user, pass) ?: return null
        return try {
            OpenVpnApi.startVpn(
                appCtx, VpnGate.stripAuthPath(ready), "WG Auto • ${server.host}",
                user, pass, emptyList()
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
