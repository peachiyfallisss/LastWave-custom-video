package com.lastwave.app.data.download

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Offline media, not playback streaming. Google video CDN whole-file GETs were
 * orders of magnitude slower than bounded ranges in the phone capture. Keep
 * each request small, without changing the signed URL, selected format, or bytes.
 * Other origins keep a single request unless explicitly opted in by a caller.
 */
internal class RangedMediaDownloader(
    private val client: OkHttpClient,
    private val chunkBytes: Long = 1024L * 1024,
    private val parallelism: Int = 4,
) {
    init {
        require(chunkBytes in 1..(2L * 1024 * 1024))
        require(parallelism in 1..4)
    }

    suspend fun copy(
        request: Request,
        destination: File,
        useRanges: Boolean = isGoogleVideoMedia(request.url),
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            RandomAccessFile(destination, "rw").use { it.setLength(0) }
            val bytesRead = AtomicLong()
            val progressLock = Any()
            var reported = -1
            fun progress(delta: Long, total: Long) = synchronized(progressLock) {
                val count = bytesRead.addAndGet(delta)
                val percent = if (total > 0) (count.toDouble() * 100 / total).toInt().coerceIn(0, 99) else 0
                if (percent > reported) { reported = percent; onProgress(percent) }
            }
            // The first real chunk discovers the total; no HEAD/probe or duplicate
            // first chunk is needed. A 200 response safely consumes that same body
            // once when a server does not support ranges.
            val firstRequest = if (useRanges) request.newBuilder()
                .header("Range", "bytes=0-${chunkBytes - 1}")
                .header("Accept-Encoding", "identity").build() else request
            val first = retryIo {
                // A failed attempt may have written bytes. Reset both file and
                // progress before retrying the first response from offset zero.
                RandomAccessFile(destination, "rw").use { it.setLength(0) }
                bytesRead.set(0)
                awaitDownloadResponse(client.newCall(firstRequest)) { response, active ->
                    validateMedia(response)
                    val range = if (response.code == 206) parseRange(response) else null
                    if (response.code != 200 && response.code != 206) throw ProtocolFailure("Unexpected media response")
                    if (range != null && (range.start != 0L || range.end !=
                            if (useRanges) minOf(chunkBytes - 1, range.total - 1) else range.total - 1)) {
                        throw ProtocolFailure("Unexpected first media range")
                    }
                    val total = range?.total ?: response.body!!.contentLength()
                    readBody(response, destination, 0, range?.length ?: total, active) { delta -> progress(delta, total) }
                    FirstChunk(range?.takeIf { useRanges }, total,
                        response.header("ETag"), response.header("Last-Modified"))
                }
            }
            val initialRange = first.range
            if (initialRange != null && initialRange.end + 1 < first.total) {
                val rangeLock = Any()
                var next = initialRange.end + 1
                fun nextRange(): ByteRange? = synchronized(rangeLock) {
                    if (next >= first.total) null else ByteRange(next,
                        next + minOf(chunkBytes, first.total - next) - 1, first.total).also { next = it.end + 1 }
                }
                // A fixed number of workers, not one job/list entry per chunk:
                // long concert recordings have bounded memory and socket use too.
                coroutineScope {
                    repeat(parallelism) {
                        launch {
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val range = nextRange() ?: break
                                var highWater = 0L
                                retryIo {
                                    var attemptBytes = 0L
                                    val ranged = request.newBuilder()
                                        .header("Range", "bytes=${range.start}-${range.end}")
                                        .header("Accept-Encoding", "identity")
                                        .apply { first.validator?.let { header("If-Range", it) } }.build()
                                    awaitDownloadResponse(client.newCall(ranged)) { response, active ->
                                        validateMedia(response)
                                        if (response.code != 206 || parseRange(response) != range) {
                                            throw ProtocolFailure("Media server did not honor the requested range")
                                        }
                                        if ((first.etag != null && response.header("ETag")?.let { it != first.etag } == true) ||
                                            (first.lastModified != null && response.header("Last-Modified")?.let { it != first.lastModified } == true)) {
                                            throw ProtocolFailure("Media changed during download")
                                        }
                                        readBody(response, destination, range.start, range.length, active) { delta ->
                                            attemptBytes += delta
                                            if (attemptBytes > highWater) {
                                                val unique = attemptBytes - highWater
                                                highWater = attemptBytes
                                                progress(unique, first.total)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (destination.length() <= 0L || (first.total > 0 && destination.length() != first.total)) {
                throw ProtocolFailure("Media download incomplete")
            }
            // Success is reported only after every range's exact body length has
            // been checked and its callback has settled.
            onProgress(100)
        } catch (failure: Throwable) {
            // coroutineScope and awaitDownloadResponse join all writers before
            // this cleanup. Never expose a preallocated/truncated partial file.
            destination.delete()
            throw failure
        }
    }

    private fun readBody(
        response: Response, destination: File, offset: Long, expected: Long,
        active: () -> Boolean, progress: (Long) -> Unit,
    ) {
        val body = response.body ?: throw ProtocolFailure("Empty media response")
        val declared = body.contentLength()
        if (expected >= 0 && declared >= 0 && declared != expected) throw ProtocolFailure("Media response length mismatch")
        RandomAccessFile(destination, "rw").use { output ->
            output.seek(offset)
            body.byteStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                var count = 0L
                while (true) {
                    if (!active()) throw CancellationException("Media download cancelled")
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (expected >= 0 && read > expected - count) throw ProtocolFailure("Media response exceeded its range")
                    output.write(buffer, 0, read)
                    count += read
                    progress(read.toLong())
                }
                if (count == 0L || (expected >= 0 && count != expected)) throw IOException("Media response truncated")
            }
        }
    }

    private fun validateMedia(response: Response) {
        if (!response.isSuccessful) throw HttpFailure(response.code)
        val body = response.body ?: throw ProtocolFailure("Empty media response")
        val type = body.contentType()
        if (type?.type == "text" || type?.subtype?.contains("json", ignoreCase = true) == true) {
            throw ProtocolFailure("Server returned a page instead of media")
        }
        if (response.header("Content-Encoding")?.let { !it.equals("identity", ignoreCase = true) } == true) {
            throw ProtocolFailure("Encoded media cannot be combined as byte ranges")
        }
    }

    private fun parseRange(response: Response): ByteRange {
        val parts = RANGE.matchEntire(response.header("Content-Range").orEmpty())
            ?: throw ProtocolFailure("Missing media Content-Range")
        val (start, end, total) = parts.groupValues.drop(1).map { it.toLongOrNull() ?: throw ProtocolFailure("Invalid media range") }
        if (start < 0 || end < start || total <= end) throw ProtocolFailure("Invalid media range bounds")
        return ByteRange(start, end, total)
    }

    private suspend fun <T> retryIo(block: suspend () -> T): T {
        for (attempt in 0..1) {
            currentCoroutineContext().ensureActive()
            try { return block() }
            catch (failure: IOException) {
                if (attempt == 1 || failure is ProtocolFailure || (failure is HttpFailure && failure.code !in 500..599)) throw failure
                delay(200)
            }
        }
        error("Unreachable")
    }

    private data class ByteRange(val start: Long, val end: Long, val total: Long) { val length: Long get() = end - start + 1 }
    private data class FirstChunk(val range: ByteRange?, val total: Long, val etag: String?, val lastModified: String?) {
        val validator: String? get() = etag?.takeUnless { it.startsWith("W/", ignoreCase = true) } ?: lastModified
    }
    private class ProtocolFailure(message: String) : IOException(message)
    private class HttpFailure(val code: Int) : IOException("Media download failed (HTTP $code)")

    companion object {
        private val RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
        internal fun isGoogleVideoMedia(url: HttpUrl): Boolean = url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com")
    }
}
