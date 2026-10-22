package com.star.wgauto

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

/**
 * يبني إعداد Xray (JSON) من رابط سيرفر مستورد.
 * المدعوم: vmess و vless و trojan و shadowsocks.
 * القالب يضمّ inbound من نوع tun (يقرأ واصف tun من VpnService) + dns-out
 * حتى يُمرَّر استعلام DNS عبر النفق بدل UDP المباشر.
 */
object XrayConfig {

    fun build(s: ExtraServer): String {
        val raw = s.raw.trim()
        val outbound = when (s.protocol.lowercase()) {
            "vmess" -> vmess(raw)
            "vless" -> vless(raw)
            "trojan" -> trojan(raw)
            "ss" -> shadowsocks(raw)
            else -> throw IllegalArgumentException("بروتوكول غير مدعوم: ${s.protocol}")
        }

        val inbounds = JSONArray().put(
            JSONObject()
                .put("tag", "tun")
                .put("protocol", "tun")
                .put("settings", JSONObject().put("name", "xray0").put("mtu", 1500).put("userLevel", 8))
                .put(
                    "sniffing", JSONObject().put("enabled", true)
                        .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                )
        )

        val outbounds = JSONArray()
            .put(outbound)
            .put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            .put(
                JSONObject().put("tag", "block").put("protocol", "blackhole")
                    .put("settings", JSONObject().put("response", JSONObject().put("type", "http")))
            )

        val routing = JSONObject()
            .put("domainStrategy", "AsIs")
            .put(
                "rules", JSONArray().put(
                    JSONObject()
                        .put("type", "field")
                        .put("inboundTag", JSONArray().put("tun"))
                        .put("port", "53")
                        .put("outboundTag", "dns-out")
                )
            )

        // DoH عبر النفق: لا يعتمد على تمرير UDP من السيرفر.
        val dns = JSONObject().put(
            "servers", JSONArray().put("https://1.1.1.1/dns-query").put("https://8.8.8.8/dns-query")
        )

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", routing)
            .put("dns", dns)
            .toString()
    }

    // ---------- البروتوكولات ----------

    private fun vmess(uri: String): JSONObject {
        val body = uri.substringAfter("://").substringBefore('#')
        val json = b64(body) ?: throw IllegalArgumentException("رابط VMess غير صالح")
        val o = JSONObject(json)
        val host = o.optString("add").trim()
        val port = o.optString("port", "0").toIntOrNull() ?: 0
        if (host.isBlank() || port <= 0) throw IllegalArgumentException("عنوان VMess ناقص")

        val net = o.optString("net", "tcp").ifBlank { "tcp" }.lowercase()
        val headerType = o.optString("type", "none").ifBlank { "none" }.lowercase()
        val tls = o.optString("tls", "").ifBlank { o.optString("security", "") }
        val sni = o.optString("sni", "")
        val hostHeader = o.optString("host", "")
        val path = o.optString("path", "/").ifBlank { "/" }
        val fp = o.optString("fp", "")
        val alpn = o.optString("alpn", "")
        val aid = o.optString("aid", "0").toIntOrNull() ?: 0
        val scy = o.optString("scy", "auto").ifBlank { "auto" }
        val id = o.optString("id").trim()
        if (id.isBlank()) throw IllegalArgumentException("VMess بلا معرّف")

        val settings = JSONObject().put(
            "vnext", JSONArray().put(
                JSONObject().put("address", host).put("port", port)
                    .put(
                        "users", JSONArray().put(
                            JSONObject().put("id", id).put("alterId", aid)
                                .put("security", scy).put("level", 8)
                        )
                    )
            )
        )

        val security = when {
            tls.equals("reality", true) -> "reality"
            tls.equals("tls", true) || tls.equals("xtls", true) -> "tls"
            else -> "none"
        }

        return outbound(
            "vmess", settings,
            stream(
                network = net, security = security, sni = sni, fp = fp, alpn = alpn,
                host = hostHeader, path = path, headerType = headerType,
                serviceName = o.optString("serviceName", ""), mode = o.optString("mode", ""),
                publicKey = o.optString("pbk", ""), shortId = o.optString("sid", ""),
                spiderX = o.optString("spx", "")
            )
        )
    }

