package com.smsforwarder.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.AppRole
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.net.ReceiveForegroundService
import com.smsforwarder.app.net.SenderKeepAliveService
import com.smsforwarder.app.update.UpdateLock

/**
 * One-shot restart after the UI task is swiped away.
 * Brings receive or sender keep-alive back if the OEM killed the process.
 */
class ServiceWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? SmsForwarderApp ?: return
        val prefs = app.prefs
        if (UpdateLock.isActive(prefs)) return
        when (prefs.role) {
            AppRole.RECEIVER -> {
                if (!prefs.receiveEnabled) return
                // #region agent log
                AgentDebugLog.log(
                    hypothesisId = "H_SWIPE",
                    location = "ServiceWatchdogReceiver",
                    message = "Watchdog restart receive after UI close",
                    runId = "lan-recv"
                )
                // #endregion
                ReceiveForegroundService.ensure(context)
            }
            AppRole.SENDER -> {
                if (!prefs.senderEnabled || !prefs.endpointConfigured) return
                // #region agent log
                AgentDebugLog.log(
                    hypothesisId = "H_SWIPE",
                    location = "ServiceWatchdogReceiver",
                    message = "Watchdog restart sender after UI close",
                    runId = "lan-send"
                )
                // #endregion
                SenderKeepAliveService.ensure(context)
            }
            AppRole.NONE -> Unit
        }
    }
}
