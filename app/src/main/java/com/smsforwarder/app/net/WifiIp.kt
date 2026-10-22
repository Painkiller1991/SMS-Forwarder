package com.smsforwarder.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import java.net.Inet4Address

object WifiIp {
    fun localIpv4(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                continue
            }
            val props: LinkProperties = cm.getLinkProperties(network) ?: continue
            for (link in props.linkAddresses) {
                val address = link.address
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }
}
