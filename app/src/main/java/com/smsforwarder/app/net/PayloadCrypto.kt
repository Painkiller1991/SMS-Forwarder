package com.smsforwarder.app.net

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object PayloadCrypto {
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    fun topicForSecret(secret: String): String {
        val hash = sha256Hex(secret).take(32)
        return "smsfw/v1/$hash"
    }

    fun encryptJson(secret: String, json: JSONObject): String {
        val key = SecretKeySpec(sha256(secret), "AES")
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val packed = iv + cipherBytes
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun decryptJson(secret: String, blob: String): JSONObject {
        val packed = Base64.decode(blob, Base64.NO_WRAP)
        require(packed.size > IV_BYTES) { "ciphertext too short" }
        val iv = packed.copyOfRange(0, IV_BYTES)
        val cipherBytes = packed.copyOfRange(IV_BYTES, packed.size)
        val key = SecretKeySpec(sha256(secret), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plain = cipher.doFinal(cipherBytes).toString(Charsets.UTF_8)
        return JSONObject(plain)
    }

    private fun sha256(secret: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))

    private fun sha256Hex(secret: String): String =
        sha256(secret).joinToString("") { "%02x".format(it) }
}
