package com.star.wgauto

import android.content.Context
import android.net.ConnectivityManager
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
 * الفحص يجري في الخلفية بأنفاق تجريبية خاصة بهذا التطبيق وحده (IncludedApplications)،
 * فلا ينقطع اتصال بقية التطبيقات أثناء الفحص. بعد اختيار الأفضل يُثبَّت نفق واحد لكل التطبيقات.
 */
class Engine(ctx: Context, private val store: Store) {

    private class Result(
        val cfg: WgConfig, val mtu: Int, val pathMtu: Int, val dns: DnsServer,
        val dnsRows: List<DnsRow>, val lat: Int, val mbps: Double?, val score: Double
    )

    private class LastBest(val cfgId: String, val mtu: Int, val pathMtu: Int, val dnsId: String, val time: Long)

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
    val dnsResults = MutableStateFlow<Map<String, Int>>(emptyMap())
    val netInfo = MutableStateFlow(NetInfo())
    val configResults = MutableStateFlow<Map<String, String>>(emptyMap())
    val report = MutableStateFlow(Report())

    private var runJob: Job? = null
    private var netJob: Job? = null
    private var scanJob: Job? = null
    private var healthFails = 0
    private var lastNetLabel = ""

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

    // ---------- تشغيل / إيقاف ----------
    fun start() {
        scanJob?.cancel()
        healthFails = 0
        lastNetLabel = networkLabel()
        status.value = Status(running = true, busy = true, message = "جارٍ الفحص…")
        reselect("تشغيل", allowFast = true)
    }

