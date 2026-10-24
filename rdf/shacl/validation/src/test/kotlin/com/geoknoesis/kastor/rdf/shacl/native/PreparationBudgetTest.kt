package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.shacl.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
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
    /** Many ill-formed SHACL functions and many SPARQL queries, each with its own `PREFIX` (SPARQL calls none of them). */
    private fun functionsAndQueries(n: Int): List<RdfTriple> {
        val ttl = buildString {
            append("@prefix sh: <http://www.w3.org/ns/shacl#> .\n@prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .\n")
            append("@prefix ex: <http://example.com/ns#> .\n")
            repeat(n) { append("ex:bad$it a sh:ListParameterExpressionFunction ; sh:bodyExpression [ shnex:limit -1 ; shnex:nodes () ] .\n") }
            repeat(n) {
                append("ex:S$it a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:select \"PREFIX p$it: <urn:p$it:> SELECT \$this WHERE { FILTER (p$it:bad$it()) }\" ] .\n")
            }
        }
        return Rdf.parse(ttl, RdfFormat.TURTLE).getTriples()
    }
    @Test fun `finding the SPARQL calls of many ill-formed functions consumes the preparation budget`() {
        val triples = functionsAndQueries(200)
        // A tiny budget stops the compilation of such a shapes graph, as any other.
        assertFailsWith<ShaclValidationException> { ShapesCompiler.compile(triples, ValidationConfig(), expires()) }
        // The scan of the queries for calls ticks the budget itself: here it is the only work left after the deadline.
        var expired = false
        val budget = ValidationBudget(Duration.ofSeconds(1)) { if (expired) 1_000_000_000_000L else 0L }
        val index = ShapeGraphIndex(triples, budget)
        assertEquals(200, index.sparqlQueries.size)
        expired = true
        val e = assertFailsWith<ShaclValidationException> { SparqlFunctionDeclarations.calledFromSparql(Iri("http://example.com/ns#bad0"), index) }
        assertTrue("SPARQL function compilation" in e.message.orEmpty(), e.message)
    }
    @Test fun `the SPARQL calls of many functions are found in one scan`() {
        val index = ShapeGraphIndex(functionsAndQueries(2_000))
        // Each query calls p<i>:bad<i>, which its PREFIX resolves to urn:p<i>:bad<i>, not to an ex: function.
        repeat(2_000) { assertFalse(SparqlFunctionDeclarations.calledFromSparql(Iri("http://example.com/ns#bad$it"), index)) }
        assertTrue(SparqlFunctionDeclarations.calledFromSparql(Iri("urn:p1999:bad1999"), index))
        // So compiling it leaves every ill-formed function out instead of failing.
        assertTrue(ShapesCompiler.compile(index.triples, ValidationConfig()).functions.isEmpty)
    }
    @Test fun `prefixed SPARQL calls are found with any prefix name and local name escapes`() {
        fun calls(query: String, function: String): Boolean {
            val ttl = "@prefix sh: <http://www.w3.org/ns/shacl#> .\n<urn:s> sh:select \"\"\"$query\"\"\" ."
            return SparqlFunctionDeclarations.calledFromSparql(Iri(function), ShapeGraphIndex(Rdf.parse(ttl, RdfFormat.TURTLE).getTriples()))
        }
        assertTrue(calls("PREFIX fé: <http://example.com/ns#> SELECT * WHERE { FILTER (fé:f(1)) }", "http://example.com/ns#f"))
        assertTrue(calls("PREFIX 例: <http://example.com/ns#> SELECT * WHERE { FILTER (例:f (1)) }", "http://example.com/ns#f"))
        assertTrue(calls("prefix : <http://example.com/ns#> SELECT * WHERE { FILTER (:f\\\\-g()) }", "http://example.com/ns#f-g"))
        assertTrue(calls("SELECT * WHERE { FILTER (<http://example.com/ns#f>()) }", "http://example.com/ns#f"))
        // Not a call: no '(' after the name, an undeclared prefix, a string or a comment.
        assertFalse(calls("PREFIX ex: <http://example.com/ns#> SELECT * WHERE { ?s ex:f ?o }", "http://example.com/ns#f"))
        assertFalse(calls("SELECT * WHERE { FILTER (ex:f()) }", "http://example.com/ns#f"))
        assertFalse(calls("SELECT * WHERE { BIND ('<http://example.com/ns#f>()' AS ?x) # <http://example.com/ns#f>()\n }", "http://example.com/ns#f"))
    }
    /** Whether a `sh:select` with [query] (the SPARQL text, after Turtle decoding) may call [function]. */
    private fun calledBy(query: String, function: String): Boolean {
        val ttl = "@prefix sh: <http://www.w3.org/ns/shacl#> .\n<urn:s> sh:select \"\"\"${query.replace("\\", "\\\\")}\"\"\" ."
        return SparqlFunctionDeclarations.calledFromSparql(Iri(function), ShapeGraphIndex(Rdf.parse(ttl, RdfFormat.TURTLE).getTriples()))
    }
    @Test fun `calls that codepoint escapes reveal are found`() {
        val f = "http://example.com/ns#f"
        val ex = "PREFIX ex: <http://example.com/ns#> "
        // RDF4J decodes \\u escapes in the whole query before parsing: a string or comment can end early, an IRI or
        // a name can spell f with an escape.
        assertTrue(calledBy(ex + "SELECT * WHERE { BIND ('a\\u0027 + ex:f() + \\u0027b' AS ?x) }", f))
        assertTrue(calledBy(ex + "SELECT * WHERE { # c \\u000A FILTER (!ex:f(?x)) }", f))
        assertTrue(calledBy("SELECT * WHERE { FILTER (<http://example.com/ns#\\u0066>()) }", f))
        assertTrue(calledBy(ex + "SELECT * WHERE { FILTER (ex:\\u0066()) }", f))
        assertTrue(calledBy(ex + "SELECT * WHERE { FILTER (ex:\\U00000066()) }", f))
        assertTrue(calledBy("PREFIX ex: <http://example.com/ns\\u0023> SELECT * WHERE { FILTER (ex:f()) }", f))
        // Without escapes, the string and the comment still hide the name.
        assertFalse(calledBy(ex + "SELECT * WHERE { BIND ('a + ex:f() + b' AS ?x) # ex:f()\n }", f))
    }
    @Test fun `relative calls resolve against BASE or may call any function`() {
        val f = "http://example.com/ns#f"
        assertTrue(calledBy("BASE <http://example.com/ns> SELECT * WHERE { FILTER (<#f>()) }", f))
        assertTrue(calledBy("BASE <http://example.com/a/b> SELECT * WHERE { FILTER (<../ns#f>()) }", f))
        assertTrue(calledBy("BASE <http://example.com/x> BASE <ns> PREFIX p: <#> SELECT * WHERE { FILTER (p:f()) }", f))
        assertTrue(calledBy("BASE <http://example.com/n\\u0073> SELECT * WHERE { FILTER (<#f>()) }", f))
        assertFalse(calledBy("BASE <http://example.com/ns> SELECT * WHERE { FILTER (<#g>()) }", f))
        // The base a provider uses without BASE is its own: a relative call may call any function.
        assertTrue(calledBy("SELECT * WHERE { FILTER (<g>()) }", f))
        assertTrue(calledBy("PREFIX p: <ns#> SELECT * WHERE { FILTER (p:g()) }", f))
        assertTrue(calledBy("BASE <rel> SELECT * WHERE { FILTER (<#g>()) }", f))
        // A relative IRI that is not called does not.
        assertFalse(calledBy("SELECT * WHERE { ?s <p> ?o }", f))
    }
    /** A `sh:select` that calls an IRI of about 1 MB: many `a/` segments, then as many `../` (resolving to `<urn:x:/f>`). */
    private fun hugeIriQuery(): List<RdfTriple> {
        val segments = 250_000
        val iri = "http://example.com/" + "a/".repeat(segments) + "../".repeat(segments) + "./".repeat(segments / 4) + "ns#f"
        val ttl = "@prefix sh: <http://www.w3.org/ns/shacl#> .\n<urn:s> sh:select \"\"\"SELECT * WHERE { FILTER (<$iri>()) }\"\"\" ."
        return Rdf.parse(ttl, RdfFormat.TURTLE).getTriples()
    }
    @Test fun `a huge IRI with dot segments is resolved in linear time`() {
        val triples = hugeIriQuery()
        val started = System.nanoTime()
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            assertTrue(SparqlFunctionDeclarations.calledFromSparql(Iri("http://example.com/ns#f"), ShapeGraphIndex(triples)))
        }
        assertTrue(System.nanoTime() - started < 20_000_000_000L)
    }
    @Test fun `resolving a huge IRI consumes the preparation budget`() {
        val triples = hugeIriQuery()
        var expired = false
        val budget = ValidationBudget(Duration.ofSeconds(1)) { if (expired) 1_000_000_000_000L else 0L }
        val index = ShapeGraphIndex(triples, budget)
        assertEquals(1, index.sparqlQueries.size)
        expired = true
        val e = assertFailsWith<ShaclValidationException> {
            assertTimeoutPreemptively(Duration.ofSeconds(20)) { SparqlFunctionDeclarations.calledFromSparql(Iri("http://example.com/ns#f"), index) }
        }
        assertTrue("SPARQL function compilation" in e.message.orEmpty(), e.message)
    }
    @Test fun `dot segments are removed as RFC 3986 specifies`() {
        // RFC 3986 5.4.1 / 5.4.2 examples, base http://a/b/c/d;p?q.
        val base = "http://a/b/c/d;p?q"
        val cases = mapOf(
            "g" to "http://a/b/c/g", "./g" to "http://a/b/c/g", "g/" to "http://a/b/c/g/", "/g" to "http://a/g",
            "?y" to "http://a/b/c/d;p?y", "g?y" to "http://a/b/c/g?y", "#s" to "http://a/b/c/d;p?q#s", "." to "http://a/b/c/",
            "./" to "http://a/b/c/", ".." to "http://a/b/", "../" to "http://a/b/", "../g" to "http://a/b/g", "../.." to "http://a/",
            "../../" to "http://a/", "../../g" to "http://a/g", "../../../g" to "http://a/g", "../../../../g" to "http://a/g",
            "/./g" to "http://a/g", "/../g" to "http://a/g", "g." to "http://a/b/c/g.", ".g" to "http://a/b/c/.g", "g.." to "http://a/b/c/g..",
            "..g" to "http://a/b/c/..g", "./../g" to "http://a/b/g", "./g/." to "http://a/b/c/g/", "g/./h" to "http://a/b/c/g/h",
            "g/../h" to "http://a/b/c/h", "g;x=1/./y" to "http://a/b/c/g;x=1/y", "g;x=1/../y" to "http://a/b/c/y", "" to "http://a/b/c/d;p?q",
            "http://x/a/./b/../c" to "http://x/a/c", "http://x/a/b" to "http://x/a/b", "urn:a:b/../c" to "urn:/c",
        )
        for ((reference, expected) in cases) assertEquals(expected, resolveIri(base, reference), reference)
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
