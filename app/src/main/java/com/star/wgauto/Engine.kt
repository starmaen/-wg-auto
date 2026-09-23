package com.star.wgauto

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * محرك الاختيار التلقائي.
 *
 * مبادئ التصميم:
 * 1) لا نُعلن "متصل" إلا بعد دليل: واجهة VPN موجودة فعلاً، ومرور البيانات مربوط بها،
 *    والـ IP الخارجي تغيّر عن الـ IP المباشر.
 * 2) الفحص في الخلفية بأنفاق تجريبية خاصة بهذا التطبيق (IncludedApplications) فلا ينقطع اتصال بقية التطبيقات.
 * 3) القياس بمعايير الجودة المعتمدة: الوسيط والتذبذب والفقد، لا زمن عينة واحدة.
 * 4) لا يُطبَّق أي تغيير على DNS النظام دون قياس مقارن، ويُتراجع عنه إن تدهور الحل.
 */
class Engine(ctx: Context, private val store: Store) {

    private class Result(
        val cfg: WgConfig, val mtu: Int, val pathMtu: Int, val dns: DnsServer?,
        val dnsRows: List<DnsRow>, val stat: Stat, val mbps: Double?, val score: Double,
        val exit: ExitInfo?, val mtuRows: List<MtuRow>
    )

    private class LastBest(val cfgId: String, val mtu: Int, val pathMtu: Int, val dnsId: String, val time: Long)
    private class Verified(
        val vpn: NetUtil.Info, val exit: ExitInfo?, val lat: Int,
        val mtu: Int, val dnsIps: List<String>, val diag: String
    )

    private class Probe(val vpn: NetUtil.Info?, val mtu: Int, val lat: Int, val exit: ExitInfo?, val note: String)
    private class SideScan(val info: SideInfo, val measures: List<Pair<DnsServer, DnsMeasure>>, val sys: Stat?)

    private val appCtx = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val backend = GoBackend(appCtx)
    private val cm = appCtx.getSystemService(ConnectivityManager::class.java)
    private val tunnel = object : Tunnel {
        override fun getName(): String = "wgauto"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    val status = MutableStateFlow(Status())
    val logs = MutableStateFlow<List<String>>(emptyList())
    val dnsResults = MutableStateFlow<Map<String, DnsMeasure>>(emptyMap())
    val netInfo = MutableStateFlow(NetInfo())
    val configResults = MutableStateFlow<Map<String, String>>(emptyMap())
    val warpStatus = MutableStateFlow("")
    val report = MutableStateFlow(Report())

    private var runJob: Job? = null
    private var netJob: Job? = null
    private var scanJob: Job? = null
    private var healthFails = 0
    private var lastNetLabel = ""

    /** حالة قاطع الطوارئ الفعلية (مستقلة عن الـ Status لتفادي تسرّبها عند إعادة ضبط الحالة). */
    @Volatile
    private var killSwitchApplied = false

    /** وقت آخر تغيير أجراه التطبيق نفسه على النفق، لتجاهل أحداث الشبكة الناتجة عنه. */
    @Volatile
    private var lastOwnChange = 0L

    /** لا تبدأ إعادة اختيار تلقائية قبل هذا الوقت (يمنع الحلقات). */
    @Volatile
    private var cooldownUntil = 0L

    private fun now() = System.currentTimeMillis()

    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logs.update { (it + "$t  $msg").takeLast(300) }
    }

    private fun setResult(id: String, text: String) {
        configResults.update { it + (id to text) }
    }

    private fun setProgress(msg: String) {
        report.update { it.copy(progress = msg) }
        status.update { it.copy(message = msg) }
    }

    private fun updateRow(id: String, f: (ConfigRow) -> ConfigRow) {
        report.update { r -> r.copy(rows = r.rows.map { if (it.id == id) f(it) else it }) }
    }

    private fun tunnelIsUp(): Boolean = try {
        backend.getState(tunnel) == Tunnel.State.UP
    } catch (e: Exception) {
        false
    }

    private fun vpnInfo(): NetUtil.Info? = NetUtil.all(cm).lastOrNull { it.isVpn }

    private fun networkLabel(): String = NetUtil.underlying(NetUtil.all(cm))?.label ?: "غير متصل"

    // ---------- إدارة الكونفيجات ----------
    /** يعيد null عند النجاح، أو رسالة الخطأ. */
    fun addConfig(name: String, text: String): String? {
        val clean = ConfigText.sanitize(text)
        try {
            Config.parse(ByteArrayInputStream(clean.toByteArray()))
        } catch (e: Exception) {
            return "كونفيج غير صالح: ${e.message ?: e.cause?.message ?: ""}"
        }
        store.updateConfigs { list ->
            val base = name.ifBlank { "config" }
            var n = base
            var i = 2
            while (list.any { it.name == n }) {
                n = "$base ($i)"
                i++
            }
            list + WgConfig(name = n, text = clean)
        }
        return null
    }

    /** يسجّل حساب Cloudflare WARP مجانياً محلياً ويضيف الكونفيج الناتج تلقائياً. */
    fun generateWarp() {
        warpStatus.value = "جارٍ التسجيل في WARP…"
        scope.launch {
            try {
                val r = WarpGenerator.register()
                val base = "WARP"
                var n = base
                var i = 2
                while (store.configs.value.any { it.name == n }) {
                    n = "$base-$i"
                    i++
                }
                val err = addConfig(n, r.configText)
                warpStatus.value = if (err == null) "✔ أُضيف $n" else "✗ $err"
            } catch (e: Exception) {
                warpStatus.value = "✗ فشل تسجيل WARP: ${e.message ?: e.javaClass.simpleName}"
                log("WARP: ${e.message}")
            }
        }
    }

