package com.smsforwarder.app.net

import android.util.Log
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.security.Auth
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * Local-only HTTP listener. Binds [BIND_HOST] (all interfaces) so LAN peers can
 * connect; UI/QR still show the Wi‑Fi IPv4 from [WifiIp].
 */
class ReceiveServer(
    port: Int,
    private val sharedSecretProvider: () -> String,
    private val onSms: (sender: String, body: String, timestamp: Long) -> Unit
) : NanoHTTPD(BIND_HOST, port) {

    @Throws(IOException::class)
    fun startListening() {
        start(SOCKET_READ_TIMEOUT, false)
        Log.i(TAG, "LAN receive server on $BIND_HOST:$listeningPort")
    }

    override fun serve(session: IHTTPSession): Response {
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H2",
            location = "ReceiveServer.serve",
            message = "LAN request",
            data = mapOf(
                "method" to session.method?.name,
                "uri" to session.uri,
                "remote" to session.remoteIpAddress
            ),
            runId = "lan-url"
        )
        // #endregion

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
                // #region agent log
                AgentDebugLog.log(
                    hypothesisId = "H5",
                    location = "ReceiveServer.emptyBody",
                    message = "POST /sms empty body after parseBody",
                    runId = "lan-url"
                )
                // #endregion
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Empty body")
            }
            val secret = sharedSecretProvider()
            val json = PayloadCrypto.decryptJson(secret, raw)
            val sender = json.getString("sender")
            val body = json.getString("body")
            val timestamp = json.getLong("timestamp")
            val signature = json.getString("signature")
            if (!Auth.verify(secret, timestamp, sender, body, signature)) {
                // #region agent log
                AgentDebugLog.log(
                    hypothesisId = "H4",
                    location = "ReceiveServer.auth",
                    message = "HMAC failed",
                    data = mapOf("secretLen" to secret.length),
                    runId = "lan-url"
                )
                // #endregion
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
            }
            onSms(sender, body, timestamp)
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "H3",
                location = "ReceiveServer.ok",
                message = "LAN SMS accepted",
                data = mapOf("sender" to sender.take(24), "bodyLen" to body.length),
                runId = "lan-url"
            )
            // #endregion
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle SMS POST", e)
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "H4",
                location = "ReceiveServer.error",
                message = "POST /sms handler error",
                data = mapOf("error" to (e.message?.take(120) ?: e.javaClass.simpleName)),
                runId = "lan-url"
            )
            // #endregion
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, e.message ?: "Bad request")
        }
    }

    companion object {
        private const val TAG = "ReceiveServer"
        const val DEFAULT_PORT = 18765
        const val BIND_HOST = "0.0.0.0"
    }
}
