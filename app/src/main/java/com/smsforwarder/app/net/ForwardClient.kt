package com.smsforwarder.app.net

import android.content.Context
import com.smsforwarder.app.data.ReceiverEndpoint
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
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class FanoutResult(
    val ok: Int,
    val total: Int,
    val failures: List<String>
) {
    val allOk: Boolean get() = total > 0 && ok == total
    val anyOk: Boolean get() = ok > 0

    fun summary(): String = when {
        total == 0 -> "No enabled devices"
        allOk -> "OK $ok/$total"
        anyOk -> "Partial $ok/$total — failed: ${failures.joinToString()}"
        else -> "FAIL 0/$total — ${failures.joinToString()}"
    }
}

/** Local Wi‑Fi only — sockets bound to non-VPN LAN network when possible. */
object ForwardClient {
    private val plainType = "text/plain; charset=utf-8".toMediaType()
    /** Skip redundant /health when this peer just answered successfully. */
    private const val HEALTH_CACHE_MS = 45_000L
    @Volatile private var lastOkKey: String? = null
    @Volatile private var lastOkAtMs: Long = 0L

    private fun peerKey(host: String, port: Int) = "${host.trim().lowercase()}:$port"

    private fun markPeerOk(host: String, port: Int) {
        lastOkKey = peerKey(host, port)
        lastOkAtMs = System.currentTimeMillis()
    }

    private fun clearPeerOk(host: String, port: Int) {
        if (lastOkKey == peerKey(host, port)) {
            lastOkKey = null
            lastOkAtMs = 0L
        }
    }

    private fun recentlyOk(host: String, port: Int): Boolean {
        val key = peerKey(host, port)
        return lastOkKey == key &&
            System.currentTimeMillis() - lastOkAtMs < HEALTH_CACHE_MS
    }

