package com.star.wgauto

import java.util.UUID

/**
 * ملف OpenVPN (.ovpn) — خزنة إدارة داخل التطبيق.
 * التشغيل الفعلي يتم عبر تطبيقات OpenVPN الخارجية (التطبيق لا يحوي نواة OpenVPN)،
 * لكن كل ملفاتك (WireGuard + OpenVPN) تُدار من مكان واحد بأناقة.
 */
data class OvpnProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val text: String,
    val remoteHost: String = "",
    val remotePort: Int = 1194,
    val proto: String = "udp",
    val cipher: String = "",
    val auth: String = "",
    val hasCa: Boolean = false,
    val hasCert: Boolean = false,
    val hasKey: Boolean = false,
    val needsAuth: Boolean = false
) {
    fun summary(): String = buildString {
        if (remoteHost.isNotEmpty()) append("$remoteHost:$remotePort")
        append(" • ${proto.uppercase()}")
        if (cipher.isNotEmpty()) append(" • $cipher")
        if (needsAuth) append(" • 🔑 يحتاج حساباً")
        if (!hasCa) append(" • ⚠ بلا شهادة مضمنة")
    }
}

object OvpnParser {
    /** يعيد (الملف) عند النجاح أو رسالة الخطأ بالعربية. */
    fun parse(name: String, rawText: String): Pair<OvpnProfile?, String?> {
        val text = rawText.replace("\uFEFF", "").replace("\r\n", "\n").replace("\r", "\n")
        var remoteHost = ""
        var remotePort = 1194
        var proto = "udp"
        var cipher = ""
        var authAlg = ""
        var hasCa = false
        var hasCert = false
        var hasKey = false
        var needsAuth = false
        var hasClient = false
        var dev = ""
        var inBlock = ""
        for (raw in text.lines()) {
            var line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("<") && !line.startsWith("</")) {
                val tag = line.trim('<', '>', ' ').lowercase()
                inBlock = tag
                when (tag) {
                    "ca" -> hasCa = true
                    "cert" -> hasCert = true
                    "key" -> hasKey = true
                }
                continue
            }
            if (line.startsWith("</")) {
                inBlock = ""
                continue
            }
            if (inBlock.isNotEmpty()) continue
            if (line.startsWith("#") || line.startsWith(";")) continue
            line = line.substringBefore("#").substringBefore(";").trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            when (parts[0].lowercase()) {
                "client" -> hasClient = true
                "dev" -> dev = parts.getOrNull(1)?.lowercase() ?: ""
                "remote" -> {
                    if (remoteHost.isEmpty() && parts.size >= 2) {
                        remoteHost = parts[1]
                        parts.getOrNull(2)?.toIntOrNull()?.let { remotePort = it }
                    }
                }
                "proto" -> {
                    val p = parts.getOrNull(1)?.lowercase() ?: ""
                    if (p.isNotEmpty()) proto = p.removeSuffix("6").removeSuffix("4")
                }
                "cipher" -> cipher = parts.getOrNull(1) ?: ""
                "auth" -> authAlg = parts.getOrNull(1) ?: ""
                "auth-user-pass" -> needsAuth = true
                "ca" -> hasCa = true
                "cert" -> hasCert = true
                "key" -> {
                    // سطر key منفرد يشير لملف خارجي — يُقبل مع تنبيه
                    hasKey = true
                }
            }
        }
        if (remoteHost.isEmpty()) {
            return null to "ملف غير صالح: لا يوجد سطر remote (تأكد أنه ملف عميل .ovpn وليس سيرفر)"
        }
        if (!hasClient) {
            return null to "ملف غير صالح: لا يحوي سطر client"
        }
        if (dev.isNotEmpty() && dev != "tun") {
            return null to "غير مدعوم على أندرويد: الوضع $dev (ندعم tun فقط)"
        }
        val cleanName = name.ifBlank { "$remoteHost-$remotePort" }
        val profile = OvpnProfile(
            name = cleanName, text = text.trim(),
            remoteHost = remoteHost, remotePort = remotePort, proto = proto,
            cipher = cipher, auth = authAlg,
            hasCa = hasCa, hasCert = hasCert, hasKey = hasKey, needsAuth = needsAuth
        )
        return profile to null
    }
}
