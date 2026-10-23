package com.smsforwarder.app.net

import android.content.Context
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.security.Auth
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Local Wi‑Fi only — sockets bound to non-VPN LAN network when possible. */
object ForwardClient {
    private val plainType = "text/plain; charset=utf-8".toMediaType()

    fun health(context: Context, host: String, port: Int): Result<Unit> = runCatching {
        val client = LanNetwork.lanHttpClient(context)
        val url = "http://$host:$port/health"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("health HTTP ${response.code}")
        }
    }

    fun forward(
        context: Context,
        host: String,
        port: Int,
        secret: String,
        sender: String,
        body: String,
        timestamp: Long = System.currentTimeMillis()
    ): Result<Unit> = runCatching {
        require(host.isNotBlank()) { "Receiver host not set — scan QR again" }
        val client = LanNetwork.lanHttpClient(context)
        val url = "http://$host:$port/sms"
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H1",
            location = "ForwardClient.forward",
            message = "LAN POST starting",
            data = mapOf(
                "url" to url,
                "bodyLen" to body.length,
                "lanIp" to (LanNetwork.ipv4(context) ?: "none")
            ),
            runId = "lan-url"
        )
        // #endregion

        val health = health(context, host, port)
        if (health.isFailure) {
            val err = health.exceptionOrNull()
            val kind = classify(err)
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "H1",
                location = "ForwardClient.health",
                message = "LAN health failed — cannot reach receiver URL",
                data = mapOf(
                    "url" to "http://$host:$port/health",
                    "kind" to kind,
                    "error" to (err?.message?.take(120) ?: err?.javaClass?.simpleName)
                ),
                runId = "lan-url"
            )
            // #endregion
            error(
                "Cannot reach $host:$port ($kind). Same Wi‑Fi? VPN blocking LAN? Receive ON?"
            )
        }

        val signature = Auth.sign(secret, timestamp, sender, body)
        val json = JSONObject()
            .put("sender", sender)
            .put("body", body)
            .put("timestamp", timestamp)
            .put("signature", signature)
        val blob = PayloadCrypto.encryptJson(secret, json)
        val request = Request.Builder()
            .url(url)
            .post(blob.toRequestBody(plainType))
            .build()
        client.newCall(request).execute().use { response ->
            val respBody = response.body?.string().orEmpty()
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "H3",
                location = "ForwardClient.forward.response",
                message = "LAN POST response",
                data = mapOf("url" to url, "code" to response.code, "body" to respBody.take(80)),
                runId = "lan-url"
            )
            // #endregion
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: $respBody")
            }
        }
    }.fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { e ->
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "H1",
                location = "ForwardClient.forward.fail",
                message = "LAN forward failed",
                data = mapOf(
                    "kind" to classify(e),
                    "error" to (e.message?.take(160) ?: e.javaClass.simpleName)
                ),
                runId = "lan-url"
            )
            // #endregion
            Result.failure(e)
        }
    )

    private fun classify(e: Throwable?): String = when (e) {
        is SocketTimeoutException -> "timeout"
        is ConnectException -> "connection_refused"
        is UnknownHostException -> "bad_host"
        is IOException -> "io"
        else -> e?.javaClass?.simpleName ?: "unknown"
    }
}
