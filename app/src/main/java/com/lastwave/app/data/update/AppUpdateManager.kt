package com.lastwave.app.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.lastwave.app.BuildConfig
import com.lastwave.app.data.download.awaitDownloadResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class UpdateInfo(
    val isChecking: Boolean = false,
    val isUpdateAvailable: Boolean = false,
    val latestVersion: String = "",
    val currentVersion: String = "",
    val releaseNotes: String = "",
    val releaseUrl: String = "",
    val downloadUrl: String? = null,
    val isDismissed: Boolean = false,
    val message: String? = null,
    val channel: UpdateChannel = UpdateChannel.AUTOMATIC,
    val resolvedChannel: UpdateChannel? = null,
    val installedChannel: String = BuildConfig.UPDATE_CHANNEL,
    val supported: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadPercent: Int = 0,
    val readyToInstall: Boolean = false,
) {
    val actionLabel: String get() = when {
        isChecking -> "Checking…"
        isDownloading -> "Downloading $downloadPercent%"
        readyToInstall -> "Install update"
        isUpdateAvailable -> "Download update"
        else -> "Check for updates"
    }
    val statusText: String get() = when {
        isDownloading -> "Downloading ${resolvedChannel?.label.orEmpty()} build • $downloadPercent%"
        readyToInstall -> message ?: "Verified update ready. Android will ask you to confirm installation."
        isChecking -> "Checking ${channel.label.lowercase()} updates…"
        message != null -> message
        else -> "Current: $currentVersion • ${installedChannel.replaceFirstChar { it.uppercase() }}"
    }
}

