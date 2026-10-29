package com.smsforwarder.app.net

import android.util.Log
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.MessageEntity
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.notify.InboxNotifier
import com.smsforwarder.app.otp.OtpParser
import kotlinx.coroutines.runBlocking

/**
 * Shared inbox + notification path for LAN [ReceiveServer] and receiver Test inject.
 * [acceptBlocking] persists before returning so HTTP ACK is durable.
 */
object IncomingSms {
    private const val TAG = "IncomingSms"

    /** Blocking persist for ReceiveServer ACK. Returns "stored" or "duplicate". */
    fun acceptBlocking(
        app: SmsForwarderApp,
        sender: String,
        body: String,
        timestamp: Long = System.currentTimeMillis(),
        msgId: String? = null
    ): String {
        return runBlocking {
            val dao = app.database.messageDao()
            if (!msgId.isNullOrBlank()) {
                val existing = dao.findIdByMsgId(msgId)
                if (existing != null) {
                    AgentDebugLog.log(
                        hypothesisId = "NOTIF",
                        location = "IncomingSms.acceptBlocking",
                        message = "Duplicate msgId — ACK duplicate",
                        data = mapOf("msgId" to msgId.take(36)),
                        runId = "lan-calm"
                    )
                    return@runBlocking "duplicate"
                }
            }
            val otp = OtpParser.extract(body)
            if (otp != null) {
                val wrote = app.otpStore.putVerified(otp, sender)
                if (!wrote) {
                    Log.w(TAG, "OtpStore write verify failed")
                }
                InboxNotifier.requestCopyOtp(app, otp)
            }
            dao.insert(
                MessageEntity(
                    sender = sender,
                    body = body,
                    receivedAt = timestamp,
                    otp = otp,
                    msgId = msgId
                )
            )
            AgentDebugLog.log(
                hypothesisId = "NOTIF",
                location = "IncomingSms.acceptBlocking",
                message = "SMS stored",
                data = mapOf(
                    "sender" to sender.take(32),
                    "bodyLen" to body.length,
                    "msgId" to (msgId?.take(36) ?: "none")
                ),
                runId = "lan-calm"
            )
            InboxNotifier.show(app, sender, body, otp)
            "stored"
        }
    }

    /** Async path for local Test inject (no LAN ACK). */
    fun accept(
        app: SmsForwarderApp,
        sender: String,
        body: String,
        timestamp: Long = System.currentTimeMillis(),
        msgId: String? = null
    ) {
        acceptBlocking(app, sender, body, timestamp, msgId)
    }
}