    private fun vless(uri: String): JSONObject {
        val u = Uri.parse(uri)
        val id = (u.userInfo ?: "").trim()
        val host = (u.host ?: "").trim()
        val port = u.port
        if (id.isBlank() || host.isBlank() || port <= 0) throw IllegalArgumentException("عنوان VLESS ناقص")

        fun q(n: String): String = u.getQueryParameter(n).orEmpty()

        val flow = q("flow")
        val settings = JSONObject().put(
            "vnext", JSONArray().put(
                JSONObject().put("address", host).put("port", port)
                    .put(
                        "users", JSONArray().put(
                            JSONObject().put("id", id).put("encryption", "none")
                                .put("flow", flow).put("level", 8)
                        )
                    )
            )
        )

        val security = q("security").ifBlank { "none" }
        return outbound(
            "vless", settings,
            stream(
                network = q("type").ifBlank { "tcp" }, security = security,
                sni = q("sni"), fp = q("fp"), alpn = q("alpn"),
                host = q("host"), path = q("path").ifBlank { "/" },
                headerType = q("headerType").ifBlank { "none" },
                serviceName = q("serviceName"), mode = q("mode"),
                publicKey = q("pbk"), shortId = q("sid"), spiderX = q("spx")
            )
        )
    }

    private fun trojan(uri: String): JSONObject {
        val u = Uri.parse(uri)
        val password = (u.userInfo ?: "").trim()
        val host = (u.host ?: "").trim()
        val port = u.port
        if (password.isBlank() || host.isBlank() || port <= 0) throw IllegalArgumentException("عنوان Trojan ناقص")

        fun q(n: String): String = u.getQueryParameter(n).orEmpty()

        val settings = JSONObject().put(
            "servers", JSONArray().put(
                JSONObject().put("address", host).put("port", port).put("password", password)
            )
        )

        val security = q("security").ifBlank { "tls" }
        return outbound(
            "trojan", settings,
            stream(
                network = q("type").ifBlank { "tcp" }, security = security,
                sni = q("sni"), fp = q("fp"), alpn = q("alpn"),
                host = q("host"), path = q("path").ifBlank { "/" },
                headerType = q("headerType").ifBlank { "none" },
                serviceName = q("serviceName"), mode = q("mode"),
                publicKey = q("pbk"), shortId = q("sid"), spiderX = q("spx")
            )
        )
    }

    private fun shadowsocks(uri: String): JSONObject {
        var body = uri.substringAfter("://").substringBefore('#')
        val qi = body.indexOf('?')
        val query = if (qi >= 0) body.substring(qi + 1) else ""
        if (qi >= 0) body = body.substring(0, qi)
        if (query.contains("plugin=")) throw IllegalArgumentException("Shadowsocks بملحق غير مدعوم")

        var method: String
        var password: String
        val hostPort: String
        val at = body.lastIndexOf('@')
        if (at >= 0) {
            val up = decode(body.substring(0, at))
            val dec = b64(up) ?: up
            method = dec.substringBefore(":").trim()
            password = dec.substringAfter(":", "").trim()
            hostPort = body.substring(at + 1)
        } else {
            val dec = b64(body) ?: throw IllegalArgumentException("رابط Shadowsocks غير صالح")
            method = dec.substringBefore(":").trim()
            password = dec.substringAfter(":", "").substringBefore("@").trim()
            hostPort = dec.substringAfter("@")
        }
        if (method.isBlank() || password.isBlank()) throw IllegalArgumentException("بيانات Shadowsocks ناقصة")

        val (host, port) = splitHostPort(hostPort)
        if (host.isBlank() || port <= 0) throw IllegalArgumentException("عنوان Shadowsocks ناقص")

        val settings = JSONObject().put(
            "servers", JSONArray().put(
                JSONObject().put("address", host).put("port", port)
                    .put("method", method).put("password", password)
            )
        )
        return outbound("shadowsocks", settings, stream(network = "tcp", security = "none"))
    }

    // ---------- القطع المشتركة ----------

