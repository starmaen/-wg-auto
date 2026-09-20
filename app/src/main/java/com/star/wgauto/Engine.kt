package com.star.wgauto

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
 * - فحص الشبكة (بدون VPN): أسرع DNS + أقصى MTU للمسار، تلقائياً في الخلفية.
 * - زر التشغيل: يفحص كل الكونفيجات ويتصل بالأفضل (كونفيج + MTU + DNS).
 * - إضافة كونفيج: يُفحص تلقائياً ويظهر الناتج بجانبه.
 */
class Engine(ctx: Context, private val store: Store) {

    private class Result(
        val cfg: WgConfig, val mtu: Int, val dns: DnsServer,
        val lat: Int, val mbps: Double?, val score: Double
    )

    private val appCtx = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val backend = GoBackend(appCtx)
    private val tunnel = object : Tunnel {
        override fun getName(): String = "wgauto"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    val status = MutableStateFlow(Status())
    val logs = MutableStateFlow<List<String>>(emptyList())
    val dnsResults = MutableStateFlow<Map<String, Int>>(emptyMap())
    val netInfo = MutableStateFlow(NetInfo())
    val configResults = MutableStateFlow<Map<String, String>>(emptyMap())

    private val cm = appCtx.getSystemService(ConnectivityManager::class.java)

    private var runJob: Job? = null
    private var netJob: Job? = null
    private var scanJob: Job? = null

    /** وقت آخر تغيير أجراه التطبيق نفسه على النفق، لتجاهل أحداث الشبكة الناتجة عنه. */
    @Volatile
    private var lastOwnChange = 0L

    private fun now() = System.currentTimeMillis()

    private fun tunnelIsUp(): Boolean = try {
        backend.getState(tunnel) == Tunnel.State.UP
    } catch (e: Exception) {
        false
    }

    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logs.update { (it + "$t  $msg").takeLast(300) }
    }

