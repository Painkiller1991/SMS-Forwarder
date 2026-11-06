package com.smsforwarder.app.update

import com.smsforwarder.app.BuildConfig
import com.smsforwarder.app.debug.AgentDebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class UpdateCheckResult {
    data class Available(val update: AvailableUpdate) : UpdateCheckResult()
    data class UpToDate(val installedCode: Int, val latestCode: Int) : UpdateCheckResult()
    data class Failed(val reason: String) : UpdateCheckResult()
}

object UpdateChecker {
    private const val OWNER = "Painkiller1991"
    private const val REPO = "SMS-Forwarder"
    private const val LATEST_API =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    /** Avoids GitHub API rate limits; follows redirect to the Latest release asset. */
    private const val MANIFEST_DIRECT =
        "https://github.com/$OWNER/$REPO/releases/latest/download/${UpdateManifest.ASSET_NAME}"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun check(skippedVersionCode: Int): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val (manifest, apkUrl, apkBytes, notes) = loadLatest()
                ?: return@withContext UpdateCheckResult.Failed("Could not reach GitHub releases")

            AgentDebugLog.log(
                hypothesisId = "UPD",
                location = "UpdateChecker.check",
                message = "Manifest loaded",
                data = mapOf(
                    "installed" to BuildConfig.VERSION_CODE,
                    "latest" to manifest.versionCode,
                    "latestName" to manifest.versionName,
                    "skipped" to skippedVersionCode
                ),
                runId = "update-check"
            )

            if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                return@withContext UpdateCheckResult.UpToDate(
                    BuildConfig.VERSION_CODE,
                    manifest.versionCode
                )
            }
            if (!manifest.forceUpdate && manifest.versionCode == skippedVersionCode) {
                return@withContext UpdateCheckResult.UpToDate(
                    BuildConfig.VERSION_CODE,
                    manifest.versionCode
                )
            }
            val url = apkUrl
                ?: return@withContext UpdateCheckResult.Failed("APK asset missing: ${manifest.apkAsset}")

            val changelog = when {
                manifest.changelog.isNotEmpty() -> manifest.changelog
                else -> notesToBullets(notes)
            }
            UpdateCheckResult.Available(
                AvailableUpdate(
                    versionCode = manifest.versionCode,
                    versionName = manifest.versionName,
                    changelog = changelog,
                    apkDownloadUrl = url,
                    apkBytes = apkBytes,
                    forceUpdate = manifest.forceUpdate
                )
            )
        } catch (e: Exception) {
            AgentDebugLog.log(
                hypothesisId = "UPD",
                location = "UpdateChecker.check",
                message = "Check failed",
                data = mapOf("error" to (e.message?.take(160) ?: e.javaClass.simpleName)),
                runId = "update-check"
            )
            UpdateCheckResult.Failed(e.message?.take(80) ?: "Update check failed")
        }
    }

    private data class Loaded(
        val manifest: UpdateManifest,
        val apkUrl: String?,
        val apkBytes: Long?,
        val notes: String
    )

    private fun loadLatest(): Loaded? {
        // 1) Direct Latest download (no API quota)
        val directJson = get(MANIFEST_DIRECT)
        if (directJson != null) {
            val manifest = UpdateManifest.parse(directJson)
            val apkUrl =
                "https://github.com/$OWNER/$REPO/releases/latest/download/${manifest.apkAsset}"
            return Loaded(manifest, apkUrl, null, "")
        }
        // 2) Fallback: GitHub API
        val releaseJson = get(LATEST_API) ?: return null
        val release = JSONObject(releaseJson)
        val notes = release.optString("body").orEmpty().trim()
        val assets = release.getJSONArray("assets")
        var manifestUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name") == UpdateManifest.ASSET_NAME) {
                manifestUrl = a.getString("browser_download_url")
                break
            }
        }
        val manifestJson = manifestUrl?.let { get(it) } ?: return null
        val manifest = UpdateManifest.parse(manifestJson)
        var apkUrl: String? = null
        var apkBytes: Long? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name") == manifest.apkAsset) {
                apkUrl = a.getString("browser_download_url")
                apkBytes = a.optLong("size").takeIf { it > 0 }
                break
            }
        }
        return Loaded(manifest, apkUrl, apkBytes, notes)
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json, application/json, */*")
            .header("User-Agent", "SMS-Forwarder/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                AgentDebugLog.log(
                    hypothesisId = "UPD",
                    location = "UpdateChecker.get",
                    message = "HTTP not OK",
                    data = mapOf("url" to url.take(120), "code" to response.code),
                    runId = "update-check"
                )
                return null
            }
            return response.body?.string()
        }
    }

    private fun notesToBullets(notes: String): List<String> {
        if (notes.isBlank()) return emptyList()
        return notes.lines()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .take(12)
    }
}
