package com.smsforwarder.app.net

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.smsforwarder.app.sms.KeepAliveAlarmReceiver

/**
 * Rare safety net if the OEM killed the FGS — not a busy loop.
 * Inexact alarm = better battery than exact every minute.
 */
object KeepAliveScheduler {
    private const val REQ = 7101
    /** ~15 minutes; system may batch with other alarms. */
    private const val INTERVAL_MS = 15 * 60 * 1000L

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context)
        val trigger = SystemClock.elapsedRealtime() + INTERVAL_MS
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(context))
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, KeepAliveAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQ,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
