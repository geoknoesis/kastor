package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.shacl.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class PreparationBudgetTest {
    @Test fun `digest length framing distinguishes adversarial literal fields`() {
        val a = RdfTriple(Iri("urn:s"), Iri("urn:p"), TypedLiteral("value|urn:part", Iri("urn:type")))
        val b = RdfTriple(Iri("urn:s"), Iri("urn:p"), TypedLiteral("value", Iri("urn:part:urn:type")))
        assertNotEquals(ShapesStructuralDigest.digest(listOf(a), ValidationConfig()),
            ShapesStructuralDigest.digest(listOf(b), ValidationConfig()))
    }
    private val triples = (0 until 1_000).map {
        RdfTriple(Iri("urn:shape:$it"), RDF.type, SHACL.NodeShape)
    }
    private fun expires(): ValidationBudget {
        val ticks = AtomicLong()
        return ValidationBudget(Duration.ofNanos(12)) { ticks.getAndIncrement() }
    }
    @Test fun `all preparation phases consume the same cooperative budget`() {
        val graph = graphFromTriples(triples)
        val phases: List<Pair<String, (ValidationBudget) -> Any>> = listOf(
            "digest" to { ShapesStructuralDigest.digest(triples, ValidationConfig(), it) },
            "compilation" to { ShapesCompiler.compile(triples, ValidationConfig(), it) },
            "shape index" to { ShapeGraphIndex(triples, it) },
            "data index" to { DataGraphIndex(graph, it) },
            "graph construction" to { graphFromTriples(triples, it) },
            "imports" to { OwlImportsExpander.expand(graph, ImportConfig(resolveOwlImports = true), emptyMap(), 2_000, it) },
            "discovery" to { ShapesGraphTriplesCollector.collectFromData(graph, null, emptyMap(), 2_000, it) },
            "merge" to { mergeGraphs(graph, graph, it) },
        )
        for ((phase, run) in phases) {
            assertFailsWith<ShaclValidationException>(phase) { run(expires()) }
        }
    }
    @Test fun `cancelled preparation does not populate compiled cache`() {
        val cache = NativeCompileCache()
        assertFailsWith<ShaclValidationException> {
            val budget = expires()
            cache.getOrCompile("cancelled", budget) { ShapesCompiler.compile(triples, ValidationConfig(), budget) }
        }
        assertEquals(0, cache.statistics().entries)
    }
    @Test fun `cache wait for the same key can time out independently of the owning compilation`() {
        val cache = NativeCompileCache()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val first = executor.submit {
            cache.getOrCompile("first") {
                entered.countDown()
                check(release.await(120, TimeUnit.SECONDS))
                ShapesCompiler.compile(emptyList(), ValidationConfig())
            }
        }
        try {
            assertTrue(entered.await(60, TimeUnit.SECONDS))
            // The waiter's budget runs out while the owner is still compiling. Its clock advances by one each time it
            // is consulted, so the budget is used up after a few consultations, on any machine and without waiting.
            assertFailsWith<ShaclValidationException> {
                cache.getOrCompile("first", expires()) { error("must not compile") }
            }
            // A different key is not blocked by the in-flight compilation (compilation runs outside the lock).
            val other = ShapesCompiler.compile(emptyList(), ValidationConfig())
            assertTrue(cache.getOrCompile("second", ValidationBudget(null)) { other } === other)
        } finally {
            release.countDown()
            first.get(120, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }
    @Test fun `cyclic shape lists and paths are rejected without looping`() {
        val node = BlankNode("cycle")
        val list = ShapeGraphIndex(listOf(RdfTriple(node, RDF.first, Iri("urn:predicate:p")), RdfTriple(node, RDF.rest, node)))
        assertFailsWith<ShapeCompileException> { list.parseRdfList(node) }
        val path = ShapeGraphIndex(listOf(RdfTriple(node, SHACL.inversePath, node)))
        assertFailsWith<ShapeCompileException> { ShaclPathParser.parse(node, path) }
    }
    @Test fun `cyclic nested property shapes are rejected without stack overflow`() {
        val node = Iri("urn:shape:root")
        val property = BlankNode("property")
        val shapes = listOf(
            RdfTriple(node, RDF.type, SHACL.NodeShape),
            RdfTriple(node, SHACL.`property`, property),
            RdfTriple(property, SHACL.path, Iri("urn:predicate:p")),
            RdfTriple(property, SHACL.`property`, property),
        )
        assertFailsWith<ShapeCompileException> { ShapesCompiler.compile(shapes, ValidationConfig()) }
    }
}
