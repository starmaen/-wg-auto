package com.star.wgauto

/** علم الدولة + اسم عربي مختصر بجانب IP الخروج. يعمل أوفلاين بالكامل. */
object Country {
    private val arabic = mapOf(
        "SY" to "سوريا", "LB" to "لبنان", "TR" to "تركيا", "DE" to "ألمانيا",
        "NL" to "هولندا", "FR" to "فرنسا", "GB" to "بريطانيا", "US" to "أمريكا",
        "CA" to "كندا", "SE" to "السويد", "NO" to "النرويج", "FI" to "فنلندا",
        "PL" to "بولندا", "EU" to "أوروبا", "RO" to "رومانيا", "BG" to "بلغاريا", "UA" to "أوكرانيا",
        "RU" to "روسيا", "AE" to "الإمارات", "SA" to "السعودية", "IQ" to "العراق",
        "JO" to "الأردن", "EG" to "مصر", "CY" to "قبرص", "GR" to "اليونان",
        "IT" to "إيطاليا", "ES" to "إسبانيا", "CH" to "سويسرا", "AT" to "النمسا",
        "CZ" to "التشيك", "HU" to "المجر", "IR" to "إيران", "IQ" to "العراق",
        "QA" to "قطر", "KW" to "الكويت", "BH" to "البحرين", "OM" to "عُمان"
    )

    /** يحول رمز ISO مثل DE إلى علم 🇩🇪 */
    fun flag(cc: String): String {
        val c = cc.trim().uppercase().take(2)
        if (c.length != 2 || !c.all { it in 'A'..'Z' }) return "🏳️"
        val a = 0x1F1E6 + (c[0] - 'A')
        val b = 0x1F1E6 + (c[1] - 'A')
        return String(Character.toChars(a)) + String(Character.toChars(b))
    }

    fun name(cc: String): String {
        val c = cc.trim().uppercase()
        if (c.isEmpty()) return ""
        return arabic[c] ?: c
    }

    /** سطر جاهز للعرض: "🇩🇪 ألمانيا" أو "—" */
    fun label(cc: String): String {
        if (cc.isBlank()) return "—"
        return "${flag(cc)} ${name(cc)}"
    }

    /** سطر IP كامل: "1.2.3.4 • 🇩🇪 ألمانيا" */
    fun ipLine(ip: String, cc: String): String {
        if (ip.isEmpty()) return "—"
        if (cc.isBlank()) return ip
        return "$ip • ${label(cc)}"
    }
}
