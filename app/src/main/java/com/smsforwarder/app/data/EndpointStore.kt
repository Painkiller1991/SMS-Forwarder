package com.smsforwarder.app.data

import android.content.Context
import java.io.File

/**
 * File-backed endpoint list for cross-process reads.
 * SharedPreferences caches are per-process; :send/:recv otherwise keep stale secrets
 * after the UI process updates Extra Secret / pairing — causing BAD_DECRYPT on outbox.
 */
object EndpointStore {
    private const val FILE = "endpoints.json"
    private const val TMP = "endpoints.json.tmp"

    fun write(context: Context, json: String) {
        val dir = context.applicationContext.filesDir
        val tmp = File(dir, TMP)
        val out = File(dir, FILE)
        tmp.writeText(json)
        if (!tmp.renameTo(out)) {
            out.writeText(json)
            tmp.delete()
        }
    }

    fun read(context: Context): String? {
        val f = File(context.applicationContext.filesDir, FILE)
        if (!f.exists()) return null
        val text = f.readText().trim()
        return text.ifBlank { null }
    }
}
