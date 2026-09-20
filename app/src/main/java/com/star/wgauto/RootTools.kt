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
}
