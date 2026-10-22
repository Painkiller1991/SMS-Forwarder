package com.smsforwarder.app.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.smsforwarder.app.R
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger

object InboxNotifier {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextId = AtomicInteger(1000)

    fun show(context: Context, sender: String, body: String, otp: String?) {
        val appCtx = context.applicationContext
        // Always post on main thread — OEM often defers bg-thread notifies until UI opens
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showNow(appCtx, sender, body, otp)
        } else {
            mainHandler.post { showNow(appCtx, sender, body, otp) }
        }
    }

    private fun showNow(context: Context, sender: String, body: String, otp: String?) {
        val nm = NotificationManagerCompat.from(context)
        val enabled = nm.areNotificationsEnabled()
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "NOTIF",
            location = "InboxNotifier.showNow",
            message = "Posting SMS notification",
            data = mapOf(
                "enabled" to enabled,
                "channel" to SmsForwarderApp.CHANNEL_INBOX,
                "sender" to sender.take(32),
                "hasOtp" to (!otp.isNullOrBlank())
            ),
            runId = "mqtt"
        )
        // #endregion
        if (!enabled) {
            Log.w(TAG, "Notifications disabled for app")
            return
        }

        // Brief wake so heads-up can appear while screen is off
        val pm = context.getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smsforwarder:notif")
        try {
            wl.acquire(3_000)
            val openIntent = PendingIntent.getActivity(
                context,
                nextId.get(),
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val builder = NotificationCompat.Builder(context, SmsForwarderApp.CHANNEL_INBOX)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_title_sms, sender))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(openIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setSound(sound)
                .setOnlyAlertOnce(false)
                .setTicker(body.take(80))

            if (!otp.isNullOrBlank()) {
                val copyIntent = Intent(context, CopyOtpReceiver::class.java).apply {
                    putExtra(CopyOtpReceiver.EXTRA_OTP, otp)
                }
                val copyPending = PendingIntent.getBroadcast(
                    context,
                    otp.hashCode(),
                    copyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, context.getString(R.string.copy_otp), copyPending)
            }

            val id = nextId.incrementAndGet()
            nm.notify(id, builder.build())
            Log.i(TAG, "notify id=$id posted")
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission missing", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification", e)
        } finally {
            runCatching { if (wl.isHeld) wl.release() }
        }
    }

    fun copyOtp(context: Context, otp: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("OTP", otp))
    }

    private const val TAG = "InboxNotifier"
}
