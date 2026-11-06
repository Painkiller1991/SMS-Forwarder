package com.smsforwarder.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.smsforwarder.app.R
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.AppRole
import com.smsforwarder.app.data.ListenFlag
import com.smsforwarder.app.net.ReceiveForegroundService
import com.smsforwarder.app.net.SmsForwardService
import com.smsforwarder.app.ui.MainActivity
import kotlinx.coroutines.runBlocking

/**
 * Home-screen widget (Widgets picker): pending sync / Receive ON-OFF.
 */
class StatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TAP -> {
                handleTap(context)
                requestUpdate(context)
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun handleTap(context: Context) {
        val app = context.applicationContext as? SmsForwarderApp ?: return
        val prefs = app.prefs
        when (prefs.role) {
            AppRole.RECEIVER -> {
                val next = !ListenFlag.read(context)
                prefs.receiveEnabled = next
                if (next) ReceiveForegroundService.start(context)
                else ReceiveForegroundService.stop(context)
            }
            AppRole.SENDER -> {
                val pending = runBlocking { app.database.outboxDao().pendingCount() }
                if (pending > 0) {
                    SmsForwardService.drain(context)
                } else {
                    openApp(context)
                }
            }
            else -> openApp(context)
        }
    }

    companion object {
        const val ACTION_TAP = "com.smsforwarder.app.widget.ACTION_TAP"

        fun requestUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, StatusWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val views = buildViews(context)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_status)
            val app = context.applicationContext as? SmsForwarderApp
            val prefs = app?.prefs
            val (status, hint) = when (prefs?.role) {
                AppRole.RECEIVER -> {
                    val on = ListenFlag.read(context)
                    if (on) {
                        "Receive ON" to "Tap to turn OFF"
                    } else {
                        "Receive OFF" to "Tap to turn ON"
                    }
                }
                AppRole.SENDER -> {
                    val pending = runBlocking {
                        app?.database?.outboxDao()?.pendingCount() ?: 0
                    }
                    if (pending > 0) {
                        "$pending pending" to "Tap to Sync now"
                    } else {
                        "No pending" to "Tap to open app"
                    }
                }
                else -> "Choose a role" to "Tap to open app"
            }
            views.setTextViewText(R.id.widget_status, status)
            views.setTextViewText(R.id.widget_hint, hint)

            val tapPi = PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, StatusWidgetProvider::class.java).setAction(ACTION_TAP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, tapPi)

            val openPi = PendingIntent.getActivity(
                context,
                2,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_title, openPi)
            return views
        }

        private fun openApp(context: Context) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }
}
