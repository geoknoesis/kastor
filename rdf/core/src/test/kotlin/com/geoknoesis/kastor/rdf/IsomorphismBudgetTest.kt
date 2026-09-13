package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import java.time.Duration
import kotlin.test.*
import org.junit.jupiter.api.Test

class IsomorphismBudgetTest {
    private fun graph(prefix: String, size: Int) = MemoryGraph((0 until size).map {
        RdfTriple(BlankNode("$prefix$it"), Iri("urn:p"), string("same"))
    })

    @Test fun `large symmetric groups do not exhaust states on already used candidates`() {
        val mapping = WeisfeilerLehmanIsomorphism(10_000).mapping(graph("left", 10_000), graph("right", 10_000))
        assertEquals(10_000, mapping?.size)
        assertEquals(10_000, mapping?.values?.toSet()?.size)
    }

    @Test fun `work budget applies before search and interruption preserves flag`() {
        val graph = graph("a", 10)
        val exhausted = assertFailsWith<IllegalStateException> {
            WeisfeilerLehmanIsomorphism(1_000, 1, Duration.ofSeconds(10)).areIsomorphic(graph, graph)
        }
        assertTrue(exhausted.message!!.contains("work limit"))
        Thread.currentThread().interrupt()
        try {
            val cancelled = assertFailsWith<IllegalStateException> { graph.isIsomorphicTo(graph) }
            assertTrue(cancelled.message!!.contains("interrupted"))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `invalid limits fail at construction`() {
        assertFailsWith<IllegalArgumentException> { WeisfeilerLehmanIsomorphism(0) }
        assertFailsWith<IllegalArgumentException> { WeisfeilerLehmanIsomorphism(1, 0, Duration.ofSeconds(1)) }
        assertFailsWith<IllegalArgumentException> { WeisfeilerLehmanIsomorphism(1, 1, Duration.ZERO) }
    }
}
