package com.lastwave.app.data.download

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CodecPacketTest {
    @Test fun directAndHeapBufferCopiesMatchEveryOriginalPacketByte() {
        val expected = ByteArray(256 * 1024 + 73) { ((it * 31 + it / 256) % 251).toByte() }
        for (buffer in listOf(ByteBuffer.allocate(expected.size), ByteBuffer.allocateDirect(expected.size))) {
            buffer.put(expected).flip()
            assertArrayEquals(expected, copyCodecPacket(buffer, expected.size))
        }
    }
    @Test fun reportedSampleStartsAtZeroAndDoesNotMutateTheExtractorsBufferState() {
        val buffer = ByteBuffer.allocateDirect(100).apply { put(ByteArray(100) { it.toByte() }); position(25); limit(45) }
        assertArrayEquals(ByteArray(80) { it.toByte() }, copyCodecPacket(buffer, 80))
        assertEquals(25, buffer.position())
        assertEquals(45, buffer.limit())
    }
    @Test fun readOnlyViewsAndEmptySamplesAreSafeWithoutWritingIntoTheInput() {
        val buffer = ByteBuffer.wrap(byteArrayOf(4, 5, 6, 7)).asReadOnlyBuffer()
        assertArrayEquals(byteArrayOf(4, 5, 6), copyCodecPacket(buffer, 3))
        assertArrayEquals(byteArrayOf(), copyCodecPacket(buffer, 0))
        assertEquals(0, buffer.position())
    }
    @Test fun invalidReportedSampleSizesAreRejectedBeforeAnyRead() {
        for (size in listOf(-1, 17)) {
            try { copyCodecPacket(ByteBuffer.allocateDirect(16), size); fail("Invalid sample size accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
