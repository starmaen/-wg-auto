package com.star.wgauto

/** أدوات معالجة نص كونفيج WireGuard (تنظيف + استبدال MTU وDNS). */
object ConfigText {
    // مفاتيح wg-quick التي لا تدعمها مكتبة الأندرويد
    private val unsupported = setOf("postup", "postdown", "preup", "predown", "table", "saveconfig", "fwmark")

    // المفاتيح المسموحة فقط — أي سطر غريب من مواقع التحميل (تذاكر/تعليقات/خيارات إضافية) يُسقط تلقائياً
    // فلا يضطر المستخدم لتعديل الملف يدوياً (مشكلة كونفيجات Proton).
    private val allowedInterface = setOf(
        "privatekey", "address", "dns", "mtu", "listenport",
        "includedapplications", "excludedapplications"
    )
    private val allowedPeer = setOf(
        "publickey", "presharedkey", "allowedips", "endpoint", "persistentkeepalive"
    )

    /** يزيل BOM ويوحّد نهايات الأسطر ويبقي فقط الأقسام والمفاتيح المعروفة. */
    fun sanitize(text: String): String {
        var t = text.replace("\uFEFF", "").replace("\r\n", "\n").replace("\r", "\n")
        val out = ArrayList<String>()
        var section = ""
        for (raw in t.lines()) {
            var line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith(";")) continue
            line = line.substringBefore("#").substringBefore(";").trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                val name = line.trim('[', ']', ' ').trim().lowercase()
                if (name == "interface" || name == "peer") {
                    section = name
                    out += if (name == "interface") "[Interface]" else "[Peer]"
                }
                continue
            }
            if (!line.contains("=")) continue
            val key = line.substringBefore("=").trim().lowercase()
            if (key in unsupported) continue
            val ok = when (section) {
                "interface" -> key in allowedInterface
                "peer" -> key in allowedPeer
                else -> false
            }
            if (!ok) continue
            // تطبيع المسافات حول = (مهم لملفات Proton المحملة من المتصفح)
            val value = line.substringAfter("=").trim().replace(Regex("\\s+"), " ")
            if (value.isEmpty()) continue
            out += "${line.substringBefore("=").trim()} = $value"
        }
        return out.joinToString("\n")
    }

    /** هل DNS داخلي خاص بـ Proton (10.x وما شابه)؟ التطبيق يحقن DNS عاماً بنفسه. */
    fun isPrivateDns(ip: String): Boolean {
        val v = ip.trim()
        if (v.startsWith("10.")) return true
        if (v.startsWith("192.168.")) return true
        if (v.startsWith("fd") || v.startsWith("fc")) return true
        val m = Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").find(v)
        if (m != null) return true
        return false
    }

    /**
     * تنظيف خاص عند الاستيراد (خاصة Proton):
     * - يحذف سطر DNS إن كان داخلياً (10.2.0.1) حتى لا يتعارض — التطبيق يحقن DNS فعلياً عند الاتصال.
     * - يضيف PersistentKeepalive=25 إن غاب (ضروري خلف NAT الجوال).
     * يعيد النص النظيف الجاهز للحفظ.
     */
    fun normalizeForImport(text: String): String {
        val lines = sanitize(text).lines().toMutableList()
        // احذف DNS الداخلي فقط، وأبقِ العام إن وُجد
        val filtered = lines.filter { l ->
            val k = l.substringBefore("=").trim().lowercase()
            if (k != "dns") true
            else {
                val ips = l.substringAfter("=").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                // إن كانت كل العناوين داخلية → احذف السطر كله
                !(ips.isNotEmpty() && ips.all { isPrivateDns(it) })
            }
        }.toMutableList()
        // أضف Keepalive للـ Peer إن غاب
        val hasKeepalive = filtered.any { it.substringBefore("=").trim().equals("persistentkeepalive", true) }
        if (!hasKeepalive) {
            val idx = filtered.indexOfLast { it.trim().equals("[Peer]", true) }
            if (idx >= 0) filtered.add(idx + 1, "PersistentKeepalive = 25")
        }
        return filtered.joinToString("\n")
    }

    /** نسخة IPv4 فقط: تحذف عناوين IPv6 ومسارات ::/0 (حل خطأ Cannot set address على أجهزة بلا IPv6). */
    fun stripIpv6(text: String): String {
        val out = ArrayList<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            val key = line.substringBefore("=").trim().lowercase()
            if (key == "address") {
                val v4 = line.substringAfter("=").split(",").map { it.trim() }
                    .filter { it.isNotEmpty() && !it.contains(":") }
                if (v4.isEmpty()) continue
                out += "Address = ${v4.joinToString(", ")}"
            } else if (key == "allowedips") {
                val v4 = line.substringAfter("=").split(",").map { it.trim() }
                    .filter { it.isNotEmpty() && !it.contains(":") }
                if (v4.isEmpty()) out += "AllowedIPs = 0.0.0.0/0"
                else out += "AllowedIPs = ${v4.joinToString(", ")}"
            } else {
                out += raw
            }
        }
        return out.joinToString("\n")
    }

    /** onlyApp: يجعل النفق خاصاً بتطبيق واحد (للفحص) فلا يتأثر اتصال بقية التطبيقات. */
    fun override(text: String, mtu: Int?, dns: List<String>, onlyApp: String? = null): String {
        val out = StringBuilder()
        var section = ""
        var injected = false

        fun inject() {
            if (injected) return
            if (mtu != null) out.append("MTU = ").append(mtu).append('\n')
            if (dns.isNotEmpty()) out.append("DNS = ").append(dns.joinToString(", ")).append('\n')
            if (onlyApp != null) out.append("IncludedApplications = ").append(onlyApp).append('\n')
            injected = true
        }

        for (raw in sanitize(text).lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                if (section == "interface") inject()
                section = line.trim('[', ']').trim().lowercase()
                out.append(line).append('\n')
                continue
            }
            if (section == "interface") {
                val key = line.substringBefore("=").trim().lowercase()
                if ((key == "mtu" && mtu != null) || (key == "dns" && dns.isNotEmpty())) continue
                if (onlyApp != null && (key == "includedapplications" || key == "excludedapplications")) continue
            }
            out.append(line).append('\n')
        }
        if (section == "interface") inject()
        return out.toString()
    }

    /** يستخرج قيمة MTU المكتوبة في الكونفيج نفسها إن وُجدت. */
    fun configuredMtu(text: String): Int? {
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.substringBefore("=").trim().equals("mtu", ignoreCase = true)) {
                return line.substringAfter("=").trim().toIntOrNull()
            }
        }
        return null
    }
}

