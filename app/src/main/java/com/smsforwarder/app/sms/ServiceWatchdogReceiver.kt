package com.smsforwarder.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.AppRole
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.net.ReceiveForegroundService

/**
 * One-shot restart after the UI task is swiped away.
 * Brings the LAN listener back if the OEM killed the process.
 */
class ServiceWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? SmsForwarderApp ?: return
        if (app.prefs.role != AppRole.RECEIVER || !app.prefs.receiveEnabled) return
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H_SWIPE",
            location = "ServiceWatchdogReceiver",
            message = "Watchdog restart after UI close",
            runId = "lan-recv"
        )
        // #endregion
        ReceiveForegroundService.ensure(context)
    }
}
