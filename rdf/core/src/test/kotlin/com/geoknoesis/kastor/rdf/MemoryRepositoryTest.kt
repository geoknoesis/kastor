package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the bundled [com.geoknoesis.kastor.rdf.provider.MemoryRepositoryProvider].
 *
 * These tests target the in-core memory provider directly (not via `Rdf.memory()`,
 * which routes to a real SPARQL-capable provider) because they assert the
 * specific semantics of the lightweight in-memory implementation.
 */
class MemoryRepositoryTest {

    private fun memory(): RdfRepository =
        RdfProviderRegistry.create(RdfConfig(providerId = "memory", variantId = "memory"))

    @Test
    fun `createGraph rejects duplicates and listGraphs updates`() {
        val repo = memory()
        val graphName = Iri("http://example.org/graph")

        assertFalse(repo.hasGraph(graphName))
        val graph = repo.createGraph(graphName)
        assertNotNull(graph)
        assertTrue(repo.hasGraph(graphName))
        assertEquals(listOf(graphName), repo.listGraphs())

        assertThrows(IllegalArgumentException::class.java) {
            repo.createGraph(graphName)
        }

        assertTrue(repo.removeGraph(graphName))
        assertFalse(repo.hasGraph(graphName))
        assertEquals(emptyList<Iri>(), repo.listGraphs())

        assertFalse(repo.removeGraph(graphName))
        repo.close()
    }

    @Test
    fun `namedGraphs reflects create and remove`() {
        val repo = memory()
        val graphName = Iri("http://example.org/graph")

        assertTrue(repo.namedGraphs.isEmpty())
        repo.createGraph(graphName)
        assertTrue(repo.namedGraphs.containsKey(graphName))
        repo.removeGraph(graphName)
        assertFalse(repo.namedGraphs.containsKey(graphName))
        repo.close()
    }

    @Test
    fun `clear removes default and named graphs`() {
        val repo = memory()
        val graphName = Iri("http://example.org/graph")
        val subject = Iri("http://example.org/s")
        val predicate = Iri("http://example.org/p")

        repo.editDefaultGraph().addTriple(RdfTriple(subject, predicate, string("default")))
        repo.editGraph(graphName).addTriple(RdfTriple(subject, predicate, string("named")))
        assertTrue(repo.defaultGraph.size() > 0)
        assertTrue(repo.hasGraph(graphName))

        assertTrue(repo.clear())
        assertEquals(0, repo.defaultGraph.size())
        assertFalse(repo.hasGraph(graphName))
        assertTrue(repo.namedGraphs.isEmpty())

        assertFalse(repo.clear())
        repo.close()
    }

    @Test
    fun `transaction and readTransaction execute blocks`() {
        val repo = memory()
        val subject = Iri("http://example.org/s")
        val predicate = Iri("http://example.org/p")

        repo.transaction {
            editDefaultGraph().addTriple(RdfTriple(subject, predicate, string("tx")))
        }
        assertEquals(1, repo.defaultGraph.size())

        var executed = false
        repo.readTransaction {
            executed = true
        }
        assertTrue(executed)

        repo.close()
    }

    @Test
    fun `close marks repository closed and clears graphs`() {
        val repo = memory()
        val graphName = Iri("http://example.org/graph")
        repo.editDefaultGraph().addTriple(
            RdfTriple(Iri("http://example.org/s"), Iri("http://example.org/p"), string("o"))
        )
        repo.createGraph(graphName)

        assertFalse(repo.isClosed())
        repo.close()
        assertTrue(repo.isClosed())
        assertEquals(0, repo.defaultGraph.size())
        assertTrue(repo.namedGraphs.isEmpty())
    }
}

