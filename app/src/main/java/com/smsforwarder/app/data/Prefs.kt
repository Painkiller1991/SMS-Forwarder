package com.smsforwarder.app.data

import android.content.Context
import android.content.SharedPreferences
import com.smsforwarder.app.net.ReceiveServer
import java.io.File
import java.util.UUID

enum class AppRole {
    NONE,
    SENDER,
    RECEIVER
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

class Prefs(context: Context) {
    private val appContext = context.applicationContext
    private val sp: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var role: AppRole
        get() = runCatching { AppRole.valueOf(sp.getString(KEY_ROLE, AppRole.NONE.name)!!) }
            .getOrDefault(AppRole.NONE)
        set(value) {
            sp.edit().putString(KEY_ROLE, value.name).commit()
        }

    /**
     * Local receive-mode secret (this phone as receiver).
     * Sender multi-device secrets live on each [ReceiverEndpoint].
     */
    val sharedSecret: String
        get() {
            SecretStore.read(appContext)?.let { return it }
            val existing = sp.getString(KEY_SECRET, null)
            if (existing != null) {
                SecretStore.write(appContext, existing)
                return existing
            }
            val generated = UUID.randomUUID().toString().replace("-", "")
            sp.edit().putString(KEY_SECRET, generated).commit()
            SecretStore.write(appContext, generated)
            return generated
        }

    fun setSharedSecret(secret: String) {
        sp.edit().putString(KEY_SECRET, secret).commit()
        SecretStore.write(appContext, secret)
    }

    /**
     * Receiver Extra Secret (passphrase). Blank = legacy QR-only crypto.
     * Never included in QR / pairing payload.
     */
    var extraSecret: String
        get() = sp.getString(KEY_EXTRA_SECRET, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_EXTRA_SECRET, value).commit()
        }

