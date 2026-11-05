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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.smsforwarder.app.R
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.ui.MainActivity
import kotlinx.coroutines.runBlocking

class SenderKeepAliveService : Service() {
    private var lanCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val debounceDrain = Runnable {
        SmsForwardService.drain(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            unregisterLanCallback()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        AgentDebugLog.log(
            hypothesisId = "KEEPALIVE",
            location = "SenderKeepAliveService.onStartCommand",
            message = "Keep-alive service started",
            runId = "lan-send"
        )
        startForegroundNow(buildNotification())
        KeepAliveScheduler.schedule(this)
        registerLanCallback()
        maybeDrainPending()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AgentDebugLog.log(
            hypothesisId = "H_SWIPE",
            location = "SenderKeepAliveService.onTaskRemoved",
            message = "UI swiped — keep :send alive + schedule watchdog",
            runId = "lan-send"
        )
        KeepAliveScheduler.schedule(this)
        ServiceWatchdog.scheduleSoon(this)
        startForegroundNow(buildNotification())
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        unregisterLanCallback()
        handler.removeCallbacks(debounceDrain)
        super.onDestroy()
    }

    private fun maybeDrainPending() {
        val app = application as? SmsForwarderApp ?: return
        val pending = runBlocking { app.database.outboxDao().pendingCount() }
        if (pending > 0) {
            SmsForwardService.drain(this)
        }
    }

    private fun scheduleDrain() {
        handler.removeCallbacks(debounceDrain)
        handler.postDelayed(debounceDrain, DRAIN_DEBOUNCE_MS)
    }

    private fun registerLanCallback() {
        if (lanCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleDrain()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                scheduleDrain()
            }
        }
        lanCallback = cb
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching { cm.registerNetworkCallback(req, cb) }
            .onFailure { Log.e(TAG, "LAN callback register failed", it) }
    }

    private fun unregisterLanCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        lanCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        lanCallback = null
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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "SenderKeepAlive"
        private const val NOTIFICATION_ID = 43
        private const val DRAIN_DEBOUNCE_MS = 400L
        const val ACTION_STOP = "com.smsforwarder.app.STOP_SENDER_KEEPALIVE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SenderKeepAliveService::class.java)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SenderKeepAliveService::class.java).apply { action = ACTION_STOP }
            )
        }

        fun ensure(context: Context) {
            start(context)
        }
    }
}
