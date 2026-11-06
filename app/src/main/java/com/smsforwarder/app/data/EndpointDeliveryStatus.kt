package com.smsforwarder.app.data

/** Live delivery / reachability snapshot for a sender endpoint card. */
data class EndpointDeliveryStatus(
    val endpointId: String,
    val reachable: Boolean? = null,
    val pendingCount: Int = 0,
    val lastError: String? = null,
    val lastSyncedAt: Long? = null
)
