package com.smsforwarder.app.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object UpdateDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    /**
     * Downloads [url] into cache/updates/update.apk.
     * [onProgress] is 0f..1f (or indeterminate -1f when total unknown).
     */
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "update.apk")
            if (out.exists()) out.delete()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "SMS-Forwarder-Updater")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download HTTP ${response.code}")
                val body = response.body ?: error("Empty body")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var readTotal = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            readTotal += n
                            if (total > 0) {
                                onProgress((readTotal.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                            } else {
                                onProgress(-1f)
                            }
                        }
                        output.flush()
                    }
                }
            }
            onProgress(1f)
            out
        }
    }
}
