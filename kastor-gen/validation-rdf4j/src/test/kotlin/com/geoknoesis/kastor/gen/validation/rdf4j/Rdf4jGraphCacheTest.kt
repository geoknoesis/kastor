package com.geoknoesis.kastor.gen.validation.rdf4j

import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.Rdf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Change detection is cheap for graphs with a modification stamp, and each data graph has its own cached store and
 * lock, so different graphs validate concurrently and alternating between them does not reload.
 */
class Rdf4jGraphCacheTest {

    private val ex = "http://example.org/"
    private fun ex(local: String) = Iri(ex + local)

    private val shapes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
            sh:property [ sh:path ex:name ; sh:minCount 1 ] .
    """.trimIndent()

    private fun personGraph(vararg people: String, named: Boolean = true): MemoryGraph = MemoryGraph().apply {
        people.forEach { p ->
            addTriple(RdfTriple(ex(p), RDF.type, ex("Person")))
            if (named) addTriple(RdfTriple(ex(p), ex("name"), Literal(p)))
        }
    }

    /** A graph without a modification stamp (delegates to [inner]). */
    private class PlainGraph(private val inner: RdfGraph) : RdfGraph by inner

    @Test
    fun `repeated validation of an unchanged stamped graph neither digests nor reloads`() {
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            val g = personGraph(*Array(50) { "p$it" })
            repeat(20) { i -> assertEquals(ValidationResult.Ok, v.validate(g, ex("p${i % 50}"))) }
            assertEquals(0, v.digestCount, "a graph with a modification stamp is never digested")
            assertEquals(1, v.loadCount)

            g.removeTriple(RdfTriple(ex("p3"), ex("name"), Literal("p3")))
            assertTrue(v.validate(g, ex("p3")) is ValidationResult.Violations, "mutation is detected")
            assertEquals(2, v.loadCount)
            assertEquals(0, v.digestCount)
        }
    }

    @Test
    fun `named graphs of the memory repository are stamped and never digested`() {
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            val repo = com.geoknoesis.kastor.rdf.provider.MemoryRepository(com.geoknoesis.kastor.rdf.RdfConfig(providerId = "memory"))
            val name = ex("people")
            repo.editGraph(name).addTriples(personGraph("a", "b").getTriples())
            val g = repo.getGraph(name)
            repeat(5) { assertEquals(ValidationResult.Ok, v.validate(g, ex("a"))) }
            assertEquals(0, v.digestCount, "a named graph view has a modification stamp")
            assertEquals(1, v.loadCount)

            repo.editGraph(name).removeTriple(RdfTriple(ex("b"), ex("name"), Literal("b")))
            assertTrue(v.validate(g, ex("b")) is ValidationResult.Violations, "mutation is detected")
            assertEquals(2, v.loadCount)
            assertEquals(0, v.digestCount)
        }
    }

    @Test
    fun `graphs without a stamp fall back to the content digest`() {
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            val inner = personGraph("a")
            val g = PlainGraph(inner)
            assertEquals(ValidationResult.Ok, v.validate(g, ex("a")))
            assertEquals(ValidationResult.Ok, v.validate(g, ex("a")))
            assertEquals(1, v.loadCount)
            assertTrue(v.digestCount >= 2)
            inner.removeTriple(RdfTriple(ex("a"), ex("name"), Literal("a")))
            assertTrue(v.validate(g, ex("a")) is ValidationResult.Violations, "mutation is detected")
            assertEquals(2, v.loadCount)
        }
    }

    @Test
    fun `alternating between graphs does not reload`() {
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            val g1 = personGraph("a")
            val g2 = personGraph("b", named = false)
            repeat(5) {
                assertEquals(ValidationResult.Ok, v.validate(g1, ex("a")))
                assertTrue(v.validate(g2, ex("b")) is ValidationResult.Violations)
            }
            assertEquals(2, v.loadCount)
        }
    }

    @Test
    fun `different graphs validate concurrently`() {
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            val g1 = personGraph("a")
            val g2 = personGraph("b")
            val secondDone = CountDownLatch(1)
            val firstLoading = CountDownLatch(1)
            // The first graph's load blocks until the second graph has been validated on another thread; with a
            // single validator-wide lock the second validation could not start and the wait would time out.
            v.beforeLoadCommit = {
                if (Thread.currentThread().name == "first") {
                    firstLoading.countDown()
                    check(secondDone.await(20, TimeUnit.SECONDS)) { "second graph did not validate concurrently" }
                }
            }
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit<ValidationResult> {
                    Thread.currentThread().name = "first"
                    v.validate(g1, ex("a"))
                }
                assertTrue(firstLoading.await(20, TimeUnit.SECONDS))
                val second = pool.submit<ValidationResult> {
                    Thread.currentThread().name = "second"
                    v.validate(g2, ex("b")).also { secondDone.countDown() }
                }
                assertEquals(ValidationResult.Ok, second.get(30, TimeUnit.SECONDS))
                assertEquals(ValidationResult.Ok, first.get(30, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `the per-graph cache is bounded by the configured size`() {
        Rdf4jValidation(Rdf.parse(shapes, "TURTLE"), 3).use { v ->
            assertEquals(3, v.maxCachedGraphs)
            val graphs = List(6) { personGraph("p$it") }
            graphs.forEachIndexed { i, g -> assertEquals(ValidationResult.Ok, v.validate(g, ex("p$i"))) }
            assertEquals(3, v.cachedGraphCount())
            // The three most recently used graphs are still loaded.
            graphs.drop(3).forEachIndexed { i, g -> assertEquals(ValidationResult.Ok, v.validate(g, ex("p${i + 3}"))) }
            assertEquals(6, v.loadCount)
        }
        assertThrows(IllegalArgumentException::class.java) { Rdf4jValidation(null, 0) }
    }

    @Test
    fun `the default cache size holds more than four graphs and follows the system property`() {
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            assertEquals(Rdf4jValidation.DEFAULT_MAX_CACHED_GRAPHS, v.maxCachedGraphs)
            val graphs = List(8) { personGraph("p$it") }
            repeat(3) { graphs.forEachIndexed { i, g -> assertEquals(ValidationResult.Ok, v.validate(g, ex("p$i"))) } }
            assertEquals(8, v.loadCount, "eight active graphs do not thrash the cache")
        }
        System.setProperty(Rdf4jValidation.MAX_CACHED_GRAPHS_PROPERTY, "2")
        try {
            Rdf4jValidation.fromTurtle(shapes).use { v -> assertEquals(2, v.maxCachedGraphs) }
            Rdf4jValidation().use { v -> assertEquals(2, v.maxCachedGraphs) }
            System.setProperty(Rdf4jValidation.MAX_CACHED_GRAPHS_PROPERTY, "not a number")
            Rdf4jValidation().use { v -> assertEquals(Rdf4jValidation.DEFAULT_MAX_CACHED_GRAPHS, v.maxCachedGraphs) }
        } finally {
            System.clearProperty(Rdf4jValidation.MAX_CACHED_GRAPHS_PROPERTY)
        }
    }

    @Test
    fun `a validation never waits for another graph and an eviction never takes a store in use`() {
        Rdf4jValidation(Rdf.parse(shapes, "TURTLE"), 1).use { v ->
            val g1 = personGraph("a")
            val g2 = personGraph("b", named = false)
            val g3 = personGraph("c")
            val firstLoading = CountDownLatch(1)
            val release = CountDownLatch(1)
            // The validation of g1 stays inside its store (holding its entry) until released.
            v.beforeLoadCommit = {
                if (Thread.currentThread().name == "first") {
                    firstLoading.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "not released" }
                }
            }
            val pool = Executors.newFixedThreadPool(2) { r -> Thread(r).apply { isDaemon = true } }
            try {
                val first = pool.submit<ValidationResult> {
                    Thread.currentThread().name = "first"
                    v.validate(g1, ex("a"))
                }
                assertTrue(firstLoading.await(20, TimeUnit.SECONDS))
                // The cache (size 1) is saturated by g1, which is in use: these validations run in private stores.
                val others = pool.submit<List<ValidationResult>> {
                    Thread.currentThread().name = "others"
                    listOf(v.validate(g2, ex("b")), v.validate(g3, ex("c")))
                }
                val results = others.get(30, TimeUnit.SECONDS)
                assertTrue(results[0] is ValidationResult.Violations)
                assertEquals(ValidationResult.Ok, results[1])
                assertEquals(2, v.temporaryStoreCount)
                assertEquals(1, v.cachedGraphCount())
                release.countDown()
                assertEquals(ValidationResult.Ok, first.get(30, TimeUnit.SECONDS), "g1's store survived the other validations")
                v.beforeLoadCommit = null
                assertEquals(ValidationResult.Ok, v.validate(g1, ex("a")))
                assertEquals(3, v.loadCount, "g1 was not evicted while in use")
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `a store that fails to shut down does not prevent releasing the others`() {
        val shutDowns = java.util.concurrent.atomic.AtomicInteger()
        class FailingRepository : org.eclipse.rdf4j.repository.sail.SailRepository(
            org.eclipse.rdf4j.sail.shacl.ShaclSail(org.eclipse.rdf4j.sail.memory.MemoryStore()),
        ) {
            override fun shutDownInternal() {
                shutDowns.incrementAndGet()
                super.shutDownInternal()
                throw IllegalStateException("simulated shutdown failure")
            }
        }
        val v = Rdf4jValidation(Rdf.parse(shapes, "TURTLE"), 2)
        v.repositoryFactory = { FailingRepository().apply { init() } }
        val graphs = List(3) { personGraph("p$it") }
        // The third graph evicts the first: the failing shutdown of the evicted store does not fail this call.
        graphs.forEachIndexed { i, g -> assertEquals(ValidationResult.Ok, v.validate(g, ex("p$i"))) }
        assertEquals(1, shutDowns.get())
        val failure = assertThrows(IllegalStateException::class.java) { v.close() }
        assertEquals(3, shutDowns.get(), "close attempted every remaining store")
        assertEquals(1, failure.suppressed.size)
        assertEquals("Rdf4jValidation has been closed", assertThrows(IllegalStateException::class.java) { v.validate(graphs[0], ex("p0")) }.message)
    }
}
