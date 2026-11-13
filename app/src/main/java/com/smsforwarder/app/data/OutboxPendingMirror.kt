package com.smsforwarder.app.data

import android.content.Context
import java.io.File

/**
 * Cross-process pending count (:send writes, UI reads).
 * Room Flow can lag across processes even with MultiInstanceInvalidation.
 */
object OutboxPendingMirror {
    private const val FILE = "outbox_pending_count.txt"

    fun write(context: Context, pending: Int) {
        File(context.applicationContext.filesDir, FILE).writeText(pending.coerceAtLeast(0).toString())
    }

    fun read(context: Context): Int? {
        val f = File(context.applicationContext.filesDir, FILE)
        if (!f.exists()) return null
        return f.readText().trim().toIntOrNull()
    }
}
