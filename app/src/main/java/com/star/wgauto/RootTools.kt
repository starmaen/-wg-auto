package com.star.wgauto

import java.util.concurrent.TimeUnit

/** أوامر الروت لتثبيت القيم المثلى (اختيارية). */
object RootTools {
    private val ifaceRe = Regex("[A-Za-z0-9_.-]{1,15}")
    private val hostRe = Regex("[A-Za-z0-9.-]{1,253}")
    private val modes = setOf("off", "opportunistic", "hostname")

    fun su(cmd: String): Pair<Int, String> = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(8, TimeUnit.SECONDS)) {
            p.destroy()
            -1 to out
        } else {
            p.exitValue() to out
        }
    } catch (e: Exception) {
        -1 to ""
    }

    fun getIfaceMtu(iface: String): Int? {
        if (!ifaceRe.matches(iface)) return null
        val (code, out) = su("cat /sys/class/net/$iface/mtu")
        return if (code == 0) out.trim().toIntOrNull() else null
    }

    fun setIfaceMtu(iface: String, mtu: Int): Boolean {
        if (!ifaceRe.matches(iface) || mtu !in 576..9000) return false
        return su("ip link set dev $iface mtu $mtu").first == 0
    }

    fun getPrivateDns(): Pair<String, String> {
        val m = su("settings get global private_dns_mode").second.trim()
        val s = su("settings get global private_dns_specifier").second.trim()
        return m to s
    }

    fun setPrivateDns(host: String): Boolean {
        if (!hostRe.matches(host)) return false
        return su(
            "settings put global private_dns_specifier $host && " +
                "settings put global private_dns_mode hostname"
        ).first == 0
    }

    fun restorePrivateDns(mode: String, spec: String): Boolean {
        val m = if (mode in modes) "settings put global private_dns_mode $mode"
        else "settings delete global private_dns_mode"
        val s = if (hostRe.matches(spec) && spec != "null") "settings put global private_dns_specifier $spec"
        else "settings delete global private_dns_specifier"
        return su("$s; $m").first == 0
    }

    // ---------- قاطع الطوارئ (Kill Switch) ----------
    // يمنع أي حركة تخرج خارج نفق WireGuard (tun+) أو المحلي (lo) طالما هو مفعّل،
    // بصرف النظر عن حالة النفق — إن سقط النفق تنقطع الشبكة كلها بدل تسريب IP الحقيقي.
    private const val CHAIN = "wgauto_ks"

    fun killSwitchEnable(): Boolean {
        val cmds = listOf(
            "iptables -D OUTPUT -j $CHAIN 2>/dev/null",
            "iptables -F $CHAIN 2>/dev/null",
            "iptables -N $CHAIN 2>/dev/null",
            "iptables -A $CHAIN -o lo -j RETURN",
            "iptables -A $CHAIN -o tun+ -j RETURN",
            "iptables -A $CHAIN -p udp --dport 53 -j RETURN",   // يسمح بحلّ الأسماء أثناء التبديل بين الكونفيجات
            "iptables -A $CHAIN -j DROP",
            "iptables -I OUTPUT 1 -j $CHAIN"
        )
        return su(cmds.joinToString(" ; ")).first == 0
    }

    fun killSwitchDisable(): Boolean {
        val cmds = listOf(
            "iptables -D OUTPUT -j $CHAIN 2>/dev/null",
            "iptables -F $CHAIN 2>/dev/null",
            "iptables -X $CHAIN 2>/dev/null"
        )
        su(cmds.joinToString(" ; "))
        return true   // تنظيف؛ لا نفشل حتى لو لم تكن القواعد موجودة أصلاً
    }

    fun killSwitchActive(): Boolean = su("iptables -L OUTPUT -n | grep -q $CHAIN").first == 0
}
