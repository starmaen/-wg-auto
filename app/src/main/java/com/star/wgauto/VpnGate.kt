package com.star.wgauto

import android.net.Network
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * عميل VPNGate (جامعة تسوكوبا — سيرفرات OpenVPN مجانية عالمية، بلا حساب):
 * https://www.vpngate.net/api/iphone/
 *
 * الفلسفة نفسها المعتمدة في محرك WireGuard:
 * - الفحص بالأرقام فقط: الزمن 50% + الفقد 30% + التذبذب 20% عبر Exam.score.
 * - البروتوكول يُختار يدوياً (UDP للسرعة / TCP لتجاوز الجدران) أو تلقائياً.
 * - DNS وMTU يعتمدان على النفق بعد الاتصال، لذلك نعرض مقترح فحص شبكتك
 *   (من Engine.netInfo) كتوصية، وكونفيج VPNGate يدفع DNS الخاص به عند الاتصال.
 */
object VpnGate {

    const val API_URL = "https://www.vpngate.net/api/iphone/"
    const val CACHE_FILE = "vpngate.csv"
    /** يُعاد الجلب تلقائياً إن تجاوز عمر الكاش هذه المدة. */
    const val STALE_MS = 6L * 3600 * 1000

    data class Server(
        val host: String,
        val ip: String,
        val country: String,
        val sessions: Int,
        val speedMbps: Double,
        val uptimeMs: Long,
        val pingAdvertised: Int,
        val configB64: String
    ) {
        val key: String get() = host.ifEmpty { ip }
    }

    data class Remote(val host: String, val port: Int, val proto: String)

    /** نتيجة فحص سيرفر واحد — نفس معادلة الامتحان الموحدة في Models.Exam. */
    data class ExamResult(val lat: Int, val jitter: Int, val loss: Int, val via: String) {
        val score: Double get() = Exam.score(lat, jitter, loss)
    }

    // ---------- الجلب والتحليل ----------

