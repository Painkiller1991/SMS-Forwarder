package com.smsforwarder.app.update

import org.json.JSONObject

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkAsset: String,
    /** Optional bullet lines shown in the update dialog Changelog section. */
    val changelog: List<String> = emptyList(),
    /** When true, clients must install — no Later/Skip; features shut down. */
    val forceUpdate: Boolean = false
) {
    companion object {
        const val ASSET_NAME = "update-manifest.json"

        fun parse(json: String): UpdateManifest {
            val o = JSONObject(json)
            val notes = buildList {
                val arr = o.optJSONArray("changelog")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val line = arr.optString(i).trim()
                        if (line.isNotEmpty()) add(line)
                    }
                }
            }
            return UpdateManifest(
                versionCode = o.getInt("versionCode"),
                versionName = o.getString("versionName"),
                apkAsset = o.getString("apkAsset"),
                changelog = notes,
                forceUpdate = o.optBoolean("forceUpdate", false)
            )
        }
    }
}

data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
    val changelog: List<String>,
    val apkDownloadUrl: String,
    val apkBytes: Long?,
    val forceUpdate: Boolean = false
)
