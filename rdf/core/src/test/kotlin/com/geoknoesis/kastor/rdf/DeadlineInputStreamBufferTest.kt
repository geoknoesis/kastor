package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream

/** DeadlineInputStream reads into the caller's array directly, and its helper reuses one buffer of its own. */
class DeadlineInputStreamBufferTest {
    /** Records the arrays it is asked to fill; with [buffered] false it reports no data at hand, like an idle socket. */
    private class Recording(bytes: ByteArray, private val buffered: Boolean = true) : FilterInputStream(ByteArrayInputStream(bytes)) {
        val arrays = ArrayList<ByteArray>()
        override fun available(): Int = if (buffered) super.available() else 0
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
        val source = Recording(data, buffered = false)
        // A blocking-read limit longer than the whole deadline sends every read that may block through the helper.
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
        stream.close()
    }

    @Test
    fun `single byte reads and skips go through the helper as well`() {
        val source = Recording(data, buffered = false)
        val stream = DeadlineInputStream(source, System.nanoTime(), 60_000, blockingReadMillis = 120_000)
        assertEquals(0, stream.read())
        assertEquals(1, stream.read())
        assertEquals(3, stream.skip(3))
        assertEquals(5, stream.read())
        val rest = ByteArray(8)
        assertEquals(4, stream.read(rest, 2, 6))
        assertArrayEquals(byteArrayOf(0, 0, 6, 7, 8, 9, 0, 0), rest)
        assertEquals(-1, stream.read())
        assertEquals(-1, stream.read())
        stream.close()
        stream.close()
    }
}
