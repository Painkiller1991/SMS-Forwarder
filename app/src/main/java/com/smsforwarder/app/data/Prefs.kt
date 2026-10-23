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

    /** Shared secret for encryption + HMAC (never sent in clear over LAN). */
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

    var paired: Boolean
        get() = sp.getBoolean(KEY_PAIRED, false)
        set(value) {
            sp.edit().putBoolean(KEY_PAIRED, value).commit()
        }

    var receiveEnabled: Boolean
        get() {
            // File is source of truth across UI + :recv processes
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

    var receiverHost: String
        get() = sp.getString(KEY_HOST, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_HOST, value).commit()
        }

    var receiverPort: Int
        get() = sp.getInt(KEY_PORT, ReceiveServer.DEFAULT_PORT)
        set(value) {
            sp.edit().putInt(KEY_PORT, value).commit()
        }

    fun pairingPayload(localIp: String): String =
        "smsfw://pair?ip=$localIp&port=${ReceiveServer.DEFAULT_PORT}&s=$sharedSecret"

    /**
     * True when host + secret are saved (durable). Used for FGS / SMS gate.
     * Reachability is UI-only and must not clear this flag.
     */
    val endpointConfigured: Boolean
        get() = receiverHost.isNotBlank() && sharedSecret.length >= 16 && paired

    /** Saves endpoint credentials and marks endpoint configured (not live reachability). */
    fun applyPairing(secret: String, host: String, port: Int) {
        val s = secret.trim()
        sp.edit()
            .putString(KEY_SECRET, s)
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_PORT, port)
            .putBoolean(KEY_PAIRED, true)
            .commit()
        SecretStore.write(appContext, s)
    }

    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(sp.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM)
        set(value) {
            sp.edit().putString(KEY_THEME, value.name).commit()
        }

    /** Skip prompting for this versionCode until a newer release appears. */
    var skippedUpdateVersionCode: Int
        get() = sp.getInt(KEY_SKIPPED_UPDATE, 0)
        set(value) {
            sp.edit().putInt(KEY_SKIPPED_UPDATE, value).commit()
        }

    companion object {
        const val PREFS_NAME = "sms_forwarder"
        private const val KEY_ROLE = "role"
        private const val KEY_SECRET = "shared_secret"
        private const val KEY_PAIRED = "paired"
        private const val KEY_RECEIVE_ENABLED = "receive_enabled"
        private const val KEY_SENDER_ENABLED = "sender_enabled"
        private const val KEY_HOST = "receiver_host"
        private const val KEY_PORT = "receiver_port"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_SKIPPED_UPDATE = "skipped_update_version_code"
    }
}
