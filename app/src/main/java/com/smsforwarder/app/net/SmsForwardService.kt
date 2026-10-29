package com.smsforwarder.app.net

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * Enqueues one outbox row per enabled endpoint, then drains due rows with ACK-aware
 * retries until each receiver confirms or the ~3 minute window expires.
 */
class SmsForwardService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg("Forwarding SMS (LAN)…")

        when (intent?.action) {
            ACTION_DRAIN -> {
                thread(name = "sms-outbox-drain") {
                    try {
                        drainOnce()
                    } finally {
                        maybeReschedule()
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
                        maybeReschedule()
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
                        nextAttemptAt = now,
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
        val now = System.currentTimeMillis()
        val due = runBlocking { app.database.outboxDao().duePending(now) }
        for (row in due) {
            attempt(app, row)
        }
    }

    private fun attempt(app: SmsForwarderApp, row: OutboxEntity) {
        val dao = app.database.outboxDao()
        val now = System.currentTimeMillis()
        if (now - row.createdAt > OutboxPolicy.EXPIRE_MS) {
            runBlocking {
                dao.updateAttempt(
                    id = row.id,
                    attempts = row.attempts,
                    nextAttemptAt = now,
                    lastError = "expired",
                    state = OutboxEntity.STATE_EXPIRED
                )
            }
            Log.w(TAG, "Outbox expired ${row.host}:${row.port} msg=${row.msgId.take(8)}")
            return
        }
        val result = ForwardClient.forward(
            context = this,
            host = row.host,
            port = row.port,
            secret = row.secret,
            sender = row.sender,
            body = row.body,
            timestamp = row.smsTimestamp,
            msgId = row.msgId
        )
        if (result.isSuccess) {
            runBlocking { dao.markDone(row.id) }
            Log.i(TAG, "ACK ok ${row.host}:${row.port}")
            return
        }
        val attempts = row.attempts + 1
        val err = result.exceptionOrNull()?.message?.take(160)
        val next = now + OutboxPolicy.backoffAfterAttempt(attempts)
        val expired = next - row.createdAt > OutboxPolicy.EXPIRE_MS ||
            now - row.createdAt > OutboxPolicy.EXPIRE_MS
        runBlocking {
            dao.updateAttempt(
                id = row.id,
                attempts = attempts,
                nextAttemptAt = if (expired) now else next,
                lastError = err,
                state = if (expired) OutboxEntity.STATE_EXPIRED else OutboxEntity.STATE_PENDING
            )
        }
        Log.w(TAG, "ACK fail ${row.host}:${row.port} attempt=$attempts err=$err")
    }

    private fun maybeReschedule() {
        val app = application as SmsForwarderApp
        val nextAt = runBlocking { app.database.outboxDao().nextPendingAt() } ?: return
        val delay = (nextAt - System.currentTimeMillis()).coerceAtLeast(500L).coerceAtMost(60_000L)
        handler.postDelayed({ drain(applicationContext) }, delay)
    }

    companion object {
        private const val TAG = "SmsForwardService"
        private const val NOTIFICATION_ID = 44
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
            context.startForegroundService(
                Intent(context, SmsForwardService::class.java).apply {
                    action = ACTION_DRAIN
                }
            )
        }
    }
}
