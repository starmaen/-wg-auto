package com.star.wgauto

import android.content.Context
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
 * محرك الاختيار التلقائي:
 * لكل كونفيج مفعّل → فحص MTU للمسار → تشغيل النفق → قياس التأخر والسرعة وأفضل DNS
 * ثم الاتصال بالأفضل.
 */
class Engine(ctx: Context, private val store: Store) {

    private class Result(
        val cfg: WgConfig, val mtu: Int, val dns: DnsServer,
        val lat: Int, val mbps: Double?, val score: Double
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val backend = GoBackend(ctx.applicationContext)
    private val tunnel = object : Tunnel {
        override fun getName(): String = "wgauto"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    val status = MutableStateFlow(Status())
    val logs = MutableStateFlow<List<String>>(emptyList())
    val dnsResults = MutableStateFlow<Map<String, Int>>(emptyMap())

    private var runJob: Job? = null
    private var netJob: Job? = null

    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logs.update { (it + "$t  $msg").takeLast(300) }
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
        store.updateConfigs { it + WgConfig(name = name.ifBlank { "config" }, text = clean) }
        return null
    }

    // ---------- تشغيل / إيقاف ----------
    fun start() {
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

    fun onNetworkChanged() {
        if (!status.value.running || !store.settings.value.reselectOnNetwork) return
        netJob?.cancel()
        netJob = scope.launch {
            delay(3000)   // تجميع التغييرات المتتابعة
            reselect("تغيّر الشبكة")
        }
    }

    fun testDnsNow() {
        scope.launch {
            val list = store.dns.value
            val res = list.map { d -> async { d.id to (Probes.dnsLatency(d.primary) ?: -1) } }.awaitAll()
            dnsResults.value = res.toMap()
        }
    }

    // ---------- الاختيار ----------
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

            val mtu = if (s.autoMtu) probeMtu(c, s) else s.selMtu
            val initDns =
                if (s.autoDns) dnsAll.first()
                else store.dns.value.firstOrNull { it.id == s.selDnsId } ?: dnsAll.first()

            if (!up(c, mtu, initDns.ips())) {
                log("✗ ${c.name}: فشل التشغيل")
                continue
            }
            delay(1500)   // مهلة المصافحة (handshake)

            val lat = Probes.tcpLatency()
            if (lat == null) {
                log("✗ ${c.name}: لا يمر أي اتصال")
                down()
                continue
            }
            val dns = if (s.autoDns) pickDns(dnsAll) else initDns
            val mbps = Probes.throughputMbps()
            val score = (mbps ?: 0.5) / (1 + lat / 100.0)
            val speedTxt = mbps?.let { "%.1f".format(it) } ?: "?"
            log("✓ ${c.name}: MTU=$mtu • ${lat}ms • ${speedTxt}Mbps • DNS=${dns.name}")
            results += Result(c, mtu, dns, lat, mbps, score)
            down()
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
            log("★ الأفضل: ${best.cfg.name} • MTU ${best.mtu} • ${best.dns.name}")
        } else {
            status.update { it.copy(connected = false, message = "فشل الاتصال بالأفضل") }
        }
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
    private fun up(c: WgConfig, mtu: Int?, dns: List<String>): Boolean = try {
        val text = ConfigText.override(c.text, mtu, dns)
        val cfg = Config.parse(ByteArrayInputStream(text.toByteArray()))
        backend.setState(tunnel, Tunnel.State.UP, cfg)
        true
    } catch (e: Exception) {
        log("خطأ: ${e.message ?: e.javaClass.simpleName}")
        false
    }

    private fun down() {
        try {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
        } catch (e: Exception) {
            // تجاهل
        }
    }
}
