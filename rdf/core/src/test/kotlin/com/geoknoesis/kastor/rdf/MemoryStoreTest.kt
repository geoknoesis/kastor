package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import com.geoknoesis.kastor.rdf.provider.MemoryRepositoryProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Direct tests of the in-core memory store (not routed through Jena via Rdf.memory()). */
class MemoryStoreTest {
    private val s = Iri("urn:s")
    private val p = Iri("urn:p")
    private val t1 = RdfTriple(s, p, string("1"))
    private val t2 = RdfTriple(s, p, string("2"))
    private val t3 = RdfTriple(Iri("urn:other"), Iri("urn:q"), string("3"))
    private val t4 = RdfTriple(s, p, string("4"))
    private val g1 = Iri("urn:g1")
    private val g2 = Iri("urn:g2")
    private val g3 = Iri("urn:g3")

    private fun repo() = MemoryRepository(RdfConfig())

    // --- MemoryGraph ---

    @Test
    fun `memory graph is a set with position indexes`() {
        val graph = MemoryGraph(listOf(t1, t1, t2, t3))
        assertEquals(3, graph.size())
        assertEquals(listOf(t1, t2), graph.find(subject = s))
        assertEquals(listOf(t3), graph.find(predicate = Iri("urn:q")))
        assertEquals(listOf(t2), graph.find(obj = string("2")))
        assertEquals(listOf(t2), graph.find(s, p, string("2")))
        assertTrue(graph.removeTriple(t2))
        assertFalse(graph.removeTriple(t2))
        assertEquals(emptyList<RdfTriple>(), graph.find(obj = string("2")))
        assertTrue(graph.hasTriple(t1))
        assertTrue(graph.clear())
        assertEquals(0, graph.size())
        assertEquals(emptyList<RdfTriple>(), graph.find(subject = s))
    }

    @Test
    fun `triple sequences are snapshots`() {
        val graph = MemoryGraph(listOf(t1))
        val snapshot = graph.getTriplesSequence()
        graph.addTriple(t2)
        assertEquals(listOf(t1), snapshot.toList())
    }

    // --- Graph handles ---

    @Test
    fun `graph handles stay live across clear and removeGraph`() {
        val repo = repo()
        val handle = repo.editGraph(g1)
        handle.addTriple(t1)

        repo.clear()
        handle.addTriple(t2)
        assertEquals(listOf(t2), repo.getGraph(g1).getTriples())
        assertTrue(repo.hasGraph(g1))

        assertTrue(repo.removeGraph(g1))
        assertFalse(repo.hasGraph(g1))
        handle.addTriple(t3)
        assertEquals(listOf(t3), repo.getGraph(g1).getTriples())
        assertEquals(listOf(g1), repo.listGraphs())
    }

    @Test
    fun `reading a graph does not create it but its handle sees later writes`() {
        val repo = repo()
        val view = repo.getGraph(g1)
        assertEquals(0, view.size())
        assertFalse(repo.hasGraph(g1))
        assertTrue(repo.listGraphs().isEmpty())

        repo.editGraph(g1).addTriple(t1)
        assertEquals(1, view.size())
        assertEquals(repo.getGraph(g1), repo.createGraph(g1))
    }

    // --- Transactions ---

    @Test
    fun `rollback restores every graph and index`() {
        val repo = repo()
        repo.editDefaultGraph().addTriple(t1)
        repo.editGraph(g1).addTriple(t2)
        repo.editGraph(g2).addTriple(t3)

        assertThrows(IllegalStateException::class.java) {
            repo.transaction {
                editDefaultGraph().removeTriple(t1)
                editDefaultGraph().addTriple(t4)
                removeGraph(g1)
                editGraph(g2).clear()
                editGraph(g3).addTriple(t1)
                clear()
                editGraph(g1).addTriple(t4)
                error("rollback")
            }
        }

        assertEquals(listOf(t1), repo.defaultGraph.getTriples())
        assertEquals(listOf(t1), repo.defaultGraph.find(obj = string("1")))
        assertEquals(emptyList<RdfTriple>(), repo.defaultGraph.find(obj = string("4")))
        assertEquals(listOf(t2), repo.getGraph(g1).getTriples())
        assertEquals(listOf(t3), repo.getGraph(g2).find(predicate = Iri("urn:q")))
        assertFalse(repo.hasGraph(g3))
        assertEquals(setOf(g1, g2), repo.listGraphs().toSet())
    }

    @Test
    fun `committed and nested transactions keep their changes`() {
        val repo = repo()
        repo.transaction {
            editDefaultGraph().addTriple(t1)
            transaction { editGraph(g1).addTriple(t2) }
        }
        assertEquals(listOf(t1), repo.defaultGraph.getTriples())
        assertEquals(listOf(t2), repo.getGraph(g1).getTriples())
    }

    @Test
    fun `writes inside a read transaction fail without deadlocking`() {
        val repo = repo()
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            repo.readTransaction {
                assertThrows(IllegalStateException::class.java) { editDefaultGraph().addTriple(t1) }
                assertThrows(IllegalStateException::class.java) { transaction { } }
            }
        }
        assertEquals(0, repo.defaultGraph.size())
    }

    @Test
    fun `readers are not blocked by a concurrent read transaction`() {
        val repo = repo()
        repo.editDefaultGraph().addTriple(t1)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reader = thread { repo.readTransaction { inside.countDown(); release.await(10, TimeUnit.SECONDS) } }
        try {
            assertTrue(inside.await(10, TimeUnit.SECONDS))
            val size = CompletableFuture.supplyAsync { repo.defaultGraph.size() }.get(5, TimeUnit.SECONDS)
            assertEquals(1, size)
        } finally {
            release.countDown()
            reader.join()
        }
    }

    // --- Capabilities ---

    @Test
    fun `capabilities describe what the store actually does`() {
        val capabilities = MemoryRepositoryProvider().getCapabilities("memory")
        assertEquals("1.2", capabilities.rdfVersion)
        assertTrue(capabilities.supportsTripleTerms)
        assertTrue(capabilities.supportsTransactions)
        assertFalse(capabilities.supportsUpdates)

        val repo = repo()
        val reified = RdfTriple(Iri("urn:r"), p, TripleTerm(t1))
        repo.editDefaultGraph().addTriple(reified)
        assertEquals(listOf(reified), repo.defaultGraph.find(obj = TripleTerm(t1)))
    }
}
