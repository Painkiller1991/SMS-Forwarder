package com.smsforwarder.app.data

/** Room projection for pending outbox rows grouped by receiver endpoint. */
data class EndpointPendingCount(
    val endpointId: String,
    val cnt: Int
)
