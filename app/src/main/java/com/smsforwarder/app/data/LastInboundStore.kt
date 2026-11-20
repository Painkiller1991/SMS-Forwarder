package com.smsforwarder.app.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.concurrent.TimeUnit

/**
 * Cross-process timestamps for receiver idle rebind.
 * Uses a FileChannel lock so UI and `:recv` cannot corrupt the JSON.
 */
object LastInboundStore {
    private const val TAG = "LastInbound"
    private const val FILE = "last_inbound.json"
    private const val LOCK_FILE = "last_inbound.lock"
    private const val KEY_LAST_INBOUND = "lastInboundAt"
    private const val KEY_LAST_IDLE_REBIND = "lastIdleRebindAt"
    private const val LOCK_WAIT_MS = 500L

    /** Corrupt / unreadable store (do not treat as "missing" and reset idle). */
    const val CORRUPT = -1L

    fun touch(context: Context, atMs: Long = System.currentTimeMillis()) {
        withFileLock(context) {
            val cur = readUnlocked(context)
            val inbound = when {
                cur.lastInboundAt == CORRUPT -> atMs
                else -> maxOf(cur.lastInboundAt.coerceAtLeast(0L), atMs)
            }
            val rebind = cur.lastIdleRebindAt.coerceAtLeast(0L)
            writeUnlocked(context, lastInboundAt = inbound, lastIdleRebindAt = rebind)
        }
    }

    fun lastAt(context: Context): Long =
        withFileLock(context) { readUnlocked(context).lastInboundAt }

    fun lastIdleRebindAt(context: Context): Long =
        withFileLock(context) {
            readUnlocked(context).lastIdleRebindAt.coerceAtLeast(0L)
        }

    fun markIdleRebind(context: Context, atMs: Long = System.currentTimeMillis()) {
        withFileLock(context) {
            val cur = readUnlocked(context)
            val inbound = if (cur.lastInboundAt == CORRUPT) 0L else cur.lastInboundAt.coerceAtLeast(0L)
            writeUnlocked(
                context,
                lastInboundAt = inbound,
                lastIdleRebindAt = maxOf(cur.lastIdleRebindAt.coerceAtLeast(0L), atMs)
            )
        }
    }

    /** Seed inbound clock when listen turns ON so an empty inbox is not treated as 12h idle. */
    fun seedIfMissing(context: Context, atMs: Long = System.currentTimeMillis()): Long {
        return withFileLock(context) {
            val cur = readUnlocked(context)
            when {
                cur.lastInboundAt > 0L -> cur.lastInboundAt
                cur.lastInboundAt == CORRUPT -> {
                    // Do not silently reset a corrupt clock to "now" — return CORRUPT.
                    CORRUPT
                }
                else -> {
                    writeUnlocked(context, lastInboundAt = atMs, lastIdleRebindAt = cur.lastIdleRebindAt.coerceAtLeast(0L))
                    atMs
                }
            }
        }
    }

    private data class Snapshot(val lastInboundAt: Long, val lastIdleRebindAt: Long)

    private fun dataFile(context: Context): File =
        File(context.applicationContext.filesDir, FILE)

    private fun lockFile(context: Context): File =
        File(context.applicationContext.filesDir, LOCK_FILE)

    private fun <T> withFileLock(context: Context, block: () -> T): T {
        val lf = lockFile(context)
        RandomAccessFile(lf, "rw").use { raf ->
            var lock: FileLock? = null
            try {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOCK_WAIT_MS)
                while (lock == null && System.nanoTime() < deadline) {
                    lock = raf.channel.tryLock()
                    if (lock == null) Thread.sleep(20)
                }
                if (lock == null) {
                    Log.w(TAG, "File lock timeout — proceeding unlocked")
                }
                return block()
            } finally {
                runCatching { lock?.release() }
            }
        }
    }

    private fun readUnlocked(context: Context): Snapshot {
        val f = dataFile(context)
        if (!f.exists()) return Snapshot(0L, 0L)
        return runCatching {
            val json = JSONObject(f.readText())
            Snapshot(
                lastInboundAt = json.optLong(KEY_LAST_INBOUND, 0L),
                lastIdleRebindAt = json.optLong(KEY_LAST_IDLE_REBIND, 0L)
            )
        }.getOrElse {
            Log.w(TAG, "Corrupt last_inbound.json", it)
            Snapshot(CORRUPT, CORRUPT)
        }
    }

    private fun writeUnlocked(context: Context, lastInboundAt: Long, lastIdleRebindAt: Long) {
        val json = JSONObject()
            .put(KEY_LAST_INBOUND, lastInboundAt)
            .put(KEY_LAST_IDLE_REBIND, lastIdleRebindAt)
            .toString()
        val target = dataFile(context)
        val tmp = File(context.applicationContext.filesDir, "$FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(target)) {
            target.writeText(json)
            tmp.delete()
        }
    }
}
