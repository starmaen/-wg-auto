package com.star.wgauto

import android.net.Network
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * فحوصات الشبكة: DNS، زمن الاتصال، السرعة، وأقصى MTU للمسار.
 * المعامل network يربط الاتصال بشبكة محددة (مثلاً الشبكة الأصلية متجاوزاً أي VPN).
 */
object Probes {

    private val ifaceRe = Regex("[A-Za-z0-9_.-]{1,15}")

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

    // ---------- DNS ----------
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

    private fun queryOnce(server: String, timeoutMs: Int, network: Network?): Long? = try {
        val q = buildQuery("www.google.com")
        DatagramSocket().use { s ->
            network?.bindSocket(s)
            s.soTimeout = timeoutMs
            val addr = InetAddress.getByName(server)
            val t0 = System.nanoTime()
            s.send(DatagramPacket(q, q.size, addr, 53))
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            if (buf[0] == q[0] && buf[1] == q[1]) (System.nanoTime() - t0) / 1_000_000 else null
        }
    } catch (e: Exception) {
        null
    }

    /** وسيط زمن الاستجابة بالمللي ثانية، أو null إذا لم يستجب الخادم. */
    fun dnsLatency(server: String, tries: Int = 3, timeoutMs: Int = 1500, network: Network? = null): Int? {
        val times = ArrayList<Long>()
        repeat(tries) { queryOnce(server, timeoutMs, network)?.let { times.add(it) } }
        if (times.isEmpty()) return null
        times.sort()
        return times[times.size / 2].toInt()
    }

    // ---------- زمن الاتصال ----------
    fun tcpLatency(
        targets: List<Pair<String, Int>> = listOf("1.1.1.1" to 443, "8.8.8.8" to 443),
        tries: Int = 3,
        timeoutMs: Int = 2500,
        network: Network? = null
    ): Int? {
        val times = ArrayList<Long>()
        for ((host, port) in targets) {
            repeat(tries) {
                try {
                    Socket().use { s ->
                        network?.bindSocket(s)
                        val t0 = System.nanoTime()
                        s.connect(InetSocketAddress(host, port), timeoutMs)
                        times.add((System.nanoTime() - t0) / 1_000_000)
                    }
                } catch (e: Exception) {
                    // تجاهل
                }
            }
        }
        if (times.isEmpty()) return null
        times.sort()
        return times[times.size / 2].toInt()
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
     * أكبر حزمة IP (بالبايت) تصل دون تجزئة، بين 1000 و1500.
     * iface: فحص عبر واجهة محددة (يتطلب روت) — يُستخدم لقياس الشبكة الأصلية أثناء عمل VPN.
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
