package com.smsforwarder.app.update

import com.smsforwarder.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateChecker {
    private const val OWNER = "Painkiller1991"
    private const val REPO = "SMS-Forwarder"
    private const val LATEST =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Returns an [AvailableUpdate] when GitHub latest is newer than this build
     * and not skipped. Failures return null (silent).
     */
    suspend fun check(skippedVersionCode: Int): AvailableUpdate? = withContext(Dispatchers.IO) {
        runCatching {
            val releaseJson = get(LATEST) ?: return@runCatching null
            val release = JSONObject(releaseJson)
            val notes = release.optString("body").orEmpty().trim()
            val assets = release.getJSONArray("assets")

            var manifestUrl: String? = null
            var apkUrl: String? = null
            var apkBytes: Long? = null
            var apkNameFromManifest: String? = null

            // First pass: find manifest
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == UpdateManifest.ASSET_NAME) {
                    manifestUrl = a.getString("browser_download_url")
                    break
                }
            }
            val manifestJson = manifestUrl?.let { get(it) } ?: return@runCatching null
            val manifest = UpdateManifest.parse(manifestJson)
            apkNameFromManifest = manifest.apkAsset

            if (manifest.versionCode <= BuildConfig.VERSION_CODE) return@runCatching null
            if (manifest.versionCode == skippedVersionCode) return@runCatching null

            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == apkNameFromManifest) {
                    apkUrl = a.getString("browser_download_url")
                    apkBytes = a.optLong("size").takeIf { it > 0 }
                    break
                }
            }
            val url = apkUrl ?: return@runCatching null

            val changelog = when {
                manifest.changelog.isNotEmpty() -> manifest.changelog
                else -> notesToBullets(notes)
            }

            AvailableUpdate(
                versionCode = manifest.versionCode,
                versionName = manifest.versionName,
                changelog = changelog,
                apkDownloadUrl = url,
                apkBytes = apkBytes
            )
        }.getOrNull()
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SMS-Forwarder/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    /** Turns GitHub release body into short changelog bullets. */
    private fun notesToBullets(notes: String): List<String> {
        if (notes.isBlank()) return emptyList()
        return notes.lines()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .take(12)
    }
}
