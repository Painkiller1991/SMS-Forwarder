package com.smsforwarder.app.net

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.smsforwarder.app.sms.ServiceWatchdogReceiver

/** Schedules a short delayed ensure() after the user swipes the app away. */
object ServiceWatchdog {
    private const val REQ = 7102
    private const val DELAY_MS = 1_500L

    fun scheduleSoon(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            REQ,
            Intent(context, ServiceWatchdogReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + DELAY_MS,
            pi
        )
    }
}
