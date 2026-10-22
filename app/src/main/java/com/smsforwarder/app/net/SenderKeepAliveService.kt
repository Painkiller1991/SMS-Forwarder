package com.smsforwarder.app.net

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.smsforwarder.app.R
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.ui.MainActivity

class SenderKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "KEEPALIVE",
            location = "SenderKeepAliveService.onStartCommand",
            message = "Keep-alive service started",
            runId = "mqtt"
        )
        // #endregion
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, SmsForwarderApp.CHANNEL_RECEIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SMS forwarding active")
            .setContentText("Waiting for SMS → local Wi‑Fi only")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 43
        const val ACTION_STOP = "com.smsforwarder.app.STOP_SENDER_KEEPALIVE"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SenderKeepAliveService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SenderKeepAliveService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