    fun health(context: Context, host: String, port: Int): Result<Unit> = runCatching {
        val client = LanNetwork.lanHttpClient(context, preferredPeerHost = host)
        val url = "http://$host:$port/health"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("health HTTP ${response.code}")
        }
    }

    /**
     * @param timestamp Auth clock used for HMAC + ±5 min skew (use "now" for outbox sync).
     * @param smsTimestamp Original SMS time for inbox display; defaults to [timestamp].
     */
    fun forward(
        context: Context,
        host: String,
        port: Int,
        secret: String,
        sender: String,
        body: String,
        timestamp: Long = System.currentTimeMillis(),
        msgId: String = UUID.randomUUID().toString(),
        quiet: Boolean = false,
        test: Boolean = false,
        smsTimestamp: Long = timestamp,
        extraSecret: String = ""
    ): Result<Unit> = runCatching {
        require(host.isNotBlank()) { "Receiver host not set — scan QR again" }
        val wireSecret = PayloadCrypto.effectiveSecret(secret, extraSecret)
        val client = LanNetwork.lanHttpClient(context, preferredPeerHost = host)
        val url = "http://$host:$port/sms"
        val skipHealth = recentlyOk(host, port)
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H12",
            location = "ForwardClient.forward",
            message = "LAN POST starting",
            data = mapOf(
                "url" to url,
                "bodyLen" to body.length,
                "msgId" to msgId.take(36),
                "lanIp" to (LanNetwork.ipv4(context) ?: "none"),
                "skipHealth" to skipHealth,
                "quiet" to quiet,
                "test" to test
            ),
            runId = "lan-url"
        )
        // #endregion

        if (!skipHealth) {
            val health = health(context, host, port)
            if (health.isFailure) {
                val err = health.exceptionOrNull()
                val kind = classify(err)
                clearPeerOk(host, port)
                AgentDebugLog.log(
                    hypothesisId = "H1",
                    location = "ForwardClient.health",
                    message = "LAN health failed",
                    data = mapOf(
                        "url" to "http://$host:$port/health",
                        "kind" to kind,
                        "error" to (err?.message?.take(120) ?: err?.javaClass?.simpleName)
                    ),
                    runId = "lan-url"
                )
                error(
                    "Cannot reach $host:$port ($kind). Same Wi‑Fi? VPN blocking LAN? Receive ON?"
                )
            }
        }

        val signature = Auth.sign(wireSecret, timestamp, sender, body)
        val json = JSONObject()
            .put("sender", sender)
            .put("body", body)
            .put("timestamp", timestamp)
            .put("signature", signature)
            .put("msgId", msgId)
            .put("quiet", quiet)
            .put("test", test)
        if (smsTimestamp != timestamp) {
            json.put("smsTimestamp", smsTimestamp)
        }
        val blob = PayloadCrypto.encryptJson(wireSecret, json)
        val request = Request.Builder()
            .url(url)
            .post(blob.toRequestBody(plainType))
            .build()
        client.newCall(request).execute().use { response ->
            val respBody = response.body?.string().orEmpty().trim()
            AgentDebugLog.log(
                hypothesisId = "H3",
                location = "ForwardClient.forward.response",
                message = "LAN POST response",
                data = mapOf(
                    "url" to url,
                    "code" to response.code,
                    "body" to respBody.take(80),
                    "skipHealth" to skipHealth
                ),
                runId = "lan-url"
            )
            if (!response.isSuccessful) {
                clearPeerOk(host, port)
                error("HTTP ${response.code}: $respBody")
            }
            if (!isAckOk(respBody)) {
                clearPeerOk(host, port)
                error("Bad ACK: ${respBody.take(80)}")
            }
            markPeerOk(host, port)
        }
    }.fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { e ->
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
            Result.failure(e)
        }
    )

    /** Accept JSON ACK or legacy plaintext OK. */
    fun isAckOk(respBody: String): Boolean {
        val raw = respBody.trim()
        if (raw.equals("OK", ignoreCase = true) || raw.equals("ok", ignoreCase = true)) {
            return true
        }
        return runCatching {
            val o = JSONObject(raw)
            o.optBoolean("ok", false)
        }.getOrDefault(false)
    }

    fun forwardToAll(
        context: Context,
        endpoints: List<ReceiverEndpoint>,
        sender: String,
        body: String,
        timestamp: Long = System.currentTimeMillis(),
        msgId: String = UUID.randomUUID().toString(),
        quiet: Boolean = false,
        test: Boolean = false
    ): FanoutResult {
        val targets = endpoints.filter { it.enabled && it.isValid() }
        if (targets.isEmpty()) return FanoutResult(0, 0, emptyList())
        if (targets.size == 1) {
            val ep = targets.first()
            val r = forward(
                context, ep.host, ep.port, ep.secret, sender, body, timestamp, msgId,
                quiet = quiet, test = test, extraSecret = ""
            )
            return if (r.isSuccess) {
                FanoutResult(1, 1, emptyList())
            } else {
                FanoutResult(0, 1, listOf(ep.displayName))
            }
        }
        val okCount = AtomicInteger(0)
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(targets.size.coerceAtMost(4))
        try {
            val futures = targets.map { ep ->
                pool.submit {
                    val r = forward(
                        context, ep.host, ep.port, ep.secret, sender, body, timestamp, msgId,
                        quiet = quiet, test = test, extraSecret = ""
                    )
                    if (r.isSuccess) okCount.incrementAndGet()
                    else failures.add(ep.displayName)
                }
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        return FanoutResult(okCount.get(), targets.size, failures.toList())
    }

    fun healthAll(context: Context, endpoints: List<ReceiverEndpoint>): FanoutResult {
        val targets = endpoints.filter { it.enabled && it.isValid() }
        if (targets.isEmpty()) return FanoutResult(0, 0, emptyList())
        var ok = 0
        val failures = mutableListOf<String>()
        for (ep in targets) {
            if (health(context, ep.host, ep.port).isSuccess) ok++
            else failures.add(ep.displayName)
        }
        return FanoutResult(ok, targets.size, failures)
    }

    private fun classify(e: Throwable?): String = when (e) {
        is SocketTimeoutException -> "timeout"
        is ConnectException -> "connection_refused"
        is UnknownHostException -> "bad_host"
        is IOException -> "io"
        else -> e?.javaClass?.simpleName ?: "unknown"
    }
}
