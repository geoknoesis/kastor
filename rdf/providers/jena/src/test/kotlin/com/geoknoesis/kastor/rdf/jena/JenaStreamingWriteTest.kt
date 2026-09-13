package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JenaStreamingWriteTest {

    private fun triples(n: Int): Sequence<RdfTriple> =
        generateSequence(0) { it + 1 }.take(n).map { RdfTriple(Iri("urn:s:$it"), Iri("urn:p"), Literal("v$it")) }

    @Test
    fun `sequence writes stream into one transaction`() {
        val repo = JenaRepository.MemoryRepository()
        try {
            val graph = repo.editDefaultGraph()
            graph.addTriples(triples(15_000).constrainOnce())
            assertEquals(15_000, graph.size())
            assertTrue(graph.removeTriples(triples(5_000).constrainOnce()))
            assertEquals(10_000, graph.size())
            assertTrue(graph.removeTriples(Iterable { triples(15_000).iterator() }))
            assertEquals(0, graph.size())
        } finally {
            repo.close()
        }
    }

    @Test
    fun `a failing sequence rolls back the whole write`() {
        val repo = JenaRepository.MemoryRepository()
        try {
            val graph = repo.editDefaultGraph()
            val failing = triples(100).map { if (it.subject == Iri("urn:s:50")) throw IllegalStateException("boom") else it }
            assertThrows(IllegalStateException::class.java) { graph.addTriples(failing) }
            assertEquals(0, graph.size(), "no triple of the failed stream may be committed")
        } finally {
            repo.close()
        }
    }

    @Test
    fun `jena models base direction natively`() {
        assertTrue(JenaProvider().getCapabilities().supportsBaseDirection)
    }

    @Test
    fun `parser rejects langString literals without a tag and malformed tags`() {
        val rdf = "<urn:s> <urn:p> \"x\"^^<http://www.w3.org/1999/02/22-rdf-syntax-ns#langString> ."
        assertThrows(RdfFormatException::class.java) { JenaProvider().parseGraph(rdf.byteInputStream(), "N-TRIPLES") }
        val dir = "<urn:s> <urn:p> \"x\"^^<http://www.w3.org/1999/02/22-rdf-syntax-ns#dirLangString> ."
        assertThrows(RdfFormatException::class.java) { JenaProvider().parseGraph(dir.byteInputStream(), "N-TRIPLES") }
        val ok = "<urn:s> <urn:p> \"x\"@en-GB ."
        assertFalse(JenaProvider().parseGraph(ok.byteInputStream(), "N-TRIPLES").getTriples().isEmpty())
    }
}
