package com.smsforwarder.app.net

object OutboxPolicy {
    /** Wall-clock delivery window from first enqueue. */
    const val EXPIRE_MS = 3 * 60 * 1000L

    /** Backoff after attempt n (1-based): 2s, 5s, 15s, 30s, 60s, 60s… */
    fun backoffAfterAttempt(attempts: Int): Long = when (attempts.coerceAtLeast(1)) {
        1 -> 2_000L
        2 -> 5_000L
        3 -> 15_000L
        4 -> 30_000L
        else -> 60_000L
    }
}
