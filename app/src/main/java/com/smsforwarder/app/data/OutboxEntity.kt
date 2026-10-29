package com.smsforwarder.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["state", "nextAttemptAt"]),
        Index(value = ["msgId", "endpointId"], unique = true)
    ]
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val msgId: String,
    val endpointId: String,
    val host: String,
    val port: Int,
    val secret: String,
    val sender: String,
    val body: String,
    val smsTimestamp: Long,
    val createdAt: Long,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val state: String = STATE_PENDING,
    val lastError: String? = null
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_DONE = "done"
        const val STATE_EXPIRED = "expired"
    }
}
