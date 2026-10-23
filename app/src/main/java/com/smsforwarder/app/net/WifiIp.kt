package com.smsforwarder.app.net

import android.content.Context

object WifiIp {
    /** Local Wi‑Fi/Ethernet IPv4 preferring a non-VPN path (works under VPN). */
    fun localIpv4(context: Context): String? = LanNetwork.ipv4(context)
}
