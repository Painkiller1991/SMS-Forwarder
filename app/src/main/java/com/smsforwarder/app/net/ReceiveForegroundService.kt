package com.smsforwarder.app.net

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.smsforwarder.app.data.ListenFlag
import com.smsforwarder.app.data.MessageEntity
import com.smsforwarder.app.data.SecretStore
import com.smsforwarder.app.debug.AgentDebugLog
import androidx.core.content.ContextCompat
import com.smsforwarder.app.notify.InboxNotifier
import com.smsforwarder.app.otp.OtpParser
import com.smsforwarder.app.ui.MainActivity
import com.smsforwarder.app.update.UpdateLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.BindException

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

    private val watchdog = object : Runnable {
        override fun run() {
            scope.launch { ensureListening(updateUi = false) }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // File flag is visible across UI + :recv (SharedPreferences is not)
        val listenOn = ListenFlag.read(this)
        if (UpdateLock.isActive(this)) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H_ON",
            location = "ReceiveForegroundService.onStartCommand",
            message = "Service start command",
            data = mapOf(
                "action" to (intent?.action ?: "START"),
                "listenOn" to listenOn,
                "serverUp" to (server != null)
            ),
            runId = "listen-toggle"
        )
        // #endregion
        when (intent?.action) {
            ACTION_STOP -> {
                if (listenOn) {
                    // Stale STOP after user already turned ON again — start instead
                    beginListening()
                    return START_STICKY
                }
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_ENSURE -> {
                if (!listenOn) {
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                beginListening()
                return START_STICKY
            }
            else -> {
                if (!listenOn) {
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                beginListening()
            }
        }
        return START_STICKY
    }

    private fun beginListening() {
        startForegroundNow(buildListeningNotification(listeningStatus()))
        registerNetworkCallback()
        KeepAliveScheduler.schedule(this)
        scope.launch { startLanServer(updateUi = true) }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
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
                // Only start if socket is down — do not rebind on every Wi‑Fi callback
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

    private fun stopLanServerQuiet() {
        val s = server
        server = null
        if (s != null) {
            runCatching { s.closeAllConnections() }
            runCatching { s.stop() }
        }
    }

    private fun isAddrInUse(e: Throwable): Boolean {
        val msg = e.message.orEmpty()
        return e is BindException ||
            msg.contains("EADDRINUSE", ignoreCase = true) ||
            msg.contains("Address already in use", ignoreCase = true)
    }

    private suspend fun startLanServer(updateUi: Boolean) {
        val app = application as SmsForwarderApp
        val prefs = app.prefs
        if (!ListenFlag.read(this)) return
        if (server != null) {
            if (updateUi) handler.post { updateListeningNotification(force = true) }
            return
        }
        SecretStore.write(this, prefs.sharedSecret)
        var lastError: Exception? = null
        for (attempt in 0 until BIND_ATTEMPTS) {
            try {
                withBriefWake {
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
                        "bind" to ReceiveServer.BIND_HOST,
                        "wifiIp" to (WifiIp.localIpv4(this) ?: "?"),
                        "port" to (server?.listeningPort ?: -1),
                        "attempt" to (attempt + 1)
                    ),
                    runId = "lan-calm"
                )
                // #endregion
                if (updateUi) handler.post { updateListeningNotification(force = true) }
                return
            } catch (e: Exception) {
                lastError = e
                stopLanServerQuiet()
                if (isAddrInUse(e) && attempt < BIND_ATTEMPTS - 1) {
                    Log.w(TAG, "Bind EADDRINUSE — retry ${attempt + 1}", e)
                    delay(BIND_RETRY_MS)
                } else if (!isAddrInUse(e)) {
                    break
                }
            }
        }
        Log.e(TAG, "LAN server start failed", lastError)
        handler.post {
            lastStatus = "FAILED: ${lastError?.message}"
            startForegroundNow(buildListeningNotification(lastStatus))
        }
    }

    private suspend fun ensureListening(updateUi: Boolean) {
        if (!ListenFlag.read(this)) return
        if (server != null) {
            if (updateUi) handler.post { updateListeningNotification(force = false) }
            return
        }
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "RECV_CLOSED",
            location = "ReceiveForegroundService.ensureListening",
            message = "Socket missing — start once",
            runId = "lan-calm"
        )
        // #endregion
        startLanServer(updateUi = true)
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
                // Store before notify/copy so Autofill's short wait can see the code
                app.otpStore.put(otp, sender)
                // Delayed sensitive clipboard — avoid stealing focus from OTP field
                InboxNotifier.copyOtpDelayed(app, otp)
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

            // Keep FGS notification as Listening…; SMS alert is a separate shade notification
            handler.post { updateListeningNotification(force = true) }
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

    private fun stopSelfSafely() {
        handler.removeCallbacks(watchdog)
        KeepAliveScheduler.cancel(this)
        networkCallback?.let {
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            }
        }
        networkCallback = null
        // Do not clear ListenFlag / prefs here — UI owns ON/OFF (avoids race with quick OFF→ON)
        stopLanServerQuiet()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        networkCallback?.let {
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            }
        }
        networkCallback = null
        stopLanServerQuiet()
        scope.cancel()
        if (ListenFlag.read(applicationContext)) {
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
        private const val BIND_ATTEMPTS = 3
        private const val BIND_RETRY_MS = 250L
        const val ACTION_STOP = "com.smsforwarder.app.STOP_RECEIVE"
        const val ACTION_ENSURE = "com.smsforwarder.app.ENSURE_RECEIVE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReceiveForegroundService::class.java)
            )
        }

        /** Quiet re-entry from alarm — avoids start/stop flicker. */
        fun ensure(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReceiveForegroundService::class.java).apply {
                    action = ACTION_ENSURE
                }
            )
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReceiveForegroundService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
