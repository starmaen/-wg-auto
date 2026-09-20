package com.star.wgauto

import java.util.UUID

data class WgConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val text: String,
    val enabled: Boolean = true
)

data class DnsServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val primary: String,
    val secondary: String = "",
    val enabled: Boolean = true,
    /** اسم مضيف DNS-over-TLS (لتثبيت DNS النظام بالروت). */
    val dot: String = ""
) {
    fun ips(): List<String> = listOf(primary, secondary).filter { it.isNotBlank() }
}

data class AppSettings(
    val autoConfig: Boolean = true,
    val autoDns: Boolean = true,
    val autoMtu: Boolean = true,
    val selConfigId: String = "",
    val selDnsId: String = "",
    val selMtu: Int = 1420,
    val reselectOnNetwork: Boolean = true,
    val periodMin: Int = 30,
    val useRoot: Boolean = false,
    val backgroundScan: Boolean = true,
    val applyRoot: Boolean = false
)

data class Status(
    val running: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val activeConfig: String? = null,
    val activeDns: String? = null,
    val activeMtu: Int? = null,
    val latencyMs: Int? = null,
    val mbps: Double? = null,
    val message: String = ""
)

/** نتيجة فحص جهة واحدة (الشبكة الأصلية أو شبكة الـ VPN). */
data class SideInfo(
    val label: String = "",
    val iface: String = "",
    val bestDns: String = "",
    val dnsMs: Int = -1,
    val latencyMs: Int = -1,
    val pathMtu: Int = 0,
    val suggestedMtu: Int = 0,
    val ifaceMtu: Int = 0
)

data class NetInfo(
    val direct: SideInfo? = null,
    val vpn: SideInfo? = null,
    val vpnOwner: String = "",
    val time: String = ""
)

object Defaults {
    val dns: List<DnsServer> = listOf(
        DnsServer("def-cloudflare", "Cloudflare", "1.1.1.1", "1.0.0.1", dot = "one.one.one.one"),
        DnsServer("def-cloudflare-sec", "Cloudflare (حماية)", "1.1.1.2", "1.0.0.2", dot = "security.cloudflare-dns.com"),
        DnsServer("def-google", "Google", "8.8.8.8", "8.8.4.4", dot = "dns.google"),
        DnsServer("def-quad9", "Quad9", "9.9.9.9", "149.112.112.112", dot = "dns.quad9.net"),
        DnsServer("def-opendns", "OpenDNS", "208.67.222.222", "208.67.220.220"),
        DnsServer("def-adguard", "AdGuard", "94.140.14.14", "94.140.15.15", dot = "dns.adguard-dns.com"),
        DnsServer("def-cleanbrowsing", "CleanBrowsing", "185.228.168.9", "185.228.169.9", dot = "security-filter-dns.cleanbrowsing.org"),
        DnsServer("def-controld", "Control D", "76.76.2.0", "76.76.10.0"),
        DnsServer("def-nextdns", "NextDNS", "45.90.28.0", "45.90.30.0", dot = "dns.nextdns.io"),
        DnsServer("def-comodo", "Comodo Secure", "8.26.56.26", "8.20.247.20"),
        DnsServer("def-yandex", "Yandex", "77.88.8.8", "77.88.8.1"),
        DnsServer("def-verisign", "Verisign", "64.6.64.6", "64.6.65.6"),
        DnsServer("def-level3", "Level3", "4.2.2.1", "4.2.2.2"),
        DnsServer("def-dnswatch", "DNS.WATCH", "84.200.69.80", "84.200.70.40"),
        DnsServer("def-alternate", "Alternate DNS", "76.76.19.19", "76.223.122.150"),
        DnsServer("def-dnssb", "DNS.SB", "185.222.222.222", "45.11.45.11", dot = "dot.sb"),
        DnsServer("def-mullvad", "Mullvad", "194.242.2.2", "", dot = "dns.mullvad.net")
    )
}

/** قيم MTU من 1000 إلى 1500 بخطوة 10، مع القيم التقنية المعروفة. */
object MtuCatalog {
    private val notes = mapOf(
        1000 to "حد أدنى تجريبي",
        1280 to "الحد الأدنى لـ IPv6 (RFC 8200)",
        1400 to "آمن للشبكات الخلوية",
        1412 to "WireGuard فوق PPPoE (IPv6) = 1492-80",
        1420 to "الافتراضي في WireGuard = 1500-80",
        1432 to "WireGuard فوق PPPoE (IPv4) = 1492-60",
        1440 to "WireGuard IPv4 على إيثرنت = 1500-60",
        1480 to "أنفاق 6in4",
        1492 to "PPPoE",
        1500 to "إيثرنت القياسي"
    )

    val values: List<Int> =
        ((1000..1500 step 10).toList() + listOf(1412, 1432, 1492)).distinct().sorted()

    fun label(v: Int): String = notes[v]?.let { "$v — $it" } ?: v.toString()

    /** WireGuard يضيف 60 بايت (IPv4) أو 80 بايت (IPv6) فوق الحزمة. */
    fun tunnelMtuFromPath(pathMtu: Int, ipv6: Boolean): Int =
        (pathMtu - if (ipv6) 80 else 60).coerceIn(1000, 1500)
}
