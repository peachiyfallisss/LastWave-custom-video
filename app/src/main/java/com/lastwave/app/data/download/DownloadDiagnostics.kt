package com.lastwave.app.data.download

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

internal data class DownloadRequestTag(val job: Long, val role: DownloadTrace.Role)

@Singleton
class DownloadDiagnostics @Inject constructor() {
    internal val trace = DownloadTrace()
    private val ids = ConcurrentHashMap<String, Long>()
    private val calls = ConcurrentHashMap<Call, DownloadTrace.Call>()
    internal fun begin(key: String): Long = trace.begin().also { ids[key] = it }
    internal fun finish(key: String, id: Long, outcome: String) { ids.remove(key, id); trace.finish(id, outcome) }
    internal fun tag(key: String, role: DownloadTrace.Role) = ids[key]?.let { DownloadRequestTag(it, role) }
    fun report(): String = trace.report()

    internal val listenerFactory = EventListener.Factory { call ->
        val tag = call.request().tag(DownloadRequestTag::class.java)
        if (tag == null) EventListener.NONE else {
            val request = call.request()
            val range = request.header("Range")?.let { Regex("bytes=([0-9]+)-([0-9]+)").matchEntire(it) }
            val length = range?.let { it.groupValues[2].toLongOrNull()?.minus(it.groupValues[1].toLongOrNull() ?: 0)?.plus(1) }
            val sample = trace.call(tag.job, tag.role, length,
                request.url.host.let { it == "googlevideo.com" || it.endsWith(".googlevideo.com") }, request.url.queryParameter("n") != null, hasRange = request.header("Range") != null)
            calls[call] = sample
            object : EventListener() {
                override fun requestHeadersEnd(call: Call, request: Request) { sample.sent() }
                override fun responseHeadersStart(call: Call) { sample.headers() }
                override fun callEnd(call: Call) { sample.end(false, false); calls.remove(call) }
                override fun callFailed(call: Call, ioe: IOException) { sample.end(true, call.isCanceled()); calls.remove(call) }
            }
        }
    }

    /** Observe bytes without buffering a file, changing headers, or logging URLs. */
    internal val networkInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val sample = calls[chain.call()]
        val body = response.body
        if (sample == null || body == null) response else response.newBuilder().body(object : ResponseBody() {
            private val monitored = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { if (it > 0) sample.bytes(it) }
            }.buffer()
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = monitored
        }).build()
    }
}
