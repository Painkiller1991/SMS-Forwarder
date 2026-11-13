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
import com.smsforwarder.app.data.EndpointStore
import com.smsforwarder.app.data.OutboxEntity
import com.smsforwarder.app.data.OutboxPendingMirror
import com.smsforwarder.app.data.ReceiverEndpoint
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.widget.StatusWidgetProvider
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
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
                        extraSecret = "",
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
        // Serialize drains — parallel keep-alive + SMS caused duplicate POSTs and delays.
        if (!drainLock.tryLock()) {
            drainAgain = true
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "H14",
                location = "SmsForwardService.drainOnce",
                message = "Drain coalesced (already running)",
                runId = "drain-debug"
            )
            // #endregion
            return
        }
        try {
            do {
                drainAgain = false
                drainOnceLocked()
            } while (drainAgain)
        } finally {
            drainLock.unlock()
        }
    }

    private fun drainOnceLocked() {
        val app = application as SmsForwarderApp
        val live = app.prefs.enabledEndpoints()
        // Re-apply current LAN address/secrets onto pending rows (IP/re-pair / Extra Secret drift).
        runBlocking {
            val dao = app.database.outboxDao()
            // Re-pair can leave duplicate msgId rows; unique index blocks remaps.
            val removed = dao.dedupePendingByMsgId()
            dao.clearPendingExtraSecrets()
            if (live.size == 1) {
                val ep = live.first()
                dao.refreshAllPending(ep.id, ep.host, ep.port, ep.secret, "")
            } else {
                for (ep in live) {
                    dao.refreshPendingEndpoint(ep.id, ep.host, ep.port, ep.secret, "")
                    dao.refreshPendingByHostPort(ep.id, ep.host, ep.port, ep.secret, "")
                }
            }
            // #region agent log
            if (removed > 0) {
                AgentDebugLog.log(
                    hypothesisId = "H15",
                    location = "SmsForwardService.drainOnceLocked",
                    message = "Deduped pending by msgId",
                    data = mapOf("removed" to removed),
                    runId = "drain-debug"
                )
            }
            // #endregion
        }
        val pending = runBlocking { app.database.outboxDao().allPendingOrdered() }
        val first = pending.firstOrNull()
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H13",
            location = "SmsForwardService.drainOnce",
            message = "Drain start",
            data = mapOf(
                "pending" to pending.size,
                "liveEps" to live.size,
                "liveHost" to (live.firstOrNull()?.host ?: "none"),
                "rowEpId" to (first?.endpointId?.take(12) ?: "none"),
                "epFile" to (EndpointStore.read(this) != null)
            ),
            runId = "drain-debug"
        )
        // #endregion
        if (pending.isEmpty()) {
            OutboxPendingMirror.write(this, 0)
            notifyOutboxChanged()
            SenderKeepAliveService.notifyPendingCleared(this)
            return
        }
        val newestMsgId = pending.maxWithOrNull(
            compareBy({ it.smsTimestamp }, { it.createdAt }, { it.id })
        )?.msgId
        // Newest first so a live SMS is not stuck behind a long quiet catch-up.
        val ordered = pending.sortedWith(
            compareByDescending<OutboxEntity> { it.msgId == newestMsgId }
                .thenBy { it.smsTimestamp }
                .thenBy { it.createdAt }
                .thenBy { it.id }
        )
        var synced = 0
        for (row in ordered) {
            val quiet = row.msgId != newestMsgId
            val ok = attempt(app, row, quiet = quiet)
            if (!ok) {
                // #region agent log
                AgentDebugLog.log(
                    hypothesisId = "H3",
                    location = "SmsForwardService.drainOnce.break",
                    message = "Drain aborted on first failure",
                    data = mapOf(
                        "syncedBeforeBreak" to synced,
                        "remaining" to (ordered.size - synced),
                        "failedHost" to "${row.host}:${row.port}",
                        "failedMsgId" to row.msgId.take(36),
                        "attempts" to row.attempts,
                        "lastError" to (row.lastError?.take(80) ?: "none")
                    ),
                    runId = "drain-debug"
                )
                // #endregion
                // Stop this drain; keep-alive / Sync / successful Test will retry.
                break
            }
            synced++
            if (quiet) {
                Thread.sleep(POST_GAP_MS)
            }
        }
        val remaining = runBlocking { app.database.outboxDao().pendingCount() }
        OutboxPendingMirror.write(this, remaining)
        // #region agent log
        if (synced > 0 || remaining > 0) {
            AgentDebugLog.log(
                hypothesisId = "H4",
                location = "SmsForwardService.drainOnce.done",
                message = "Drain progress",
                data = mapOf(
                    "synced" to synced,
                    "pendingAtStart" to pending.size,
                    "remaining" to remaining
                ),
                runId = "drain-debug"
            )
        }
        // #endregion
        notifyOutboxChanged()
        if (remaining > 0) {
            SenderKeepAliveService.notifyPendingLagging(this)
        } else {
            SenderKeepAliveService.notifyPendingCleared(this)
        }
    }

    private fun notifyOutboxChanged() {
        StatusWidgetProvider.requestUpdate(this)
        sendBroadcast(
            Intent(ACTION_OUTBOX_CHANGED).setPackage(packageName)
        )
    }

    private fun resolveLiveEndpoint(
        live: List<ReceiverEndpoint>,
        row: OutboxEntity
    ): Pair<ReceiverEndpoint?, String> {
        live.find { it.id == row.endpointId }?.let { return it to "id" }
        live.find {
            it.host.equals(row.host, ignoreCase = true) && it.port == row.port
        }?.let { return it to "host" }
        if (live.size == 1) return live.first() to "single"
        return null to "none"
    }

    /** @return true if synced (ACK ok). */
    private fun attempt(app: SmsForwarderApp, row: OutboxEntity, quiet: Boolean): Boolean {
        val dao = app.database.outboxDao()
        val live = app.prefs.enabledEndpoints()
        val (liveEp, matchBy) = resolveLiveEndpoint(live, row)
        val host = liveEp?.host ?: row.host
        val port = liveEp?.port ?: row.port
        val secret = liveEp?.secret ?: row.secret
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H7_H10",
            location = "SmsForwardService.attempt",
            message = "Outbox attempt",
            data = mapOf(
                "msgId" to row.msgId.take(36),
                "matchBy" to matchBy,
                "useHost" to "$host:$port",
                "quiet" to quiet,
                "attemptsBefore" to row.attempts
            ),
            runId = "drain-debug"
        )
        // #endregion
        // Auth skew is ±5 min: sign with "now", keep original SMS time for inbox.
        val authTs = System.currentTimeMillis()
        val result = ForwardClient.forward(
            context = this,
            host = host,
            port = port,
            secret = secret,
            sender = row.sender,
            body = row.body,
            timestamp = authTs,
            msgId = row.msgId,
            quiet = quiet,
            test = false,
            smsTimestamp = row.smsTimestamp,
            extraSecret = ""
        )
        if (result.isSuccess) {
            runBlocking { dao.markSynced(row.id) }
            Log.i(TAG, "Synced $host:$port quiet=$quiet")
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "H3",
                location = "SmsForwardService.attempt.ok",
                message = "Outbox row synced",
                data = mapOf(
                    "msgId" to row.msgId.take(36),
                    "host" to "$host:$port",
                    "matchBy" to matchBy
                ),
                runId = "drain-debug"
            )
            // #endregion
            StatusWidgetProvider.requestUpdate(this)
            return true
        }
        val attempts = row.attempts + 1
        val err = result.exceptionOrNull()?.message?.take(160)
        runBlocking { dao.markAttemptFailed(row.id, attempts, err) }
        Log.w(TAG, "Sync fail $host:$port attempt=$attempts err=$err")
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H7_H10",
            location = "SmsForwardService.attempt.fail",
            message = "Outbox row failed",
            data = mapOf(
                "msgId" to row.msgId.take(36),
                "host" to "$host:$port",
                "matchBy" to matchBy,
                "attempts" to attempts,
                "error" to (err ?: "none")
            ),
            runId = "drain-debug"
        )
        // #endregion
        StatusWidgetProvider.requestUpdate(this)
        return false
    }

    companion object {
        private const val TAG = "SmsForwardService"
        private const val NOTIFICATION_ID = 44
        /** Gap only between quiet catch-up rows (newest is sent first, no wait). */
        private const val POST_GAP_MS = 40L
        private val drainLock = ReentrantLock()
        @Volatile private var drainAgain = false
        const val EXTRA_SENDER = "sender"
        const val EXTRA_BODY = "body"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val ACTION_DRAIN = "com.smsforwarder.app.DRAIN_OUTBOX"
        /** UI process listens so pending count updates after :send markSynced. */
        const val ACTION_OUTBOX_CHANGED = "com.smsforwarder.app.OUTBOX_CHANGED"

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
