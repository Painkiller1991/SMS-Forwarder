package com.smsforwarder.app.data

import android.content.Context
import java.io.File

/**
 * Cross-process listen ON/OFF flag.
 * SharedPreferences caches per process, so the UI process write is invisible to `:recv`
 * until a cold start — which is why Listen ON needed an app reopen.
 */
object ListenFlag {
    private const val FILE = "listen_enabled.flag"

    fun exists(context: Context): Boolean =
        File(context.applicationContext.filesDir, FILE).exists()

    fun write(context: Context, enabled: Boolean) {
        File(context.applicationContext.filesDir, FILE).writeText(if (enabled) "1" else "0")
    }

    fun read(context: Context): Boolean {
        val f = File(context.applicationContext.filesDir, FILE)
        if (!f.exists()) return false
        return f.readText().trim() == "1"
    }

    /**
     * If the flag file is missing (upgrade / wipe), seed it from SharedPreferences
     * so UI, boot, idle rebind, and `:recv` agree on listen state.
     */
    fun migrateFromSharedPreferencesIfMissing(context: Context, prefsEnabled: Boolean) {
        if (!exists(context)) {
            write(context, prefsEnabled)
        }
    }
}
