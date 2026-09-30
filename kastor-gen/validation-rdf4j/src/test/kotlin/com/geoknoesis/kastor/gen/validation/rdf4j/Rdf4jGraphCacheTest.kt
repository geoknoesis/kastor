package com.geoknoesis.kastor.gen.validation.rdf4j

import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.assertEquals
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
            assertEquals(0, v.digestCount.get(), "a graph with a modification stamp is never digested")
            assertEquals(1, v.loadCount.get())

            g.removeTriple(RdfTriple(ex("p3"), ex("name"), Literal("p3")))
            assertTrue(v.validate(g, ex("p3")) is ValidationResult.Violations, "mutation is detected")
            assertEquals(2, v.loadCount.get())
            assertEquals(0, v.digestCount.get())
        }
    }

    @Test
    fun `graphs without a stamp fall back to the content digest`() {
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            val inner = personGraph("a")
            val g = PlainGraph(inner)
            assertEquals(ValidationResult.Ok, v.validate(g, ex("a")))
            assertEquals(ValidationResult.Ok, v.validate(g, ex("a")))
            assertEquals(1, v.loadCount.get())
            assertTrue(v.digestCount.get() >= 2)
            inner.removeTriple(RdfTriple(ex("a"), ex("name"), Literal("a")))
            assertTrue(v.validate(g, ex("a")) is ValidationResult.Violations, "mutation is detected")
            assertEquals(2, v.loadCount.get())
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
            assertEquals(2, v.loadCount.get())
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
    fun `the per-graph cache is bounded`() {
        Rdf4jValidation.fromTurtle(shapes).use { v ->
            val graphs = List(Rdf4jValidation.MAX_CACHED_GRAPHS + 3) { personGraph("p$it") }
            graphs.forEachIndexed { i, g -> assertEquals(ValidationResult.Ok, v.validate(g, ex("p$i"))) }
            assertTrue(v.cachedGraphCount() <= Rdf4jValidation.MAX_CACHED_GRAPHS)
        }
    }
}
