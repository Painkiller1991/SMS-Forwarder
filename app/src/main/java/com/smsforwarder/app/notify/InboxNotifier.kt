package com.smsforwarder.app.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.smsforwarder.app.R
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.ui.MainActivity
import com.smsforwarder.app.ui.SmsAlertActivity
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
        val hasOtp = !otp.isNullOrBlank()
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "NOTIF",
            location = "InboxNotifier.showNow",
            message = "Posting SMS notification",
            data = mapOf(
                "enabled" to enabled,
                "channel" to if (hasOtp) SmsForwarderApp.CHANNEL_INBOX_OTP else SmsForwarderApp.CHANNEL_INBOX,
                "sender" to sender.take(32),
                "hasOtp" to hasOtp
            ),
            runId = "mqtt"
        )
        // #endregion
        if (!enabled) {
            Log.w(TAG, "Notifications disabled for app")
            return
        }

        // Brief wake so shade can appear while screen is off
        val pm = context.getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smsforwarder:notif")
        try {
            wl.acquire(3_000)
            val notifyId = nextId.incrementAndGet()
            val openIntent = PendingIntent.getActivity(
                context,
                notifyId,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            // OTP: DEFAULT channel / priority — avoid aggressive heads-up stealing autofill focus
            val channel = if (hasOtp) SmsForwarderApp.CHANNEL_INBOX_OTP else SmsForwarderApp.CHANNEL_INBOX
            val priority = if (hasOtp) {
                NotificationCompat.PRIORITY_DEFAULT
            } else {
                NotificationCompat.PRIORITY_MAX
            }
            val builder = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_title_sms, sender))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(priority)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(openIntent)
                .setSound(sound)
                .setOnlyAlertOnce(false)
                .setTicker(body.take(80))

            if (!hasOtp) {
                builder.setDefaults(NotificationCompat.DEFAULT_ALL)
                // Full-screen on lock when allowed — SmsAlertActivity shows full body
                if (canUseFullScreenIntent(context)) {
                    val alertIntent = Intent(context, SmsAlertActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(SmsAlertActivity.EXTRA_SENDER, sender)
                        putExtra(SmsAlertActivity.EXTRA_BODY, body)
                    }
                    val fullScreen = PendingIntent.getActivity(
                        context,
                        notifyId + 10_000,
                        alertIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.setFullScreenIntent(fullScreen, true)
                }
            }

            if (hasOtp) {
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

            nm.notify(notifyId, builder.build())
            Log.i(TAG, "notify id=$notifyId posted channel=$channel")
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission missing", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification", e)
        } finally {
            runCatching { if (wl.isHeld) wl.release() }
        }
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.canUseFullScreenIntent()
    }

    /** True when Android 14+ and full-screen intents are not yet allowed for this app. */
    fun needsFullScreenIntentSetup(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return !canUseFullScreenIntent(context)
    }

    /** Android 14+ settings to allow full-screen / lock-screen SMS alerts. */
    fun fullScreenIntentSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        }
    }

    fun copyOtp(context: Context, otp: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("OTP", otp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
    }

    /** Store first; delay clipboard so waiting autofill can finish before any overlay. */
    fun copyOtpDelayed(context: Context, otp: String, delayMs: Long = 1_000L) {
        val appCtx = context.applicationContext
        mainHandler.postDelayed({ copyOtp(appCtx, otp) }, delayMs)
    }

    private const val TAG = "InboxNotifier"
}
