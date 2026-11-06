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
import com.smsforwarder.app.data.OutboxEntity
import com.smsforwarder.app.debug.AgentDebugLog
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Durable per-endpoint outbox: enqueue on SMS, drain when LAN is up.
 * Catch-up sends all older rows quiet; only the newest msgId alerts the receiver.
 */
class SmsForwardService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg("Syncing SMS (LAN)…")

        when (intent?.action) {
            ACTION_DRAIN -> {
                // #region agent log
                AgentDebugLog.log(
                    hypothesisId = "H2",
                    location = "SmsForwardService.onStartCommand",
                    message = "ACTION_DRAIN received",
                    data = mapOf("startId" to startId),
                    runId = "sync-debug"
                )
                // #endregion
                thread(name = "sms-outbox-drain") {
                    try {
                        drainOnce()
                    } finally {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
                return START_NOT_STICKY
            }
            else -> {
                val sender = intent?.getStringExtra(EXTRA_SENDER).orEmpty()
                val body = intent?.getStringExtra(EXTRA_BODY).orEmpty()
                val timestamp = intent?.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
                    ?: System.currentTimeMillis()
                thread(name = "sms-outbox-enqueue") {
                    try {
                        enqueueAndDrain(sender, body, timestamp)
                    } finally {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
                return START_NOT_STICKY
            }
        }
    }

    private fun startFg(title: String) {
        val notification: Notification = NotificationCompat.Builder(this, SmsForwarderApp.CHANNEL_RECEIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
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
    }

    private fun enqueueAndDrain(sender: String, body: String, timestamp: Long) {
        val app = application as SmsForwarderApp
        val endpoints = app.prefs.enabledEndpoints()
        if (endpoints.isEmpty()) {
            Log.w(TAG, "No enabled endpoints")
            return
        }
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        runBlocking {
            val dao = app.database.outboxDao()
            for (ep in endpoints) {
                dao.insert(
                    OutboxEntity(
                        msgId = msgId,
                        endpointId = ep.id,
                        host = ep.host,
                        port = ep.port,
                        secret = ep.secret,
                        sender = sender,
                        body = body,
                        smsTimestamp = timestamp,
                        createdAt = now,
                        attempts = 0,
                        nextAttemptAt = 0,
                        state = OutboxEntity.STATE_PENDING
                    )
                )
            }
            dao.purgeOld(now - 24 * 60 * 60 * 1000L)
        }
        AgentDebugLog.log(
            hypothesisId = "LAN",
            location = "SmsForwardService.enqueue",
            message = "Outbox enqueued",
            data = mapOf("msgId" to msgId.take(36), "targets" to endpoints.size),
            runId = "lan"
        )
        drainOnce()
    }

    private fun drainOnce() {
        val app = application as SmsForwarderApp
        val pending = runBlocking { app.database.outboxDao().allPendingOrdered() }
        val now = System.currentTimeMillis()
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H2",
            location = "SmsForwardService.drainOnce",
            message = "Drain start",
            data = mapOf(
                "pending" to pending.size,
                "oldestAgeMs" to (pending.firstOrNull()?.let { now - it.smsTimestamp } ?: -1),
                "newestAgeMs" to (pending.lastOrNull()?.let { now - it.smsTimestamp } ?: -1)
            ),
            runId = "sync-debug"
        )
        // #endregion
        if (pending.isEmpty()) return
        val newestMsgId = pending.maxWithOrNull(
            compareBy({ it.smsTimestamp }, { it.createdAt }, { it.id })
        )?.msgId
        for (row in pending) {
            val quiet = row.msgId != newestMsgId
            val ok = attempt(app, row, quiet = quiet)
            if (!ok) {
                // Stop this drain; connectivity/SMS will retry. Leave remaining pending.
                break
            }
            if (quiet) {
                Thread.sleep(POST_GAP_MS)
            }
        }
    }

    /** @return true if synced (ACK ok). */
    private fun attempt(app: SmsForwarderApp, row: OutboxEntity, quiet: Boolean): Boolean {
        val dao = app.database.outboxDao()
        val ageMs = System.currentTimeMillis() - row.smsTimestamp
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H1",
            location = "SmsForwardService.attempt",
            message = "Forward attempt",
            data = mapOf(
                "host" to row.host,
                "port" to row.port,
                "ageMs" to ageMs,
                "skewRisk" to (ageMs > 5 * 60 * 1000L),
                "quiet" to quiet,
                "attempts" to row.attempts,
                "msgId" to row.msgId.take(8)
            ),
            runId = "sync-debug"
        )
        // #endregion
        val result = ForwardClient.forward(
            context = this,
            host = row.host,
            port = row.port,
            secret = row.secret,
            sender = row.sender,
            body = row.body,
            timestamp = row.smsTimestamp,
            msgId = row.msgId,
            quiet = quiet,
            test = false
        )
        if (result.isSuccess) {
            runBlocking { dao.markSynced(row.id) }
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "H3",
                location = "SmsForwardService.attempt",
                message = "Forward OK synced",
                data = mapOf("id" to row.id, "quiet" to quiet),
                runId = "sync-debug"
            )
            // #endregion
            Log.i(TAG, "Synced ${row.host}:${row.port} quiet=$quiet")
            return true
        }
        val attempts = row.attempts + 1
        val err = result.exceptionOrNull()?.message?.take(160)
        runBlocking { dao.markAttemptFailed(row.id, attempts, err) }
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H1",
            location = "SmsForwardService.attempt",
            message = "Forward FAIL",
            data = mapOf(
                "err" to err,
                "ageMs" to ageMs,
                "skewRisk" to (ageMs > 5 * 60 * 1000L),
                "attempts" to attempts
            ),
            runId = "sync-debug"
        )
        // #endregion
        Log.w(TAG, "Sync fail ${row.host}:${row.port} attempt=$attempts err=$err")
        return false
    }

    companion object {
        private const val TAG = "SmsForwardService"
        private const val NOTIFICATION_ID = 44
        private const val POST_GAP_MS = 150L
        const val EXTRA_SENDER = "sender"
        const val EXTRA_BODY = "body"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val ACTION_DRAIN = "com.smsforwarder.app.DRAIN_OUTBOX"

        fun enqueue(context: Context, sender: String, body: String, timestamp: Long) {
            context.startForegroundService(
                Intent(context, SmsForwardService::class.java).apply {
                    putExtra(EXTRA_SENDER, sender)
                    putExtra(EXTRA_BODY, body)
                    putExtra(EXTRA_TIMESTAMP, timestamp)
                }
            )
        }

        fun drain(context: Context) {
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "H2",
                location = "SmsForwardService.drain",
                message = "drain() requested",
                runId = "sync-debug"
            )
            // #endregion
            context.startForegroundService(
                Intent(context, SmsForwardService::class.java).apply {
                    action = ACTION_DRAIN
                }
            )
        }
    }
}
