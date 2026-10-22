package com.smsforwarder.app.data

import android.content.Context
import java.io.File

/**
 * File-backed secret so the UI process and `:recv` process always see the same value.
 * SharedPreferences caches are per-process and can drift.
 */
object SecretStore {
    private const val FILE = "pair_secret.txt"

    fun write(context: Context, secret: String) {
        File(context.applicationContext.filesDir, FILE).writeText(secret.trim())
    }

    fun read(context: Context): String? {
        val f = File(context.applicationContext.filesDir, FILE)
        if (!f.exists()) return null
        return f.readText().trim().takeIf { it.isNotBlank() }
    }
}
