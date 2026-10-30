package com.lastwave.app.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.provider.Settings
import io.mockk.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppUpdateManagerTest {
    @get:Rule val temp = TemporaryFolder()
    private val context = mockk<Context>(relaxed = true)
    private val pm = mockk<PackageManager>(relaxed = true)
    private val signature = Signature(byteArrayOf(1, 2, 3))
    private val payload = ByteArray(4096) { 4 }
    private var manager: AppUpdateManager? = null
    private lateinit var cache: File
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun packageInfo(code: Long, signature: Signature = this.signature, pkg: String = UpdatePolicy.PACKAGE_ID) = PackageInfo().apply {
        packageName = pkg
        longVersionCode = code
        versionName = "4.2.2-test.$code"
        applicationInfo = ApplicationInfo().apply { minSdkVersion = 29; metaData = android.os.Bundle().apply { putInt("lastwave.update.DATA_SCHEMA", 13) } }
        signingInfo = mockk<SigningInfo> { every { apkContentsSigners } returns arrayOf(signature) }
    }

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val app = RuntimeEnvironment.getApplication() as Context
        app.getSharedPreferences("lastwave_updates", 0).edit().clear().commit()
        cache = temp.newFolder("cache")
        every { context.cacheDir } returns cache
        every { context.packageName } returns UpdatePolicy.PACKAGE_ID
        every { context.applicationContext } returns context
        every { context.packageManager } returns pm
        every { context.getSharedPreferences(any(), any()) } answers { app.getSharedPreferences(firstArg(), secondArg()) }
        every { pm.getPackageInfo(any<String>(), any<Int>()) } returns packageInfo(100)
        every { pm.getPackageArchiveInfo(any<String>(), any<Int>()) } returns packageInfo(200)
        every { pm.canRequestPackageInstalls() } returns false
    }
    @After fun cleanup() {
        manager?.close()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun feed(hash: String = digest(payload)): JSONObject = JSONObject().put("schemaVersion", 1)
        .put("packageId", UpdatePolicy.PACKAGE_ID).put("activeChannel", "test")
        .put("releases", JSONObject().put("test", JSONObject()
            .put("versionCode", 200).put("versionName", "4.2.2-test.200").put("minSdk", 29).put("sizeBytes", payload.size)
            .put("dataSchemaVersion", 13).put("sha256", hash).put("certificateSha256", digest(signature.toByteArray()))
            .put("apkUrl", "https://github.com/${UpdatePolicy.REPOSITORY}/releases/download/managed-test-200/LastWave-Updates.apk")
            .put("releaseUrl", "https://github.com/${UpdatePolicy.REPOSITORY}/releases/tag/managed-test-200")))

    private fun create(feed: JSONObject = feed(), onApk: () -> Unit = {}): AppUpdateManager {
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            val request = chain.request()
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            val body = if (request.url.encodedPath.endsWith("updates.json")) feed.toString().toResponseBody() else {
                onApk(); payload.toResponseBody()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test").body(body).build()
        }.build()
        return AppUpdateManager(context, client).also { manager = it }
    }
    private suspend fun AppUpdateManager.checked() = withTimeout(5_000) { updateInfo.first { !it.isChecking } }

    @Test fun sameSemanticVersionUsesBuildCodeAndOnlyVerifiedApkReachesAndroidPermissionScreen() = runBlocking {
        val manager = create()
        assertTrue(manager.checked().isUpdateAvailable)
        manager.openUpdate(context)
        withTimeout(5_000) { manager.updateInfo.first { it.readyToInstall } }
        verify(timeout = 5_000) { context.startActivity(match { it.action == Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES && it.data.toString() == "package:${UpdatePolicy.PACKAGE_ID}" }) }
        assertEquals(payload.size.toLong(), File(cache, "updates/${digest(payload)}.apk").length())
    }
    @Test fun checksumFailureNeverOpensInstallerAndRemovesPartialFiles() = runBlocking {
        val manager = create(feed("a".repeat(64)))
        manager.checked()
        manager.openUpdate(context)
        withTimeout(5_000) { manager.updateInfo.first { !it.isDownloading && it.message.orEmpty().contains("checksum") } }
        verify(exactly = 0) { pm.getPackageArchiveInfo(any<String>(), any<Int>()) }
        verify(exactly = 0) { context.startActivity(any()) }
        withTimeout(5_000) { while (File(cache, "updates").listFiles().orEmpty().isNotEmpty()) delay(10) }
    }
    @Test fun differentArchiveSignerCannotBeInstalledEvenWithCorrectDownloadHash() = runBlocking {
        every { pm.getPackageArchiveInfo(any<String>(), any<Int>()) } returns packageInfo(200, Signature(byteArrayOf(9)))
        val manager = create()
        manager.checked(); manager.openUpdate(context)
        withTimeout(5_000) { manager.updateInfo.first { !it.isDownloading && it.message.orEmpty().contains("signing key") } }
        verify(exactly = 0) { context.startActivity(any()) }
    }
    @Test fun channelChangeCancelsOldDownloadWithoutInstallingOrOverwritingNewChannelState() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manager = create(onApk = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        manager.checked(); manager.openUpdate(context)
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            manager.selectChannel(UpdateChannel.STABLE)
            val state = manager.checked()
            assertEquals(UpdateChannel.STABLE, state.channel)
            assertFalse(state.isUpdateAvailable)
        } finally { release.countDown() }
        delay(100)
        assertFalse(manager.updateInfo.value.readyToInstall)
        assertEquals(UpdateChannel.STABLE, manager.updateInfo.value.channel)
        verify(exactly = 0) { context.startActivity(any()) }
    }
    @Test fun malformedOrForeignFeedsFailClosed() {
        val raw = feed()
        assertEquals(UpdateChannel.TEST, parseUpdateFeed(raw.toString()).activeChannel)
        for (bad in listOf(JSONObject(raw.toString()).put("packageId", "com.lastwave.app.custom"),
            JSONObject(raw.toString()).put("schemaVersion", 2), JSONObject(raw.toString()).put("activeChannel", "evil"))) {
            assertTrue(runCatching { parseUpdateFeed(bad.toString()) }.isFailure)
        }
        raw.getJSONObject("releases").getJSONObject("test").put("apkUrl", "https://evil.test/app.apk")
        assertTrue(runCatching { parseUpdateFeed(raw.toString()) }.isFailure)
    }
}
