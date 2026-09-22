package com.star.wgauto

/** أدوات معالجة نص كونفيج WireGuard (تنظيف + استبدال MTU وDNS). */
object ConfigText {
    // مفاتيح wg-quick التي لا تدعمها مكتبة الأندرويد
    private val unsupported = setOf("postup", "postdown", "preup", "predown", "table", "saveconfig", "fwmark")

    fun sanitize(text: String): String =
        text.lines().map { it.trim() }
            .filter { line ->
                val key = line.substringBefore("=").trim().lowercase()
                !(line.contains("=") && key in unsupported)
            }
            .joinToString("\n")

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

