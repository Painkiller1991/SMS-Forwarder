package com.smsforwarder.app.data

import android.content.Context
import java.io.File

/** Cross-process Extra Secret for the receiver role (:recv vs UI). */
object ExtraSecretStore {
    private const val FILE = "extra_secret.txt"

    fun write(context: Context, value: String) {
        File(context.applicationContext.filesDir, FILE).writeText(value)
    }

    fun read(context: Context): String? {
        val f = File(context.applicationContext.filesDir, FILE)
        if (!f.exists()) return null
        return f.readText()
    }
}