    // ---------- تشغيل / إيقاف ----------
    fun start() {
        scanJob?.cancel()
        healthFails = 0
        lastNetLabel = networkLabel()
        status.value = Status(running = true, busy = true, message = "جارٍ الفحص…")
        if (store.settings.value.killSwitch) {
            scope.launch {
                if (RootTools.killSwitchEnable()) {
                    killSwitchApplied = true
                    status.update { it.copy(killSwitchOn = true) }
                } else {
                    log("✗ تعذّر تفعيل قاطع الطوارئ (يتطلب روت)")
                }
            }
        }
        reselect("تشغيل", allowFast = true)
    }

    fun stop() {
        scope.launch {
            netJob?.cancelAndJoin()
            runJob?.cancelAndJoin()
            mutex.withLock { downSafe() }
            if (killSwitchApplied) {
                RootTools.killSwitchDisable()
                killSwitchApplied = false
            }
            status.value = Status(running = false, message = "متوقف")
            report.update { it.copy(progress = "", chosenConfigId = "", chosenDnsId = "") }
            log("تم الإيقاف")
            if (store.settings.value.backgroundScan) scanNetwork("بعد الإيقاف")
        }
    }

    /**
     * إغلاق شامل نهائي: إيقاف النفق، إلغاء قاطع الطوارئ، إيقاف الفحص الدوري في الخلفية،
     * واستعادة DNS الخاص للنظام إلى ما كان عليه قبل أي تدخّل من التطبيق.
     */
    fun shutdownAll(onDone: () -> Unit) {
        scope.launch {
            netJob?.cancelAndJoin()
            runJob?.cancelAndJoin()
            scanJob?.cancelAndJoin()
            mutex.withLock {
                downSafe()
                if (killSwitchApplied || RootTools.killSwitchActive()) {
                    RootTools.killSwitchDisable()
                    killSwitchApplied = false
                }
                if (store.prefGet("prevDnsSaved") != null) restoreNow()
                store.updateSettings { it.copy(backgroundScan = false) }
                status.value = Status(running = false, message = "أُغلق التطبيق بالكامل")
                report.value = Report()
                netInfo.value = NetInfo()
                log("إغلاق شامل: أُوقف كل شيء واستُعيدت إعدادات النظام")
            }
            onDone()
        }
    }

    /** إعادة اختيار كاملة (يدوية أو تلقائية). تلغي أي فحص جارٍ. */
    fun reselect(reason: String, allowFast: Boolean = false) {
        if (!status.value.running) return
        runJob?.cancel()
        runJob = scope.launch {
            mutex.withLock {
                try {
                    selectAndConnect(reason, allowFast)
                } finally {
                    status.update { it.copy(busy = false) }
                    cooldownUntil = now() + if (status.value.connected) 20_000 else 60_000
                }
            }
        }
    }

    /** إعادة اختيار تلقائية: لا تبدأ إن كان هناك فحص جارٍ أو ضمن فترة التهدئة. */
    fun autoReselect(reason: String) {
        if (status.value.busy || now() < cooldownUntil) return
        reselect(reason)
    }

    // ---------- أحداث الشبكة ----------
    /** kind = "vpn": ظهر أو اختفى VPN (من أي تطبيق) — kind = "net": تبدّلت الشبكة الأصلية أو IP. */
    fun onNetworkChanged(kind: String) {
        if (status.value.busy) return
        if (now() - lastOwnChange < 6000) return   // حدث ناتج عن تشغيل/إيقاف نفق التطبيق نفسه
        val s = store.settings.value

        if (kind == "vpn") {
            netJob?.cancel()
            netJob = scope.launch {
                delay(1500)
                if (status.value.busy) return@launch
                if (status.value.running) {
                    // نتأكد مرتين أن نفقنا سقط فعلاً (لأن VPN آخر استلم الاتصال)
                    if (!status.value.connected || tunnelIsUp()) return@launch
                    delay(2500)
                    if (status.value.busy || tunnelIsUp()) return@launch
                    if (vpnInfo() != null) {
                        log("⚠ VPN آخر استبدل نفق التطبيق")
                        status.value = Status(running = false, message = "أوقفه VPN آخر")
                    } else {
                        mutex.withLock { recover("اختفى النفق") }
                        return@launch
                    }
                }
                if (s.backgroundScan) {
                    scanNetwork(if (hasExternalVpn()) "اكتُشف تشغيل VPN خارجي" else "تغيّرت حالة الـ VPN")
                }
            }
            return
        }

        val running = status.value.running
        if (running && !s.reselectOnNetwork) return
        if (!running && !s.backgroundScan) return
        netJob?.cancel()
        netJob = scope.launch {
            delay(3000)   // تجميع التغييرات المتتابعة
            if (status.value.busy || now() < cooldownUntil) return@launch
            val st = status.value
            if (!st.running) {
                scanNetwork("تغيّر الشبكة")
                return@launch
            }
            val label = networkLabel()
            when {
                !st.connected -> reselect("تغيّر الشبكة", allowFast = true)
                label != lastNetLabel && label != "غير متصل" -> {
                    lastNetLabel = label
                    log("تبدّلت الشبكة إلى $label")
                    reselect("تبدّل الشبكة إلى $label", allowFast = true)
                }
                else -> mutex.withLock {
                    // نفس نوع الشبكة (تغيّر IP فقط): نتحقق أن النفق سليم فعلاً
                    val lat = aliveNow()
                    if (lat != null) {
                        status.update { it.copy(latencyMs = lat, lastScanAt = now()) }
                        log("النفق سليم بعد تغيّر الشبكة (${lat}ms)")
                    } else {
                        recover("تغيّر الشبكة")
                    }
                }
            }
        }
    }

    private fun hasExternalVpn(): Boolean = NetUtil.all(cm).any { it.isVpn } && !tunnelIsUp()

