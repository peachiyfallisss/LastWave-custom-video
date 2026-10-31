package com.lastwave.app.data.download

import java.nio.ByteBuffer

/** MediaExtractor writes at offset zero; copy its exact reported sample in one bulk operation. */
internal fun copyCodecPacket(buffer: ByteBuffer, size: Int): ByteArray {
    require(size in 0..buffer.capacity()) { "Invalid codec sample size" }
    return ByteArray(size).also { bytes ->
        // A view leaves the extractor's buffer position/limit untouched. Avoid
        // millions of individual direct-buffer accesses across a concert file.
        buffer.duplicate().apply { clear(); limit(size) }.get(bytes)
    }
}
