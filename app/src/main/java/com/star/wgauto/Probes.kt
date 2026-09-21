package com.star.wgauto

import android.net.Network
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * فحوصات الشبكة وفق معايير القياس المعتمدة: الوسيط (median) والتذبذب (jitter) ونسبة الفقد (loss).
 * المعامل network يربط الاتصال بشبكة محددة (مثلاً الشبكة الأصلية متجاوزاً أي VPN، أو واجهة الـ VPN نفسها).
 */
object Probes {

    private val ifaceRe = Regex("[A-Za-z0-9_.-]{1,15}")
    private val ipRe = Regex("[0-9a-fA-F:.]{7,45}")

    /** نطاقات ذات خوادم أسماء موثوقة وموزّعة عالمياً لقياس الاستعلامات. */
    private val benchDomains = listOf(
        "cloudflare.com", "google.com", "wikipedia.org", "amazon.com", "github.com", "microsoft.com"
    )

    // ---------- endpoint ----------
    fun parseEndpoint(text: String): Pair<String, Int>? {
        var inPeer = false
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.startsWith("[")) {
                inPeer = line.equals("[Peer]", ignoreCase = true)
                continue
            }
            if (inPeer && line.substringBefore("=").trim().equals("endpoint", ignoreCase = true)) {
                val v = line.substringAfter("=").trim()
                return if (v.startsWith("[") && v.contains("]")) {
                    v.substring(1, v.indexOf(']')) to (v.substringAfter("]:", "").toIntOrNull() ?: 51820)
                } else {
                    v.substringBeforeLast(":") to (v.substringAfterLast(":").toIntOrNull() ?: 51820)
                }
            }
        }
        return null
    }

    // ---------- الإحصاء ----------
    /** الوسيط + التذبذب (متوسط الانحراف) + نسبة الفقد. null إن لم تنجح أي عينة. */
    fun stat(samples: List<Long>, attempts: Int): Stat? {
        if (samples.isEmpty() || attempts <= 0) return null
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2].toInt()
        val mean = samples.average()
        val jitter = samples.map { Math.abs(it - mean) }.average().toInt()
        val loss = ((attempts - samples.size) * 100) / attempts
        return Stat(median, jitter, loss)
    }

    // ---------- DNS: أدوات ----------
    private fun nonceName(domain: String) = "w${Random.nextInt(100000, 999999)}.$domain"

    private fun buildQuery(name: String): ByteArray {
        val bos = ByteArrayOutputStream()
        val id = Random.nextInt(0, 65535)
        bos.write(id shr 8); bos.write(id and 0xff)
        bos.write(0x01); bos.write(0x00)          // flags: RD
        bos.write(0); bos.write(1)                // QDCOUNT
        repeat(6) { bos.write(0) }                // AN/NS/AR
        for (label in name.split('.')) {
            bos.write(label.length)
            bos.write(label.toByteArray())
        }
        bos.write(0)
        bos.write(0); bos.write(1)                // QTYPE A
        bos.write(0); bos.write(1)                // QCLASS IN
        return bos.toByteArray()
    }

    private fun sameId(resp: ByteArray, q: ByteArray) = resp.size >= 2 && resp[0] == q[0] && resp[1] == q[1]

    // ---------- DNS عبر UDP/53 ----------
    private fun udpOnce(server: String, name: String, timeoutMs: Int, network: Network?): Long? = try {
        val q = buildQuery(name)
        DatagramSocket().use { s ->
            network?.bindSocket(s)
            s.soTimeout = timeoutMs
            val addr = InetAddress.getByName(server)
            val t0 = System.nanoTime()
            s.send(DatagramPacket(q, q.size, addr, 53))
            val buf = ByteArray(1500)
            s.receive(DatagramPacket(buf, buf.size))
            if (sameId(buf, q)) (System.nanoTime() - t0) / 1_000_000 else null
        }
    } catch (e: Exception) {
        null
    }

    /** استعلامات بأسماء فريدة (غير مخزّنة مؤقتاً) لقياس زمن الحل الحقيقي لا مجرد زمن الذهاب والإياب. */
    fun dnsUdp(server: String, network: Network?, attempts: Int = 6): Stat? {
        val t = ArrayList<Long>()
        var fails = 0
        for (i in 0 until attempts) {
            val r = udpOnce(server, nonceName(benchDomains[i % benchDomains.size]), 1500, network)
            if (r != null) t.add(r) else {
                fails++
                if (t.isEmpty() && fails >= 2) return null   // لا استجابة أصلاً
            }
        }
        return stat(t, attempts)
    }

    // ---------- DNS المشفّر: DoT وDoH ----------
    private fun tls(ip: String, host: String, port: Int, network: Network?, timeoutMs: Int): SSLSocket? = try {
        val raw = Socket()
        network?.bindSocket(raw)
        raw.connect(InetSocketAddress(ip, port), timeoutMs)
        raw.soTimeout = timeoutMs
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
        ssl.startHandshake()
        if (HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
            ssl
        } else {
            ssl.close()
            null
        }
    } catch (e: Exception) {
        null
    }

    /** DNS-over-TLS (منفذ 853). العينات بعد إتمام المصافحة، كما يعمل Private DNS في أندرويد. */
    fun dnsDot(ip: String, host: String, network: Network?, attempts: Int = 5): Stat? {
        val s = tls(ip, host, 853, network, 2500) ?: return null
        val t = ArrayList<Long>()
        try {
            val out = s.outputStream
            val ins = s.inputStream
            for (i in 0 until attempts) {
                val q = buildQuery(nonceName(benchDomains[i % benchDomains.size]))
                val t0 = System.nanoTime()
                out.write(byteArrayOf((q.size shr 8).toByte(), q.size.toByte()))
                out.write(q)
                out.flush()
                val hi = ins.read()
                val lo = ins.read()
                if (hi < 0 || lo < 0) break
                val len = (hi shl 8) or lo
                val buf = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = ins.read(buf, got, len - got)
                    if (n < 0) break
                    got += n
                }
                if (got == len && sameId(buf, q)) t.add((System.nanoTime() - t0) / 1_000_000) else break
            }
        } catch (e: Exception) {
            // نكتفي بما جُمع
        } finally {
            try { s.close() } catch (e: Exception) { }
        }
        return stat(t, attempts)
    }

    /** DNS-over-HTTPS (منفذ 443، مسار /dns-query). */
    fun dnsDoh(ip: String, host: String, network: Network?, attempts: Int = 5): Stat? {
        val s = tls(ip, host, 443, network, 2500) ?: return null
        val t = ArrayList<Long>()
        try {
            val out = s.outputStream
            val ins = BufferedInputStream(s.inputStream)
            for (i in 0 until attempts) {
                val q = buildQuery(nonceName(benchDomains[i % benchDomains.size]))
                val req = ("POST /dns-query HTTP/1.1\r\nHost: $host\r\n" +
                    "Content-Type: application/dns-message\r\nAccept: application/dns-message\r\n" +
                    "Content-Length: ${q.size}\r\nConnection: keep-alive\r\n\r\n").toByteArray()
                val t0 = System.nanoTime()
                out.write(req)
                out.write(q)
                out.flush()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val c = ins.read()
                    if (c < 0 || head.length > 8192) throw IOException("header")
                    head.append(c.toChar())
                }
                val h = head.toString()
                val len = h.lines().firstOrNull { it.startsWith("content-length:", true) }
                    ?.substringAfter(":")?.trim()?.toIntOrNull() ?: throw IOException("len")
                val body = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = ins.read(body, got, len - got)
                    if (n < 0) throw IOException("body")
                    got += n
                }
                val ok200 = h.startsWith("HTTP/1.1 200") || h.startsWith("HTTP/2 200")
                if (ok200 && sameId(body, q)) t.add((System.nanoTime() - t0) / 1_000_000) else break
            }
        } catch (e: Exception) {
            // نكتفي بما جُمع
        } finally {
            try { s.close() } catch (e: Exception) { }
        }
        return stat(t, attempts)
    }

    /** قياس خادم DNS بالبروتوكولات الثلاثة (المشفّر فقط إن طُلب وكان للخادم مضيف DoT/DoH). */
    fun dnsMeasure(d: DnsServer, network: Network?, encrypted: Boolean): DnsMeasure {
        val udp = dnsUdp(d.primary, network)
        val useEnc = encrypted && d.dot.isNotEmpty()
        val dot = if (useEnc) dnsDot(d.primary, d.dot, network) else null
        val doh = if (useEnc) dnsDoh(d.primary, d.dot, network) else null
        return DnsMeasure(udp, dot, doh)
    }

    /** أداء محلّل DNS الحالي في النظام (خط الأساس للمقارنة العادلة قبل أي تغيير). */
    fun systemResolveStat(network: Network?, attempts: Int = 4): Stat? {
        val t = ArrayList<Long>()
        for (i in 0 until attempts) {
            val name = nonceName(benchDomains[i % benchDomains.size])
            val out = AtomicLong(-1)
            val th = Thread {
                val t0 = System.nanoTime()
                try {
                    if (network != null) network.getAllByName(name) else InetAddress.getAllByName(name)
                } catch (e: UnknownHostException) {
                    // NXDOMAIN: وصلنا جواب صحيح (الاسم عشوائي)، أما المهلة فتتجاوز 2.8 ثانية
                } catch (e: Exception) {
                    return@Thread
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (ms < 2800) out.set(ms)
            }
            th.start()
            th.join(3000)
            if (out.get() >= 0) t.add(out.get())
        }
        return stat(t, attempts)
    }

    // ---------- زمن الاتصال ----------
    private fun connectTime(host: String, port: Int, timeoutMs: Int, network: Network?): Int? = try {
        Socket().use { s ->
            network?.bindSocket(s)
            val t0 = System.nanoTime()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            ((System.nanoTime() - t0) / 1_000_000).toInt()
        }
    } catch (e: Exception) {
        null
    }

    /** زمن اتصال TCP بعدة وجهات: وسيط وتذبذب وفقد (ما يقيسه أي فحص جودة شبكة). */
    fun tcpStat(network: Network?, attempts: Int = 6): Stat? {
        val hosts = listOf("1.1.1.1", "8.8.8.8")
        val t = ArrayList<Long>()
        for (i in 0 until attempts) {
            connectTime(hosts[i % hosts.size], 443, 1500, network)?.let { t.add(it.toLong()) }
        }
        return stat(t, attempts)
    }

    fun tcpLatency(network: Network? = null): Int? = tcpStat(network, 4)?.median

    /**
     * اختبار سريع لمرور البيانات: يحاول عدة وجهات لمدة قصيرة، وعند أول نجاح يأخذ أفضل قياسين.
     * مرّر network لربطه بواجهة الـ VPN، وإلا فسيُقاس المسار الافتراضي (وقد لا يكون النفق).
     */
    fun quickAlive(network: Network? = null, budgetMs: Long = 3200): Int? {
        val hosts = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "208.67.222.222")
        val t0 = System.nanoTime()
        var i = 0
        var first: Int? = null
        var okHost = hosts[0]
        while (first == null && (System.nanoTime() - t0) / 1_000_000 < budgetMs) {
            val h = hosts[i % hosts.size]
            i++
            first = connectTime(h, 443, 1200, network)
            if (first != null) okHost = h else Thread.sleep(150)
        }
        if (first == null) {
            return udpOnce("1.1.1.1", nonceName("cloudflare.com"), 1000, network)?.toInt()
        }
        var best: Int = first
        repeat(2) {
            connectTime(okHost, 443, 1200, network)?.let { if (it < best) best = it }
        }
        return best
    }

    // ---------- الـ IP الخارجي (دليل أن النفق حقيقي) ----------
    fun exitInfo(network: Network?): ExitInfo? {
        val urls = listOf(
            "https://1.1.1.1/cdn-cgi/trace",
            "https://www.cloudflare.com/cdn-cgi/trace",
            "https://api64.ipify.org"
        )
        for (u in urls) {
            try {
                val url = URL(u)
                val c = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                c.connectTimeout = 3500
                c.readTimeout = 3500
                val t0 = System.nanoTime()
                val text = c.inputStream.bufferedReader().use { it.readText() }
                val ms = ((System.nanoTime() - t0) / 1_000_000).toInt()
                c.disconnect()
                val map = text.lines()
                    .mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 } }
                    .associate { it[0].trim() to it[1].trim() }
                val ip = map["ip"] ?: text.trim().takeIf { ipRe.matches(it) } ?: continue
                return ExitInfo(ip, map["loc"] ?: "", ms)
            } catch (e: Exception) {
                // نجرّب الوجهة التالية
            }
        }
        return null
    }

    // ---------- السرعة ----------
    fun throughputMbps(maxMs: Long = 5000, network: Network? = null): Double? {
        return try {
            val url = URL("https://speed.cloudflare.com/__down?bytes=3000000")
            val c = (if (network != null) network.openConnection(url) else url.openConnection()) as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 4000
            var total = 0L
            var t0 = 0L
            c.inputStream.use { ins ->
                val buf = ByteArray(16384)
                val first = ins.read(buf)
                if (first < 0) return null
                t0 = System.nanoTime()          // نبدأ العدّ بعد وصول أول بايتات
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if ((System.nanoTime() - t0) / 1_000_000 > maxMs) break
                }
            }
            c.disconnect()
            val sec = (System.nanoTime() - t0) / 1e9
            if (total > 0 && sec > 0) total * 8 / sec / 1e6 else null
        } catch (e: Exception) {
            null
        }
    }

    // ---------- MTU ----------
    /** true = مرّت الحزمة، false = فشلت، null = أمر ping غير صالح للاستخدام. */
    private fun ping(host: String, packetSize: Int, ipv6: Boolean, root: Boolean, iface: String?): Boolean? {
        val payload = packetSize - if (ipv6) 48 else 28
        val cmd = "ping ${if (ipv6) "-6 " else ""}${if (iface != null) "-I $iface " else ""}" +
            "-c 1 -W 2 -M do -s $payload $host"
        return try {
            val pb = if (root) ProcessBuilder("su", "-c", cmd) else ProcessBuilder("sh", "-c", cmd)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroy()
                return false
            }
            val low = out.lowercase()
            when {
                p.exitValue() == 0 -> true
                "invalid option" in low || "unknown option" in low || "usage" in low || "not found" in low -> null
                else -> false
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * أكبر حزمة IP (بالبايت) تصل دون تجزئة، بين 1000 و1500 (مؤشر أولي فقط: بعض الشبكات تحجب ICMP الكبير).
     * iface: فحص عبر واجهة محددة (يتطلب روت).
     */
    fun pathMtu(addr: InetAddress, allowRoot: Boolean, iface: String? = null): Int? {
        if (iface != null && !ifaceRe.matches(iface)) return null
        val v6 = addr is Inet6Address
        val host = addr.hostAddress ?: return null
        val modes = when {
            iface != null -> if (allowRoot) listOf(true) else emptyList()
            allowRoot -> listOf(false, true)
            else -> listOf(false)
        }
        for (root in modes) {
            val first = ping(host, 1000, v6, root, iface) ?: continue
            if (!first) continue
            var lo = 1000
            var hi = 1500
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (ping(host, mid, v6, root, iface) == true) lo = mid else hi = mid - 1
            }
            return lo
        }
        return null
    }
}
