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
import com.smsforwarder.app.data.LastInboundStore
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.BindException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * LAN listener with an always-visible ON/OFF foreground status notification.
 * VPN/Wi‑Fi transitions force a hard rebind so a dead socket is never kept after VPN drops.
 */
class ReceiveForegroundService : Service() {
    private var server: ReceiveServer? = null
    private var boundHost: String? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnCallback: ConnectivityManager.NetworkCallback? = null
    private var lastStatus: String = ""
    private var lastTitle: String = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val listenMutex = Mutex()
    @Volatile private var pendingUpdateUi = false
    @Volatile private var pendingForceRebind = false
    @Volatile private var tearingDown = false

    private val watchdog = object : Runnable {
        override fun run() {
            if (ListenFlag.read(this@ReceiveForegroundService)) {
                scope.launch { ensureListeningSafe(updateUi = false, forceRebind = false) }
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private val debounceEnsure = Runnable {
        val ui = pendingUpdateUi
        val force = pendingForceRebind
        pendingUpdateUi = false
        pendingForceRebind = false
        if (ListenFlag.read(this@ReceiveForegroundService)) {
            scope.launch { ensureListeningSafe(updateUi = ui, forceRebind = force) }
        }
    }

    private val bindRetry = Runnable {
        if (ListenFlag.read(this@ReceiveForegroundService)) {
            scope.launch { ensureListeningSafe(updateUi = true, forceRebind = true) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val listenOn = ListenFlag.read(this)
        publishStatus(force = true)

        if (UpdateLock.isActive(this)) {
            tearDownFully()
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
            ACTION_TEARDOWN -> {
                tearDownFully()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                if (listenOn) {
                    beginListening(forceRebind = true)
                } else {
                    showOffStatus()
                }
                return START_STICKY
            }
            ACTION_FORCE_REBIND -> {
                if (listenOn) beginListening(forceRebind = true) else showOffStatus()
                return START_STICKY
            }
            ACTION_ENSURE -> {
                if (listenOn) beginListening(forceRebind = false) else showOffStatus()
                return START_STICKY
            }
            else -> {
                // Default start (Receive ON) always hard-rebinds.
                if (listenOn) beginListening(forceRebind = true) else showOffStatus()
            }
        }
        return START_STICKY
    }

    private fun beginListening(forceRebind: Boolean) {
        publishStatus(force = true)
        registerNetworkCallback()
        LastInboundStore.seedIfMissing(this)
        KeepAliveScheduler.schedule(this)
        // Do not push the 12h alarm forward on every ENSURE — only arm if missing.
        IdleRebindScheduler.ensureScheduled(this)
        scope.launch { ensureListeningSafe(updateUi = true, forceRebind = forceRebind) }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    /** Port closed, but keep FGS so ON/OFF is always visible. */
    private fun showOffStatus() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(debounceEnsure)
        handler.removeCallbacks(bindRetry)
        pendingForceRebind = false
        pendingUpdateUi = false
        KeepAliveScheduler.cancel(this)
        IdleRebindScheduler.cancel(this)
        unregisterNetworkCallbacks()
        scope.launch {
            listenMutex.withLock { stopLanServerQuiet() }
            handler.post { publishStatus(force = true) }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AgentDebugLog.log(
            hypothesisId = "H_SWIPE",
            location = "ReceiveForegroundService.onTaskRemoved",
            message = "UI swiped — keep :recv status + schedule watchdog",
            data = mapOf("serverUp" to (server != null), "listenOn" to ListenFlag.read(this)),
            runId = "lan-recv"
        )
        if (ListenFlag.read(this)) {
            KeepAliveScheduler.schedule(this)
            IdleRebindScheduler.ensureScheduled(this)
            ServiceWatchdog.scheduleSoon(this)
            scope.launch { ensureListeningSafe(updateUi = false, forceRebind = false) }
        }
        publishStatus(force = true)
        super.onTaskRemoved(rootIntent)
    }

    /**
     * While VPN is active, bind all interfaces so inbound LAN is less likely to be
     * stuck on a Wi‑Fi address isolated by the VPN stack. Notification still shows Wi‑Fi IP.
     */
    private fun desiredBindHost(): String {
        if (LanNetwork.vpnActive(this)) return ReceiveServer.BIND_ALL
        return WifiIp.localIpv4(this) ?: ReceiveServer.BIND_ALL
    }

    private fun statusTitle(): String =
        if (ListenFlag.read(this)) "Receive ON" else "Receive OFF"

    private fun listeningStatus(): String {
        if (!ListenFlag.read(this)) {
            return "Port closed — tap to open"
        }
        val ip = WifiIp.localIpv4(this)
            ?: if (isRealLanIp(boundHost)) boundHost!! else boundHost ?: "no-wifi"
        val port = server?.listeningPort ?: ReceiveServer.DEFAULT_PORT
        val vpn = if (LanNetwork.vpnActive(this)) " (VPN)" else ""
        return if (server != null) "Listening $ip:$port$vpn" else "Starting… $ip:$port"
    }

    private fun publishStatus(force: Boolean = false) {
        val title = statusTitle()
        val status = listeningStatus()
        if (!force && title == lastTitle && status == lastStatus) return
        lastTitle = title
        lastStatus = status
        startForegroundNow(buildStatusNotification(title, status))
    }

    private fun updateListeningNotification(force: Boolean = false) {
        publishStatus(force)
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

    private fun scheduleEnsure(updateUi: Boolean, forceRebind: Boolean = false) {
        if (!ListenFlag.read(this)) return
        if (updateUi) pendingUpdateUi = true
        if (forceRebind) pendingForceRebind = true
        handler.removeCallbacks(debounceEnsure)
        handler.postDelayed(debounceEnsure, NETWORK_DEBOUNCE_MS)
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        if (wifiCallback == null) {
            val wifiCb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scheduleEnsure(updateUi = true, forceRebind = true)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    scheduleEnsure(updateUi = true, forceRebind = false)
                }

                override fun onLost(network: Network) {
                    handler.post {
                        lastStatus = "Wi‑Fi lost — will resume on reconnect"
                        lastTitle = statusTitle()
                        startForegroundNow(buildStatusNotification(lastTitle, lastStatus))
                    }
                    scheduleEnsure(updateUi = true, forceRebind = true)
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
                    scheduleEnsure(updateUi = true, forceRebind = true)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    // Soft ensure only — capability chatter must not flap the listen socket.
                    scheduleEnsure(updateUi = true, forceRebind = false)
                }

                override fun onLost(network: Network) {
                    // VPN dropped — hard rebind so we do not keep a dead socket on the same IP.
                    scheduleEnsure(updateUi = true, forceRebind = true)
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

    /** True if the current NanoHTTPD still answers /health locally. */
    private fun isServerAlive(): Boolean {
        val s = server ?: return false
        val port = s.listeningPort.takeIf { it > 0 } ?: ReceiveServer.DEFAULT_PORT
        return runCatching {
            // Prefer loopback — works for both specific-IP and 0.0.0.0 binds.
            val url = URL("http://127.0.0.1:$port/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 400
                readTimeout = 400
                requestMethod = "GET"
                instanceFollowRedirects = false
            }
            try {
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        }.getOrElse {
            // Fallback: TCP connect to bound host or loopback.
            runCatching {
                Socket().use { sock ->
                    val host = if (isRealLanIp(boundHost)) boundHost!! else "127.0.0.1"
                    sock.connect(InetSocketAddress(host, port), 400)
                    true
                }
            }.getOrDefault(false)
        }
    }

    private suspend fun ensureListeningSafe(updateUi: Boolean, forceRebind: Boolean) {
        listenMutex.withLock {
            ensureListening(updateUi, forceRebind)
        }
    }

    private suspend fun startLanServer(updateUi: Boolean, forceRebind: Boolean) {
        val app = application as SmsForwarderApp
        val prefs = app.prefs
        if (!ListenFlag.read(this)) return
        val host = desiredBindHost()
        if (!forceRebind && server != null && boundHost == host && isServerAlive()) {
            if (updateUi) handler.post { updateListeningNotification(force = true) }
            return
        }
        if (server != null) {
            stopLanServerQuiet()
            delay(PORT_SETTLE_MS)
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
                        onSms = { sender, body, timestamp, msgId, quiet, test ->
                            handleIncoming(app, sender, body, timestamp, msgId, quiet, test)
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
                        "attempt" to (attempt + 1),
                        "force" to forceRebind
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
            lastTitle = "Receive ON"
            lastStatus = "FAILED: ${lastError?.message}"
            startForegroundNow(buildStatusNotification(lastTitle, lastStatus))
        }
        handler.removeCallbacks(bindRetry)
        handler.postDelayed(bindRetry, BIND_FAIL_RETRY_MS)
    }

    private suspend fun ensureListening(updateUi: Boolean, forceRebind: Boolean) {
        if (!ListenFlag.read(this)) return
        val host = desiredBindHost()

        if (forceRebind) {
            AgentDebugLog.log(
                hypothesisId = "RECV_FORCE",
                location = "ReceiveForegroundService.ensureListening",
                message = "Force rebind",
                data = mapOf(
                    "was" to (boundHost ?: "none"),
                    "want" to host,
                    "vpn" to LanNetwork.vpnActive(this),
                    "alive" to (server != null && isServerAlive())
                ),
                runId = "lan-calm"
            )
            startLanServer(updateUi = true, forceRebind = true)
            return
        }

        if (server != null && boundHost == host) {
            if (isServerAlive()) {
                if (updateUi) handler.post { updateListeningNotification(force = false) }
                return
            }
            // Same host string but dead socket — rebind.
            startLanServer(updateUi = true, forceRebind = true)
            return
        }

        // Anti-flap: keep a live LAN bind when resolve briefly returns null → 0.0.0.0
        // (and VPN is not forcing BIND_ALL).
        if (server != null &&
            isRealLanIp(boundHost) &&
            host == ReceiveServer.BIND_ALL &&
            !LanNetwork.vpnActive(this) &&
            isServerAlive()
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
        startLanServer(updateUi = true, forceRebind = false)
    }

    private fun handleIncoming(
        app: SmsForwarderApp,
        sender: String,
        body: String,
        timestamp: Long,
        msgId: String?,
        quiet: Boolean,
        test: Boolean
    ): String {
        var status = "stored"
        withBriefWake {
            status = IncomingSms.acceptBlocking(
                app, sender, body, timestamp, msgId, quiet = quiet, test = test
            )
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

    private fun buildStatusNotification(title: String, status: String): Notification {
        return NotificationCompat.Builder(this, SmsForwarderApp.CHANNEL_RECEIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(status)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun tearDownFully() {
        tearingDown = true
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(debounceEnsure)
        handler.removeCallbacks(bindRetry)
        KeepAliveScheduler.cancel(this)
        IdleRebindScheduler.cancel(this)
        unregisterNetworkCallbacks()
        runBlocking {
            listenMutex.withLock { stopLanServerQuiet() }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(debounceEnsure)
        handler.removeCallbacks(bindRetry)
        unregisterNetworkCallbacks()
        runBlocking {
            listenMutex.withLock { stopLanServerQuiet() }
        }
        scope.cancel()
        val app = applicationContext as? SmsForwarderApp
        if (!tearingDown &&
            app != null &&
            app.prefs.role == com.smsforwarder.app.data.AppRole.RECEIVER
        ) {
            if (ListenFlag.read(applicationContext)) {
                KeepAliveScheduler.schedule(applicationContext)
                IdleRebindScheduler.ensureScheduled(applicationContext)
            }
            ensure(applicationContext)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ReceiveService"
        private const val NOTIFICATION_ID = 42
        private const val WATCHDOG_MS = 5 * 60 * 1000L
        private const val NETWORK_DEBOUNCE_MS = 400L
        private const val PORT_SETTLE_MS = 700L
        private const val BIND_ATTEMPTS = 6
        private const val BIND_RETRY_MS = 400L
        private const val BIND_FAIL_RETRY_MS = 2_000L
        const val ACTION_STOP = "com.smsforwarder.app.STOP_RECEIVE"
        const val ACTION_ENSURE = "com.smsforwarder.app.ENSURE_RECEIVE"
        const val ACTION_FORCE_REBIND = "com.smsforwarder.app.FORCE_REBIND_RECEIVE"
        const val ACTION_TEARDOWN = "com.smsforwarder.app.TEARDOWN_RECEIVE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReceiveForegroundService::class.java).apply {
                    action = ACTION_FORCE_REBIND
                }
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

        /** Hard stop + rebind listen (VPN recovery / Restart listen). */
        fun forceRebind(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReceiveForegroundService::class.java).apply {
                    action = ACTION_FORCE_REBIND
                }
            )
        }

        /** Listen OFF — keep ongoing Receive OFF notification. */
        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReceiveForegroundService::class.java).apply { action = ACTION_STOP }
            )
        }

        /** Leave receiver role — remove status notification entirely. */
        fun teardown(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReceiveForegroundService::class.java).apply {
                    action = ACTION_TEARDOWN
                }
            )
        }
    }
}
