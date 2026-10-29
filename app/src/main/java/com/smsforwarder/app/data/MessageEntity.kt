package com.smsforwarder.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index(value = ["msgId"], unique = true)]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val otp: String? = null,
    val pinned: Boolean = false,
    /** LAN delivery id for dedup / ACK; null for local/test inserts without id. */
    val msgId: String? = null
)
