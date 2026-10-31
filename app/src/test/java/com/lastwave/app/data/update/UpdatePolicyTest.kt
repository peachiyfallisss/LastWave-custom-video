package com.lastwave.app.data.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private fun release(channel: UpdateChannel = UpdateChannel.TEST, code: Long = 200): AppUpdateRelease {
        val tag = "managed-${channel.name.lowercase()}-$code"
        return AppUpdateRelease(channel, code, "4.2.2-test.$code", 29, 1024, "a".repeat(64), "b".repeat(64),
            "https://github.com/${UpdatePolicy.REPOSITORY}/releases/download/$tag/LastWave-Updates.apk",
            "https://github.com/${UpdatePolicy.REPOSITORY}/releases/tag/$tag", dataSchemaVersion = 13)
    }
    @Test fun automaticFollowsPublishedChannelWhileExplicitChannelsStayPinned() {
        val test = release()
        val stable = release(UpdateChannel.STABLE, 201)
        val feed = AppUpdateFeed(UpdateChannel.STABLE, mapOf(UpdateChannel.STABLE to stable, UpdateChannel.TEST to test))
        assertEquals(stable, feed.select(UpdateChannel.AUTOMATIC))
        assertEquals(test, feed.select(UpdateChannel.TEST))
        assertEquals(stable, feed.select(UpdateChannel.STABLE))
        assertNull(AppUpdateFeed(UpdateChannel.TEST, mapOf(UpdateChannel.TEST to test)).select(UpdateChannel.STABLE))
        assertEquals(UpdateChannel.AUTOMATIC, UpdateChannel.fromPreference("unknown"))
    }
    @Test fun newBuildOfSameSemanticVersionCanUpdateAndOldStableCannotDowngradeTest() {
        val release = release()
        UpdatePolicy.verifyIdentity(release, 199, 33, UpdatePolicy.PACKAGE_ID, 200, 29, setOf("b".repeat(64)), setOf("b".repeat(64)))
        assertTrue(runCatching { UpdatePolicy.verifyIdentity(release, 201, 33, UpdatePolicy.PACKAGE_ID, 200, 29,
            setOf("b".repeat(64)), setOf("b".repeat(64))) }.isFailure)
    }
    @Test fun wrongPackageKeyVersionAndUnsupportedAndroidAreRejected() {
        val release = release()
        fun verify(pkg: String = UpdatePolicy.PACKAGE_ID, code: Long = 200, min: Int = 29, sdk: Int = 33,
            signers: Set<String> = setOf("b".repeat(64))) = runCatching {
            UpdatePolicy.verifyIdentity(release, 100, sdk, pkg, code, min, setOf("b".repeat(64)), signers)
        }
        assertTrue(verify(pkg = "com.lastwave.app.custom").isFailure)
        assertTrue(verify(code = 201).isFailure)
        assertTrue(verify(min = 35).isFailure)
        assertTrue(verify(sdk = 28).isFailure)
        assertTrue(verify(signers = emptySet()).isFailure)
        assertTrue(verify(signers = setOf("c".repeat(64))).isFailure)
        assertTrue(runCatching { UpdatePolicy.verifyIdentity(release, 100, 33, UpdatePolicy.PACKAGE_ID, 200, 29,
            setOf("b".repeat(64)), setOf("b".repeat(64)), installedDataSchema = 14) }.isFailure)
    }
    @Test fun apkUrlsAndIntegrityFieldsAreStrict() {
        val release = release()
        listOf(release.copy(apkUrl = release.apkUrl + "?redirect=evil"), release.copy(apkUrl = "http://example.test/app.apk"),
            release.copy(sha256 = ""), release.copy(sizeBytes = UpdatePolicy.MAX_APK_BYTES + 1),
            release.copy(channel = UpdateChannel.AUTOMATIC), release.copy(versionCode = 0)).forEach {
            assertTrue(runCatching { UpdatePolicy.validate(it) }.isFailure)
        }
    }
    @Test fun redirectsAreRestrictedToHttpsGitHubAssetInfrastructure() {
        assertTrue(UpdatePolicy.trustedTransport("https://release-assets.githubusercontent.com/file?sig=abc"))
        assertTrue(UpdatePolicy.trustedTransport(UpdatePolicy.FEED_URL))
        listOf("http://github.com/file", "https://github.com.evil.test/file", "https://github.com@evil.test/file",
            "file:///tmp/app.apk", "https://github.com:8443/file", "https://evil.test/file").forEach {
            assertFalse(UpdatePolicy.trustedTransport(it))
        }
    }
}
