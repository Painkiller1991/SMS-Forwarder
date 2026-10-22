package com.smsforwarder.app.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Auth {
    private const val ALGORITHM = "HmacSHA256"

    fun sign(secret: String, timestamp: Long, sender: String, body: String): String {
        val payload = "$timestamp|$sender|$body"
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun verify(
        secret: String,
        timestamp: Long,
        sender: String,
        body: String,
        signature: String,
        maxSkewMs: Long = 5 * 60 * 1000
    ): Boolean {
        if (kotlin.math.abs(System.currentTimeMillis() - timestamp) > maxSkewMs) return false
        val expected = sign(secret, timestamp, sender, body)
        return expected.equals(signature, ignoreCase = true)
    }
}
