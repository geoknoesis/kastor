package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** Exceeded isomorphism limits are reported with a typed exception naming the limit. */
class IsomorphismLimitExceptionTest {
    private fun graph(prefix: String, size: Int) = MemoryGraph((0 until size).map {
        RdfTriple(BlankNode("$prefix$it"), Iri("urn:p"), BlankNode("$prefix${(it + 1) % size}"))
    })

    @Test
    fun `a timeout throws GraphIsomorphismLimitException with the TIME reason`() {
        val error = assertThrows(GraphIsomorphismLimitException::class.java) {
            graph("a", 50).isIsomorphicTo(graph("b", 50), null, Duration.ofNanos(1))
        }
        assertEquals(GraphIsomorphismLimitException.Reason.TIME, error.reason)
        assertTrue(error.message!!.contains("time limit"), error.message)
        // Still an IllegalStateException, so existing handlers keep working.
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `work and interruption limits carry their reason`() {
        val work = assertThrows(GraphIsomorphismLimitException::class.java) {
            graph("a", 50).isIsomorphicTo(graph("b", 50), 1L, null)
        }
        assertEquals(GraphIsomorphismLimitException.Reason.WORK, work.reason)

        Thread.currentThread().interrupt()
        try {
            val interrupted = assertThrows(GraphIsomorphismLimitException::class.java) { graph("a", 5).isIsomorphicTo(graph("b", 5)) }
            assertEquals(GraphIsomorphismLimitException.Reason.INTERRUPTED, interrupted.reason)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
