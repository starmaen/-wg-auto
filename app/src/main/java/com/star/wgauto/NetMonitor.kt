package com.star.wgauto

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * يراقب الشبكة الأصلية وأي VPN (من هذا التطبيق أو من غيره).
 * onChange("net") عند تبدّل الشبكة/IP، وonChange("vpn") عند ظهور أو اختفاء VPN.
 */
class NetMonitor(ctx: Context, private val onChange: (String) -> Unit) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private var lastUnder = ""
    private var lastVpn = ""

    private fun newCallback() = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = check()
        override fun onLost(network: Network) = check()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = check()
    }

    // يلزم كائنان منفصلان: أندرويد لا يقبل تسجيل نفس الـ callback مرتين
    private val cbPhysical = newCallback()
    private val cbVpn = newCallback()

    fun start() {
        val (u, v) = signatures()
        lastUnder = u
        lastVpn = v
        // الشبكات الفيزيائية
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            cbPhysical
        )
        // شبكات VPN
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            cbVpn
        )
    }

    fun stop() {
        for (c in listOf(cbPhysical, cbVpn)) {
            try {
                cm.unregisterNetworkCallback(c)
            } catch (e: Exception) {
                // تجاهل
            }
        }
    }

    @Synchronized
    private fun check() {
        val (u, v) = signatures()
        val vpnChanged = v != lastVpn
        val underChanged = u != lastUnder
        if (!vpnChanged && !underChanged) return
        lastUnder = u
        lastVpn = v
        onChange(if (vpnChanged) "vpn" else "net")
    }

    private fun signatures(): Pair<String, String> {
        val nets = NetUtil.all(cm)
        val under = nets.filter { !it.isVpn }.map { "${it.label}:${it.ips}" }.sorted().joinToString("|")
        val vpn = nets.filter { it.isVpn }.map { it.iface }.sorted().joinToString("|")
        return under to vpn
    }
}