    /** Default Extra Secret applied when pairing a new sender endpoint (optional). */
    var defaultExtraSecret: String
        get() = sp.getString(KEY_DEFAULT_EXTRA_SECRET, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_DEFAULT_EXTRA_SECRET, value).commit()
        }

    var batteryBannerSnoozeUntil: Long
        get() = sp.getLong(KEY_BATTERY_SNOOZE_UNTIL, 0L)
        set(value) {
            sp.edit().putLong(KEY_BATTERY_SNOOZE_UNTIL, value).commit()
        }

    fun isBatteryBannerSnoozed(now: Long = System.currentTimeMillis()): Boolean =
        now < batteryBannerSnoozeUntil

    fun snoozeBatteryBanner(days: Int = 7) {
        batteryBannerSnoozeUntil = System.currentTimeMillis() + days * 24L * 60L * 60L * 1000L
    }

    var paired: Boolean
        get() = endpointConfigured || sp.getBoolean(KEY_PAIRED, false)
        set(value) {
            sp.edit().putBoolean(KEY_PAIRED, value).commit()
        }

    var receiveEnabled: Boolean
        get() {
            if (File(appContext.filesDir, "listen_enabled.flag").exists()) {
                return ListenFlag.read(appContext)
            }
            return sp.getBoolean(KEY_RECEIVE_ENABLED, false)
        }
        set(value) {
            sp.edit().putBoolean(KEY_RECEIVE_ENABLED, value).commit()
            ListenFlag.write(appContext, value)
        }

    var senderEnabled: Boolean
        get() = sp.getBoolean(KEY_SENDER_ENABLED, false)
        set(value) {
            sp.edit().putBoolean(KEY_SENDER_ENABLED, value).commit()
        }

    /** @deprecated Prefer [endpoints]; kept for migration / first-endpoint mirror. */
    var receiverHost: String
        get() = enabledEndpoints().firstOrNull()?.host
            ?: sp.getString(KEY_HOST, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_HOST, value).commit()
        }

    var receiverPort: Int
        get() = enabledEndpoints().firstOrNull()?.port
            ?: sp.getInt(KEY_PORT, ReceiveServer.DEFAULT_PORT)
        set(value) {
            sp.edit().putInt(KEY_PORT, value).commit()
        }

    fun pairingPayload(localIp: String): String =
        "smsfw://pair?ip=$localIp&port=${ReceiveServer.DEFAULT_PORT}&s=$sharedSecret"

    val endpoints: List<ReceiverEndpoint>
        get() {
            migrateLegacyEndpointIfNeeded()
            return ReceiverEndpoint.listFromJson(sp.getString(KEY_ENDPOINTS, null))
        }

    fun enabledEndpoints(): List<ReceiverEndpoint> =
        endpoints.filter { it.enabled && it.isValid() }

    /**
     * True when at least one enabled sender endpoint is configured.
     * Reachability is UI-only and must not clear this flag.
     */
    val endpointConfigured: Boolean
        get() = enabledEndpoints().isNotEmpty()

    private fun persistEndpoints(list: List<ReceiverEndpoint>) {
        val first = list.firstOrNull { it.enabled && it.isValid() } ?: list.firstOrNull()
        val editor = sp.edit()
            .putString(KEY_ENDPOINTS, ReceiverEndpoint.listToJson(list))
            .putBoolean(KEY_PAIRED, list.any { it.enabled && it.isValid() })
        if (first != null) {
            editor
                .putString(KEY_HOST, first.host)
                .putInt(KEY_PORT, first.port)
        }
        editor.commit()
    }

    private fun migrateLegacyEndpointIfNeeded() {
        if (sp.contains(KEY_ENDPOINTS)) return
        val host = sp.getString(KEY_HOST, "").orEmpty().trim()
        val secret = sp.getString(KEY_SECRET, null)?.trim().orEmpty()
        val port = sp.getInt(KEY_PORT, ReceiveServer.DEFAULT_PORT)
        val wasPaired = sp.getBoolean(KEY_PAIRED, false)
        if (wasPaired && host.isNotBlank() && secret.length >= 16) {
            val migrated = listOf(
                ReceiverEndpoint(
                    host = host,
                    port = port,
                    secret = secret,
                    enabled = true
                )
            )
            sp.edit()
                .putString(KEY_ENDPOINTS, ReceiverEndpoint.listToJson(migrated))
                .commit()
        } else {
            sp.edit().putString(KEY_ENDPOINTS, "[]").commit()
        }
    }

    /**
     * Adds or updates a sender target by host:port. Returns error message or null.
     */
    fun upsertEndpoint(
        secret: String,
        host: String,
        port: Int,
        label: String = "",
        extraSecret: String? = null
    ): String? {
        val s = secret.trim()
        val h = host.trim()
        if (h.isBlank()) return "Enter receiver IP"
        if (s.length < 16) return "Secret too short"
        if (port !in 1..65535) return "Port out of range"
        val current = endpoints.toMutableList()
        val key = "${h.lowercase()}:$port"
        val idx = current.indexOfFirst { it.addressKey == key }
        val resolvedExtra = when {
            extraSecret != null -> extraSecret
            idx >= 0 -> current[idx].extraSecret
            else -> defaultExtraSecret
        }.trim()
        if (resolvedExtra.isNotEmpty() && resolvedExtra.length < ReceiverEndpoint.MIN_EXTRA_SECRET_LEN) {
            return "Extra Secret must be at least ${ReceiverEndpoint.MIN_EXTRA_SECRET_LEN} characters"
        }
        if (idx >= 0) {
            current[idx] = current[idx].copy(
                secret = s,
                extraSecret = resolvedExtra,
                label = label.ifBlank { current[idx].label },
                enabled = true
            )
        } else {
            if (current.size >= ReceiverEndpoint.MAX_DEVICES) {
                return "Max ${ReceiverEndpoint.MAX_DEVICES} devices — remove one first"
            }
            current.add(
                ReceiverEndpoint(
                    host = h,
                    port = port,
                    secret = s,
                    extraSecret = resolvedExtra,
                    enabled = true,
                    label = label
                )
            )
        }
        persistEndpoints(current)
        return null
    }

    /** Legacy single-pair API — upserts into the endpoint list. */
    fun applyPairing(secret: String, host: String, port: Int, extraSecret: String? = null) {
        upsertEndpoint(secret, host, port, extraSecret = extraSecret)
    }

    fun removeEndpoint(id: String) {
        persistEndpoints(endpoints.filterNot { it.id == id })
    }

    fun setEndpointEnabled(id: String, enabled: Boolean) {
        persistEndpoints(
            endpoints.map { if (it.id == id) it.copy(enabled = enabled) else it }
        )
    }

    fun setEndpointExtraSecret(id: String, extraSecret: String): String? {
        val trimmed = extraSecret.trim()
        if (trimmed.isNotEmpty() && trimmed.length < ReceiverEndpoint.MIN_EXTRA_SECRET_LEN) {
            return "Extra Secret must be at least ${ReceiverEndpoint.MIN_EXTRA_SECRET_LEN} characters"
        }
        persistEndpoints(
            endpoints.map { if (it.id == id) it.copy(extraSecret = trimmed) else it }
        )
        return null
    }

    fun updateEndpoint(
        id: String,
        host: String? = null,
        port: Int? = null,
        secret: String? = null,
        extraSecret: String? = null,
        label: String? = null,
        enabled: Boolean? = null
    ): String? {
        val current = endpoints.toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return "Device not found"
        val ep = current[idx]
        val next = ep.copy(
            host = host?.trim() ?: ep.host,
            port = port ?: ep.port,
            secret = secret?.trim() ?: ep.secret,
            extraSecret = extraSecret?.trim() ?: ep.extraSecret,
            label = label ?: ep.label,
            enabled = enabled ?: ep.enabled
        )
        if (!next.isValid()) {
            if (next.extraSecret.isNotEmpty() &&
                next.extraSecret.length < ReceiverEndpoint.MIN_EXTRA_SECRET_LEN
            ) {
                return "Extra Secret must be at least ${ReceiverEndpoint.MIN_EXTRA_SECRET_LEN} characters"
            }
            return "Invalid device settings"
        }
        current[idx] = next
        persistEndpoints(current)
        return null
    }

    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(sp.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM)
        set(value) {
            sp.edit().putString(KEY_THEME, value.name).commit()
        }

    var skippedUpdateVersionCode: Int
        get() = sp.getInt(KEY_SKIPPED_UPDATE, 0)
        set(value) {
            sp.edit().putInt(KEY_SKIPPED_UPDATE, value).commit()
        }

    var updateLockedVersionCode: Int
        get() = sp.getInt(KEY_UPDATE_LOCKED, 0)
        set(value) {
            sp.edit().putInt(KEY_UPDATE_LOCKED, value).commit()
        }

    companion object {
        const val PREFS_NAME = "sms_forwarder"
        private const val KEY_ROLE = "role"
        private const val KEY_SECRET = "shared_secret"
        private const val KEY_EXTRA_SECRET = "extra_secret"
        private const val KEY_DEFAULT_EXTRA_SECRET = "default_extra_secret"
        private const val KEY_BATTERY_SNOOZE_UNTIL = "battery_banner_snooze_until"
        private const val KEY_PAIRED = "paired"
        private const val KEY_RECEIVE_ENABLED = "receive_enabled"
        private const val KEY_SENDER_ENABLED = "sender_enabled"
        private const val KEY_HOST = "receiver_host"
        private const val KEY_PORT = "receiver_port"
        private const val KEY_ENDPOINTS = "receiver_endpoints_json"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_SKIPPED_UPDATE = "skipped_update_version_code"
        private const val KEY_UPDATE_LOCKED = "update_locked_version_code"
    }
}
