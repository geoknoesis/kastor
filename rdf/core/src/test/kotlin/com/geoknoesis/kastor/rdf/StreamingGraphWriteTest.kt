package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamingGraphWriteTest {

    private fun triples(n: Int): Sequence<RdfTriple> =
        generateSequence(0) { it + 1 }.take(n).map { RdfTriple(Iri("urn:s:$it"), Iri("urn:p"), Iri("urn:o")) }

    @Test
    fun `sequence overloads stream in batches larger than one chunk`() {
        val graph = MemoryGraph()
        // constrainOnce: the default implementation must consume the sequence exactly once.
        graph.addTriples(triples(25_000).constrainOnce())
        assertEquals(25_000, graph.size())

        assertTrue(graph.removeTriples(triples(12_000).constrainOnce()))
        assertEquals(13_000, graph.size())
        assertFalse(graph.removeTriples(triples(10).constrainOnce()))
    }

    @Test
    fun `iterable overloads accept non-collection iterables`() {
        val graph = MemoryGraph()
        val iterable = Iterable { triples(3).iterator() }
        graph.addTriples(iterable)
        assertEquals(3, graph.size())
        assertTrue(graph.removeTriples(Iterable { triples(2).iterator() }))
        assertEquals(1, graph.size())
    }

    @Test
    fun `collection arguments still resolve to the collection overload`() {
        val graph = MemoryGraph()
        val list: List<RdfTriple> = triples(2).toList()
        graph.addTriples(list)
        graph.addTriples(emptyList())
        assertEquals(2, graph.size())
        assertTrue(graph.removeTriples(list.toSet()))
    }

    @Test
    fun `memory provider reports native base direction support`() {
        assertTrue(com.geoknoesis.kastor.rdf.provider.MemoryRepositoryProvider().getCapabilities().supportsBaseDirection)
        assertFalse(ProviderCapabilities().supportsBaseDirection)
    }
}
