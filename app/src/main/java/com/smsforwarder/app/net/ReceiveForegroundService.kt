package com.smsforwarder.app.net

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.smsforwarder.app.R
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.MessageEntity
import com.smsforwarder.app.data.SecretStore
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.notify.InboxNotifier
import com.smsforwarder.app.otp.OtpParser
import com.smsforwarder.app.ui.MainActivity
import com.smsforwarder.app.ui.SmsAlertActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Stable LAN listener via one quiet foreground notification.
 * No per-minute restarts, no ticking clock on the notification (those looked like open/close).
 * Sender already hits /health before each SMS — that is the light “wake check”.
 */
class ReceiveForegroundService : Service() {
    private var server: ReceiveServer? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastStatus: String = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private val restoreListening = Runnable {
        updateListeningNotification(force = true)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            scope.launch { ensureListening(updateUi = false) }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_ENSURE -> {
                // Quiet path from rare alarm — do not flash “Starting…”
                if (server == null) {
                    startForegroundNow(buildListeningNotification(listeningStatus()))
                } else {
                    startForegroundNow(buildListeningNotification(lastStatus.ifBlank { listeningStatus() }))
                }
                registerNetworkCallback()
                KeepAliveScheduler.schedule(this)
                scope.launch { ensureListening(updateUi = server == null) }
                handler.removeCallbacks(watchdog)
                handler.postDelayed(watchdog, WATCHDOG_MS)
                return START_STICKY
            }
            else -> {
                startForegroundNow(buildListeningNotification(listeningStatus()))
                registerNetworkCallback()
                KeepAliveScheduler.schedule(this)
                scope.launch { startLanServer(updateUi = true) }
                handler.removeCallbacks(watchdog)
                handler.postDelayed(watchdog, WATCHDOG_MS)
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H_SWIPE",
            location = "ReceiveForegroundService.onTaskRemoved",
            message = "UI swiped — keep :recv listener + schedule watchdog",
            data = mapOf("serverUp" to (server != null)),
            runId = "lan-recv"
        )
        // #endregion
        KeepAliveScheduler.schedule(this)
        ServiceWatchdog.scheduleSoon(this)
        scope.launch { ensureListening(updateUi = false) }
        // Re-assert FGS so OEMs do not demote this process after task removal
        startForegroundNow(buildListeningNotification(listeningStatus()))
        super.onTaskRemoved(rootIntent)
    }

    private fun listeningStatus(): String {
        val ip = WifiIp.localIpv4(this) ?: "no-wifi"
        val port = server?.listeningPort ?: ReceiveServer.DEFAULT_PORT
        return if (server != null) "Listening $ip:$port" else "Starting… $ip:$port"
    }

    private fun updateListeningNotification(force: Boolean = false) {
        val status = listeningStatus()
        if (!force && status == lastStatus) return
        lastStatus = status
        startForegroundNow(buildListeningNotification(status))
    }

