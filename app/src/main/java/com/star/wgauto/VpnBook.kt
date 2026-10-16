package com.star.wgauto

/**
 * تكامل VPNBook للـ WireGuard المجاني (بلا حساب):
 * 10 سيرفرات أجنبية حقيقية (أمريكا/كندا/بريطانيا/ألمانيا/فرنسا) × 4 منافذ UDP
 * (443 الأفضل لتجاوز الجدران، ثم 123، ثم 80، ثم 25018 للسرعة).
 *
 * ⚠ صدق تقني: التوليد نفسه يتم في موقعهم (زر Generate خلف فحص anti-bot
 * يمنع الأتمتة — وتجاوزه إساءة استخدام تُغلق الخدمة). دور التطبيق:
 * اختيار أسرع سيرفر (ping) + فتح المولد + استيراد .conf + تتبع انتهاء 7 أيام.
 * WARP يبقى المولد الأوتوماتيكي الوحيد داخل التطبيق.
 */
object VpnBook {
    data class Server(val host: String, val country: String, val label: String)
    data class Port(val port: Int, val label: String)

    const val GEN_URL = "https://www.vpnbook.com/freevpn/wireguard-vpn"
    const val OVPN_URL = "https://www.vpnbook.com/freevpn"
    const val OVPN_USER = "vpnbook"

    /** كلمة سر OpenVPN الأسبوعية منشورة في الصفحة نفسها (JSON-LD). */
    suspend fun fetchPassword(network: android.net.Network? = null): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = java.net.URL("https://www.vpnbook.com/freevpn")
                val c = (network?.openConnection(url) ?: url.openConnection()) as java.net.HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 15000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
                val raw = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                // "Username: vpnbook. Password: e5c65ph"
                Regex("Password:\\s*([A-Za-z0-9]{4,20})").find(raw)?.groupValues?.getOrNull(1)
            } catch (e: Exception) { null }
        }

    /** دولة مضيف VPNBook (تشمل بولندا وكندا الإضافية لنسخة OpenVPN). */
    fun countryOf(host: String): String {
        servers.firstOrNull { it.host.equals(host, true) }?.let { return it.country }
        // احتياط للمضيفات الجديدة (pl134.vpnbook.com ...): أول حرفين بعد البادئة
        val h = host.lowercase().substringBefore(".")
        val cc = when {
            h.startsWith("pl") -> "PL"
            h.startsWith("us") -> "US"
            h.startsWith("ca") -> "CA"
            h.startsWith("uk") -> "GB"
            h.startsWith("de") -> "DE"
            h.startsWith("fr") -> "FR"
            h.startsWith("eu") -> "EU"
            h.startsWith("euro") -> "EU"
            else -> ""
        }
        if (host.endsWith("vpnbook.com", true)) return cc
        // مضيف IP مباشر لسيرفر VPNBook مستورد: نستدل من الاسم المحفوظ إن أمكن
        return cc
    }

    /** هل هذا سيرفر VPNBook-OpenVPN مستورد (مفتاح vbk-...)؟ */
    fun isBookKey(key: String): Boolean = key.startsWith("vbk-")

    /** دولة العرض لسيرفر قائمة VPN: المخزنة أولاً ثم الاستدلال من المضيف. */
    fun displayCountry(host: String, stored: String): String {
        if (stored.isNotBlank()) return stored.uppercase().take(2)
        return countryOf(host)
    }

    /** بادئة عرض موحدة: 📖 + علم + اسم الدولة — لسيرفرات VPNBook فقط. */
    fun bookBadge(host: String, stored: String): String {
        val cc = displayCountry(host, stored)
        return if (cc.isBlank()) "📖 VPNBook"
        else "📖 ${Country.flag(cc)} ${Country.name(cc)}"
    }

    val servers: List<Server> = listOf(
        Server("us16.vpnbook.com", "US", "أمريكا 1"),
        Server("us178.vpnbook.com", "US", "أمريكا 2"),
        Server("ca149.vpnbook.com", "CA", "كندا 1"),
        Server("ca196.vpnbook.com", "CA", "كندا 2"),
        Server("uk205.vpnbook.com", "GB", "بريطانيا 1"),
        Server("uk68.vpnbook.com", "GB", "بريطانيا 2"),
        Server("de20.vpnbook.com", "DE", "ألمانيا 1"),
        Server("de220.vpnbook.com", "DE", "ألمانيا 2"),
        Server("fr200.vpnbook.com", "FR", "فرنسا 1"),
        Server("fr231.vpnbook.com", "FR", "فرنسا 2")
    )

    val ports: List<Port> = listOf(
        Port(443, "443 — الأفضل لتجاوز الجدران (جرّبه أولاً)"),
        Port(123, "123 — مفتوح غالباً في الشبكات المقيدة"),
        Port(80, "80 — بديل إن حُجب 443"),
        Port(25018, "25018 — الأسرع إن لم يُحجب شيء")
    )

    const val EXPIRY_DAYS = 7L

    fun isVpnBook(text: String, name: String): Boolean {
        val ep = Probes.parseEndpoint(text)?.first ?: ""
        return ep.contains("vpnbook.com", ignoreCase = true) ||
            name.contains("vpnbook", ignoreCase = true)
    }

    /** نص الانتهاء: null إن بلا انتهاء. */
    fun expiryText(expiresAt: Long, now: Long = System.currentTimeMillis()): String? {
        if (expiresAt <= 0) return null
        val left = expiresAt - now
        if (left <= 0) return "⚠ انتهى — ولّد كونفيجاً جديداً من VPNBook"
        val days = left / (24L * 3600 * 1000)
        val hours = (left % (24L * 3600 * 1000)) / (3600 * 1000)
        return when {
            days >= 2 -> "⏳ ينتهي بعد $days أيام"
            days >= 1 -> "⏳ ينتهي بعد يوم وساعات"
            else -> "⏳ ينتهي بعد ~$hours ساعة"
        }
    }

    fun expired(expiresAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        expiresAt > 0 && expiresAt <= now

    fun expiringSoon(expiresAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        expiresAt > 0 && expiresAt - now < 2L * 24 * 3600 * 1000
}
