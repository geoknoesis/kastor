package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Inference views of `*-inference` repositories under concurrent readers and writers. */
class JenaInferenceConcurrencyTest {
    private val ex = "http://example.org/"
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    private val x = Iri(ex + "x")
    private fun cls(n: Int) = Iri(ex + "C$n")

    @Test
    @Timeout(60)
    fun `a reader in an old snapshot never caches stale inferences for newer readers`() {
        JenaRepository.MemoryRepositoryWithInference().use { repo ->
            repo.editDefaultGraph().addTriples(listOf(RdfTriple(x, type, cls(0)), RdfTriple(cls(0), subClassOf, cls(1))))
            val inSnapshot = CountDownLatch(1)
            val committed = CountDownLatch(1)
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            val reader = thread {
                try {
                    repo.readTransaction {
                        inSnapshot.countDown()
                        assertTrue(committed.await(20, TimeUnit.SECONDS))
                        // First inference read of this snapshot happens after the concurrent commit.
                        assertFalse(defaultGraph.hasTriple(RdfTriple(x, type, cls(2))), "snapshot isolation")
                        assertTrue(defaultGraph.hasTriple(RdfTriple(x, type, cls(1))))
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                }
            }
            assertTrue(inSnapshot.await(20, TimeUnit.SECONDS))
            repo.editDefaultGraph().addTriple(RdfTriple(cls(1), subClassOf, cls(2)))
            committed.countDown()
            reader.join(30_000)
            assertTrue(failures.isEmpty(), "$failures")

            assertTrue(repo.defaultGraph.hasTriple(RdfTriple(x, type, cls(2))), "inference after commit must not be stale")
        }
    }

    @Test
    @Timeout(60)
    fun `a consumer can wait for a read on another thread without deadlocking`() {
        JenaRepository.MemoryRepositoryWithInference().use { repo ->
            repo.editDefaultGraph().addTriples(listOf(RdfTriple(x, type, cls(0)), RdfTriple(cls(0), subClassOf, cls(1))))
            val executor = Executors.newSingleThreadExecutor()
            try {
                val rows = repo.withSelectRows(SparqlSelectQuery("SELECT ?c WHERE { <${x.value}> a ?c }")) { rows ->
                    val otherThreadRead = executor.submit<Boolean> { repo.defaultGraph.hasTriple(RdfTriple(x, type, cls(1))) }
                    assertTrue(otherThreadRead.get(20, TimeUnit.SECONDS))
                    rows.count()
                }
                assertTrue(rows >= 2)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    @Timeout(120)
    fun `writer sees its committed inferences while concurrent readers populate the cache`() {
        JenaRepository.MemoryRepositoryWithInference().use { repo ->
            repo.editDefaultGraph().addTriple(RdfTriple(x, type, cls(0)))
            val done = AtomicBoolean(false)
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            val readers = (1..4).map {
                thread {
                    try {
                        while (!done.get()) {
                            repo.readTransaction {
                                defaultGraph.find(x, type, null)
                                Thread.yield()
                                defaultGraph.find(null, subClassOf, null)
                            }
                        }
                    } catch (t: Throwable) {
                        failures.add(t)
                    }
                }
            }
            try {
                for (i in 0 until 40) {
                    repo.editDefaultGraph().addTriple(RdfTriple(cls(i), subClassOf, cls(i + 1)))
                    assertTrue(repo.defaultGraph.hasTriple(RdfTriple(x, type, cls(i + 1))), "stale inference after commit $i")
                }
            } finally {
                done.set(true)
                readers.forEach { it.join(30_000) }
            }
            assertTrue(failures.isEmpty(), "$failures")
        }
    }
}