    /** مرور حقيقي عبر واجهة الـ VPN (وليس عبر الشبكة الأصلية). null إن لم يوجد نفق أو لم يمر شيء. */
    private fun aliveNow(): Int? {
        val v = vpnInfo() ?: return null
        if (!tunnelIsUp()) return null
        return Probes.quickAlive(v.network)
    }

    /** فحص صحة دوري خفيف: يتحقق أن النفق موجود فعلاً وأن البيانات تمر عبره. */
    fun healthCheck() {
        val st = status.value
        if (!st.running || st.busy || !st.connected) return
        if (now() < cooldownUntil) return
        scope.launch {
            mutex.withLock {
                val s2 = status.value
                if (!s2.running || s2.busy || !s2.connected) return@withLock
                if (NetUtil.underlying(NetUtil.all(cm)) == null) return@withLock   // لا شبكة أصلاً
                val v = vpnInfo()
                if (v == null || !tunnelIsUp()) {
                    log("⚠ لا توجد واجهة VPN فعلية رغم الحالة «متصل»")
                    recover("النفق غير موجود")
                    return@withLock
                }
                val lat = Probes.quickAlive(v.network)
                if (lat != null) {
                    healthFails = 0
                    status.update { it.copy(latencyMs = lat, lastScanAt = now()) }
                } else {
                    healthFails++
                    log("⚠ لا تمر بيانات عبر النفق ($healthFails/3)")
                    if (healthFails >= 3) {
                        healthFails = 0
                        recover("انقطاع الاتصال")
                    }
                }
            }
        }
    }

    /** يُستدعى داخل القفل: يعيد بناء نفس النفق مع التحقق، فإن فشل يجري اختياراً كاملاً. */
    private suspend fun recover(reason: String) {
        if (!tunnelIsUp() && NetUtil.all(cm).any { it.isVpn }) {
            log("⚠ VPN آخر استلم الاتصال — أوقف التطبيق نفقه")
            if (killSwitchApplied) {
                RootTools.killSwitchDisable()
                killSwitchApplied = false
                log("قاطع الطوارئ أُوقف تلقائياً (لم يعد التطبيق يتحكم بالنفق)")
            }
            status.value = Status(running = false, message = "أوقفه VPN آخر")
            return
        }
        val st = status.value
        val cfg = store.configs.value.firstOrNull { it.id == st.activeConfigId }
        val dns = store.dns.value.firstOrNull { it.id == st.activeDnsId }
        if (cfg != null) {
            log("إعادة بناء النفق ($reason)")
            val direct = if (st.directIp.isNotEmpty()) ExitInfo(st.directIp, st.directLoc, 0) else null
            val ver = connectAndVerify(cfg, st.activeMtu, dns?.ips() ?: emptyList(), direct)
            if (ver != null) {
                status.update {
                    it.copy(
                        latencyMs = ver.lat, exitIp = ver.exit?.ip ?: it.exitIp,
                        exitLoc = ver.exit?.loc ?: it.exitLoc, vpnIface = ver.vpn.iface,
                        activeMtu = ver.mtu, diag = ver.diag
                    )
                }
                log("✔ عاد النفق")
                return
            }
        }
        try {
            selectAndConnect("استرجاع: $reason", false)
        } finally {
            status.update { it.copy(busy = false) }
            cooldownUntil = now() + if (status.value.connected) 20_000 else 60_000
        }
    }

    // ---------- فحص الشبكة (مستقل عن أي VPN) ----------
    /** يفحص جهة واحدة مربوطة بشبكة محددة. mtuIface: واجهة الفحص (روت) أو null للفحص العادي. */
    private suspend fun scanSide(
        n: NetUtil.Info, dnsList: List<DnsServer>, doMtu: Boolean,
        root: Boolean, mtuIface: String?, vpnSide: Boolean, encrypted: Boolean, allowFiltered: Boolean
    ): SideScan {
        val (res, sys) = coroutineScope {
            val sysJob = async { Probes.systemResolveStat(n.network) }
            val list = dnsList.map { d -> async { d to Probes.dnsMeasure(d, n.network, encrypted) } }.awaitAll()
            list to sysJob.await()
        }
        // الأفضل: أدنى درجة (وسيط + نصف التذبذب + 4×الفقد) بين الخوادم المسموح باختيارها
        val best = res
            .filter { allowFiltered || !it.first.filtered }
            .mapNotNull { (d, m) -> m.best()?.let { Triple(d, it.first, it.second) } }
            .filter { it.third.loss <= 50 }
            .minByOrNull { it.third.score }
        val lat = Probes.tcpStat(n.network, 4)?.median
        currentCoroutineContext().ensureActive()
        val p = if (doMtu) Probes.pathMtu(InetAddress.getByName("1.1.1.1"), root, mtuIface) else null
        val suggested = when {
            p == null -> 0
            vpnSide -> p
            else -> MtuCatalog.tunnelMtuFromPath(p, false)
        }
        return SideScan(
            SideInfo(
                label = n.label, iface = n.iface, bestDns = best?.first?.name ?: "—",
                bestDnsId = best?.first?.id ?: "", dnsMs = best?.third?.median ?: -1,
                dnsProto = best?.second ?: "", dnsLoss = best?.third?.loss ?: 100,
                sysDnsMs = sys?.median ?: -1, latencyMs = lat ?: -1,
                pathMtu = p ?: 0, suggestedMtu = suggested, ifaceMtu = n.mtu
            ),
            res, sys
        )
    }

