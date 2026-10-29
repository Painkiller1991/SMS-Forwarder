package com.smsforwarder.app.net

import com.smsforwarder.app.security.Auth
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Local Wi‑Fi HTTP listener used by Android and Windows receivers.
 * Prefer binding to a LAN IPv4 (not a VPN tunnel address) when possible.
 */
class ReceiveServer(
    hostname: String,
    port: Int,
    private val sharedSecretProvider: () -> String,
    private val onSms: (sender: String, body: String, timestamp: Long) -> Unit
) : NanoHTTPD(hostname, port) {

    val bindHost: String = hostname

    @Throws(IOException::class)
    fun startListening() {
        start(SOCKET_READ_TIMEOUT, false)
        LOG.info("LAN receive server on $bindHost:$listeningPort")
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.GET && session.uri == "/health") {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
        }
        if (session.method != Method.POST || session.uri != "/sms") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val raw = files["postData"]?.trim().orEmpty()
            if (raw.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Empty body")
            }
            val secret = sharedSecretProvider()
            val json = PayloadCrypto.decryptJson(secret, raw)
            val sender = json.getString("sender")
            val body = json.getString("body")
            val timestamp = json.getLong("timestamp")
            val signature = json.getString("signature")
            if (!Auth.verify(secret, timestamp, sender, body, signature)) {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
            }
            onSms(sender, body, timestamp)
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Failed to handle SMS POST", e)
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, e.message ?: "Bad request")
        }
    }

    companion object {
        private val LOG = Logger.getLogger("ReceiveServer")
        const val DEFAULT_PORT = 18765
        /** Fallback when no Wi‑Fi IPv4 is known yet. */
        const val BIND_ALL = "0.0.0.0"
    }
}
