package com.smsforwarder.app.pairing

import android.net.Uri
import com.smsforwarder.app.net.ReceiveServer

data class PairingInfo(
    val secret: String,
    val host: String,
    val port: Int
)

object PairingParser {
    fun parse(raw: String): PairingInfo? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // smsfw://pair?ip=…&port=…&s=…
        if (trimmed.startsWith("smsfw://")) {
            val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
            val secret = uri.getQueryParameter("s")?.takeIf { it.isNotBlank() } ?: return null
            val host = uri.getQueryParameter("ip")?.takeIf { it.isNotBlank() } ?: return null
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: ReceiveServer.DEFAULT_PORT
            return PairingInfo(secret = secret, host = host, port = port)
        }

        // http://192.168.1.5:18765  or  http://192.168.1.5:18765/?s=SECRET
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = if (uri.port > 0) uri.port else ReceiveServer.DEFAULT_PORT
            val secret = uri.getQueryParameter("s")?.takeIf { it.length >= 16 }
            // URL alone without secret is incomplete unless already paired elsewhere
            if (secret == null) return null
            return PairingInfo(secret = secret, host = host, port = port)
        }

        // 192.168.1.5:18765:SECRET  or  192.168.1.5:SECRET (default port)
        val parts = trimmed.split(":")
        if (parts.size >= 3 && looksLikeIp(parts[0])) {
            val port = parts[1].toIntOrNull() ?: return null
            val secret = parts.drop(2).joinToString(":").takeIf { it.length >= 16 } ?: return null
            return PairingInfo(secret = secret, host = parts[0], port = port)
        }
        if (parts.size == 2 && looksLikeIp(parts[0]) && parts[1].length >= 16) {
            return PairingInfo(
                secret = parts[1],
                host = parts[0],
                port = ReceiveServer.DEFAULT_PORT
            )
        }
        return null
    }

    private fun looksLikeIp(s: String): Boolean =
        s.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
}
