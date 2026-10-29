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
import androidx.core.content.ContextCompat
import com.smsforwarder.app.R
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.ListenFlag
import com.smsforwarder.app.data.SecretStore
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.ui.MainActivity
import com.smsforwarder.app.update.UpdateLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.BindException

/**
 * Stable LAN listener via one quiet foreground notification.
 * VPN/Wi‑Fi transitions are debounced and anti-flapped so we do not tear down
 * a working Wi‑Fi bind when [LanNetwork] briefly returns null.
 */
class ReceiveForegroundService : Service() {
    private var server: ReceiveServer? = null
    private var boundHost: String? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnCallback: ConnectivityManager.NetworkCallback? = null
    private var lastStatus: String = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val listenMutex = Mutex()
    @Volatile private var pendingUpdateUi = false

    private val watchdog = object : Runnable {
        override fun run() {
            scope.launch { ensureListeningSafe(updateUi = false) }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private val debounceEnsure = Runnable {
        val ui = pendingUpdateUi
        pendingUpdateUi = false
        scope.launch { ensureListeningSafe(updateUi = ui) }
    }

    private val bindRetry = Runnable {
        scope.launch { ensureListeningSafe(updateUi = true) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNow(buildListeningNotification(listeningStatus()))

        val listenOn = ListenFlag.read(this)
        if (UpdateLock.isActive(this)) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
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
        when (intent?.action) {
            ACTION_STOP -> {
                if (listenOn) {
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
        scope.launch { ensureListeningSafe(updateUi = true) }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AgentDebugLog.log(
            hypothesisId = "H_SWIPE",
            location = "ReceiveForegroundService.onTaskRemoved",
            message = "UI swiped — keep :recv listener + schedule watchdog",
            data = mapOf("serverUp" to (server != null)),
            runId = "lan-recv"
        )
        KeepAliveScheduler.schedule(this)
        ServiceWatchdog.scheduleSoon(this)
        scope.launch { ensureListeningSafe(updateUi = false) }
        startForegroundNow(buildListeningNotification(listeningStatus()))
        super.onTaskRemoved(rootIntent)
    }

    private fun desiredBindHost(): String =
        WifiIp.localIpv4(this) ?: ReceiveServer.BIND_ALL

    private fun listeningStatus(): String {
        val ip = when {
            boundHost != null && boundHost != ReceiveServer.BIND_ALL -> boundHost!!
            else -> WifiIp.localIpv4(this) ?: boundHost ?: "no-wifi"
        }
        val port = server?.listeningPort ?: ReceiveServer.DEFAULT_PORT
        val vpn = if (LanNetwork.vpnActive(this)) " (VPN)" else ""
        return if (server != null) "Listening $ip:$port$vpn" else "Starting… $ip:$port"
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

    private fun scheduleEnsure(updateUi: Boolean) {
        if (updateUi) pendingUpdateUi = true
        handler.removeCallbacks(debounceEnsure)
        handler.postDelayed(debounceEnsure, NETWORK_DEBOUNCE_MS)
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        if (wifiCallback == null) {
            val wifiCb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scheduleEnsure(updateUi = true)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    scheduleEnsure(updateUi = true)
                }

                override fun onLost(network: Network) {
                    handler.post {
                        lastStatus = "Wi‑Fi lost — will resume on reconnect"
                        startForegroundNow(buildListeningNotification(lastStatus))
                    }
                    scheduleEnsure(updateUi = true)
                }
            }
            wifiCallback = wifiCb
            val wifiReq = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            runCatching { cm.registerNetworkCallback(wifiReq, wifiCb) }
                .onFailure { Log.e(TAG, "Wi‑Fi callback register failed", it) }
        }
        if (vpnCallback == null) {
            val vpnCb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scheduleEnsure(updateUi = true)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    scheduleEnsure(updateUi = true)
                }

                override fun onLost(network: Network) {
                    scheduleEnsure(updateUi = true)
                }
            }
            vpnCallback = vpnCb
            val vpnReq = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .build()
            runCatching { cm.registerNetworkCallback(vpnReq, vpnCb) }
                .onFailure { Log.e(TAG, "VPN callback register failed", it) }
        }
    }

    private fun unregisterNetworkCallbacks() {
        val cm = getSystemService(ConnectivityManager::class.java)
        wifiCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        vpnCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        wifiCallback = null
        vpnCallback = null
    }

    private fun stopLanServerQuiet() {
        val s = server
        server = null
        boundHost = null
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

    private fun isRealLanIp(host: String?): Boolean =
        !host.isNullOrBlank() && host != ReceiveServer.BIND_ALL

    private suspend fun ensureListeningSafe(updateUi: Boolean) {
        listenMutex.withLock {
            ensureListening(updateUi)
        }
    }

    private suspend fun startLanServer(updateUi: Boolean) {
        val app = application as SmsForwarderApp
        val prefs = app.prefs
        if (!ListenFlag.read(this)) return
        val host = desiredBindHost()
        if (server != null && boundHost == host) {
            if (updateUi) handler.post { updateListeningNotification(force = true) }
            return
        }
        if (server != null) {
            stopLanServerQuiet()
        }
        SecretStore.write(this, prefs.sharedSecret)
        var lastError: Exception? = null
        for (attempt in 0 until BIND_ATTEMPTS) {
            try {
                withBriefWake {
                    val receiveServer = ReceiveServer(
                        hostname = host,
                        port = ReceiveServer.DEFAULT_PORT,
                        sharedSecretProvider = {
                            SecretStore.read(this) ?: prefs.sharedSecret
                        },
                        onSms = { sender, body, timestamp, msgId ->
                            handleIncoming(app, sender, body, timestamp, msgId)
                        }
                    )
                    receiveServer.startListening()
                    server = receiveServer
                    boundHost = host
                }
                AgentDebugLog.log(
                    hypothesisId = "LAN",
                    location = "ReceiveForegroundService.startLanServer",
                    message = "LAN server listening",
                    data = mapOf(
                        "bind" to host,
                        "wifiIp" to (WifiIp.localIpv4(this) ?: "?"),
                        "vpn" to LanNetwork.vpnActive(this),
                        "port" to (server?.listeningPort ?: -1),
                        "attempt" to (attempt + 1)
                    ),
                    runId = "lan-calm"
                )
                handler.removeCallbacks(bindRetry)
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
        handler.removeCallbacks(bindRetry)
        handler.postDelayed(bindRetry, BIND_FAIL_RETRY_MS)
    }

    private suspend fun ensureListening(updateUi: Boolean) {
        if (!ListenFlag.read(this)) return
        val host = desiredBindHost()
        if (server != null && boundHost == host) {
            if (updateUi) handler.post { updateListeningNotification(force = false) }
            return
        }
        // Anti-flap: keep a real LAN bind when resolve briefly returns null → 0.0.0.0
        if (server != null &&
            isRealLanIp(boundHost) &&
            host == ReceiveServer.BIND_ALL
        ) {
            if (updateUi) handler.post { updateListeningNotification(force = false) }
            return
        }
        AgentDebugLog.log(
            hypothesisId = "RECV_CLOSED",
            location = "ReceiveForegroundService.ensureListening",
            message = if (server == null) "Socket missing — start" else "Bind host changed — rebind",
            data = mapOf(
                "was" to (boundHost ?: "none"),
                "want" to host,
                "vpn" to LanNetwork.vpnActive(this)
            ),
            runId = "lan-calm"
        )
        startLanServer(updateUi = true)
    }

    private fun handleIncoming(
        app: SmsForwarderApp,
        sender: String,
        body: String,
        timestamp: Long,
        msgId: String?
    ): String {
        var status = "stored"
        withBriefWake {
            status = IncomingSms.acceptBlocking(app, sender, body, timestamp, msgId)
            handler.post { updateListeningNotification(force = true) }
        }
        return status
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
        handler.removeCallbacks(debounceEnsure)
        handler.removeCallbacks(bindRetry)
        KeepAliveScheduler.cancel(this)
        unregisterNetworkCallbacks()
        stopLanServerQuiet()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(debounceEnsure)
        handler.removeCallbacks(bindRetry)
        unregisterNetworkCallbacks()
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
        private const val WATCHDOG_MS = 5 * 60 * 1000L
        private const val NETWORK_DEBOUNCE_MS = 400L
        private const val BIND_ATTEMPTS = 3
        private const val BIND_RETRY_MS = 250L
        private const val BIND_FAIL_RETRY_MS = 2_000L
        const val ACTION_STOP = "com.smsforwarder.app.STOP_RECEIVE"
        const val ACTION_ENSURE = "com.smsforwarder.app.ENSURE_RECEIVE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReceiveForegroundService::class.java)
            )
        }

        fun ensure(context: Context) {
            ContextCompat.startForegroundService(
                context,
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
