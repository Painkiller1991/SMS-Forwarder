package com.smsforwarder.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.AppRole
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.net.SmsForwardService

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "A",
            location = "SmsReceiver.onReceive:entry",
            message = "SMS broadcast received",
            data = mapOf("action" to intent.action),
            runId = "mqtt"
        )
        // #endregion
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val app = context.applicationContext as? SmsForwarderApp ?: return
        val prefs = app.prefs
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "B",
            location = "SmsReceiver.onReceive:prefs",
            message = "Sender prefs gate",
            data = mapOf(
                "role" to prefs.role.name,
                "senderEnabled" to prefs.senderEnabled,
                "paired" to prefs.paired
            ),
            runId = "mqtt"
        )
        // #endregion
        if (prefs.role != AppRole.SENDER || !prefs.senderEnabled || !prefs.paired) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.firstOrNull()?.displayOriginatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        val timestamp = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        Log.i(TAG, "Enqueue MQTT forward from $sender")
        SmsForwardService.enqueue(context, sender, body, timestamp)
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
