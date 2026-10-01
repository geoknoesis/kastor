package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlAskQuery
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.UpdateQuery
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An explicit `_:r rdf:reifies <<( s p o )>>` triple is kept in the store itself, so it does not depend on the
 * [Rdf4jRepository] instance that wrote it, on the process, or on the interleaving of concurrent writers.
 */
class Rdf4jReifiesDurabilityTest {
    @TempDir
    lateinit var tmp: Path

    private val ex = "http://example.org/"
    private val vf = SimpleValueFactory.getInstance()
    private val quoted = vf.createTriple(vf.createIRI(ex + "a"), vf.createIRI(ex + "b"), vf.createIRI(ex + "c"))
    private val reifier = Rdf4jTerms.reifierFor(quoted)
    private val reifies = RdfTriple(reifier, RDF.reifies, TripleTerm(RdfTriple(Iri(ex + "a"), Iri(ex + "b"), Iri(ex + "c"))))
    private val annotation = RdfTriple(reifier, Iri(ex + "p"), Literal("o"))
    private val other = RdfTriple(reifier, Iri(ex + "q"), Literal("o2"))

    /** A persistent RDF-star capable store: RDF4J's `MemoryStore` with a data directory. */
    private fun persistentMemory(name: String): Rdf4jRepository {
        val store = MemoryStore(tmp.resolve(name).toFile())
        store.syncDelay = 0
        return Rdf4jRepository(SailRepository(store).also { it.init() })
    }

    @Test
    fun `an explicit rdf-reifies on a persistent RDF-star store survives a restart and the removal of its annotation`() {
        persistentMemory("explicit").use { repo ->
            val graph = repo.editDefaultGraph()
            graph.addTriple(annotation)
            graph.addTriple(reifies)
        }
        persistentMemory("explicit").use { repo ->
            val graph = repo.editDefaultGraph()
            assertEquals(setOf(annotation, reifies), graph.getTriples().toSet())
            assertTrue(graph.removeTriple(annotation))
            assertEquals(setOf(reifies), graph.getTriples().toSet(), "the explicit rdf:reifies must not be deleted with the annotation")
            assertEquals(1, graph.size())
        }
        persistentMemory("explicit").use { repo ->
            assertEquals(setOf(reifies), repo.defaultGraph.getTriples().toSet())
        }
    }

    @Test
    fun `a merely implied rdf-reifies on a persistent RDF-star store still goes with its last annotation after a restart`() {
        persistentMemory("implied").use { repo -> repo.editDefaultGraph().addTriple(annotation) }
        persistentMemory("implied").use { repo ->
            val graph = repo.editDefaultGraph()
            assertEquals(setOf(annotation, reifies), graph.getTriples().toSet())
            assertTrue(graph.removeTriple(annotation))
            assertEquals(emptySet(), graph.getTriples().toSet())
        }
    }

    @Test
    fun `a removed rdf-reifies stays removed across a restart`() {
        persistentMemory("removed").use { repo ->
            val graph = repo.editDefaultGraph()
            graph.addTriple(annotation)
            assertTrue(graph.removeTriple(reifies))
        }
        persistentMemory("removed").use { repo ->
            val graph = repo.editDefaultGraph()
            assertEquals(setOf(annotation), graph.getTriples().toSet())
            graph.addTriple(other)
            assertEquals(setOf(annotation, other), graph.getTriples().toSet())
        }
    }

    /** NativeStore cannot hold triple terms: statements about a reifier blank node are plain statements there. */
    @Test
    fun `statements about a reifier blank node on a native store survive a restart unchanged`() {
        val location = tmp.resolve("native").toString()
        Rdf4jRepository.NativeRepository(location).use { repo ->
            repo.editDefaultGraph().addTriples(listOf(annotation, other))
        }
        Rdf4jRepository.NativeRepository(location).use { repo ->
            val graph = repo.editDefaultGraph()
            assertEquals(setOf(annotation, other), graph.getTriples().toSet())
            assertTrue(graph.removeTriple(annotation))
            assertEquals(setOf(other), graph.getTriples().toSet())
            assertEquals(1, graph.size())
            assertFalse(graph.hasTriple(reifies))
        }
        Rdf4jRepository.NativeRepository(location).use { repo ->
            assertEquals(setOf(other), repo.defaultGraph.getTriples().toSet())
        }
    }

    @Test
    fun `two repositories over one RDF4J repository agree about explicit rdf-reifies triples`() {
        val shared = SailRepository(MemoryStore()).also { it.init() }
        val first = Rdf4jRepository(shared)
        val second = Rdf4jRepository(shared)
        try {
            first.editDefaultGraph().addTriple(annotation)
            first.editDefaultGraph().addTriple(reifies)
            assertTrue(second.editDefaultGraph().removeTriple(annotation))
            assertEquals(setOf(reifies), second.defaultGraph.getTriples().toSet(), "seen by the second repository")
            assertEquals(setOf(reifies), first.defaultGraph.getTriples().toSet(), "seen by the first repository")
            assertTrue(first.editDefaultGraph().removeTriple(reifies))
            second.editDefaultGraph().addTriple(annotation)
            assertTrue(first.editDefaultGraph().removeTriple(annotation))
            assertEquals(emptySet(), second.defaultGraph.getTriples().toSet(), "a removed rdf:reifies is not resurrected")
        } finally {
            first.close()
        }
    }