    /**
     * يفحص الجهتين: الشبكة الأصلية مباشرة (متجاوزاً أي VPN)، وشبكة الـ VPN إن وُجد
     * (تطبيقنا أو تطبيق آخر). ثم يثبّت القيم المثلى بالروت إن فُعّل الخيار وثبت التحسّن قياساً.
     */
    fun scanNetwork(reason: String) {
        if (status.value.busy) return
        scanJob?.cancel()
        scanJob = scope.launch {
            mutex.withLock {
                val s = store.settings.value
                val nets = NetUtil.all(cm)
                val under = NetUtil.underlying(nets)
                if (under == null) {
                    netInfo.value = NetInfo()
                    return@withLock
                }
                val vpn = nets.firstOrNull { it.isVpn }
                val owner = when {
                    vpn == null -> ""
                    status.value.connected && tunnelIsUp() -> "التطبيق"
                    else -> "خارجي"
                }
                val root = s.useRoot || s.applyRoot
                val dnsList = store.dns.value.filter { it.enabled }
                log("فحص الشبكة: $reason")

                val direct = scanSide(
                    under, dnsList,
                    doMtu = vpn == null || root,
                    root = root,
                    mtuIface = if (vpn == null) null else under.iface,
                    vpnSide = false,
                    encrypted = s.applyRoot,
                    allowFiltered = s.allowFilteredDns
                )
                dnsResults.value = direct.measures.associate { it.first.id to it.second }

                val viaVpn = if (vpn != null) {
                    scanSide(vpn, dnsList, true, false, null, true, false, s.allowFilteredDns)
                } else null

                netInfo.value = NetInfo(
                    direct = direct.info,
                    vpn = viaVpn?.info,
                    vpnOwner = owner,
                    time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                )
                status.update { it.copy(lastScanAt = now()) }
                log(
                    "الأصلية (${direct.info.label}): DNS ${direct.info.bestDns} ${direct.info.dnsMs}ms" +
                        (if (direct.info.sysDnsMs >= 0) " (الحالي ${direct.info.sysDnsMs}ms)" else "") +
                        " • MTU ${direct.info.pathMtu}" +
                        (viaVpn?.let { " | VPN ($owner): MTU ${it.info.pathMtu}" } ?: "")
                )

                if (s.applyRoot) applyValues(direct, viaVpn, owner, vpn?.iface ?: "", under, s.allowFilteredDns)
            }
        }
    }

    // ---------- تثبيت القيم بالروت (بعد قياس مقارن، مع تراجع تلقائي) ----------
    private suspend fun applyValues(
        direct: SideScan, viaVpn: SideScan?, owner: String, vpnIface: String,
        under: NetUtil.Info, allowFiltered: Boolean
    ) {
        // 1) MTU لواجهة VPN خارجي: نخفضه إلى أكبر حزمة تمر فعلاً (لا نرفعه أبداً)
        if (owner == "خارجي" && vpnIface.isNotEmpty() && viaVpn != null && viaVpn.info.pathMtu in 1000..1500) {
            val cur = RootTools.getIfaceMtu(vpnIface)
            if (cur != null && viaVpn.info.pathMtu < cur) {
                val ok = RootTools.setIfaceMtu(vpnIface, viaVpn.info.pathMtu)
                log(
                    if (ok) "✔ MTU على $vpnIface: $cur → ${viaVpn.info.pathMtu}"
                    else "✗ تعذّر ضبط MTU على $vpnIface"
                )
            }
        }

        // 2) DNS النظام (Private DNS): خادم DoT مقاس فعلاً وبفقد 0% وأفضل من الحالي بوضوح
        val cand = direct.measures
            .filter { allowFiltered || !it.first.filtered }
            .mapNotNull { (d, m) -> m.dot?.takeIf { it.loss == 0 && d.dot.isNotEmpty() }?.let { d to it } }
            .minByOrNull { it.second.score }
        if (cand == null) {
            log("DNS النظام: لا يوجد خادم DoT مقاس بنجاح كامل — لم يُغيَّر شيء")
            return
        }
        val base = direct.sys
        if (base != null && cand.second.score >= base.score * 0.85) {
            log("DNS النظام: الحالي (${base.median}ms) قريب من ${cand.first.name} (${cand.second.median}ms) — لم يُغيَّر")
            return
        }
        if (store.prefGet("appliedDot") == cand.first.dot) return

        if (store.prefGet("prevDnsSaved") == null) {
            val (mode, spec) = RootTools.getPrivateDns()
            store.prefPut("prevMode", mode)
            store.prefPut("prevSpec", spec)
            store.prefPut("prevDnsSaved", "1")
        }
        if (!RootTools.setPrivateDns(cand.first.dot)) {
            log("✗ تعذّر ضبط DNS النظام")
            return
        }
        delay(1500)
        val after = Probes.systemResolveStat(under.network)
        if (after == null || after.loss > 25) {
            log("✗ فشل الحل بعد تطبيق ${cand.first.name} — تراجعتُ عن التغيير")
            restoreNow()
        } else {
            store.prefPut("appliedDot", cand.first.dot)
            log(
                "✔ DNS النظام: ${cand.first.name} (${cand.first.dot}) — DoT ${cand.second.median}ms " +
                    "مقابل الحالي ${base?.median ?: "؟"}ms، وبعد التطبيق ${after.median}ms"
            )
        }
    }

    private fun restoreNow(): Boolean {
        if (store.prefGet("prevDnsSaved") == null) return true
        val ok = RootTools.restorePrivateDns(store.prefGet("prevMode") ?: "", store.prefGet("prevSpec") ?: "")
        store.prefPut("appliedDot", null)
        store.prefPut("prevDnsSaved", null)
        return ok
    }

    /** يعيد إعداد Private DNS إلى ما كان قبل تدخل التطبيق. */
    fun restoreSystemDns() {
        scope.launch {
            if (store.prefGet("prevDnsSaved") == null) {
                log("لا يوجد إعداد DNS سابق محفوظ للاستعادة")
                return@launch
            }
            log(if (restoreNow()) "تمت استعادة إعداد DNS الأصلي للنظام" else "تعذّرت استعادة DNS")
        }
    }

