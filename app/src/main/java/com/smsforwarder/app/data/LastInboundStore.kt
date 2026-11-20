package com.smsforwarder.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Cross-process timestamps for receiver idle rebind.
 * SharedPreferences are per-process; a file under filesDir is visible to UI and :recv.
 */
object LastInboundStore {
    private const val FILE = "last_inbound.json"
    private const val KEY_LAST_INBOUND = "lastInboundAt"
    private const val KEY_LAST_IDLE_REBIND = "lastIdleRebindAt"
    private val lock = Any()

    fun touch(context: Context, atMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val cur = readUnlocked(context)
            writeUnlocked(
                context,
                lastInboundAt = atMs,
                lastIdleRebindAt = cur.lastIdleRebindAt
            )
        }
    }

    fun lastAt(context: Context): Long = synchronized(lock) { readUnlocked(context).lastInboundAt }

    fun lastIdleRebindAt(context: Context): Long =
        synchronized(lock) { readUnlocked(context).lastIdleRebindAt }

    fun markIdleRebind(context: Context, atMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val cur = readUnlocked(context)
            writeUnlocked(
                context,
                lastInboundAt = cur.lastInboundAt,
                lastIdleRebindAt = atMs
            )
        }
    }

    /** Seed inbound clock when listen turns ON so an empty inbox is not treated as 12h idle. */
    fun seedIfMissing(context: Context, atMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (readUnlocked(context).lastInboundAt <= 0L) {
                writeUnlocked(context, lastInboundAt = atMs, lastIdleRebindAt = 0L)
            }
        }
    }

    private data class Snapshot(val lastInboundAt: Long, val lastIdleRebindAt: Long)

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE)

    private fun readUnlocked(context: Context): Snapshot {
        val f = file(context)
        if (!f.exists()) return Snapshot(0L, 0L)
        return runCatching {
            val json = JSONObject(f.readText())
            Snapshot(
                lastInboundAt = json.optLong(KEY_LAST_INBOUND, 0L),
                lastIdleRebindAt = json.optLong(KEY_LAST_IDLE_REBIND, 0L)
            )
        }.getOrDefault(Snapshot(0L, 0L))
    }

    private fun writeUnlocked(context: Context, lastInboundAt: Long, lastIdleRebindAt: Long) {
        runCatching {
            val json = JSONObject()
                .put(KEY_LAST_INBOUND, lastInboundAt)
                .put(KEY_LAST_IDLE_REBIND, lastIdleRebindAt)
            file(context).writeText(json.toString())
        }
    }
}
