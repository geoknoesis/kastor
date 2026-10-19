package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AtomicGraphMutationTest {
    private val old = RdfTriple(Iri("urn:s"), Iri("urn:p"), string("old"))
    private val new = old.copy(obj = string("new"))

    @Test
    fun `standalone memory edits roll back removals additions and clears`() {
        val graph = MemoryGraph(listOf(old))
        listOf<MutableRdfGraph.() -> Unit>(
            { removeTriple(old); addTriple(new) },
            { clear(); addTriple(new) },
        ).forEach { edit ->
            assertThrows(IllegalStateException::class.java) {
                graph.mutateAtomically { edit(); error("abort") }
            }
            assertEquals(listOf(old), graph.getTriples())
            assertEquals(listOf(old), graph.find(old.subject, old.predicate))
            assertTrue(graph.hasTriple(old))
            assertFalse(graph.hasTriple(new))
        }
    }

    @Test
    fun `repository memory default and named graphs roll back compound edits`() {
        MemoryRepository(RdfConfig()).use { repo ->
            listOf(repo.editDefaultGraph(), repo.editGraph(Iri("urn:g"))).forEach { graph ->
                graph.addTriple(old)
                assertThrows(IllegalStateException::class.java) {
                    (graph as AtomicMutableRdfGraph).mutateAtomically { removeTriple(old); addTriple(new); error("abort") }
                }
                assertEquals(listOf(old), graph.getTriples())
            }
        }
    }

    @Test
    fun `standalone readers cannot see an in-progress edit and stamps stay committed`() {
        val graph = MemoryGraph(listOf(old))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val stamp = graph.modificationStamp
        try {
            val writer = pool.submit {
                graph.mutateAtomically {
                    removeTriple(old)
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    addTriple(new)
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(stamp, graph.modificationStamp)
            val reader = pool.submit<List<RdfTriple>> { reading.countDown(); graph.getTriples() }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertFalse(reader.isDone)
            release.countDown()
            writer.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(new), reader.get(5, TimeUnit.SECONDS))
            assertNotEquals(stamp, graph.modificationStamp)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
