package com.smsforwarder.app.otp

import java.util.concurrent.atomic.AtomicReference

data class StoredOtp(
    val code: String,
    val from: String,
    val storedAt: Long = System.currentTimeMillis()
)

class OtpStore(
    private val ttlMs: Long = 5 * 60 * 1000
) {
    private val latest = AtomicReference<StoredOtp?>(null)

    fun put(code: String, from: String) {
        latest.set(StoredOtp(code = code, from = from))
    }

    fun getValid(): StoredOtp? {
        val current = latest.get() ?: return null
        if (System.currentTimeMillis() - current.storedAt > ttlMs) {
            latest.compareAndSet(current, null)
            return null
        }
        return current
    }

    fun clear() {
        latest.set(null)
    }
}