    /** اختبار DNS يدوي: كل الخوادم بالبروتوكولات الثلاثة على الشبكة الأصلية. */
    fun testDnsNow() {
        scope.launch {
            val under = NetUtil.underlying(NetUtil.all(cm)) ?: return@launch
            val list = store.dns.value
            val res = coroutineScope {
                list.map { d -> async { d.id to Probes.dnsMeasure(d, under.network, true) } }.awaitAll()
            }
            dnsResults.value = res.toMap()
        }
    }

    // ---------- التحكم بالنفق: رفع وإنزال آمن مع التحقق ----------
    /** appOnly = true: نفق تجريبي خاص بهذا التطبيق فقط (لا يقطع بقية التطبيقات). */
    private fun up(c: WgConfig, mtu: Int?, dns: List<String>, appOnly: Boolean, quiet: Boolean = false): Boolean {
        lastOwnChange = now()
        return try {
            val text = ConfigText.override(c.text, mtu, dns, if (appOnly) appCtx.packageName else null)
            val cfg = Config.parse(ByteArrayInputStream(text.toByteArray()))
            backend.setState(tunnel, Tunnel.State.UP, cfg)
            lastOwnChange = now()
            true
        } catch (e: Exception) {
            lastOwnChange = now()
            if (!quiet) log("خطأ في ${c.name}: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun down() {
        lastOwnChange = now()
        try {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
        } catch (e: Exception) {
            // تجاهل
        }
        lastOwnChange = now()
    }

    /**
     * ينزل النفق وينتظر زوال واجهة الـ VPN فعلاً. هذا ضروري: رفع نفق جديد قبل اكتمال إغلاق
     * خدمة الـ VPN السابقة يجعل النظام يهدم النفق الجديد بعد لحظات (نفق «وهمي»).
     */
    private suspend fun downSafe() {
        val wasUp = tunnelIsUp()
        down()
        if (wasUp) {
            val t0 = now()
            while (now() - t0 < 4000 && NetUtil.all(cm).any { it.isVpn }) delay(150)
            delay(250)
        }
        lastOwnChange = now()
    }

    private suspend fun waitVpnUp(maxMs: Long = 3500): NetUtil.Info? {
        val t0 = now()
        while (now() - t0 < maxMs) {
            val v = vpnInfo()
            if (v != null && tunnelIsUp()) return v
            delay(150)
        }
        return null
    }

    /** يرفع نفقاً ويتأكد أن واجهة VPN أُنشئت فعلاً (بمحاولتين). */
    private suspend fun bringUp(
        c: WgConfig, mtu: Int?, dns: List<String>, appOnly: Boolean, quiet: Boolean = false
    ): NetUtil.Info? {
        downSafe()
        for (attempt in 1..2) {
            if (!up(c, mtu, dns, appOnly, quiet)) return null
            val v = waitVpnUp()
            if (v != null) {
                lastOwnChange = now()
                return v
            }
            if (!quiet) log("⚠ لم تُنشأ واجهة VPN (محاولة $attempt)")
            downSafe()
        }
        return null
    }

    private fun fallbackDns(): List<String> =
        store.dns.value.firstOrNull { it.enabled && !it.filtered }?.ips()?.takeIf { it.isNotEmpty() }
            ?: listOf("1.1.1.1", "1.0.0.1")

    /**
     * يرفع النفق الكامل ويتحقق من كل الطبقات: واجهة VPN، اتصال TCP، مصافحة HTTPS (تثبت مرور الحزم الكبيرة)،
     * تغيّر الـ IP الخارجي، وحل DNS. عند الفشل يجرّب سلّم MTU أدنى ثم الكونفيج بلا أي تعديل (كتطبيق WireGuard الرسمي).
     */
    private suspend fun connectAndVerify(cfg: WgConfig, mtu: Int?, dns: List<String>, direct: ExitInfo?): Verified? {
        val rungs: List<Int?> = listOfNotNull(mtu, 1280, 1120).distinct() + listOf<Int?>(null)
        for (m in rungs) {
            val label = m?.toString() ?: "الافتراضي"
            var ips: List<String> = if (m == null) emptyList() else dns
            for (round in 0..1) {
                val v = bringUp(cfg, m, ips, appOnly = false) ?: break
                delay(400)
                val lat = Probes.quickAlive(v.network)
                if (lat == null) {
                    log("✗ MTU $label: لا يتصل TCP عبر النفق")
                    break
                }
                val exit = Probes.exitInfo(v.network, fast = true)
                if (exit == null) {
                    log("✗ MTU $label: TCP يتصل لكن HTTPS لا يمر (حزم كبيرة محجوبة؟) — أجرّب قيمة أدنى")
                    break
                }
                if (direct != null && exit.ip == direct.ip) {
                    log("✗ الـ IP لم يتغيّر (${exit.ip}) — النفق لا يحمل الحركة")
                    return null
                }
                val dnsStat = Probes.systemResolveStat(v.network, 2)
                if (dnsStat == null && round == 0 && m != null) {
                    val alt = fallbackDns()
                    if (alt != ips) {
                        log("⚠ DNS النفق لا يستجيب — أجرّب ${alt.joinToString()}")
                        ips = alt
                        continue
                    }
                }
                // متوسط 3 محاولات لنفس المرجع (1.1.1.1) لعرض عادل يُقارَن بزمن TCP بلا تحيّز أول اتصال
                val httpsAvg = Probes.httpsLatencyAvg(v.network) ?: exit.ms
                val diag = "TCP ✔ ${lat}ms • HTTPS ✔ ${httpsAvg}ms • DNS " +
                    (dnsStat?.let { "✔ ${it.median}ms" } ?: "✗ (لا يحل الأسماء)") + " • MTU $label"
                return Verified(v, exit.copy(ms = httpsAvg), lat, m ?: 1280, ips, diag)
            }
        }
        return null
    }

    /** فحص نفق تجريبي (خاص بالتطبيق): واجهة + TCP + HTTPS عبر النفق، بسلّم MTU. vpn=null يعني فشل مع note. */
    private suspend fun probeTunnel(c: WgConfig, mtus: List<Int>, dns: List<String>, direct: ExitInfo?): Probe {
        var note = "لم تُنشأ واجهة النفق"
        for (m in mtus) {
            val v = bringUp(c, m, dns, appOnly = true, quiet = true)
                ?: return Probe(null, m, -1, null, "لم تُنشأ واجهة النفق")
            val lat = Probes.quickAlive(v.network)
                ?: return Probe(null, m, -1, null, "الخادم لا يستجيب (لا تمر بيانات)")
            val exit = Probes.exitInfo(v.network, fast = true)
            if (exit == null) {
                note = "TCP يتصل لكن HTTPS لا يمر (MTU $m)"
                continue
            }
            if (direct != null && exit.ip == direct.ip) {
                return Probe(null, m, lat, exit, "الـ IP لم يتغيّر — لا يحمل الحركة")
            }
            return Probe(v, m, lat, exit, "")
        }
        return Probe(null, mtus.lastOrNull() ?: 0, -1, null, note)
    }

    // ---------- فحص يدوي لكونفيج (بنفق تجريبي، بلا سجل) ----------
    fun testAll() = testConfigs(store.configs.value.filter { it.enabled }.map { it.id })

    fun testConfigs(ids: List<String>) {
        if (ids.isEmpty()) return
        if (status.value.running) {
            ids.forEach { setResult(it, "سيُفحص عند إعادة الفحص (الـ VPN يعمل)") }
            return
        }
        ids.forEach { setResult(it, "بانتظار الفحص…") }
        scope.launch {
            mutex.withLock {
                if (status.value.running) return@withLock
                val dns = store.dns.value.filter { it.enabled && !it.filtered }.firstOrNull()
                val under = NetUtil.underlying(NetUtil.all(cm))
                val direct = under?.let { Probes.exitInfo(it.network, fast = true) }
                for (id in ids) {
                    val c = store.configs.value.firstOrNull { it.id == id } ?: continue
                    setResult(id, "جارٍ الفحص…")
                    val p = probeTunnel(c, listOf(1280, 1120), dns?.ips() ?: emptyList(), direct)
                    val v = p.vpn
                    if (v == null) {
                        setResult(id, "✗ ${p.note}")
                        continue
                    }
                    val st = Probes.tcpStat(v.network, 6)
                    val mbps = Probes.throughputMbps(network = v.network)
                    setResult(
                        id,
                        "✓ ${st?.median ?: p.lat}ms ±${st?.jitter ?: 0} • MTU ${p.mtu} • " +
                            "${mbps?.let { "%.1f".format(it) } ?: "?"}Mbps • ${p.exit?.ip ?: ""} ${p.exit?.loc ?: ""}"
                    )
                }
                downSafe()
            }
        }
    }

    // ---------- الاختيار عند التشغيل ----------
    private fun readLastBest(label: String): LastBest? {
        val p = store.prefGet("lastBest_$label")?.split("|") ?: return null
        if (p.size < 5) return null
        return LastBest(p[0], p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: 0, p[3], p[4].toLongOrNull() ?: 0L)
    }

    private fun saveLastBest(label: String, cfgId: String, mtu: Int, pathMtu: Int, dnsId: String) {
        store.prefPut("lastBest_$label", "$cfgId|$mtu|$pathMtu|$dnsId|${now()}")
    }

    private fun markConnected(
        cfg: WgConfig, dns: DnsServer?, dnsRows: List<DnsRow>,
        ver: Verified, mbps: Double?, label: String, direct: ExitInfo?, mtuRows: List<MtuRow>
    ) {
        val verified = ver.exit != null && direct != null && ver.exit.ip != direct.ip
        status.update {
            it.copy(
                connected = true, activeConfig = cfg.name, activeConfigId = cfg.id,
                activeDns = dns?.name ?: "حسب الكونفيج", activeDnsId = dns?.id,
                activeMtu = ver.mtu, latencyMs = ver.lat, mbps = mbps,
                message = if (verified) "متصل ✔ (تم التحقق من الـ IP)" else "متصل (لم يُتحقق من الـ IP)",
                verified = verified, exitIp = ver.exit?.ip ?: "", exitLoc = ver.exit?.loc ?: "",
                directIp = direct?.ip ?: "", directLoc = direct?.loc ?: "", vpnIface = ver.vpn.iface,
                diag = ver.diag, lastScanAt = now()
            )
        }
        report.update {
            it.copy(
                chosenConfigId = cfg.id, chosenDnsId = dns?.id ?: "", chosenDnsProto = "UDP",
                chosenMtu = ver.mtu, chosenPathMtu = 0,
                dnsRows = dnsRows.ifEmpty { it.dnsRows },
                mtuRows = mtuRows.ifEmpty { it.mtuRows }, progress = "تم الاتصال"
            )
        }
        saveLastBest(label, cfg.id, ver.mtu, 0, dns?.id ?: "")
        healthFails = 0
    }

    private suspend fun selectAndConnect(reason: String, allowFast: Boolean) {
        val s = store.settings.value
        val allCfg = store.configs.value.filter { it.enabled }
        if (allCfg.isEmpty()) {
            log("لا توجد كونفيجات مفعّلة")
            status.update { it.copy(connected = false, busy = false, message = "أضف كونفيجاً وفعّله") }
            return
        }
        // خوادم DNS المسموح باختيارها تلقائياً (المُرشِّحة/الحاجبة تحتاج إذناً صريحاً)
        val dnsAll = store.dns.value.filter { it.enabled && (!it.filtered || s.allowFilteredDns) }
        val defDns: DnsServer? =
            if (s.autoDns) null else store.dns.value.firstOrNull { it.id == s.selDnsId }
        val testDns: DnsServer? = defDns ?: dnsAll.firstOrNull()
        val label = networkLabel()
        val under = NetUtil.underlying(NetUtil.all(cm))

        status.update { it.copy(busy = true, connected = false, message = "جارٍ الفحص ($reason)…") }

        // الـ IP المباشر (مرجع التحقق): عبر الشبكة الأصلية متجاوزاً أي نفق
        val direct = under?.let { Probes.exitInfo(it.network) }
        if (direct != null) status.update { it.copy(directIp = direct.ip, directLoc = direct.loc) }

        // ---- مسار سريع: آخر كونفيج نجح على هذا النوع من الشبكات
        if (allowFast) {
            val lb = readLastBest(label)
            val cfg = lb?.let { l -> allCfg.firstOrNull { it.id == l.cfgId } }
            val dns = lb?.let { l -> store.dns.value.firstOrNull { it.id == l.dnsId } }
            if (lb != null && cfg != null && now() - lb.time < 6 * 3600 * 1000L &&
                (s.autoConfig || cfg.id == s.selConfigId)
            ) {
                setProgress("اتصال سريع: ${cfg.name}")
                log("اتصال سريع بآخر كونفيج ناجح على $label: ${cfg.name}")
                val ver = connectAndVerify(cfg, lb.mtu, dns?.ips() ?: emptyList(), direct)
                if (ver != null) {
                    markConnected(cfg, dns, emptyList(), ver, null, label, direct, emptyList())
                    log("✔ متصل: ${cfg.name} • ${ver.diag} • IP ${ver.exit?.ip ?: "؟"} ${ver.exit?.loc ?: ""}")
                    return
                }
                log("✗ فشل الاتصال السريع، سيجري فحص كامل")
            }
        }

        val candidates =
            if (s.autoConfig) allCfg
            else listOf(allCfg.firstOrNull { it.id == s.selConfigId } ?: allCfg.first())

        report.value = Report(
            rows = candidates.map { ConfigRow(it.id, it.name) },
            progress = "الفحص في الخلفية — اتصال بقية التطبيقات لا يتأثر"
        )
        log("بدء الفحص: $reason (${candidates.size} كونفيج)")
        downSafe()

        // MTU ابتدائي آمن: 1280 (الافتراضي في تطبيق WireGuard للأندرويد وحدّ IPv6 الأدنى)، ثم 1120 كاحتياط
        val startMtus: List<Int> = if (s.autoMtu) listOf(1280, 1120) else listOf(s.selMtu)

        // ---- المرحلة أ: فحص سريع بدليل (واجهة + TCP + HTTPS + تغيّر IP) عبر النفق التجريبي
        val alive = ArrayList<Triple<WgConfig, Int, Int>>()   // كونفيج، تأخر، MTU الناجح
        for ((i, c) in candidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            setProgress("فحص سريع ${i + 1}/${candidates.size}: ${c.name}")
            updateRow(c.id) { it.copy(state = "testing") }
            val p = probeTunnel(c, startMtus, testDns?.ips() ?: emptyList(), direct)
            if (p.vpn == null) {
                updateRow(c.id) { it.copy(state = "fail", note = p.note) }
                log("✗ ${c.name}: ${p.note}")
                continue
            }
            updateRow(c.id) {
                it.copy(
                    state = "ok", latencyMs = p.lat, mtu = p.mtu,
                    exitIp = p.exit?.ip ?: "", exitLoc = p.exit?.loc ?: ""
                )
            }
            log("✓ ${c.name}: ${p.lat}ms • MTU ${p.mtu} • ${p.exit?.ip ?: "؟"} ${p.exit?.loc ?: ""}")
            alive += Triple(c, p.lat, p.mtu)
        }
        downSafe()

        if (alive.isEmpty()) {
            setProgress("لم ينجح أي كونفيج")
            status.update { it.copy(connected = false, message = "لا يوجد كونفيج يحمل الحركة فعلاً — إعادة المحاولة بعد دقيقة") }
            log("لم ينجح أي كونفيج من ${candidates.size} (راجع سبب كل كونفيج في نتيجة الفحص)")
            return
        }

        // ---- المرحلة ب: فحص دقيق لأسرع 3 (جودة: وسيط/تذبذب/فقد + DNS + سرعة)
        val top = alive.sortedBy { it.second }.take(if (s.autoConfig) 3 else 1)
        val results = ArrayList<Result>()
        for ((i, t) in top.withIndex()) {
            currentCoroutineContext().ensureActive()
            val c = t.first
            val m0 = t.third
            setProgress("فحص دقيق ${i + 1}/${top.size}: ${c.name}")
            val v = bringUp(c, m0, testDns?.ips() ?: emptyList(), appOnly = true) ?: continue
            val st = Probes.tcpStat(v.network, 6)
            if (st == null) {
                updateRow(c.id) { it.copy(state = "fail", note = "انقطع أثناء الفحص الدقيق") }
                continue
            }
            if (st.loss > 40) {
                updateRow(c.id) { it.copy(state = "fail", note = "فقد حزم مرتفع (${st.loss}%) — مستبعَد") }
                log("✗ ${c.name}: فقد حزم ${st.loss}% — مستبعَد تلقائياً")
                continue
            }
            val exit = Probes.exitInfo(v.network, fast = true)
            val (dns, dnsRows) = if (s.autoDns) pickDns(dnsAll, v.network) else (defDns to emptyList())
            val mbps = Probes.throughputMbps(network = v.network)
            val score = (mbps ?: 0.5) / (1 + st.score / 100.0)
            results += Result(c, m0, 0, dns, dnsRows, st, mbps, score, exit, emptyList())
            updateRow(c.id) {
                it.copy(
                    state = "ok", latencyMs = st.median, jitter = st.jitter, loss = st.loss, mbps = mbps,
                    mtu = m0, dnsName = dns?.name ?: "", score = score,
                    exitIp = exit?.ip ?: it.exitIp, exitLoc = exit?.loc ?: it.exitLoc
                )
            }
            log(
                "★ ${c.name}: ${st.median}ms ±${st.jitter} • فقد ${st.loss}% • " +
                    "${mbps?.let { "%.1f".format(it) } ?: "?"}Mbps • MTU $m0"
            )
        }
        downSafe()

        if (results.isEmpty()) {
            setProgress("فشل الفحص الدقيق لكل المرشحين")
            status.update { it.copy(connected = false, message = "فشل الفحص الدقيق — إعادة المحاولة بعد دقيقة") }
            log("لم ينجح أي كونفيج في الفحص الدقيق")
            return
        }
        var ordered = results.sortedByDescending { it.score }

        // ---- المرحلة ج: رفع MTU تجريبياً فوق القيمة الآمنة، بشرط أن يمرّ HTTPS وتبقى السرعة مقاربة
        if (s.autoMtu) {
            val w = ordered.first()
            setProgress("ضبط MTU للكونفيج ${w.cfg.name}")
            val (bestMtu, rows) = tuneMtu(w, testDns)
            ordered = listOf(
                Result(w.cfg, bestMtu, 0, w.dns, w.dnsRows, w.stat, w.mbps, w.score, w.exit, rows)
            ) + ordered.drop(1)
            updateRow(w.cfg.id) { it.copy(mtu = bestMtu) }
        }

        // ---- التثبيت: نفق واحد لكل التطبيقات، مع التحقق من كل الطبقات
        for (r in ordered) {
            currentCoroutineContext().ensureActive()
            setProgress("تثبيت الاتصال: ${r.cfg.name}")
            val ver = connectAndVerify(r.cfg, r.mtu, r.dns?.ips() ?: emptyList(), direct)
            if (ver != null) {
                markConnected(r.cfg, r.dns, r.dnsRows, ver, r.mbps, label, direct, r.mtuRows)
                log("★ الأفضل: ${r.cfg.name} • ${ver.diag} • DNS ${r.dns?.name ?: "حسب الكونفيج"} • IP ${ver.exit?.ip ?: "؟"}")
                return
            }
            log("✗ فشل التحقق بعد التثبيت: ${r.cfg.name}")
        }
        downSafe()
        setProgress("تعذّر تثبيت الاتصال")
        status.update { it.copy(connected = false, message = "تعذّر تثبيت الاتصال — إعادة المحاولة بعد دقيقة") }
    }

    /**
     * MTU تجريبي: ننطلق من القيمة الآمنة الناجحة ونجرّب قيماً أعلى (من الأعلى إلى الأدنى).
     * تُقبل القيمة إذا مرّت مصافحة HTTPS عبر النفق (دليل مرور الحزم كاملة الحجم) وبقيت السرعة ≥ 92% من السرعة الآمنة.
     * لا نعتمد على ICMP لأن كثيراً من الشبكات تحجبه أو تحدّه فيعطي أرقاماً أقل من الحقيقة.
     */
    private suspend fun tuneMtu(r: Result, dns: DnsServer?): Pair<Int, List<MtuRow>> {
        val ladder = listOf(1420, 1380, 1340, 1280, 1200, 1120, 1040, 1000)
        val cands = ladder.filter { it > r.mtu }.sorted().take(4).sortedDescending()
        val rows = ArrayList<MtuRow>()
        rows += MtuRow(r.mtu, r.mbps)
        var chosen = r.mtu
        for (m in cands) {
            currentCoroutineContext().ensureActive()
            val v = bringUp(r.cfg, m, dns?.ips() ?: emptyList(), appOnly = true, quiet = true)
            val exit = v?.let { Probes.exitInfo(it.network, fast = true) }
            if (exit == null) {
                rows += MtuRow(m, null)
                log("MTU $m: لا يمر HTTPS")
                continue
            }
            val mbps = Probes.throughputMbps(4000, v?.network)
            rows += MtuRow(m, mbps)
            log("MTU $m: HTTPS ✔ • ${mbps?.let { "%.1f".format(it) } ?: "؟"} Mbps")
            val base = r.mbps
            if (base == null || mbps == null || mbps >= base * 0.92) {
                chosen = m
                break
            }
        }
        downSafe()
        return chosen to rows.sortedByDescending { it.mtu }
    }

    /** يقيس DNS عبر النفق نفسه (UDP، وهو ما يستخدمه WireGuard) ويختار الأفضل بالوسيط والتذبذب والفقد. */
    private suspend fun pickDns(list: List<DnsServer>, network: Network?): Pair<DnsServer?, List<DnsRow>> =
        coroutineScope {
            val res = list.map { d -> async(Dispatchers.IO) { d to Probes.dnsMeasure(d, network, false) } }.awaitAll()
            dnsResults.value = dnsResults.value + res.associate { it.first.id to it.second }
            val rows = res
                .sortedBy { it.second.udp?.score ?: Int.MAX_VALUE }
                .map { (d, m) ->
                    DnsRow(d.id, d.name, m.udp?.median ?: -1, "UDP", m.udp?.jitter ?: 0, m.udp?.loss ?: 100)
                }
            val best = res.filter { (it.second.udp?.loss ?: 100) <= 50 }
                .minByOrNull { it.second.udp?.score ?: Int.MAX_VALUE }?.first
            best to rows
        }
}