    fun stop() {
        scope.launch {
            netJob?.cancelAndJoin()
            runJob?.cancelAndJoin()
            mutex.withLock { down() }
            status.value = Status(running = false, message = "متوقف")
            report.update { it.copy(progress = "", chosenConfigId = "", chosenDnsId = "") }
            log("تم الإيقاف")
            if (store.settings.value.backgroundScan) scanNetwork("بعد الإيقاف")
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
                    log("⚠ VPN آخر استبدل نفق التطبيق")
                    status.value = Status(running = false, message = "أوقفه VPN آخر")
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
                    // نفس نوع الشبكة (تغيّر IP فقط): نتحقق أن الاتصال سليم
                    val lat = Probes.quickAlive()
                    if (lat != null) {
                        status.update { it.copy(latencyMs = lat) }
                        log("الاتصال سليم بعد تغيّر الشبكة (${lat}ms)")
                    } else {
                        recover("تغيّر الشبكة")
                    }
                }
            }
        }
    }

    private fun hasExternalVpn(): Boolean = NetUtil.all(cm).any { it.isVpn } && !tunnelIsUp()

    /** فحص صحة دوري خفيف: لا يقطع النفق ولا يغيّر شيئاً إلا بعد 3 إخفاقات متتالية. */
    fun healthCheck() {
        val st = status.value
        if (!st.running || st.busy || !st.connected) return
        if (now() < cooldownUntil) return
        scope.launch {
            mutex.withLock {
                val s2 = status.value
                if (!s2.running || s2.busy || !s2.connected) return@withLock
                if (NetUtil.underlying(NetUtil.all(cm)) == null) return@withLock   // لا شبكة أصلاً
                val lat = Probes.quickAlive()
                if (lat != null) {
                    healthFails = 0
                    status.update { it.copy(latencyMs = lat) }
                } else {
                    healthFails++
                    log("⚠ الاتصال لا يمر ($healthFails/3)")
                    if (healthFails >= 3) {
                        healthFails = 0
                        recover("انقطاع الاتصال")
                    }
                }
            }
        }
    }

    /** يُستدعى داخل القفل: يعيد تشغيل نفس النفق، فإن فشل يجري اختياراً كاملاً. */
    private suspend fun recover(reason: String) {
        val st = status.value
        val cfg = store.configs.value.firstOrNull { it.id == st.activeConfigId }
        val dns = store.dns.value.firstOrNull { it.id == st.activeDnsId }
        if (cfg != null && dns != null) {
            log("إعادة تشغيل النفق ($reason)")
            if (up(cfg, st.activeMtu, dns.ips(), appOnly = false)) {
                delay(800)
                val lat = Probes.quickAlive()
                if (lat != null) {
                    status.update { it.copy(latencyMs = lat) }
                    log("✔ عاد الاتصال")
                    return
                }
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
    private class SideScan(val info: SideInfo, val dns: List<Pair<DnsServer, Int?>>)

    /** يفحص جهة واحدة مربوطة بشبكة محددة. mtuIface: واجهة الفحص (روت) أو null للفحص العادي. */
    private suspend fun scanSide(
        n: NetUtil.Info, dnsList: List<DnsServer>, doMtu: Boolean,
        root: Boolean, mtuIface: String?, vpnSide: Boolean
    ): SideScan {
        val res = coroutineScope {
            dnsList.map { d -> async { d to Probes.dnsLatency(d.primary, network = n.network) } }.awaitAll()
        }
        val best = res.filter { it.second != null }.minByOrNull { it.second!! }
        val lat = Probes.tcpLatency(network = n.network)
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
                bestDnsId = best?.first?.id ?: "",
                dnsMs = best?.second ?: -1, latencyMs = lat ?: -1,
                pathMtu = p ?: 0, suggestedMtu = suggested, ifaceMtu = n.mtu
            ),
            res
        )
    }

    /**
     * يفحص الجهتين: الشبكة الأصلية مباشرة (متجاوزاً أي VPN)، وشبكة الـ VPN إن وُجد
     * (تطبيقنا أو تطبيق آخر). ثم يثبّت القيم المثلى بالروت إن فُعّل الخيار.
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
                    vpnSide = false
                )
                dnsResults.value = direct.dns.associate { it.first.id to (it.second ?: -1) }

                val viaVpn = if (vpn != null) scanSide(vpn, dnsList, true, false, null, true) else null

                netInfo.value = NetInfo(
                    direct = direct.info,
                    vpn = viaVpn?.info,
                    vpnOwner = owner,
                    time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                )
                log(
                    "الأصلية (${direct.info.label}): DNS ${direct.info.bestDns} • MTU ${direct.info.pathMtu}" +
                        (viaVpn?.let { " | VPN ($owner): MTU ${it.info.pathMtu}" } ?: "")
                )

                if (s.applyRoot) applyValues(direct, viaVpn, owner, vpn?.iface ?: "")
            }
        }
    }

    // ---------- تثبيت القيم بالروت ----------
    private fun applyValues(direct: SideScan, viaVpn: SideScan?, owner: String, vpnIface: String) {
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
        // 2) DNS النظام (Private DNS): أسرع خادم يدعم DoT
        val cand = direct.dns
            .filter { it.second != null && it.first.dot.isNotEmpty() }
            .minByOrNull { it.second!! }?.first
        if (cand != null && store.prefGet("appliedDot") != cand.dot) {
            if (store.prefGet("prevDnsSaved") == null) {
                val (mode, spec) = RootTools.getPrivateDns()
                store.prefPut("prevMode", mode)
                store.prefPut("prevSpec", spec)
                store.prefPut("prevDnsSaved", "1")
            }
            val ok = RootTools.setPrivateDns(cand.dot)
            if (ok) store.prefPut("appliedDot", cand.dot)
            log(if (ok) "✔ DNS النظام: ${cand.name} (${cand.dot})" else "✗ تعذّر ضبط DNS النظام")
        }
    }

    /** يعيد إعداد Private DNS إلى ما كان قبل تدخل التطبيق. */
    fun restoreSystemDns() {
        scope.launch {
            if (store.prefGet("prevDnsSaved") == null) return@launch
            val ok = RootTools.restorePrivateDns(store.prefGet("prevMode") ?: "", store.prefGet("prevSpec") ?: "")
            store.prefPut("appliedDot", null)
            store.prefPut("prevDnsSaved", null)
            log(if (ok) "تمت استعادة إعداد DNS الأصلي للنظام" else "تعذّرت استعادة DNS")
        }
    }

    fun testDnsNow() {
        scope.launch {
            val list = store.dns.value
            val res = list.map { d -> async { d.id to (Probes.dnsLatency(d.primary) ?: -1) } }.awaitAll()
            dnsResults.value = res.toMap()
        }
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
                val dns = store.dns.value.filter { it.enabled }.ifEmpty { listOf(Defaults.dns.first()) }.first()
                for (id in ids) {
                    val c = store.configs.value.firstOrNull { it.id == id } ?: continue
                    setResult(id, "جارٍ الفحص…")
                    val ok = up(c, 1420, dns.ips(), appOnly = true, quiet = true)
                    val lat = if (ok) Probes.quickAlive() else null
                    val mbps = if (lat != null) Probes.throughputMbps() else null
                    setResult(
                        id,
                        if (lat == null) "✗ لا يعمل"
                        else "✓ ${lat}ms • ${mbps?.let { "%.1f".format(it) } ?: "?"}Mbps"
                    )
                }
                down()
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
        cfg: WgConfig, mtu: Int, pathMtu: Int, dns: DnsServer, dnsRows: List<DnsRow>,
        lat: Int, mbps: Double?, label: String
    ) {
        status.update {
            it.copy(
                connected = true, activeConfig = cfg.name, activeConfigId = cfg.id,
                activeDns = dns.name, activeDnsId = dns.id, activeMtu = mtu,
                latencyMs = lat, mbps = mbps, message = "متصل"
            )
        }
        report.update {
            it.copy(
                chosenConfigId = cfg.id, chosenDnsId = dns.id, chosenMtu = mtu, chosenPathMtu = pathMtu,
                dnsRows = dnsRows.ifEmpty { it.dnsRows }, progress = "تم الاتصال"
            )
        }
        saveLastBest(label, cfg.id, mtu, pathMtu, dns.id)
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
        val dnsAll = store.dns.value.filter { it.enabled }.ifEmpty { listOf(Defaults.dns.first()) }
        val defDns =
            if (s.autoDns) dnsAll.first()
            else store.dns.value.firstOrNull { it.id == s.selDnsId } ?: dnsAll.first()
        val label = networkLabel()

        status.update { it.copy(busy = true, connected = false, message = "جارٍ الفحص ($reason)…") }

        // ---- مسار سريع: آخر كونفيج نجح على هذا النوع من الشبكات
        if (allowFast) {
            val lb = readLastBest(label)
            val cfg = lb?.let { l -> allCfg.firstOrNull { it.id == l.cfgId } }
            val dns = lb?.let { l -> store.dns.value.firstOrNull { it.id == l.dnsId } }
            if (lb != null && cfg != null && dns != null &&
                now() - lb.time < 6 * 3600 * 1000L && (s.autoConfig || cfg.id == s.selConfigId)
            ) {
                setProgress("اتصال سريع: ${cfg.name}")
                log("اتصال سريع بآخر كونفيج ناجح على $label: ${cfg.name}")
                if (up(cfg, lb.mtu, dns.ips(), appOnly = false)) {
                    delay(700)
                    val lat = Probes.quickAlive()
                    if (lat != null) {
                        markConnected(cfg, lb.mtu, lb.pathMtu, dns, emptyList(), lat, null, label)
                        log("✔ متصل: ${cfg.name} • ${lat}ms — اضغط «فحص وإعادة الاختيار» لإجراء فحص كامل")
                        return
                    }
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
        down()

        val defaultMtu =
            if (s.autoMtu) (netInfo.value.direct?.suggestedMtu?.takeIf { it in 1000..1500 } ?: 1420)
            else s.selMtu

        // ---- المرحلة أ: فحص سريع لكل كونفيج (هل يمر الاتصال؟ وما التأخر؟)
        val alive = ArrayList<Pair<WgConfig, Int>>()
        for ((i, c) in candidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            setProgress("فحص سريع ${i + 1}/${candidates.size}: ${c.name}")
            updateRow(c.id) { it.copy(state = "testing") }
            if (!up(c, defaultMtu, defDns.ips(), appOnly = true)) {
                updateRow(c.id) { it.copy(state = "fail", note = "فشل التشغيل") }
                continue
            }
            val lat = Probes.quickAlive()
            if (lat == null) {
                updateRow(c.id) { it.copy(state = "fail", note = "لا يمر اتصال") }
                log("✗ ${c.name}: لا يمر اتصال")
                continue
            }
            updateRow(c.id) { it.copy(state = "ok", latencyMs = lat, mtu = defaultMtu) }
            log("✓ ${c.name}: ${lat}ms")
            alive += c to lat
        }
        down()

        if (alive.isEmpty()) {
            setProgress("لم ينجح أي كونفيج")
            status.update { it.copy(connected = false, message = "لا يوجد كونفيج يعمل — إعادة المحاولة بعد دقيقة") }
            log("لم ينجح أي كونفيج من ${candidates.size}")
            return
        }

        // ---- المرحلة ب: فحص دقيق لأسرع 3 (MTU + DNS + سرعة)
        val top = alive.sortedBy { it.second }.take(if (s.autoConfig) 3 else 1)
        val results = ArrayList<Result>()
        for ((i, pair) in top.withIndex()) {
            currentCoroutineContext().ensureActive()
            val c = pair.first
            setProgress("فحص دقيق ${i + 1}/${top.size}: ${c.name}")
            down()   // نفحص MTU للخادم مباشرة وبدون نفق
            val (mtu, pathMtu) = if (s.autoMtu) probeMtu(c, s) else (s.selMtu to 0)
            if (!up(c, mtu, defDns.ips(), appOnly = true)) continue
            delay(700)
            val lat = Probes.tcpLatency() ?: pair.second
            val (dns, dnsRows) = if (s.autoDns) pickDns(dnsAll) else (defDns to emptyList())
            val mbps = Probes.throughputMbps()
            val score = (mbps ?: 0.5) / (1 + lat / 100.0)
            results += Result(c, mtu, pathMtu, dns, dnsRows, lat, mbps, score)
            updateRow(c.id) {
                it.copy(
                    state = "ok", latencyMs = lat, mbps = mbps, mtu = mtu, pathMtu = pathMtu,
                    dnsName = dns.name, score = score
                )
            }
            log("★ ${c.name}: ${lat}ms • ${mbps?.let { "%.1f".format(it) } ?: "?"}Mbps • MTU $mtu • ${dns.name}")
        }
        down()

        // إن فشلت المرحلة ب لكل المرشحين نعتمد نتائج المرحلة أ
        val ordered: List<Result> =
            if (results.isNotEmpty()) results.sortedByDescending { it.score }
            else alive.sortedBy { it.second }.map {
                Result(it.first, defaultMtu, 0, defDns, emptyList(), it.second, null, 0.0)
            }

        // ---- التثبيت: نفق واحد لكل التطبيقات، مع التحقق منه
        for (r in ordered) {
            currentCoroutineContext().ensureActive()
            setProgress("تثبيت الاتصال: ${r.cfg.name}")
            if (!up(r.cfg, r.mtu, r.dns.ips(), appOnly = false)) continue
            delay(700)
            val lat = Probes.quickAlive()
            if (lat != null) {
                markConnected(r.cfg, r.mtu, r.pathMtu, r.dns, r.dnsRows, lat, r.mbps, label)
                log("★ الأفضل: ${r.cfg.name} • MTU ${r.mtu} • ${r.dns.name}")
                return
            }
            log("✗ فشل التحقق بعد التثبيت: ${r.cfg.name}")
        }
        down()
        setProgress("تعذّر تثبيت الاتصال")
        status.update { it.copy(connected = false, message = "تعذّر تثبيت الاتصال — إعادة المحاولة بعد دقيقة") }
    }

    /** يعيد (MTU النفق، أقصى حزمة للخادم أو 0). */
    private fun probeMtu(c: WgConfig, s: AppSettings): Pair<Int, Int> {
        val ep = Probes.parseEndpoint(c.text) ?: return 1420 to 0
        val addr = try {
            InetAddress.getByName(ep.first)
        } catch (e: Exception) {
            log("تعذّر حل العنوان ${ep.first}")
            return 1420 to 0
        }
        val p = Probes.pathMtu(addr, s.useRoot)
        if (p == null) {
            log("${c.name}: تعذّر فحص MTU، استُخدمت 1420")
            return 1420 to 0
        }
        val m = MtuCatalog.tunnelMtuFromPath(p, addr is Inet6Address)
        log("${c.name}: أقصى حزمة $p → MTU $m")
        return m to p
    }

    private suspend fun pickDns(list: List<DnsServer>): Pair<DnsServer, List<DnsRow>> = coroutineScope {
        val res = list.map { d -> async(Dispatchers.IO) { d to Probes.dnsLatency(d.primary) } }.awaitAll()
        dnsResults.value = res.associate { it.first.id to (it.second ?: -1) }
        val rows = res.map { DnsRow(it.first.id, it.first.name, it.second ?: -1) }
            .sortedBy { if (it.ms < 0) Int.MAX_VALUE else it.ms }
        val best = res.filter { it.second != null }.minByOrNull { it.second!! }?.first ?: list.first()
        best to rows
    }

    // ---------- التحكم بالنفق ----------
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
}
