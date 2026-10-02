@file:OptIn(com.geoknoesis.kastor.gen.runtime.KastorGenInternalApi::class)

package com.geoknoesis.kastor.gen.validation.jena

import com.geoknoesis.kastor.gen.runtime.GraphStateCache
import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Non-Jena data graphs are converted once per content version, not on every call. */
class JenaGraphCacheTest {

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

    private fun personGraph(vararg names: String): MemoryGraph = MemoryGraph().apply { addTriples(people(*names)) }

    /** A graph without a modification stamp (delegates to [inner]). */
    private class PlainGraph(private val inner: RdfGraph) : RdfGraph by inner

    @Test
    fun `a memory graph is converted once and again after a change`() {
        JenaValidation.fromTurtle(shapes).use { v ->
            val g = personGraph(*Array(30) { "p$it" })
            repeat(20) { i -> assertEquals(ValidationResult.Ok, v.validate(g, ex("p$i"))) }
            assertEquals(1, v.loadCount)

            g.removeTriple(RdfTriple(ex("p3"), ex("name"), Literal("p3")))
            assertTrue(v.validate(g, ex("p3")) is ValidationResult.Violations, "mutation is detected")
            assertEquals(2, v.loadCount)
            assertEquals(1, v.cachedGraphCount())
        }
    }

    @Test
    fun `fresh handles of a memory named graph share one converted copy`() {
        JenaValidation.fromTurtle(shapes).use { v ->
            val repo = MemoryRepository(RdfConfig(providerId = "memory"))
            val name = ex("people")
            repo.editGraph(name).addTriples(people("a", "b"))
            repeat(10) { assertEquals(ValidationResult.Ok, v.validate(repo.getGraph(name), ex("a"))) }
            assertEquals(1, v.loadCount)
        }
    }

    @Test
    fun `graphs without a stamp are converted once per content`() {
        JenaValidation.fromTurtle(shapes).use { v ->
            val inner = personGraph("a")
            repeat(3) { assertEquals(ValidationResult.Ok, v.validate(PlainGraph(inner), ex("a"))) }
            assertEquals(1, v.loadCount)
            inner.removeTriple(RdfTriple(ex("a"), ex("name"), Literal("a")))
            assertTrue(v.validate(PlainGraph(inner), ex("a")) is ValidationResult.Violations)
            assertEquals(2, v.loadCount)
        }
    }

    @Test
    fun `shapes embedded in a cached graph follow the graph's content`() {
        JenaValidation().use { v ->
            val g = MemoryGraph()
            g.addTriples(Rdf.parse(shapes, "TURTLE").getTriples())
            g.addTriple(RdfTriple(ex("a"), RDF.type, ex("Person")))
            assertTrue(v.validate(g, ex("a")) is ValidationResult.Violations)
            assertTrue(v.validate(g, ex("a")) is ValidationResult.Violations)
            assertEquals(1, v.loadCount)
            g.addTriple(RdfTriple(ex("a"), ex("name"), Literal("a")))
            assertEquals(ValidationResult.Ok, v.validate(g, ex("a")))
            assertEquals(2, v.loadCount)
        }
    }

    @Test
    fun `the cache size is configurable`() {
        JenaValidation(Rdf.parse(shapes, "TURTLE"), 2).use { v ->
            assertEquals(2, v.maxCachedGraphs)
            val graphs = List(5) { personGraph("p$it") }
            graphs.forEachIndexed { i, g -> assertEquals(ValidationResult.Ok, v.validate(g, ex("p$i"))) }
            assertEquals(2, v.cachedGraphCount())
        }
        JenaValidation().use { v -> assertEquals(JenaValidation.DEFAULT_MAX_CACHED_GRAPHS, v.maxCachedGraphs) }
    }

    @Test
    fun `validation inside a repository transaction does not deadlock with a concurrent validation`() {
        JenaValidation.fromTurtle(shapes).use { v ->
            val repo = MemoryRepository(RdfConfig(providerId = "memory"))
            val name = ex("people")
            repo.editGraph(name).addTriples(people("a", "b"))
            val graph = repo.getGraph(name)

            val inTransaction = CountDownLatch(1)
            val otherReads = CountDownLatch(1)
            val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
            try {
                // The other thread tells when it is about to read the graph, which it cannot do before the
                // transaction ends: no thread state is polled.
                v.onCacheEvent = { event ->
                    if (event == GraphStateCache.Event.GRAPH_READ && Thread.currentThread().name == "other-validator") {
                        otherReads.countDown()
                    }
                }
                val first = pool.submit<ValidationResult> {
                    var result: ValidationResult? = null
                    repo.transaction {
                        editGraph(name).removeTriple(RdfTriple(ex("b"), ex("name"), Literal("b")))
                        inTransaction.countDown()
                        check(otherReads.await(10, TimeUnit.SECONDS)) { "the other thread never came to read the graph" }
                        result = v.validate(graph, ex("b"))
                    }
                    result!!
                }
                assertTrue(inTransaction.await(10, TimeUnit.SECONDS))
                val second = pool.submit<ValidationResult> {
                    Thread.currentThread().name = "other-validator"
                    v.validate(graph, ex("a"))
                }
                assertTrue(first.get(30, TimeUnit.SECONDS) is ValidationResult.Violations, "sees the uncommitted removal")
                assertEquals(ValidationResult.Ok, second.get(30, TimeUnit.SECONDS))
            } finally {
                v.onCacheEvent = null
                pool.shutdownNow()
            }
        }
    }

}
