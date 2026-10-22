package com.star.wgauto

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

/**
 * سيرفر «إضافي» مستورد من رابط اشتراك عام.
 * البروتوكولات المدعومة للاستيراد: wireguard (wg) و openvpn (ovpn) و vmess/vless/trojan/ss.
 * التشغيل: wg و ovpn عبر محركيهما، و vmess/vless/trojan/ss عبر محرّك Xray المدمج.
 */
data class ExtraServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val country: String = "",
    val raw: String = "",
    val source: String = "",
    val lat: Int = -1,
    val note: String = "",
    val addedAt: Long = System.currentTimeMillis()
) {
    val connectable: Boolean
        get() = protocol == "wg" || protocol == "ovpn" ||
            protocol == "vmess" || protocol == "vless" || protocol == "trojan" || protocol == "ss"
    val key: String get() = protocol + "|" + host.lowercase() + "|" + port
    val title: String get() = name.ifBlank { host }
}

/** تحميل/تحليل/فحص الاشتراكات العامة. بلا أي مكتبة خارجية. */
object ExtraServers {
    private const val UA = "WG Auto/1.54"

    // ---------- الشبكة ----------
    fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Language", "en")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return text
        } finally {
            try { conn.disconnect() } catch (e: Exception) { }
        }
    }

    /** طلب HTTPS بسيط لا يرمي استثناء (لاستعلام دولة السيرفر). */
    private fun fetchPlain(url: String, timeoutMs: Int = 3500): String {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection)
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            val text = if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else ""
            c.disconnect()
            text
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * يجلب رمز دولة نقطة الاتصال (ISO-3166) من عنوانها.
     * مصدران مجانيان عبر HTTPS، والفشل يعني رمزاً فارغاً (يُعرض 🏳️).
     */
    fun resolveCountry(host: String): String {
        return try {
            val ip = if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) host
            else java.net.InetAddress.getByName(host).hostAddress ?: return ""
            var text = fetchPlain("https://api.country.is/$ip")
            var cc = try {
                JSONObject(text).optString("country", "")
            } catch (e: Exception) {
                ""
            }
            if (cc.length != 2) {
                text = fetchPlain("https://ipwho.is/$ip?fields=country_code")
                cc = try {
                    JSONObject(text).optString("country_code", "")
                } catch (e: Exception) {
                    ""
                }
            }
            val up = cc.trim().uppercase()
            if (up.length == 2 && up.all { it in 'A'..'Z' }) up else ""
        } catch (e: Exception) {
            ""
        }
    }

    /** فحص وصول TCP (زمن بالمللي ثانية أو -1). */
    fun tcpTest(host: String, port: Int, timeoutMs: Int = 4500): Int {
        if (host.isBlank() || port <= 0 || port > 65535) return -1
        return try {
            val t0 = System.currentTimeMillis()
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            (System.currentTimeMillis() - t0).toInt()
        } catch (e: Exception) {
            -1
        }
    }

    // ---------- Base64 ----------
    private fun pad(s: String): String {
        var t = s.trim().replace("\n", "").replace("\r", "").replace(" ", "")
            .replace('-', '+').replace('_', '/')
        val m = t.length % 4
        if (m != 0) t += "=".repeat(4 - m)
        return t
    }

    fun b64(s: String): String? = try {
        Base64.getDecoder().decode(pad(s)).toString(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun b64mime(s: String): String? = try {
        Base64.getMimeDecoder().decode(s).toString(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    // ---------- التحليل ----------
    fun parseSubscription(textIn: String, source: String = ""): List<ExtraServer> {
        var text = textIn.trim()
        if (text.isEmpty()) return emptyList()

        // اشتراك base64 كامل (يُفك مرة واحدة عند الحاجة)
        if (!text.contains("://") && !text.contains("[Interface]") && !text.contains("dev ")) {
            val d = b64(text) ?: b64mime(text)
            if (d != null && (d.contains("://") || d.contains("[Interface]"))) text = d.trim()
        }

        val out = ArrayList<ExtraServer>()
        val seen = HashSet<String>()
        fun add(s: ExtraServer?) {
            if (s == null || s.host.isBlank()) return
            val fixed = s.copy(source = if (s.source.isBlank()) source else s.source)
            if (seen.add(fixed.key + "|" + fixed.name)) out.add(fixed)
        }

        val hasUris = text.lineSequence().any {
            val l = it.trim()
            l.startsWith("ss://", true) || l.startsWith("vmess://", true) ||
                l.startsWith("vless://", true) || l.startsWith("trojan://", true)
        }

        // كونفيج WireGuard كامل (واحد أو أكثر)
        if (text.contains("[Interface]") && text.contains("[Peer]")) {
            val parts = text.split(Regex("(?m)^\\s*\\[Interface\\]\\s*$"))
            if (parts.size > 1) {
                for (i in 1 until parts.size) {
                    val block = "[Interface]\n" + parts[i].trim()
                    if (!block.contains("[Peer]")) continue
                    val (h, p) = endpointOf(block)
                    if (h.isBlank()) continue
                    add(
                        ExtraServer(
                            name = nameFromBlock(block, h), protocol = "wg",
                            host = h, port = p, country = guessCountry(block), raw = block
                        )
                    )
                }
            } else {
                val (h, p) = endpointOf(text)
                if (h.isNotBlank()) add(
                    ExtraServer(
                        name = nameFromBlock(text, h), protocol = "wg",
                        host = h, port = p, country = guessCountry(text), raw = text
                    )
                )
            }
        } else if (!hasUris && isOvpnText(text)) {
            val (h, p) = remoteOf(text)
            if (h.isNotBlank()) add(
                ExtraServer(
                    name = nameFromBlock(text, h), protocol = "ovpn",
                    host = h, port = p, country = guessCountry(text), raw = text
                )
            )
        }

        // روابط سطرية
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            try {
                when {
                    line.startsWith("ss://", true) -> add(parseSs(line))
                    line.startsWith("vmess://", true) -> add(parseVmess(line))
                    line.startsWith("vless://", true) -> add(parseUri(line, "vless"))
                    line.startsWith("trojan://", true) -> add(parseUri(line, "trojan"))
                }
            } catch (e: Exception) {
            }
        }
        return out
    }

    private fun endpointOf(block: String): Pair<String, Int> {
        val m = Regex("(?im)^\\s*Endpoint\\s*=\\s*(\\[[^\\]]+\\]|[^\\s:#]+)\\s*:\\s*(\\d+)").find(block)
            ?: return "" to 0
        var host = m.groupValues[1].removePrefix("[").removeSuffix("]")
        var port = m.groupValues[2].toIntOrNull() ?: 0
        if (host.contains(":")) {
            // IPv6 مضغوط قد يظهر بلا أقواس
            val idx = host.lastIndexOf(':')
            if (idx > 0) {
                port = host.substring(idx + 1).toIntOrNull() ?: port
                host = host.substring(0, idx)
            }
        }
        return host to port
    }

    private fun remoteOf(text: String): Pair<String, Int> {
        val m = Regex("(?im)^\\s*remote\\s+(\\S+)\\s+(\\d+)").find(text) ?: return "" to 0
        return m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: 0)
    }

    private fun isOvpnText(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("remote ") && (t.contains("client") || t.contains("dev tun") || t.contains("dev tap"))
    }

    private fun parseVmess(line: String): ExtraServer? {
        val body = line.substringAfter("://")
        val json = b64(body) ?: return null
        val o = JSONObject(json)
        val host = o.optString("add", "")
        val port = o.optString("port", "0").toIntOrNull() ?: 0
        val ps = o.optString("ps", "")
        val cc = if (o.optString("id", "").isNotBlank()) guessCountry(ps) else guessCountry(ps)
        return ExtraServer(name = ps.ifBlank { host }, protocol = "vmess", host = host, port = port, country = cc, raw = line)
    }

    private fun parseUri(line: String, protocol: String): ExtraServer? {
        val body = line.substringAfter("://")
        val frag = body.substringAfter('#', "")
        val name = if (frag.isBlank()) "" else try {
            URLDecoder.decode(frag, "UTF-8")
        } catch (e: Exception) {
            frag
        }
        val core = body.substringBefore('#').substringBefore('?')
        val hostPort = core.substringAfterLast('@').substringBefore('/')
        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            host = hostPort.substringAfter('[').substringBefore(']')
            port = hostPort.substringAfter("]:", "").toIntOrNull() ?: 0
        } else {
            host = hostPort.substringBefore(':')
            port = hostPort.substringAfter(':', "").toIntOrNull() ?: 0
        }
        if (host.isBlank() || port <= 0) return null
        return ExtraServer(
            name = name.ifBlank { host }, protocol = protocol,
            host = host, port = port, country = guessCountry(name), raw = line
        )
    }

    private fun parseSs(line: String): ExtraServer? {
        var body = line.substringAfter("://")
        val frag = body.substringAfter('#', "")
        val name = if (frag.isBlank()) "" else try {
            URLDecoder.decode(frag, "UTF-8")
        } catch (e: Exception) {
            frag
        }
        body = body.substringBefore('#')
        var hostPort: String
        if (body.contains('@')) {
            hostPort = body.substringAfter('@')
        } else {
            val dec = b64(body) ?: return null
            hostPort = dec.substringAfter('@', dec)
        }
        val hp = hostPort.substringBefore('/').substringBefore('?')
        val host: String
        val port: Int
        if (hp.startsWith("[")) {
            host = hp.substringAfter('[').substringBefore(']')
            port = hp.substringAfter("]:", "").toIntOrNull() ?: 0
        } else {
            host = hp.substringBefore(':')
            port = hp.substringAfter(':', "").toIntOrNull() ?: 0
        }
        if (host.isBlank() || port <= 0) return null
        return ExtraServer(
            name = name.ifBlank { host }, protocol = "ss",
            host = host, port = port, country = guessCountry(name), raw = line
        )
    }

    /** اسم من تعليق (# أو //) وإلا المضيف. */
    private fun nameFromBlock(block: String, host: String): String {
        val m = Regex("(?m)^\\s*(?:#|//)\\s*(.+)$").find(block)
        val n = m?.groupValues?.get(1)?.trim()?.take(48) ?: ""
        return n.ifBlank { host }
    }

    /** يستخرج رمز الدولة من: علم إيموجي، [XX]/(XX)/-XX، رمز ISO، أو اسم دولة عربي. */
    fun guessCountry(s: String): String {
        val cps = s.codePoints().toArray()
        for (i in 0 until cps.size - 1) {
            val a = cps[i]
            val b = cps[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                return "" + ('A' + (a - 0x1F1E6)) + ('A' + (b - 0x1F1E6))
            }
        }
        // [DE] أو (DE) أو -DE- أو _DE_ أو DE-
        val bracketed = Regex("(?:\\[|\\(|[-_])([A-Za-z]{2})(?:\\]|\\)|[-_]|\\b)").find(s)
        if (bracketed != null) {
            val c = bracketed.groupValues[1].uppercase()
            if (c !in setOf("IP", "WG", "OV", "SS", "VM", "VL", "EX")) return c
        }
        val m = Regex("(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])").find(s)
        if (m != null) {
            val c = m.groupValues[1]
            if (c !in setOf("IP", "WG", "OV", "SS", "VM", "VL", "EX")) return c
        }
        val ar = Country.codeFromText(s)
        return ar
    }

    // ---------- JSON ----------
    fun toJson(s: ExtraServer): JSONObject = JSONObject()
        .put("id", s.id).put("name", s.name).put("protocol", s.protocol)
        .put("host", s.host).put("port", s.port).put("country", s.country)
        .put("raw", s.raw).put("source", s.source).put("lat", s.lat)
        .put("note", s.note).put("addedAt", s.addedAt)

    fun fromJson(o: JSONObject): ExtraServer? = try {
        ExtraServer(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", ""), protocol = o.optString("protocol", ""),
            host = o.optString("host", ""), port = o.optInt("port", 0),
            country = o.optString("country", ""), raw = o.optString("raw", ""),
            source = o.optString("source", ""), lat = o.optInt("lat", -1),
            note = o.optString("note", ""), addedAt = o.optLong("addedAt", System.currentTimeMillis())
        )
    } catch (e: Exception) {
        null
    }

    fun toJsonList(list: List<ExtraServer>): String {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        return a.toString()
    }
}
