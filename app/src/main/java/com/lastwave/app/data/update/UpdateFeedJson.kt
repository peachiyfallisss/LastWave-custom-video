package com.lastwave.app.data.update

import org.json.JSONObject

internal fun parseUpdateFeed(raw: String): AppUpdateFeed {
    val json = JSONObject(raw)
    require(json.getInt("schemaVersion") == 1 && json.getString("packageId") == UpdatePolicy.PACKAGE_ID) { "Unsupported update feed" }
    fun channel(value: String): UpdateChannel = when (value) {
        "stable" -> UpdateChannel.STABLE
        "test" -> UpdateChannel.TEST
        else -> error("Invalid update channel")
    }
    val active = channel(json.getString("activeChannel"))
    val releases = json.getJSONObject("releases")
    val mapped = listOf("stable", "test").mapNotNull { name ->
        val item = releases.optJSONObject(name) ?: return@mapNotNull null
        val release = AppUpdateRelease(channel(name), item.getLong("versionCode"), item.getString("versionName"),
            item.getInt("minSdk"), item.getLong("sizeBytes"), item.getString("sha256"), item.getString("certificateSha256"),
            item.getString("apkUrl"), item.getString("releaseUrl"), item.optString("notes").take(20_000), item.getInt("dataSchemaVersion"))
        UpdatePolicy.validate(release)
        release.channel to release
    }.toMap()
    require(mapped.containsKey(active)) { "Active update channel has no published build" }
    return AppUpdateFeed(active, mapped)
}
