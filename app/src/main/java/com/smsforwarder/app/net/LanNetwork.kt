package com.smsforwarder.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Picks the underlying Wi‑Fi/Ethernet/Soft AP [Network] that is not the VPN tunnel,
 * so LAN peer traffic still works while a system VPN is on or when using a phone hotspot.
 */
object LanNetwork {
    data class LanPath(
        val network: Network,
        val ipv4: String
    )

    fun resolve(context: Context, preferredPeerHost: String? = null): LanPath? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val candidates = mutableListOf<Pair<LanPath, Int>>()
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue

            val wifiOrEth =
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val ip = ipv4On(cm, network) ?: continue
            // Soft AP host / hotspot client: private IPv4 even if transport looks odd.
            if (!wifiOrEth && !isPrivateLanIpv4(ip)) continue

            var score = 0
            if (wifiOrEth) score += 10
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) score += 5
            // Prefer no-internet Soft AP / hotspot LAN over cellular default.
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) score += 8
            if (isPrivateLanIpv4(ip)) score += 4
            if (sameSubnet24(ip, preferredPeerHost)) score += 50
            if (isLikelyHotspotSubnet(ip)) score += 6

            candidates += LanPath(network, ip) to score
        }
        return candidates.maxByOrNull { it.second }?.first
    }

    fun ipv4(context: Context): String? = resolve(context)?.ipv4

    /** True when any system VPN network is present (listen/UI guidance). */
    fun vpnActive(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
        }
        return false
    }

    fun lanHttpClient(context: Context, preferredPeerHost: String? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
        val path = resolve(context, preferredPeerHost) ?: return builder.build()
        val network = path.network
        return builder
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return network.getAllByName(hostname).toList()
                }
            })
            .build()
    }

    fun isPrivateLanIpv4(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val a = parts[0]
        val b = parts[1]
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
    }

    fun isLikelyHotspotSubnet(ip: String): Boolean {
        // Common Android Soft AP / LocalOnlyHotspot pools.
        return ip.startsWith("192.168.43.") ||
            ip.startsWith("192.168.137.") ||
            ip.startsWith("192.168.75.") ||
            ip.startsWith("192.168.49.") ||
            ip.startsWith("192.168.42.")
    }

    private fun sameSubnet24(localIp: String, peerHost: String?): Boolean {
        if (peerHost.isNullOrBlank()) return false
        val a = localIp.split('.')
        val b = peerHost.trim().substringBefore('%').split('.')
        if (a.size < 3 || b.size < 3) return false
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2]
    }

    private fun ipv4On(cm: ConnectivityManager, network: Network): String? {
        val props: LinkProperties = cm.getLinkProperties(network) ?: return null
        for (link in props.linkAddresses) {
            val address = link.address
            if (address is Inet4Address && !address.isLoopbackAddress) {
                return address.hostAddress
            }
        }
        return null
    }
}
