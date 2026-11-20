package com.smsforwarder.app.net

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.smsforwarder.app.sms.IdleRebindReceiver
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/**
 * Rare receiver safety: if no SMS for 12h, force-rebind the listen socket.
 *
 * Use [ensureScheduled] from keep-alive / ENSURE paths so the alarm is not
 * continually pushed out to now+12h. Use [scheduleNext] / [scheduleIn] after ticks.
 */
object IdleRebindScheduler {
    private const val TAG = "IdleRebind"
    private const val REQ = 7102
    private const val DEADLINE_FILE = "idle_rebind_deadline.txt"
    private const val DEADLINE_LOCK = "idle_rebind_deadline.lock"
    const val INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val MIN_DELAY_MS = 60_000L
    private const val VALID_SLACK_MS = 5 * 60 * 1000L

    /** Drop deadline + cancel PI after reboot (elapsedRealtime resets). */
    fun invalidateAfterBoot(context: Context) {
        cancel(context)
    }

    /** Schedule only if no *valid* future idle-rebind deadline is recorded. */
    fun ensureScheduled(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val deadline = readDeadline(context)
        if (isValidFutureDeadline(now, deadline)) return
        if (deadline > 0L) clearDeadline(context)
        scheduleNext(context)
    }

    /** Full 12h window from now. */
    fun scheduleNext(context: Context) = scheduleIn(context, INTERVAL_MS)

    /**
     * Arm alarm after [delayMs] (clamped). Used when a tick fires early so we do not
     * push a full extra 12h and stretch idle-to-rebind toward ~24h.
     */
    fun scheduleIn(context: Context, delayMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, create = true) ?: return
        val delay = delayMs.coerceIn(MIN_DELAY_MS, INTERVAL_MS)
        val trigger = SystemClock.elapsedRealtime() + delay
        try {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            writeDeadline(context, trigger)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to arm idle rebind alarm", e)
            clearDeadline(context)
        }
    }

    @Deprecated("Use ensureScheduled or scheduleNext", ReplaceWith("ensureScheduled(context)"))
    fun schedule(context: Context) = ensureScheduled(context)

    fun cancel(context: Context) {
        clearDeadline(context)
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, create = false) ?: return
        am.cancel(pi)
        pi.cancel()
    }

    private fun isValidFutureDeadline(nowElapsed: Long, deadline: Long): Boolean {
        if (deadline <= nowElapsed) return false
        return deadline <= nowElapsed + INTERVAL_MS + VALID_SLACK_MS
    }

    private fun deadlineFile(context: Context): File =
        File(context.applicationContext.filesDir, DEADLINE_FILE)

    private fun lockFile(context: Context): File =
        File(context.applicationContext.filesDir, DEADLINE_LOCK)

    private fun readDeadline(context: Context): Long = withDeadlineLock(context) {
        val f = deadlineFile(context)
        if (!f.exists()) return@withDeadlineLock 0L
        f.readText().trim().toLongOrNull() ?: 0L
    }

    private fun writeDeadline(context: Context, elapsedRealtimeMs: Long) {
        withDeadlineLock(context) {
            runCatching { deadlineFile(context).writeText(elapsedRealtimeMs.toString()) }
        }
    }

    private fun clearDeadline(context: Context) {
        withDeadlineLock(context) {
            runCatching { deadlineFile(context).delete() }
        }
    }

    private fun <T> withDeadlineLock(context: Context, block: () -> T): T {
        val lf = lockFile(context)
        RandomAccessFile(lf, "rw").use { raf ->
            var lock: FileLock? = null
            try {
                lock = raf.channel.tryLock() ?: raf.channel.lock()
                return block()
            } finally {
                runCatching { lock?.release() }
            }
        }
    }

    private fun pending(context: Context, create: Boolean): PendingIntent? {
        val intent = Intent(context, IdleRebindReceiver::class.java)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, REQ, intent, flags)
    }
}