@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("updates") private val okHttpClient: OkHttpClient,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("lastwave_updates", Context.MODE_PRIVATE)
    private val lock = Any()
    private var generation = 0L
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var lastCheckAt = 0L
    private var candidate: AppUpdateRelease? = null
    private val supported = context.packageName == UpdatePolicy.PACKAGE_ID
    private val _updateInfo = MutableStateFlow(UpdateInfo(currentVersion = getCurrentVersion(), supported = supported,
        channel = UpdateChannel.fromPreference(prefs.getString("channel", null))))
    val updateInfo: StateFlow<UpdateInfo> = _updateInfo.asStateFlow()

    init { checkForUpdate(isSilent = true) }
    override fun close() { scope.cancel() }

    @Suppress("DEPRECATION")
    private fun installed(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    fun getCurrentVersion(): String = runCatching { installed().versionName.orEmpty() }.getOrDefault("")

    fun selectChannel(channel: UpdateChannel) {
        synchronized(lock) {
            if (_updateInfo.value.channel == channel) return
            generation++
            checkJob?.cancel(); downloadJob?.cancel()
            checkJob = null; downloadJob = null; candidate = null
            prefs.edit().putString("channel", channel.name).apply()
            _updateInfo.value = UpdateInfo(currentVersion = getCurrentVersion(), channel = channel, supported = supported)
        }
        checkForUpdate()
    }

    fun checkForUpdate(isSilent: Boolean = false) = synchronized(lock) {
        if (!supported) {
            _updateInfo.update { it.copy(message = "One-time setup: install LastWave Updates alongside this app. Existing app data stays untouched.") }
            return@synchronized
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (checkJob?.isActive == true || downloadJob?.isActive == true ||
            (isSilent && lastCheckAt != 0L && now - lastCheckAt < 5 * 60_000L)) return@synchronized
        lastCheckAt = now
        val token = ++generation
        val preference = _updateInfo.value.channel
        _updateInfo.update { it.copy(isChecking = true, message = null) }
        checkJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val feed = parseUpdateFeed(readUrl(UpdatePolicy.FEED_URL) { response, _ ->
                    val body = response.body ?: throw IOException("Empty update feed")
                    if (body.contentLength() > MAX_FEED_BYTES) throw IOException("Update feed is too large")
                    body.byteStream().use { input ->
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(8_192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (bytes.size() + count > MAX_FEED_BYTES) throw IOException("Update feed is too large")
                            bytes.write(buffer, 0, count)
                        }
                        bytes.toString("UTF-8")
                    }
                })
                val release = feed.select(preference)
                val info = installed()
                val newer = release != null && release.versionCode > info.longVersionCode
                val compatible = release == null || (Build.VERSION.SDK_INT >= release.minSdk && release.dataSchemaVersion >= BuildConfig.UPDATE_DATA_SCHEMA &&
                    signerDigests(info) == setOf(release.certificateSha256))
                synchronized(lock) {
                    if (token != generation) return@synchronized
                    candidate = release?.takeIf { newer && compatible }
                    val message = when {
                        release == null -> "No ${preference.label.lowercase()} build has been published yet."
                        !compatible -> "This build is not compatible with this app's signing key, Android version, or database. Nothing was downloaded."
                        newer -> "${release.channel.label} build ${release.versionName} is available."
                        BuildConfig.UPDATE_CHANNEL != release.channel.name.lowercase() ->
                            "Waiting for a newer ${release.channel.label.lowercase()} build. Android will not downgrade your installed test build."
                        else -> "You're up to date on ${release.channel.label.lowercase()}."
                    }
                    _updateInfo.update { it.copy(isChecking = false, isUpdateAvailable = newer && compatible,
                        latestVersion = release?.versionName.orEmpty(), releaseNotes = release?.notes.orEmpty(),
                        releaseUrl = release?.releaseUrl.orEmpty(), downloadUrl = candidate?.apkUrl,
                        resolvedChannel = release?.channel, readyToInstall = false,
                        isDismissed = release?.let { r -> prefs.getString("dismissed_build", null) == dismissKey(r) } ?: false,
                        message = message) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                synchronized(lock) {
                    if (token == generation) {
                        candidate = null
                        _updateInfo.update { it.copy(isChecking = false, isUpdateAvailable = false, readyToInstall = false,
                            message = "Could not check updates. ${failure.message ?: "Try again while online."}") }
                    }
                }
            } finally { synchronized(lock) { if (token == generation) checkJob = null } }
        }.also { it.start() }
    }

    fun dismissUpdate(version: String) = synchronized(lock) {
        candidate?.let { prefs.edit().putString("dismissed_build", dismissKey(it)).apply() }
        _updateInfo.update { it.copy(isDismissed = true) }
    }
    private fun dismissKey(release: AppUpdateRelease) = "${release.channel.name}:${release.versionCode}"

    fun cancelUpdateDownload() = synchronized(lock) { downloadJob?.cancel() }

    /** Download privately, verify, then hand off to Android's user-confirmed installer. */
    fun openUpdate(context: Context) = synchronized(lock) {
        val release = candidate ?: return@synchronized
        if (downloadJob?.isActive == true || checkJob?.isActive == true) return@synchronized
        val token = generation
        val installerContext = context.applicationContext
        _updateInfo.update { it.copy(isDownloading = true, message = null, downloadPercent = 0) }
        downloadJob = scope.launch(start = CoroutineStart.LAZY) {
            val root = File(this@AppUpdateManager.context.cacheDir, "updates")
            val file = File(root, "${release.sha256}.apk")
            val partial = File(root, "${release.sha256}.${System.nanoTime()}.part")
            try {
                if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create update download folder")
                // Recover abandoned process-death downloads without touching a live reader.
                root.listFiles()?.filter { it.extension == "part" && System.currentTimeMillis() - it.lastModified() > 86_400_000L }
                    ?.forEach { it.delete() }
                if (!file.isFile) {
                    readUrl(release.apkUrl) { response, active ->
                        val body = response.body ?: throw IOException("Empty APK response")
                        val length = body.contentLength()
                        if (length > 0 && length != release.sizeBytes) throw IOException("Update size does not match its manifest")
                        var copied = 0L
                        var lastPercent = -1
                        body.byteStream().use { input -> partial.outputStream().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (active()) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied += count
                                if (copied > release.sizeBytes) throw IOException("Update exceeded its expected size")
                                output.write(buffer, 0, count)
                                val percent = (copied * 100 / release.sizeBytes).toInt()
                                if (percent != lastPercent) synchronized(lock) {
                                    lastPercent = percent
                                    if (token == generation) _updateInfo.update { it.copy(downloadPercent = percent) }
                                }
                            }
                            output.flush()
                            output.fd.sync()
                        } }
                        if (active() && copied != release.sizeBytes) throw IOException("Update download was incomplete")
                    }
                    currentCoroutineContext().ensureActive()
                    verifyApk(partial, release)
                    if (!partial.renameTo(file)) throw IOException("Cannot save verified update")
                }
                verifyApk(file, release) // Re-verify cached files before every installer launch.
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (token != generation) return@launch
                    _updateInfo.update { it.copy(isDownloading = false, readyToInstall = true, message = null) }
                }
                // Retain the previous APK while Android may still be reading it.
                root.listFiles()?.filter { it.extension == "apk" && it != file && System.currentTimeMillis() - it.lastModified() > 86_400_000L }
                    ?.forEach { it.delete() }
                withContext(Dispatchers.Main) { launchInstaller(installerContext, file) }
            } catch (cancelled: CancellationException) {
                synchronized(lock) { if (token == generation) _updateInfo.update { it.copy(isDownloading = false, message = "Update download cancelled. Tap to retry.") } }
                throw cancelled
            } catch (failure: Exception) {
                file.delete()
                synchronized(lock) { if (token == generation) _updateInfo.update { it.copy(isDownloading = false, readyToInstall = false,
                    message = "Update not installed: ${failure.message ?: "try again"}") } }
            } finally {
                partial.delete()
                synchronized(lock) { if (token == generation) downloadJob = null }
            }
        }.also { it.start() }
    }

    private fun launchInstaller(context: Context, file: File) {
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                _updateInfo.update { it.copy(message = "Allow updates from LastWave Updates, then return and tap Install update.") }
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (failure: Exception) {
            _updateInfo.update { it.copy(message = "Verified APK is ready, but Android could not open the installer. Tap Install update to retry.") }
        }
    }

    @Suppress("DEPRECATION")
    internal fun verifyApk(file: File, release: AppUpdateRelease) {
        require(file.length() == release.sizeBytes && fileSha256(file) == release.sha256) { "APK checksum does not match the published update" }
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA)
            ?: throw IOException("Android could not read this APK")
        val current = installed()
        UpdatePolicy.verifyIdentity(release, current.longVersionCode, Build.VERSION.SDK_INT, archive.packageName,
            archive.longVersionCode, archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE, signerDigests(current), signerDigests(archive), BuildConfig.UPDATE_DATA_SCHEMA,
            archive.applicationInfo?.metaData?.getInt("lastwave.update.DATA_SCHEMA", 0) ?: 0)
        require(archive.versionName == release.versionName) { "APK version does not match its manifest" }
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun signerDigests(info: PackageInfo): Set<String> = info.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class Redirect(val location: String) : IOException()
    private suspend fun <T> readUrl(url: String, redirects: Int = 0, read: (Response, () -> Boolean) -> T): T {
        require(redirects <= 5 && UpdatePolicy.trustedTransport(url)) { "Untrusted update redirect" }
        try {
            return awaitDownloadResponse(okHttpClient.newCall(Request.Builder().url(url).header("User-Agent", "LastWave-Updates")
                .header("Cache-Control", "no-cache").build())) { response, active ->
                if (response.isRedirect) {
                    val next = response.header("Location")?.let { response.request.url.resolve(it) }?.toString()
                        ?: throw IOException("Invalid update redirect")
                    throw Redirect(next)
                }
                if (!response.isSuccessful) throw IOException(if (response.code == 404) "No managed update has been published yet." else "Server returned HTTP ${response.code}.")
                read(response, active)
            }
        } catch (redirect: Redirect) { return readUrl(redirect.location, redirects + 1, read) }
    }

    companion object { private const val MAX_FEED_BYTES = 256 * 1024 }
}
