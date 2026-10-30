package com.lastwave.app.data.download

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Cancel the socket AND join its reader before releasing a transfer slot or deleting files. */
internal suspend fun <T> awaitDownloadResponse(call: Call, read: (Response, isActive: () -> Boolean) -> T): T {
    val settled = CompletableDeferred<Unit>()
    try {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        try { if (continuation.isActive) continuation.resumeWithException(e) }
                        finally { settled.complete(Unit) }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use { read(it) { continuation.isActive } }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (failure: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        } finally { settled.complete(Unit) }
                    }
                })
            } catch (failure: Throwable) {
                settled.complete(Unit)
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    } finally {
        // Cancelling a continuation alone does not wait for OkHttp's callback.
        withContext(NonCancellable) { settled.await() }
    }
}
