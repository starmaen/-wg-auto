package com.star.wgauto

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address

/** قراءة الشبكات الحالية (الأصلية وشبكات الـ VPN من أي تطبيق). */
object NetUtil {
    class Info(
        val network: Network,
        val iface: String,
        val mtu: Int,
        val label: String,
        val isVpn: Boolean,
        val validated: Boolean,
        val ips: String
    )

    @Suppress("DEPRECATION")
    fun all(cm: ConnectivityManager): List<Info> = cm.allNetworks.mapNotNull { n ->
        val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
        val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        if (!isVpn && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
        val lp = cm.getLinkProperties(n)
        val label = when {
            isVpn -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "واي فاي"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "بيانات الجوال"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "إيثرنت"
            else -> "أخرى"
        }
        val mtu = if (Build.VERSION.SDK_INT >= 29) (lp?.mtu ?: 0) else 0
        val ips = lp?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.mapNotNull { it.hostAddress }
            ?.sorted()
            ?.joinToString(",") ?: ""
        Info(
            n, lp?.interfaceName ?: "", mtu, label, isVpn,
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), ips
        )
    }

    /** الشبكة الفيزيائية المعتمدة (المتحقَّق منها أولاً، والواي فاي قبل البيانات). */
    fun underlying(nets: List<Info>): Info? =
        nets.filter { !it.isVpn }
            .sortedWith(compareByDescending<Info> { it.validated }.thenBy { if (it.label == "بيانات الجوال") 1 else 0 })
            .firstOrNull()
}
