package com.lastwave.app.data.update

import java.net.URI

enum class UpdateChannel(val label: String) {
    AUTOMATIC("Automatic"), STABLE("Stable"), TEST("Test");
    companion object {
        fun fromPreference(value: String?): UpdateChannel = entries.firstOrNull { it.name == value } ?: AUTOMATIC
    }
}

data class AppUpdateRelease(
    val channel: UpdateChannel,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val sizeBytes: Long,
    val sha256: String,
    val certificateSha256: String,
    val apkUrl: String,
    val releaseUrl: String,
    val notes: String = "",
    val dataSchemaVersion: Int = 0,
)

data class AppUpdateFeed(val activeChannel: UpdateChannel, val releases: Map<UpdateChannel, AppUpdateRelease>) {
    fun select(preference: UpdateChannel): AppUpdateRelease? = releases[if (preference == UpdateChannel.AUTOMATIC) activeChannel else preference]
}

internal object UpdatePolicy {
    const val PACKAGE_ID = "com.lastwave.app.custom.updates"
    const val REPOSITORY = "peachiyfallisss/LastWave-custom-video"
    const val FEED_URL = "https://github.com/$REPOSITORY/releases/download/update-feed/updates.json"
    const val MAX_APK_BYTES = 300L * 1024 * 1024

    fun validate(release: AppUpdateRelease) {
        require(release.channel != UpdateChannel.AUTOMATIC) { "Invalid release channel" }
        require(release.versionCode in 1..Int.MAX_VALUE.toLong()) { "Invalid version code" }
        require(release.versionName.isNotBlank() && release.versionName.length <= 100) { "Invalid version name" }
        require(release.minSdk in 29..100 && release.sizeBytes in 1..MAX_APK_BYTES) { "Invalid update size or Android version" }
        require(release.dataSchemaVersion > 0) { "Missing data compatibility version" }
        require(release.sha256.matches(Regex("[a-f0-9]{64}")) && release.certificateSha256.matches(Regex("[a-f0-9]{64}"))) { "Missing update integrity data" }
        val tag = "managed-${release.channel.name.lowercase()}-${release.versionCode}"
        require(release.apkUrl == "https://github.com/$REPOSITORY/releases/download/$tag/LastWave-Updates.apk") { "Untrusted APK location" }
        require(release.releaseUrl == "https://github.com/$REPOSITORY/releases/tag/$tag") { "Untrusted release location" }
    }

    /** Redirects are allowed only to GitHub's HTTPS release-asset infrastructure. */
    fun trustedTransport(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase().orEmpty()
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            (host == "github.com" || host == "release-assets.githubusercontent.com" || host == "objects.githubusercontent.com")
    }.getOrDefault(false)

    fun verifyIdentity(release: AppUpdateRelease, installedCode: Long, sdk: Int, packageId: String,
        archiveCode: Long, archiveMinSdk: Int, installedSigners: Set<String>, archiveSigners: Set<String>,
        installedDataSchema: Int = 0, archiveDataSchema: Int = release.dataSchemaVersion) {
        validate(release)
        require(packageId == PACKAGE_ID) { "This APK belongs to a different app" }
        require(release.versionCode > installedCode && archiveCode == release.versionCode) { "Android cannot install an older or mismatched build" }
        require(sdk >= release.minSdk && sdk >= archiveMinSdk && archiveMinSdk == release.minSdk) { "This update needs a newer Android version" }
        require(release.dataSchemaVersion >= installedDataSchema && archiveDataSchema == release.dataSchemaVersion) { "Update would downgrade or mismatch the app database" }
        require(installedSigners.size == 1 && installedSigners == archiveSigners &&
            installedSigners.single() == release.certificateSha256) { "Update signing key does not match this app" }
    }
}
