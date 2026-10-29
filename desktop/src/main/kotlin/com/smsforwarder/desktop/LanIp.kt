package com.smsforwarder.desktop

import java.net.Inet4Address
import java.net.NetworkInterface

object LanIp {
    /** Prefer a non-loopback IPv4 that looks like LAN (not obvious VPN tun names). */
    fun localIpv4(): String? {
        val candidates = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.lowercase()
            val display = (nif.displayName ?: "").lowercase()
            val looksVpn =
                name.contains("tun") ||
                    name.contains("tap") ||
                    name.contains("wg") ||
                    name.contains("vpn") ||
                    display.contains("vpn") ||
                    display.contains("tunnel") ||
                    display.contains("wireguard") ||
                    display.contains("nordlynx") ||
                    display.contains("wintun")
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val host = addr.hostAddress ?: continue
                    if (looksVpn) continue
                    candidates += host
                }
            }
        }
        return candidates.firstOrNull { it.startsWith("192.168.") }
            ?: candidates.firstOrNull { it.startsWith("10.") }
            ?: candidates.firstOrNull()
    }
}
