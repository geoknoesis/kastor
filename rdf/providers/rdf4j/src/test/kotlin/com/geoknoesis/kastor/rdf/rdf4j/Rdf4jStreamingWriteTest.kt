package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Rdf4jStreamingWriteTest {

    private fun triples(n: Int): Sequence<RdfTriple> =
        generateSequence(0) { it + 1 }.take(n).map { RdfTriple(Iri("urn:s:$it"), Iri("urn:p"), Literal("v$it")) }

    @Test
    fun `sequence writes stream through one connection`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val graph = repo.editDefaultGraph()
            graph.addTriples(triples(15_000).constrainOnce())
            assertEquals(15_000, graph.size())
            assertTrue(graph.removeTriples(triples(5_000).constrainOnce()))
            assertEquals(10_000, graph.size())
            assertTrue(graph.removeTriples(Iterable { triples(15_000).iterator() }))
            assertEquals(0, graph.size())
            assertFalse(graph.removeTriples(triples(3)))
        }
    }

    @Test
    fun `a failing sequence rolls back the whole write`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val graph = repo.editDefaultGraph()
            val failing = triples(100).map { if (it.subject == Iri("urn:s:50")) throw IllegalStateException("boom") else it }
            assertThrows(IllegalStateException::class.java) { graph.addTriples(failing) }
            assertEquals(0, graph.size(), "no triple of the failed stream may be committed")
        }
    }

    @Test
    fun `rdf4j reports encoded rather than native base direction`() {
        assertFalse(Rdf4jProvider().getCapabilities().supportsBaseDirection)
    }

    @Test
    fun `term checks reject tagless langString literals`() {
        assertThrows(IllegalArgumentException::class.java) {
            Rdf4jTerms.requireWellFormed(TypedLiteral("x", Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#langString")))
        }
        Rdf4jTerms.requireWellFormed(LangString("x", "ar", Direction.RTL))
    }
}
