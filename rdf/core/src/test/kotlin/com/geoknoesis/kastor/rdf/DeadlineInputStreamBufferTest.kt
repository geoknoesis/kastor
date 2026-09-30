package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream

/** DeadlineInputStream reads into the caller's array directly, and reuses one buffer for helper-thread reads. */
class DeadlineInputStreamBufferTest {
    private class Recording(bytes: ByteArray) : FilterInputStream(ByteArrayInputStream(bytes)) {
        val arrays = ArrayList<ByteArray>()
        override fun read(b: ByteArray, off: Int, len: Int): Int { arrays.add(b); return super.read(b, off, len) }
    }

    private val data = ByteArray(10) { it.toByte() }

    @Test
    fun `reads without a helper thread go straight into the caller array`() {
        val source = Recording(data)
        val stream = DeadlineInputStream(source, System.nanoTime(), 60_000)
        val target = ByteArray(10)
        assertEquals(4, stream.read(target, 0, 4))
        assertEquals(6, stream.read(target, 4, 6))
        assertArrayEquals(data, target)
        source.arrays.forEach { assertSame(target, it) }
    }

    @Test
    fun `helper thread reads reuse one private buffer`() {
        val source = Recording(data)
        // A blocking-read limit longer than the whole deadline sends every read through the helper thread.
        val stream = DeadlineInputStream(source, System.nanoTime(), 60_000, blockingReadMillis = 120_000)
        val target = ByteArray(10)
        assertEquals(4, stream.read(target, 0, 4))
        assertEquals(3, stream.read(target, 4, 3))
        assertEquals(3, stream.read(target, 7, 3))
        assertArrayEquals(data, target)
        assertEquals(3, source.arrays.size)
        source.arrays.forEach { assertNotSame(target, it) }
        assertSame(source.arrays[0], source.arrays[1])
        assertSame(source.arrays[0], source.arrays[2])
        assertEquals(-1, stream.read(target, 0, 1))
    }
}
