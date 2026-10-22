package com.smsforwarder.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.smsforwarder.app.data.AppDatabase
import com.smsforwarder.app.data.Prefs
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.otp.OtpStore

class SmsForwarderApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var otpStore: OtpStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.create(this)
        prefs = Prefs(this)
        otpStore = OtpStore(this)
        AgentDebugLog.init(this)
        installCrashLogger()
        createNotificationChannels()
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "A_E",
            location = "SmsForwarderApp.onCreate",
            message = "Application created (cold start or resume)",
            data = mapOf(
                "role" to prefs.role.name,
                "senderEnabled" to prefs.senderEnabled,
                "paired" to prefs.paired,
                "receiveEnabled" to prefs.receiveEnabled
            )
        )
        // #endregion
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // #region agent log
            runCatching {
                AgentDebugLog.log(
                    hypothesisId = "H8",
                    location = "UncaughtException",
                    message = "Process crash",
                    data = mapOf(
                        "thread" to thread.name,
                        "ex" to throwable.javaClass.name,
                        "error" to (throwable.message?.take(200) ?: "null"),
                        "stack" to throwable.stackTraceToString().take(500)
                    ),
                    runId = "sender-crash"
                )
            }
            // #endregion
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECEIVE,
                getString(R.string.receive_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.receive_channel_desc)
                setShowBadge(false)
            }
        )

        // New channel id so HIGH importance applies even if an old channel was created wrong
        val alert = NotificationChannel(
            CHANNEL_INBOX,
            getString(R.string.inbox_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.inbox_channel_desc)
            enableVibration(true)
            enableLights(true)
            setShowBadge(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        manager.createNotificationChannel(alert)
        // Remove obsolete low/broken channel if present from older builds
        runCatching { manager.deleteNotificationChannel("forwarded_sms") }
    }

    companion object {
        const val CHANNEL_RECEIVE = "receive_server"
        /** Heads-up SMS alerts (not the silent FGS channel). */
        const val CHANNEL_INBOX = "forwarded_sms_alert_v2"

        lateinit var instance: SmsForwarderApp
            private set
    }
}
