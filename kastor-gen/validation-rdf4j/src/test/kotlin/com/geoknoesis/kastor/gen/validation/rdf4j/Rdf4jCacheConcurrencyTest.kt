package com.geoknoesis.kastor.gen.validation.rdf4j

import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The validator never reads the data graph while it holds a cache lock (so validating inside a repository
 * transaction cannot deadlock with a concurrent validation), and graph handles obtained anew for every call share
 * one cached copy.
 */
class Rdf4jCacheConcurrencyTest {

    private val ex = "http://example.org/"
    private fun ex(local: String) = Iri(ex + local)

    private val shapes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
            sh:property [ sh:path ex:name ; sh:minCount 1 ] .
    """.trimIndent()

    private fun people(vararg names: String): List<RdfTriple> = names.flatMap { p ->
        listOf(RdfTriple(ex(p), RDF.type, ex("Person")), RdfTriple(ex(p), ex("name"), Literal(p)))
    }

    private fun daemonPool() = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    /** Waits until [thread] is parked (waiting for a lock), i.e. it reached the repository lock. */
    private fun awaitParked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (thread.state != Thread.State.WAITING && thread.state != Thread.State.TIMED_WAITING) {
            check(System.nanoTime() < deadline) { "the other thread never blocked on the repository lock" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `validation inside a repository transaction does not deadlock with a concurrent validation`() {
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            val repo = MemoryRepository(RdfConfig(providerId = "memory"))
            val name = ex("people")
            repo.editGraph(name).addTriples(people("a", "b"))
            val graph = repo.getGraph(name)
            assertEquals(ValidationResult.Ok, v.validate(graph, ex("a")))

            val inTransaction = CountDownLatch(1)
            val other = AtomicReference<Thread>()
            val otherStarted = CountDownLatch(1)
            val pool = daemonPool()
            try {
                // T1 holds the repository write lock for the whole transaction and validates inside it.
                val first = pool.submit<ValidationResult> {
                    var result: ValidationResult? = null
                    repo.transaction {
                        editGraph(name).removeTriple(RdfTriple(ex("b"), ex("name"), Literal("b")))
                        inTransaction.countDown()
                        check(otherStarted.await(10, TimeUnit.SECONDS)) { "second thread did not start" }
                        // T2 is now inside validate(), blocked reading the graph (repository read lock).
                        awaitParked(other.get())
                        result = v.validate(graph, ex("b"))
                    }
                    result!!
                }
                assertTrue(inTransaction.await(10, TimeUnit.SECONDS))
                val second = pool.submit<ValidationResult> {
                    other.set(Thread.currentThread())
                    otherStarted.countDown()
                    v.validate(graph, ex("a"))
                }
                // Before the fix T2 held the graph's cache lock while waiting for the repository lock and T1 held the
                // repository lock while waiting for the cache lock: neither call returned.
                assertTrue(first.get(30, TimeUnit.SECONDS) is ValidationResult.Violations, "sees the uncommitted removal")
                assertEquals(ValidationResult.Ok, second.get(30, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `fresh handles of a memory named graph share one cached copy`() {
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            val repo = MemoryRepository(RdfConfig(providerId = "memory"))
            val name = ex("people")
            repo.editGraph(name).addTriples(people("a", "b"))
            repeat(10) { assertEquals(ValidationResult.Ok, v.validate(repo.getGraph(name), ex("a"))) }
            assertEquals(1, v.loadCount, "a new handle of the same named graph must hit the cache")
            assertEquals(1, v.cachedGraphCount())

            repo.editGraph(name).removeTriple(RdfTriple(ex("b"), ex("name"), Literal("b")))
            assertTrue(v.validate(repo.getGraph(name), ex("b")) is ValidationResult.Violations, "mutation is detected")
            assertEquals(2, v.loadCount)
            assertEquals(1, v.cachedGraphCount(), "the changed graph is reloaded into its existing store")
        }
    }

    @Test
    fun `fresh handles of an RDF4J named graph share one cached copy`() {
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            Rdf4jRepository(SailRepository(MemoryStore())).use { repo ->
                val name = ex("people")
                repo.editGraph(name).addTriples(people("a", "b"))
                repeat(10) { assertEquals(ValidationResult.Ok, v.validate(repo.getGraph(name), ex("a"))) }
                assertEquals(1, v.loadCount, "a new handle with unchanged content must hit the cache")
                assertEquals(1, v.cachedGraphCount())

                repo.editGraph(name).removeTriple(RdfTriple(ex("b"), ex("name"), Literal("b")))
                assertTrue(v.validate(repo.getGraph(name), ex("b")) is ValidationResult.Violations, "mutation is detected")
                assertEquals(2, v.loadCount)
            }
        }
    }
}