    private fun setResult(id: String, text: String) {
        configResults.update { it + (id to text) }
    }

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
        status.update { it.copy(running = true) }
        reselect("تشغيل")
    }

    fun stop() {
        scope.launch {
            netJob?.cancelAndJoin()
            runJob?.cancelAndJoin()
            mutex.withLock { down() }
            status.value = Status(running = false, message = "متوقف")
            log("تم الإيقاف")
            if (store.settings.value.backgroundScan) scanNetwork("بعد الإيقاف")
        }
    }

    fun reselect(reason: String) {
        if (!status.value.running) return
        runJob?.cancel()
        runJob = scope.launch {
            mutex.withLock {
                try {
                    selectAndConnect(reason)
                } finally {
                    status.update { it.copy(busy = false) }
                }
            }
        }
    }

    /**
     * يُستدعى من مراقب الشبكة.
     * kind = "vpn": ظهر أو اختفى VPN (من أي تطبيق) — kind = "net": تبدّلت الشبكة الأصلية أو IP.
     */
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
                    // إن كان نفقنا قد أُسقط لأن VPN آخر استلم الاتصال
                    if (status.value.connected && !tunnelIsUp()) {
                        log("⚠ VPN آخر استبدل نفق التطبيق")
                        status.value = Status(running = false, message = "أوقفه VPN آخر")
                    } else {
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
            if (status.value.running) reselect("تغيّر الشبكة") else scanNetwork("تغيّر الشبكة")
        }
    }

    private fun hasExternalVpn(): Boolean = NetUtil.all(cm).any { it.isVpn } && !tunnelIsUp()

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

                // الجهة الأولى: الشبكة الأصلية. مع وجود VPN لا يمكن قياس MTU إلا بالروت عبر الواجهة.
                val direct = scanSide(
                    under, dnsList,
                    doMtu = vpn == null || root,
                    root = root,
                    mtuIface = if (vpn == null) null else under.iface,
                    vpnSide = false
                )
                dnsResults.value = direct.dns.associate { it.first.id to (it.second ?: -1) }

                // الجهة الثانية: عبر الـ VPN
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

    // ---------- فحص الكونفيجات ----------
    fun testAll() = testConfigs(store.configs.value.filter { it.enabled }.map { it.id })

    fun testUntested() = testConfigs(
        store.configs.value.filter { it.enabled && it.id !in configResults.value }.map { it.id }
    )

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
                val s = store.settings.value
                val dnsAll = store.dns.value.filter { it.enabled }.ifEmpty { listOf(Defaults.dns.first()) }
                for (id in ids) {
                    val c = store.configs.value.firstOrNull { it.id == id } ?: continue
                    setResult(id, "جارٍ الفحص…")
                    val r = measure(c, s, dnsAll)
                    setResult(
                        id,
                        if (r == null) "✗ لا يعمل"
                        else "✓ ${r.lat}ms • ${r.mbps?.let { "%.1f".format(it) } ?: "?"}Mbps • MTU ${r.mtu}"
                    )
                }
                down()
            }
        }
    }

    // ---------- الاختيار عند التشغيل ----------
    private suspend fun selectAndConnect(reason: String) {
        val s = store.settings.value
        val allCfg = store.configs.value.filter { it.enabled }
        if (allCfg.isEmpty()) {
            log("لا توجد كونفيجات مفعّلة")
            status.update { it.copy(connected = false, message = "أضف كونفيجاً وفعّله") }
            return
        }
        val dnsAll = store.dns.value.filter { it.enabled }.ifEmpty { listOf(Defaults.dns.first()) }

        status.update { it.copy(busy = true, message = "جارٍ الفحص ($reason)…") }
        log("بدء الفحص: $reason")
        down()

        val candidates =
            if (s.autoConfig) allCfg
            else listOf(allCfg.firstOrNull { it.id == s.selConfigId } ?: allCfg.first())

        val results = ArrayList<Result>()
        for (c in candidates) {
            currentCoroutineContext().ensureActive()
            status.update { it.copy(message = "فحص: ${c.name}") }
            val r = measure(c, s, dnsAll) ?: continue
            results += r
        }

        val best = results.maxByOrNull { it.score }
        if (best == null) {
            status.update { it.copy(connected = false, message = "لا يوجد كونفيج يعمل — إعادة المحاولة بعد دقيقة") }
            log("لم ينجح أي كونفيج")
            return
        }
        if (up(best.cfg, best.mtu, best.dns.ips())) {
            status.update {
                it.copy(
                    connected = true, activeConfig = best.cfg.name, activeDns = best.dns.name,
                    activeMtu = best.mtu, latencyMs = best.lat, mbps = best.mbps, message = "متصل"
                )
            }
            setResult(best.cfg.id, "✓ ${best.lat}ms • ${best.mbps?.let { "%.1f".format(it) } ?: "?"}Mbps • MTU ${best.mtu}")
            log("★ الأفضل: ${best.cfg.name} • MTU ${best.mtu} • ${best.dns.name}")
        } else {
            status.update { it.copy(connected = false, message = "فشل الاتصال بالأفضل") }
        }
    }

    /** يقيس كونفيجاً واحداً (MTU + تشغيل + تأخر + DNS + سرعة) ثم يُنزل النفق. */
    private suspend fun measure(c: WgConfig, s: AppSettings, dnsAll: List<DnsServer>): Result? {
        val mtu = if (s.autoMtu) probeMtu(c, s) else s.selMtu
        val initDns =
            if (s.autoDns) dnsAll.first()
            else store.dns.value.firstOrNull { it.id == s.selDnsId } ?: dnsAll.first()

        if (!up(c, mtu, initDns.ips())) {
            log("✗ ${c.name}: فشل التشغيل")
            return null
        }
        delay(1500)   // مهلة المصافحة (handshake)

        val lat = Probes.tcpLatency()
        if (lat == null) {
            log("✗ ${c.name}: لا يمر أي اتصال")
            down()
            return null
        }
        val dns = if (s.autoDns) pickDns(dnsAll) else initDns
        val mbps = Probes.throughputMbps()
        val score = (mbps ?: 0.5) / (1 + lat / 100.0)
        val speedTxt = mbps?.let { "%.1f".format(it) } ?: "?"
        log("✓ ${c.name}: MTU=$mtu • ${lat}ms • ${speedTxt}Mbps • DNS=${dns.name}")
        down()
        return Result(c, mtu, dns, lat, mbps, score)
    }

    private fun probeMtu(c: WgConfig, s: AppSettings): Int {
        val ep = Probes.parseEndpoint(c.text) ?: return 1420
        val addr = try {
            InetAddress.getByName(ep.first)
        } catch (e: Exception) {
            log("تعذّر حل العنوان ${ep.first}")
            return 1420
        }
        val p = Probes.pathMtu(addr, s.useRoot)
        if (p == null) {
            log("${c.name}: تعذّر فحص MTU، استُخدمت 1420")
            return 1420
        }
        val m = MtuCatalog.tunnelMtuFromPath(p, addr is Inet6Address)
        log("${c.name}: أقصى حزمة $p → MTU $m")
        return m
    }

    private suspend fun pickDns(list: List<DnsServer>): DnsServer = coroutineScope {
        val res = list.map { d -> async(Dispatchers.IO) { d to Probes.dnsLatency(d.primary) } }.awaitAll()
        dnsResults.value = res.associate { it.first.id to (it.second ?: -1) }
        res.filter { it.second != null }.minByOrNull { it.second!! }?.first ?: list.first()
    }

    // ---------- التحكم بالنفق ----------
    private fun up(c: WgConfig, mtu: Int?, dns: List<String>): Boolean {
        lastOwnChange = now()
        return try {
            val text = ConfigText.override(c.text, mtu, dns)
            val cfg = Config.parse(ByteArrayInputStream(text.toByteArray()))
            backend.setState(tunnel, Tunnel.State.UP, cfg)
            lastOwnChange = now()
            true
        } catch (e: Exception) {
            lastOwnChange = now()
            log("خطأ: ${e.message ?: e.javaClass.simpleName}")
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
