package com.smsforwarder.app.net

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.smsforwarder.app.R
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.debug.AgentDebugLog
import kotlin.concurrent.thread

/** Short-lived FGS: POST one SMS to the paired receiver on local Wi‑Fi. */
class SmsForwardService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, SmsForwarderApp.CHANNEL_RECEIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Forwarding SMS (LAN)…")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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

        val sender = intent?.getStringExtra(EXTRA_SENDER).orEmpty()
        val body = intent?.getStringExtra(EXTRA_BODY).orEmpty()
        val timestamp = intent?.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
            ?: System.currentTimeMillis()
        val prefs = (application as SmsForwarderApp).prefs

        thread(name = "sms-lan-forward") {
            try {
                // #region agent log
                AgentDebugLog.log(
                    hypothesisId = "LAN",
                    location = "SmsForwardService.start",
                    message = "LAN forward starting",
                    data = mapOf(
                        "host" to prefs.receiverHost,
                        "port" to prefs.receiverPort,
                        "bodyLen" to body.length
                    ),
                    runId = "lan"
                )
                // #endregion
                val result = ForwardClient.forward(
                    host = prefs.receiverHost,
                    port = prefs.receiverPort,
                    secret = prefs.sharedSecret,
                    sender = sender,
                    body = body,
                    timestamp = timestamp
                )
                // #region agent log
                AgentDebugLog.log(
                    hypothesisId = "LAN",
                    location = "SmsForwardService.result",
                    message = "LAN forward finished",
                    data = mapOf(
                        "success" to result.isSuccess,
                        "error" to result.exceptionOrNull()?.message?.take(160)
                    ),
                    runId = "lan"
                )
                // #endregion
                result.onFailure { Log.e(TAG, "LAN forward failed", it) }
                    .onSuccess { Log.i(TAG, "Forwarded SMS from $sender") }
            } catch (e: Exception) {
                Log.e(TAG, "Forward service error", e)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "SmsForwardService"
        private const val NOTIFICATION_ID = 44
        const val EXTRA_SENDER = "sender"
        const val EXTRA_BODY = "body"
        const val EXTRA_TIMESTAMP = "timestamp"

        fun enqueue(context: Context, sender: String, body: String, timestamp: Long) {
            context.startForegroundService(
                Intent(context, SmsForwardService::class.java).apply {
                    putExtra(EXTRA_SENDER, sender)
                    putExtra(EXTRA_BODY, body)
                    putExtra(EXTRA_TIMESTAMP, timestamp)
                }
            )
        }
    }
}