    @Test
    fun `an explicit rdf-reifies is a stored statement that SPARQL sees and updates do not copy`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val graph = repo.editDefaultGraph()
            graph.addTriple(annotation)
            graph.addTriple(reifies)
            val ask = SparqlAskQuery("ASK { ?r <${RDF.reifies.value}> << <${ex}a> <${ex}b> <${ex}c> >> }")
            assertTrue(repo.ask(ask), "explicit rdf:reifies is visible to SPARQL")
            repeat(3) { repo.update(UpdateQuery("INSERT DATA { <${ex}x> <${ex}y> \"$it\" }")) }
            assertEquals(5, graph.size())
            repo.update(UpdateQuery("DELETE WHERE { ?s <${ex}p> ?o }"))
            assertTrue(graph.hasTriple(reifies))
            assertTrue(graph.removeTriple(reifies))
            assertFalse(repo.ask(ask))
            assertEquals(3, graph.size())
        }
    }

    private fun <T> onTwoThreads(block: (java.util.concurrent.ExecutorService) -> T): T {
        val pool = Executors.newFixedThreadPool(2)
        try {
            return block(pool)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun task(block: () -> Unit): java.util.concurrent.Callable<Unit> = java.util.concurrent.Callable { block() }

    private fun assertOnlyReifiesRemains(repo: Rdf4jRepository) {
        val graph = repo.editDefaultGraph()
        assertEquals(setOf(reifies), graph.getTriples().toSet(), "the explicit rdf:reifies must be stored once its annotation is gone")
        // Nothing dangles: once removed, the triple is not resurrected by a later annotation that comes and goes.
        assertTrue(graph.removeTriple(reifies))
        graph.addTriple(annotation)
        assertTrue(graph.removeTriple(annotation))
        assertEquals(emptySet(), graph.getTriples().toSet())
    }

    @RepeatedTest(10)
    fun `an explicit rdf-reifies added in an open transaction survives a concurrent removal of the last annotation`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.editDefaultGraph().addTriple(annotation)
            val added = CountDownLatch(1)
            val removed = CountDownLatch(1)
            onTwoThreads { pool ->
                val adder = pool.submit(task {
                    repo.transaction {
                        editDefaultGraph().addTriple(reifies)
                        added.countDown()
                        check(removed.await(20, TimeUnit.SECONDS)) { "the removal did not finish" }
                    }
                })
                val remover = pool.submit(task {
                    try {
                        check(added.await(20, TimeUnit.SECONDS)) { "the add did not happen" }
                        repo.editDefaultGraph().removeTriple(annotation)
                    } finally {
                        removed.countDown()
                    }
                })
                adder.get(60, TimeUnit.SECONDS)
                remover.get(60, TimeUnit.SECONDS)
            }
            assertOnlyReifiesRemains(repo)
        }
    }

    @RepeatedTest(10)
    fun `an explicit rdf-reifies added while the last annotation is being removed in an open transaction survives`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.editDefaultGraph().addTriple(annotation)
            val removed = CountDownLatch(1)
            val added = CountDownLatch(1)
            onTwoThreads { pool ->
                val remover = pool.submit(task {
                    repo.transaction {
                        editDefaultGraph().removeTriple(annotation)
                        removed.countDown()
                        check(added.await(20, TimeUnit.SECONDS)) { "the add did not finish" }
                    }
                })
                val adder = pool.submit(task {
                    try {
                        check(removed.await(20, TimeUnit.SECONDS)) { "the removal did not happen" }
                        repo.editDefaultGraph().addTriple(reifies)
                    } finally {
                        added.countDown()
                    }
                })
                remover.get(60, TimeUnit.SECONDS)
                adder.get(60, TimeUnit.SECONDS)
            }
            assertOnlyReifiesRemains(repo)
        }
    }

    @Test
    fun `racing writers never lose an explicit rdf-reifies`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            onTwoThreads { pool ->
                repeat(200) { round ->
                    repo.clear()
                    repo.editDefaultGraph().addTriple(annotation)
                    val barrier = CyclicBarrier(2)
                    val adder = pool.submit(task {
                        barrier.await(20, TimeUnit.SECONDS)
                        repo.editDefaultGraph().addTriple(reifies)
                    })
                    val remover = pool.submit(task {
                        barrier.await(20, TimeUnit.SECONDS)
                        repo.editDefaultGraph().removeTriple(annotation)
                    })
                    adder.get(60, TimeUnit.SECONDS)
                    remover.get(60, TimeUnit.SECONDS)
                    assertEquals(setOf(reifies), repo.defaultGraph.getTriples().toSet(), "round $round")
                }
            }
        }
    }
}
