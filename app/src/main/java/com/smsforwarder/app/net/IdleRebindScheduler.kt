package com.smsforwarder.app.net

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.smsforwarder.app.sms.IdleRebindReceiver

/**
 * Rare receiver safety: if no SMS for 12h, force-rebind the listen socket.
 * Inexact / allow-while-idle — not a busy loop.
 *
 * Use [ensureScheduled] from keep-alive / ENSURE paths so the alarm is not
 * continually pushed out to now+12h. Use [scheduleNext] only after a tick
 * (or when explicitly resetting the 12h window).
 */
object IdleRebindScheduler {
    private const val REQ = 7102
    const val INTERVAL_MS = 12 * 60 * 60 * 1000L

    /** Schedule only if no idle-rebind alarm is already pending. */
    fun ensureScheduled(context: Context) {
        if (isPending(context)) return
        scheduleNext(context)
    }

    /** Always set the next fire to now + 12h (replaces any existing alarm). */
    fun scheduleNext(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, create = true) ?: return
        val trigger = SystemClock.elapsedRealtime() + INTERVAL_MS
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
    }

    @Deprecated("Use ensureScheduled or scheduleNext", ReplaceWith("ensureScheduled(context)"))
    fun schedule(context: Context) = ensureScheduled(context)

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, create = false) ?: return
        am.cancel(pi)
        pi.cancel()
    }

    private fun isPending(context: Context): Boolean =
        pending(context, create = false) != null

    private fun pending(context: Context, create: Boolean): PendingIntent? {
        val intent = Intent(context, IdleRebindReceiver::class.java)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, REQ, intent, flags)
    }
}
