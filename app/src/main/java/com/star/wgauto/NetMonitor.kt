package com.star.wgauto

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address

/** يراقب تبدّل الشبكة الأساسية (واي فاي ↔ بيانات) أو تغيّر عنوان IP. */
class NetMonitor(ctx: Context, private val onChange: () -> Unit) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private var last: String? = null

    private val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = check()
        override fun onLost(network: Network) = check()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = check()
    }

    fun start() {
        last = signature()
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(req, cb)
    }

    fun stop() {
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // تجاهل
        }
    }

    private fun check() {
        val s = signature()
        if (s != last) {
            last = s
            onChange()
        }
    }

    @Suppress("DEPRECATION")
    private fun signature(): String = cm.allNetworks.mapNotNull { n ->
        val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
        val t = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            else -> "other"
        }
        val ips = cm.getLinkProperties(n)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.mapNotNull { it.hostAddress }
            ?.sorted()
            ?.joinToString(",") ?: ""
        "$t:$ips"
    }.sorted().joinToString("|")
}