    private fun outbound(protocol: String, settings: JSONObject, stream: JSONObject): JSONObject =
        JSONObject()
            .put("tag", "proxy")
            .put("protocol", protocol)
            .put("settings", settings)
            .put("streamSettings", stream)
            .put("mux", JSONObject().put("enabled", false))

    private fun stream(
        network: String, security: String, sni: String = "", fp: String = "", alpn: String = "",
        host: String = "", path: String = "/", headerType: String = "none",
        serviceName: String = "", mode: String = "", publicKey: String = "",
        shortId: String = "", spiderX: String = ""
    ): JSONObject {
        val ss = JSONObject()
        val net = when (network.lowercase()) {
            "http", "h2" -> "h2"
            "" -> "tcp"
            else -> network.lowercase()
        }
        ss.put("network", net)

        val firstHost = host.split(",").map { it.trim() }.firstOrNull().orEmpty()
        val ht = headerType.lowercase()

        when (net) {
            "ws" -> {
                val ws = JSONObject().put("path", path.ifBlank { "/" })
                if (firstHost.isNotBlank()) ws.put("headers", JSONObject().put("Host", firstHost))
                ss.put("wsSettings", ws)
            }
            "grpc" -> ss.put(
                "grpcSettings",
                JSONObject().put("serviceName", serviceName).put("multiMode", mode == "multi")
            )
            "h2" -> ss.put(
                "httpSettings",
                JSONObject().put("host", JSONArray().put(firstHost.ifBlank { "localhost" }))
                    .put("path", path.ifBlank { "/" })
            )
            "httpupgrade" -> ss.put(
                "httpupgradeSettings",
                JSONObject().put("host", firstHost).put("path", path.ifBlank { "/" })
            )
            "tcp" -> {
                if (ht == "http") {
                    ss.put(
                        "tcpSettings", JSONObject().put(
                            "header", JSONObject().put("type", "http")
                                .put(
                                    "request", JSONObject()
                                        .put("version", "1.1")
                                        .put("method", "GET")
                                        .put("path", JSONArray().put(path.ifBlank { "/" }))
                                        .put(
                                            "headers",
                                            JSONObject().put("Host", JSONArray().put(firstHost))
                                        )
                                )
                        )
                    )
                } else {
                    ss.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "none")))
                }
            }
            "kcp" -> ss.put(
                "kcpSettings", JSONObject().put("mtu", 1350).put("tti", 20)
                    .put("header", JSONObject().put("type", ht.ifBlank { "none" }))
            )
            else -> {}
        }

        val sec = when (security.lowercase()) {
            "tls", "xtls" -> "tls"
            "reality" -> "reality"
            else -> "none"
        }
        if (sec == "none") {
            ss.put("security", "none")
            return ss
        }

        val t = JSONObject()
        val serverName = sni.ifBlank { firstHost }
        if (serverName.isNotBlank()) t.put("serverName", serverName)
        if (fp.isNotBlank()) t.put("fingerprint", fp)
        val alpns = alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (alpns.isNotEmpty()) t.put("alpn", JSONArray(alpns))
        t.put("allowInsecure", false)

        ss.put("security", sec)
        if (sec == "reality") {
            t.put("publicKey", publicKey)
            if (shortId.isNotBlank()) t.put("shortId", shortId)
            t.put("spiderX", spiderX.ifBlank { "/" })
            ss.put("realitySettings", t)
        } else {
            ss.put("tlsSettings", t)
        }
        return ss
    }

    // ---------- أدوات ----------

    private fun splitHostPort(s: String): Pair<String, Int> {
        val t = s.trim()
        return if (t.startsWith("[")) {
            val host = t.substringAfter('[').substringBefore(']')
            val port = t.substringAfter("]:", "").toIntOrNull() ?: 0
            host to port
        } else {
            val host = t.substringBefore(':')
            val port = t.substringAfter(':', "0").toIntOrNull() ?: 0
            host to port
        }
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    private fun b64(s: String): String? = try {
        val t = s.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        val pad = (4 - t.length % 4) % 4
        String(Base64.getDecoder().decode(t + "=".repeat(pad)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