    private fun startForegroundNow(notification: Notification) {
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

    /** Brief CPU wake only while binding the socket / handling SMS — not all day. */
    private fun withBriefWake(block: () -> Unit) {
        val pm = getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smsforwarder:lan_brief").apply {
            setReferenceCounted(false)
            acquire(30_000L)
        }
        try {
            block()
        } finally {
            runCatching { if (wl.isHeld) wl.release() }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { ensureListening(updateUi = true) }
            }

            override fun onLost(network: Network) {
                handler.post {
                    lastStatus = "Wi‑Fi lost — will resume on reconnect"
                    startForegroundNow(buildListeningNotification(lastStatus))
                }
            }
        }
        networkCallback = cb
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { cm.registerNetworkCallback(req, cb) }
    }

    private fun startLanServer(updateUi: Boolean) {
        val app = application as SmsForwarderApp
        val prefs = app.prefs
        prefs.receiveEnabled = true
        try {
            if (server != null) {
                if (updateUi) handler.post { updateListeningNotification(force = true) }
                return
            }
            withBriefWake {
                SecretStore.write(this, prefs.sharedSecret)
                val receiveServer = ReceiveServer(
                    port = ReceiveServer.DEFAULT_PORT,
                    sharedSecretProvider = {
                        SecretStore.read(this) ?: prefs.sharedSecret
                    },
                    onSms = { sender, body, timestamp ->
                        handleIncoming(app, sender, body, timestamp)
                    }
                )
                receiveServer.startListening()
                server = receiveServer
            }
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "LAN",
                location = "ReceiveForegroundService.startLanServer",
                message = "LAN server listening (calm mode)",
                data = mapOf(
                    "ip" to (WifiIp.localIpv4(this) ?: "?"),
                    "port" to (server?.listeningPort ?: -1)
                ),
                runId = "lan-calm"
            )
            // #endregion
            if (updateUi) handler.post { updateListeningNotification(force = true) }
        } catch (e: Exception) {
            Log.e(TAG, "LAN server start failed", e)
            handler.post {
                lastStatus = "FAILED: ${e.message}"
                startForegroundNow(buildListeningNotification(lastStatus))
            }
        }
    }

    private fun ensureListening(updateUi: Boolean) {
        val app = application as SmsForwarderApp
        if (!app.prefs.receiveEnabled) return
        if (server == null) {
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "RECV_CLOSED",
                location = "ReceiveForegroundService.ensureListening",
                message = "Socket missing — start once",
                runId = "lan-calm"
            )
            // #endregion
            startLanServer(updateUi = true)
        } else if (updateUi) {
            handler.post { updateListeningNotification(force = false) }
        }
    }

    private fun handleIncoming(
        app: SmsForwarderApp,
        sender: String,
        body: String,
        timestamp: Long
    ) {
        withBriefWake {
            val otp = OtpParser.extract(body)
            if (otp != null) {
                app.otpStore.put(otp, sender)
            }
            scope.launch {
                app.database.messageDao().insert(
                    MessageEntity(
                        sender = sender,
                        body = body,
                        receivedAt = timestamp,
                        otp = otp
                    )
                )
            }

            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "NOTIF",
                location = "ReceiveForegroundService.handleIncoming",
                message = "LAN SMS received",
                data = mapOf("sender" to sender.take(32), "bodyLen" to body.length),
                runId = "lan-calm"
            )
            // #endregion

            handler.post {
                startForegroundNow(buildSmsAlertNotification(sender, body, otp))
                handler.removeCallbacks(restoreListening)
                handler.postDelayed(restoreListening, ALERT_HOLD_MS)
            }
            InboxNotifier.show(app, sender, body, otp)
        }
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun fullScreenAlertIntent(sender: String, body: String, otp: String?): PendingIntent {
        val i = Intent(this, SmsAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(SmsAlertActivity.EXTRA_SENDER, sender)
            putExtra(SmsAlertActivity.EXTRA_BODY, body)
            putExtra(SmsAlertActivity.EXTRA_OTP, otp)
        }
        return PendingIntent.getActivity(
            this,
            (System.currentTimeMillis() % 100000).toInt(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildListeningNotification(status: String): Notification {
        return NotificationCompat.Builder(this, SmsForwarderApp.CHANNEL_RECEIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SMS receive (local Wi‑Fi)")
            .setContentText(status)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildSmsAlertNotification(sender: String, body: String, otp: String?): Notification {
        val title = getString(R.string.notification_title_sms, sender)
        val text = if (!otp.isNullOrBlank()) "OTP $otp — $body" else body
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        return NotificationCompat.Builder(this, SmsForwarderApp.CHANNEL_INBOX)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .setFullScreenIntent(fullScreenAlertIntent(sender, body, otp), true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setSound(sound)
            .setOnlyAlertOnce(false)
            .setTicker(text.take(80))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun stopSelfSafely() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(restoreListening)
        KeepAliveScheduler.cancel(this)
        networkCallback?.let {
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            }
        }
        networkCallback = null
        (application as? SmsForwarderApp)?.prefs?.receiveEnabled = false
        runCatching { server?.stop() }
        server = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(restoreListening)
        networkCallback?.let {
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            }
        }
        networkCallback = null
        runCatching { server?.stop() }
        server = null
        scope.cancel()
        val prefs = (application as? SmsForwarderApp)?.prefs
        if (prefs?.receiveEnabled == true) {
            KeepAliveScheduler.schedule(applicationContext)
            ensure(applicationContext)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ReceiveService"
        private const val NOTIFICATION_ID = 42
        /** Soft check every 5 min — only restarts socket if missing. */
        private const val WATCHDOG_MS = 5 * 60 * 1000L
        private const val ALERT_HOLD_MS = 10_000L
        const val ACTION_STOP = "com.smsforwarder.app.STOP_RECEIVE"
        const val ACTION_ENSURE = "com.smsforwarder.app.ENSURE_RECEIVE"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReceiveForegroundService::class.java))
        }

        /** Quiet re-entry from alarm — avoids start/stop flicker. */
        fun ensure(context: Context) {
            context.startForegroundService(
                Intent(context, ReceiveForegroundService::class.java).apply {
                    action = ACTION_ENSURE
                }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ReceiveForegroundService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
