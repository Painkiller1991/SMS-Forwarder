package com.smsforwarder.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.AppRole
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.net.KeepAliveScheduler
import com.smsforwarder.app.net.ReceiveForegroundService
import com.smsforwarder.app.net.SenderKeepAliveService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        val app = context.applicationContext as? SmsForwarderApp ?: return
        val prefs = app.prefs
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "KEEPALIVE",
            location = "BootReceiver.onReceive",
            message = "Boot — restoring services",
            data = mapOf(
                "role" to prefs.role.name,
                "senderEnabled" to prefs.senderEnabled,
                "receiveEnabled" to prefs.receiveEnabled
            ),
            runId = "mqtt"
        )
        // #endregion
        when (prefs.role) {
            AppRole.SENDER -> if (prefs.senderEnabled && prefs.endpointConfigured) {
                SenderKeepAliveService.start(context)
                KeepAliveScheduler.schedule(context)
            }
            AppRole.RECEIVER -> if (prefs.receiveEnabled) {
                ReceiveForegroundService.start(context)
                KeepAliveScheduler.schedule(context)
            }
            AppRole.NONE -> Unit
        }
    }
}
