package com.smsforwarder.app.net

import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.MessageEntity
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.notify.InboxNotifier
import com.smsforwarder.app.otp.OtpParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Shared inbox + notification path for LAN [ReceiveServer] and receiver Test inject.
 */
object IncomingSms {
    fun accept(
        app: SmsForwarderApp,
        sender: String,
        body: String,
        timestamp: Long = System.currentTimeMillis(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    ) {
        val otp = OtpParser.extract(body)
        if (otp != null) {
            app.otpStore.put(otp, sender)
            InboxNotifier.copyOtpDelayed(app, otp)
        }
        scope.launch {
            app.database.messageDao().insert(
                MessageEntity(
                    sender = sender,
                    body = body,
                    receivedAt = timestamp,
                    otp = otp
                )
            )
        }
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "NOTIF",
            location = "IncomingSms.accept",
            message = "SMS accepted into inbox",
            data = mapOf("sender" to sender.take(32), "bodyLen" to body.length),
            runId = "lan-calm"
        )
        // #endregion
        InboxNotifier.show(app, sender, body, otp)
    }
}
