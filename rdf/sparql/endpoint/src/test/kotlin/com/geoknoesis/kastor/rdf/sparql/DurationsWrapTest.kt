package com.geoknoesis.kastor.rdf.sparql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `System.nanoTime()` readings may wrap around: deadlines hold across the wrap and are compared by difference. */
class DurationsWrapTest {
    private val nearWrap = Long.MAX_VALUE - 1_000

    @Test
    fun `a deadline whose sum wraps is still a deadline`() {
        val deadline = Durations.deadline(nearWrap, 5_000)
        assertNotNull(deadline)
        assertTrue(deadline!! < 0, "the sum wrapped")
        assertFalse(Durations.reached(nearWrap, deadline))
        assertFalse(Durations.reached(nearWrap + 4_999, deadline))
        assertTrue(Durations.reached(nearWrap + 5_000, deadline))
        assertNull(Durations.deadline(nearWrap, Durations.UNBOUNDED))
        // The markers are never returned.
        assertEquals(Long.MAX_VALUE - 1, Durations.deadline(Long.MAX_VALUE - 10, 10))
        assertEquals(Long.MIN_VALUE - 1, Durations.deadline(Long.MAX_VALUE, 1))
    }

    @Test
    fun `the earlier of two deadlines is found across the wrap`() {
        val before = nearWrap + 10
        val after = nearWrap + 2_000 // wrapped: negative
        assertTrue(after < before)
        assertEquals(before, Durations.earlier(before, after))
        assertEquals(before, Durations.earlier(after, before))
        assertEquals(after, Durations.earlier(Durations.NONE, after))
        assertEquals(before, Durations.earlier(before, Durations.NONE))
    }

    @Test
    fun `the watchdog expires a read whose deadline wrapped only when it is due`() {
        var closed = false
        val stream = object : java.io.InputStream() {
            override fun read(): Int = -1
            override fun close() { closed = true }
        }
        // The read publishes a real deadline; expireIfDue is then asked at chosen clock readings.
        val guarded = GuardedInputStream(stream, ReadWatchdog(), java.time.Duration.ofSeconds(60), null, null)
        val deadline = Durations.deadline(nearWrap, 5_000)!!
        guarded.publishForTest(deadline)
        assertEquals(deadline, guarded.expireIfDue(nearWrap))
        assertFalse(closed)
        assertEquals(Durations.NONE, guarded.expireIfDue(nearWrap + 5_000))
        assertTrue(closed)
    }
}
