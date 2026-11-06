package com.smsforwarder.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.smsforwarder.app.debug.AgentDebugLog
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Picks the underlying Wi‑Fi/Ethernet [Network] that is not the VPN tunnel,
 * so LAN peer traffic still works while a system VPN is on.
 */
object LanNetwork {
    data class LanPath(
        val network: Network,
        val ipv4: String
    )

    private val lastLoggedKey = AtomicReference("")

    fun resolve(context: Context): LanPath? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        var fallback: LanPath? = null
        var wifiCount = 0
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val wifiOrEth =
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!wifiOrEth) continue
            wifiCount++
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue

            val ip = ipv4On(cm, network) ?: continue
            val path = LanPath(network, ip)
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                logResolveOnce(context, path.ipv4, wifiCount, vpnActive(context), "not_vpn")
                return path
            }
            if (fallback == null) fallback = path
        }
        logResolveOnce(
            context,
            fallback?.ipv4 ?: "null",
            wifiCount,
            vpnActive(context),
            if (fallback != null) "fallback" else "none"
        )
        return fallback
    }

    private fun logResolveOnce(
        context: Context,
        ip: String,
        wifiCount: Int,
        vpn: Boolean,
        mode: String
    ) {
        val key = "$ip|$wifiCount|$vpn|$mode"
        val prev = lastLoggedKey.get()
        if (prev == key) return
        lastLoggedKey.set(key)
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H7",
            location = "LanNetwork.resolve",
            message = "LAN path resolve",
            data = mapOf(
                "ip" to ip,
                "wifiCount" to wifiCount,
                "vpn" to vpn,
                "mode" to mode,
                "pid" to android.os.Process.myPid()
            ),
            runId = "sync-debug"
        )
        // #endregion
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

    fun lanHttpClient(context: Context): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
        val path = resolve(context) ?: return builder.build()
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
