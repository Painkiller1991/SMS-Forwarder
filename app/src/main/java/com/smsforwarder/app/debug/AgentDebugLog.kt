package com.smsforwarder.app.debug

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Debug logger — **on-device file only**.
 * PC HTTP ingest listens on 127.0.0.1 only, so Wi‑Fi pings always fail; do not rely on them.
 * Use Copy debug log → paste in chat.
 */
object AgentDebugLog {
    private const val TAG = "AgentDebug"
    private const val SESSION = "4fa2f1"
    private const val LOG_FILE = "debug-4fa2f1.ndjson"
    private val io = Executors.newSingleThreadExecutor()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun logFile(context: Context = requireNotNull(appContext)): File {
        // External files dir is shared across :recv and main process
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, LOG_FILE)
    }

    fun readAll(context: Context = requireNotNull(appContext)): String {
        val f = logFile(context)
        return if (f.exists()) f.readText() else ""
    }

    fun lineCount(context: Context = requireNotNull(appContext)): Int {
        val text = readAll(context)
        if (text.isBlank()) return 0
        return text.lines().count { it.isNotBlank() }
    }

    fun clear(context: Context = requireNotNull(appContext)) {
        logFile(context).writeText("")
    }

    fun log(
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
        runId: String = "pre-fix",
        onResult: ((ok: Boolean, detail: String) -> Unit)? = null
    ) {
        val payload = JSONObject()
            .put("sessionId", SESSION)
            .put("runId", runId)
            .put("hypothesisId", hypothesisId)
            .put("location", location)
            .put("message", message)
            .put("timestamp", System.currentTimeMillis())
            .put("data", JSONObject(data.mapValues { it.value?.toString() ?: "null" }))
        val body = payload.toString()
        Log.i(TAG, body)

        io.execute {
            try {
                val ctx = appContext
                if (ctx == null) {
                    onResult?.invoke(false, "App context missing")
                    return@execute
                }
                logFile(ctx).appendText(body + "\n")
                val n = lineCount(ctx)
                onResult?.invoke(true, "Saved on phone ($n lines). Tap Copy debug log → paste in chat.")
            } catch (e: Exception) {
                onResult?.invoke(false, "File write failed: ${e.message}")
            }
        }
    }

    fun sendTestPing(onResult: (ok: Boolean, detail: String) -> Unit) {
        log(
            hypothesisId = "PING",
            location = "AgentDebugLog.sendTestPing",
            message = "Manual debug ping (on-device file)",
            data = mapOf("source" to "debug_button", "note" to "no_http"),
            runId = "ping",
            onResult = onResult
        )
    }
}
