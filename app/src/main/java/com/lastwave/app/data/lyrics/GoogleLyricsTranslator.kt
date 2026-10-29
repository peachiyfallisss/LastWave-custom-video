package com.lastwave.app.data.lyrics

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Official Cloud Translation Basic API. Keys exist only in the editor's memory for this request. */
class GoogleLyricsTranslator @Inject constructor() {
    // Deliberately no shared interceptors, account cookies, disk cache, or request logging.
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun translate(lines: List<String>, language: String, apiKey: String): List<String> {
        require(apiKey.isNotBlank()) { "Enter your Google Cloud Translation API key." }
        require(language.matches(Regex("[a-zA-Z]{2,3}(?:-[a-zA-Z]{2,4})?"))) { "Enter a language code such as en, pt, or zh-TW." }
        require(lines.isNotEmpty() && lines.sumOf { it.length } <= CustomLyricsDocument.MAX_TEXT_LENGTH) { "Enter lyrics before translating." }
        // One request item per row keeps translations associated with their original timestamps.
        val result = MutableList(lines.size) { "" }
        for (indices in lyricTranslationBatches(lines)) {
            val body = buildJsonObject {
                put("q", buildJsonArray { indices.forEach { add(lines[it]) } })
                put("target", language)
                put("format", "text")
            }.toString()
            val request = Request.Builder().url("https://translation.googleapis.com/language/translate/v2")
                .header("X-Goog-Api-Key", apiKey.trim())
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            val translated = performRequest(request)
            check(translated.size == indices.size) { "Google returned a different number of lines; no translation was applied." }
            indices.forEachIndexed { index, row -> result[row] = translated[index] }
        }
        return result
    }

    private suspend fun performRequest(request: Request): List<String> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Do not surface URLs, keys, or provider response bodies in UI/logs.
                if (continuation.isActive) continuation.resumeWithException(IOException("Translation request failed. Check your connection and try again."))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val rows = response.use {
                        check(it.isSuccessful) { "Google translation failed (HTTP ${it.code}). Check API access, quota, and billing." }
                        val data = Json.parseToJsonElement(it.body?.string() ?: error("Empty translation response.")).jsonObject
                        data.getValue("data").jsonObject.getValue("translations").jsonArray.map { row ->
                            decodeLyricTranslation(row.jsonObject.getValue("translatedText").jsonPrimitive.content)
                        }
                    }
                    if (continuation.isActive) continuation.resume(rows)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Google translation failed (HTTP ${response.code}). Check API access, quota, and billing."))
                }
            }
        })
    }
}
