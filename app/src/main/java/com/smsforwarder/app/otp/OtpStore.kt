package com.smsforwarder.app.otp

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

data class StoredOtp(
    val code: String,
    val from: String,
    val storedAt: Long = System.currentTimeMillis()
)

/**
 * Latest OTP shared across processes (UI / :recv / Autofill).
 * In-memory alone fails because :recv writes and Autofill reads another process.
 */
class OtpStore(
    context: Context,
    private val ttlMs: Long = 5 * 60 * 1000
) {
    private val file = File(context.applicationContext.filesDir, FILE)
    private val latest = AtomicReference<StoredOtp?>(null)

    fun put(code: String, from: String) {
        val otp = StoredOtp(code = code.trim(), from = from.trim())
        latest.set(otp)
        runCatching {
            val json = JSONObject()
                .put("code", otp.code)
                .put("from", otp.from)
                .put("storedAt", otp.storedAt)
            file.writeText(json.toString())
        }
    }

    fun getValid(): StoredOtp? {
        val mem = latest.get()
        if (mem != null && !isExpired(mem)) return mem

        val fromDisk = readFile() ?: run {
            latest.set(null)
            return null
        }
        if (isExpired(fromDisk)) {
            clear()
            return null
        }
        latest.set(fromDisk)
        return fromDisk
    }

    fun clear() {
        latest.set(null)
        runCatching { if (file.exists()) file.delete() }
    }

    private fun isExpired(otp: StoredOtp): Boolean =
        System.currentTimeMillis() - otp.storedAt > ttlMs

    private fun readFile(): StoredOtp? {
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            StoredOtp(
                code = json.getString("code"),
                from = json.optString("from", ""),
                storedAt = json.getLong("storedAt")
            )
        }.getOrNull()
    }

    companion object {
        private const val FILE = "latest_otp.json"
    }
}
