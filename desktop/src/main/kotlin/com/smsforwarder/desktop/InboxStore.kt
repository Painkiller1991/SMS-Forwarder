package com.smsforwarder.desktop

import androidx.compose.runtime.mutableStateListOf
import com.smsforwarder.app.otp.OtpParser
import java.text.DateFormat
import java.util.Date

data class InboxMessage(
    val id: Long,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val otp: String?
) {
    val timeLabel: String
        get() = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(receivedAt))
}

class InboxStore {
    private var nextId = 1L
    val messages = mutableStateListOf<InboxMessage>()

    fun add(sender: String, body: String, timestamp: Long): InboxMessage {
        val msg = InboxMessage(
            id = nextId++,
            sender = sender,
            body = body,
            receivedAt = timestamp,
            otp = OtpParser.extract(body)
        )
        messages.add(0, msg)
        while (messages.size > 200) {
            messages.removeAt(messages.lastIndex)
        }
        return msg
    }

    fun clear() {
        messages.clear()
    }
}
