package com.smsforwarder.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ReceiverEndpoint(
    val id: String = UUID.randomUUID().toString(),
    val host: String,
    val port: Int,
    val secret: String,
    val enabled: Boolean = true,
    val label: String = ""
) {
    val displayName: String
        get() = label.ifBlank { "$host:$port" }

    val addressKey: String
        get() = "${host.trim().lowercase()}:$port"

    fun isValid(): Boolean =
        host.isNotBlank() && secret.length >= 16 && port in 1..65535

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("host", host)
        .put("port", port)
        .put("secret", secret)
        .put("enabled", enabled)
        .put("label", label)

    companion object {
        const val MAX_DEVICES = 8

        fun fromJson(o: JSONObject): ReceiverEndpoint = ReceiverEndpoint(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            host = o.optString("host").trim(),
            port = o.optInt("port", 18765),
            secret = o.optString("secret").trim(),
            enabled = o.optBoolean("enabled", true),
            label = o.optString("label").orEmpty()
        )

        fun listToJson(list: List<ReceiverEndpoint>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(raw: String?): List<ReceiverEndpoint> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        add(fromJson(arr.getJSONObject(i)))
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
