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
import com.smsforwarder.app.update.UpdateLock

/** Occasional wake if OEM killed services — does not restart every minute. */
class KeepAliveAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? SmsForwarderApp ?: return
        val prefs = app.prefs
        if (UpdateLock.isActive(prefs)) return
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "KEEPALIVE",
            location = "KeepAliveAlarmReceiver",
            message = "Rare keep-alive tick",
            data = mapOf(
                "role" to prefs.role.name,
                "receiveEnabled" to prefs.receiveEnabled
            ),
            runId = "lan-calm"
        )
        // #endregion
        when (prefs.role) {
            AppRole.RECEIVER -> if (prefs.receiveEnabled) {
                ReceiveForegroundService.ensure(context)
                KeepAliveScheduler.schedule(context)
            }
            AppRole.SENDER -> if (prefs.senderEnabled && prefs.endpointConfigured) {
                SenderKeepAliveService.ensure(context)
                KeepAliveScheduler.schedule(context)
            }
            AppRole.NONE -> Unit
        }
    }
}