    suspend fun fetchCsv(network: Network? = null): String? = withContext(Dispatchers.IO) {
        try {
            val c = (network?.openConnection(URL(API_URL)) ?: URL(API_URL).openConnection()) as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "WG-Auto")
            val code = c.responseCode
            val t = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code in 200..299 && t.contains("OpenVPN_ConfigData_Base64")) t else null
        } catch (e: Exception) {
            null
        }
    }

    /** يحلل CSV. الحقل الأخير Base64 بلا فواصل، فنقسم بحد أقصى 15 عموداً. */
    fun parse(csv: String): List<Server> {
        val out = ArrayList<Server>()
        for (raw in csv.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("*")) continue
            val cols = line.split(",", limit = 15)
            if (cols.size < 15) continue
            if (cols[0].equals("HostName", ignoreCase = true)) continue
            try {
                val b64 = cols[14].trim()
                if (b64.isEmpty()) continue
                out += Server(
                    host = cols[0].trim(),
                    ip = cols[1].trim(),
                    country = cols[6].trim().uppercase().take(2),
                    sessions = cols[7].trim().toIntOrNull() ?: 0,
                    speedMbps = (cols[4].trim().toLongOrNull() ?: 0L) / 1_000_000.0,
                    uptimeMs = cols[8].trim().toLongOrNull() ?: 0L,
                    pingAdvertised = cols[3].trim().toIntOrNull() ?: -1,
                    configB64 = b64
                )
            } catch (e: Exception) {
                continue
            }
        }
        // الأخف حملاً والأسرع معلناً أولاً كمرشحين للفحص
        return out.sortedWith(compareBy({ it.pingAdvertised.takeIf { p -> p >= 0 } ?: 9999 }, { -it.speedMbps }))
    }

    fun decodeConfig(b64: String): String? = try {
        Base64.decode(b64.trim(), Base64.DEFAULT).toString(Charsets.UTF_8).takeIf { it.contains("remote") }
    } catch (e: Exception) {
        null
    }

    /** يستخرج أسطر remote مع البروتوكول الافتراضي من proto. */
    fun remotes(configText: String): List<Remote> {
        var defaultProto = "udp"
        for (raw in configText.lines()) {
            val l = raw.substringBefore('#').trim()
            if (l.startsWith("proto", ignoreCase = true)) {
                val v = l.split(Regex("\\s+")).getOrNull(1)?.lowercase() ?: ""
                if (v.contains("tcp")) defaultProto = "tcp" else if (v.contains("udp")) defaultProto = "udp"
            }
        }
        val out = ArrayList<Remote>()
        for (raw in configText.lines()) {
            val l = raw.substringBefore('#').trim()
            // remote الحقيقي فقط (remote + مسافة) — نتجاهل remote-cert-tls وremote-random
            if (!Regex("^remote\\s", RegexOption.IGNORE_CASE).containsMatchIn(l)) continue
            val p = l.split(Regex("\\s+"))
            if (p.size < 2) continue
            val host = p[1]
            var port = p.getOrNull(2)?.toIntOrNull() ?: 1194
            var proto = defaultProto
            for (tok in p.drop(3)) {
                val t = tok.lowercase()
                if (t == "udp" || t == "tcp") proto = t
            }
            // صيغة "remote host 1194 udp" مقابل "remote host port"
            if (p.size >= 4 && p[3].lowercase() in listOf("udp", "tcp")) {
                port = p.getOrNull(2)?.toIntOrNull() ?: 1194
            }
            out += Remote(host, port, proto)
        }
        return out.distinct()
    }

    fun hasProto(configText: String, proto: String): Boolean =
        remotes(configText).any { it.proto == proto.lowercase() }

    /**
     * يبقي فقط remotes البروتوكول المطلوب (udp/tcp) ويضبط سطر proto.
     * auto = يبقي الكل كما وزّعه المتطوع.
     */
    fun filterConfig(configText: String, prefer: String): String {
        if (prefer == "auto") return configText
        val keep = prefer.lowercase()
        val offered = remotes(configText).map { it.proto }.toSet()
        // السيرفر لا يتكلم البروتوكول المطلوب، أو الملف أحادي البروتوكول أصلاً:
        // أي تعديل هنا يكسر الملف (مثل كتابة proto udp لسيرفر TCP)، فنعيده كما هو.
        if (keep !in offered || offered.size == 1) return configText
        val sb = StringBuilder()
        var protoWritten = false
        for (raw in configText.lines()) {
            val t = raw.trim()
            val low = t.substringBefore('#').trim().lowercase()
            val isProto = Regex("^proto(\\s|$)").containsMatchIn(low)
            val isRemote = Regex("^remote\\s").containsMatchIn(low)
            when {
                isProto && !protoWritten -> {
                    sb.appendLine(if (keep == "tcp") "proto tcp-client" else "proto udp")
                    protoWritten = true
                }
                isRemote -> {
                    val parts = t.split(Regex("\\s+"))
                    var rp = ""
                    for (tok in parts.drop(1)) {
                        if (tok.lowercase() == "udp" || tok.lowercase() == "tcp") { rp = tok.lowercase(); break }
                    }
                    // بدون وسم صريح: نعتمد البروتوكول الافتراضي للملف
                    val isKeep = when {
                        rp == keep -> true
                        rp.isEmpty() && keep == "udp" && !hasProto(configText, "tcp") -> true
                        rp.isEmpty() -> true // لا يمكن الجزم — نُبقي السطر لتفادي كونفيج فارغ
                        else -> false
                    }
                    if (isKeep) {
                        // وسْم صريح ليس ضرورياً لـ OpenVPN، نُبقي السطر كما هو
                        sb.appendLine(raw)
                    }
                }
                else -> sb.appendLine(raw)
            }
        }
        val res = sb.toString()
        // ضمان: إن لم يتبق أي remote (ملف أحادي البروتوكول المعاكس)، نعيد الأصل كاملاً
        if (!res.contains(Regex("(?m)^\\s*remote\\s+"))) return configText
        return res
    }

    // ---------- الفحص: ping حقيقي بنفس معادلة Exam ----------

    private fun tcpOnce(host: String, port: Int, timeoutMs: Int): Long? = try {
        val s = Socket()
        val t0 = System.nanoTime()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        val ms = (System.nanoTime() - t0) / 1_000_000
        s.close()
        ms
    } catch (e: Exception) {
        null
    }

    /** ICMP عبر ثنائية ping في النظام (تعمل بلا روت). 3 عينات. */
    private fun icmpPing(ip: String): List<Long> {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("/system/bin/ping", "-c", "3", "-W", "2", ip))
            val out = ByteArrayOutputStream()
            p.inputStream.copyTo(out)
            p.waitFor()
            val re = Regex("time=([\\d.]+)")
            return re.findAll(out.toString()).mapNotNull { it.groupValues[1].toDoubleOrNull()?.toLong() }.toList()
        } catch (e: Exception) {
            return emptyList()
        }
    }

    /**
     * يفحص سيرفراً واحداً: TCP على منافذ remote المعلنة أولاً (الأدق)،
     * ثم ICMP كبديل. يعيد null إن تعذّر القياس.
     */
    suspend fun exam(server: Server, prefer: String): ExamResult? = withContext(Dispatchers.IO) {
        val cfg = decodeConfig(server.configB64) ?: return@withContext null
        val rems = remotes(cfg)
        val ordered = when (prefer) {
            "tcp" -> rems.sortedBy { if (it.proto == "tcp") 0 else 1 }
            "udp" -> rems.sortedBy { if (it.proto == "udp") 0 else 1 }
            else -> rems
        }
        // TCP أولاً — UDP لا يقبل اتصال قياس مباشر
        for (r in ordered.filter { it.proto == "tcp" }.take(2)) {
            val samples = ArrayList<Long>()
            repeat(4) {
                tcpOnce(r.host, r.port, 2500)?.let { samples += it }
            }
            val st = Probes.stat(samples, 4)
            if (st != null) return@withContext ExamResult(st.median, st.jitter, st.loss, "TCP ${r.port}")
        }
        // بديل ICMP على IP السيرفر
        val samples = icmpPing(server.ip)
        val st = Probes.stat(samples, 3)
        if (st != null) return@withContext ExamResult(st.median, st.jitter, st.loss, "Ping")
        null
    }

    /** يفحص قائمة مرشحين بالتوازي (8 في المرة) ويعيد map من key إلى النتيجة. */
    suspend fun examMany(
        servers: List<Server>,
        prefer: String,
        onOne: (String, ExamResult?) -> Unit = { _, _ -> }
    ): Map<String, ExamResult> = coroutineScope {
        servers.map { s ->
            async(Dispatchers.IO) {
                val r = try { exam(s, prefer) } catch (e: Exception) { null }
                onOne(s.key, r)
                s.key to r
            }
        }.awaitAll().mapNotNull { (k, r) -> r?.let { k to it } }.toMap()
    }

    // ---------- ترتيب المرشحين قبل الفحص ----------

    /** أفضل 25 مرشحاً للفحص العميق: المعلن الأسرع والأخف حملاً أولاً. */
    fun candidates(servers: List<Server>, prefer: String, n: Int = 25): List<Server> {
        val filtered = if (prefer == "auto") servers else servers.filter {
            decodeConfig(it.configB64)?.let { c -> hasProto(c, prefer) } ?: false
        }
        val pool = filtered.ifEmpty { servers }
        return pool.sortedWith(
            compareBy(
                { it.pingAdvertised.takeIf { p -> p >= 0 } ?: 9999 },
                { -(it.speedMbps) },
                { it.sessions }
            )
        ).take(n)
    }

    fun speedText(mbps: Double): String = when {
        mbps >= 1000 -> "%.1f Gbps".format(mbps / 1000)
        mbps >= 1 -> "%.1f Mbps".format(mbps)
        mbps > 0 -> "%d Kbps".format((mbps * 1000).toInt())
        else -> "—"
    }

    fun examText(r: ExamResult?): String = when {
        r == null -> "لم يُفحص"
        r.loss >= 100 -> "✘ لا استجابة"
        else -> "${r.lat}ms • فقد ${r.loss}% • تذبذب ${r.jitter} • ${r.via}"
    }
}
