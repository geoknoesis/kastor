package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.eclipse.rdf4j.model.vocabulary.RDF4J
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Regression tests for RDF4J provider audit findings. */
class Rdf4jProviderContractTest {

    private val s = Iri("http://example.org/s")
    private val p = Iri("http://example.org/p")
    private val o = Iri("http://example.org/o")

    @Test
    fun `non-canonical and ill-typed literals round-trip without throwing`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val literals = listOf(
                TypedLiteral("1", XSD.boolean),
                TypedLiteral("maybe", XSD.boolean),
                TypedLiteral("1.50", XSD.decimal),
                TypedLiteral("007", XSD.integer),
            )
            val graph = repo.editDefaultGraph()
            graph.addTriples(literals.map { RdfTriple(s, p, it) })
            assertEquals(literals.toSet(), graph.getTriples().map { it.obj }.toSet())
            val rows = repo.select(SparqlSelectQuery("SELECT ?o WHERE { ?s ?p ?o }")).toList()
            assertEquals(literals.toSet(), rows.map { it.get("o") }.toSet())
            graph.getTriples().forEach { assertTrue(graph.removeTriple(it)) }
            assertEquals(0, graph.size())
        }
    }

    @Test
    fun `canonical booleans map to singletons`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.editDefaultGraph().addTriple(RdfTriple(s, p, TypedLiteral("true", XSD.boolean)))
            assertSame(TrueLiteral, repo.defaultGraph.getTriples().single().obj)
        }
    }

    @Test
    fun `parseGraph rejects quad formats`() {
        val nq = "<http://example.org/s> <http://example.org/p> <http://example.org/o> <http://example.org/g> ."
        assertThrows(RdfFormatException::class.java) { Rdf4jProvider().parseGraph(nq.byteInputStream(), "N-QUADS") }
        assertThrows(RdfFormatException::class.java) { Rdf4jProvider().parseGraph(nq.byteInputStream(), "TRIG") }
    }

    @Test
    fun `relative IRIs need an explicit base and syntax errors are format errors`() {
        val ttl = "<rel> <http://example.org/p> <http://example.org/o> ."
        assertThrows(RdfFormatException::class.java) { Rdf4jProvider().parseGraph(ttl.byteInputStream(), "TURTLE") }
        val graph = Rdf4jProvider().parseGraph(ttl.byteInputStream(), "TURTLE", "http://example.org/")
        assertEquals(Iri("http://example.org/rel"), graph.getTriples().single().subject)
        assertThrows(RdfFormatException::class.java) { Rdf4jProvider().parseGraph("<a> <b".byteInputStream(), "TURTLE") }
    }

    @Test
    fun `openTripleStream streams, reports errors and closes its input`() {
        val lines = (1..5000).joinToString("\n") { "<http://example.org/s$it> <http://example.org/p> \"$it\" ." }
        var closed = false
        val input = object : java.io.ByteArrayInputStream(lines.toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        Rdf4jProvider().openTripleStream(input, "N-TRIPLES").use { stream ->
            assertEquals(3, stream.take(3).count())
        }
        assertTrue(closed, "closing the stream must close the input")
        assertEquals(5000, Rdf4jProvider().openTripleStream(lines.byteInputStream(), "N-TRIPLES").use { it.count() })
        assertThrows(RdfFormatException::class.java) {
            Rdf4jProvider().openTripleStream("<http://e/s> <http://e/p> .".byteInputStream(), "N-TRIPLES").use { it.toList() }
        }
    }

    @Test
    fun `consumer exceptions propagate unwrapped while engine errors are RdfQueryException`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.editDefaultGraph().addTriple(RdfTriple(s, p, o))
            val select = SparqlSelectQuery("SELECT ?s WHERE { ?s ?p ?o }")
            val marker = IllegalStateException("consumer failure")
            assertSame(marker, assertThrows(IllegalStateException::class.java) { repo.withSelectRows(select) { it.toList(); throw marker } })
            assertSame(marker, assertThrows(IllegalStateException::class.java) {
                repo.withSelectRows(select, emptyMap(), Duration.ofMillis(10)) { it.toList(); throw marker }
            })
            assertSame(marker, assertThrows(IllegalStateException::class.java) {
                repo.withConstructTriples(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")) { it.toList(); throw marker }
            })
            assertThrows(RdfQueryException::class.java) { repo.withSelectRows(SparqlSelectQuery("SELECT WHERE {")) { it.toList() } }
            assertThrows(RdfQueryException::class.java) {
                repo.withConstructTriples(SparqlConstructQuery("CONSTRUCT WHERE {")) { it.toList() }
            }
            assertThrows(RdfQueryException::class.java) {
                repo.withSelectRows(SparqlSelectQuery("SELECT WHERE {"), emptyMap(), Duration.ofSeconds(1)) { it.toList() }
            }
        }
    }

    @Test
    fun `addTriples commits a batch as one transaction on a SHACL store`() {
        Rdf4jRepository.MemoryShaclRepository().use { repo ->
            val shapes = """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix ex: <http://example.org/> .
                ex:Shape a sh:NodeShape ; sh:targetClass ex:Person ;
                  sh:property [ sh:path ex:name ; sh:minCount 1 ] .
            """.trimIndent()
            val shapeTriples = Rdf4jProvider().parseGraph(shapes.byteInputStream(), "TURTLE").getTriples()
            repo.editGraph(Iri(RDF4J.SHACL_SHAPE_GRAPH.stringValue())).addTriples(shapeTriples)
            val alice = Iri("http://example.org/alice")
            val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
            // Adding the type alone would violate minCount; the batch must be validated as a whole.
            repo.editDefaultGraph().addTriples(listOf(
                RdfTriple(alice, type, Iri("http://example.org/Person")),
                RdfTriple(alice, Iri("http://example.org/name"), string("Alice")),
            ))
            assertEquals(2, repo.defaultGraph.size())
        }
    }

    @Test
    fun `transactions are confined to the thread that opened them`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            try {
                val tx = pool.submit {
                    repo.transaction {
                        editDefaultGraph().addTriple(RdfTriple(s, p, o))
                        inside.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                }
                assertTrue(inside.await(30, TimeUnit.SECONDS))
                // Another thread neither joins nor observes the uncommitted transaction.
                assertEquals(0, repo.defaultGraph.size(), "uncommitted data must not be visible to other threads")
                release.countDown()
                tx.get(30, TimeUnit.SECONDS)
                assertEquals(1, repo.defaultGraph.size())
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `serialization honours prefixes and base`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.editGraph(Iri("http://example.org/g")).addTriple(RdfTriple(s, p, o))
            val options = SerializationOptions(baseUri = "http://example.org/", prefixMappings = mapOf("ex" to "http://example.org/"))
            val trig = Rdf4jProvider().serializeDataset(repo, "TRIG", options)
            assertTrue(trig.contains("@prefix ex:"), trig)
            assertTrue(trig.contains("@base"), trig)
            val turtle = Rdf4jProvider().serializeGraph(repo.getGraph(Iri("http://example.org/g")), "TURTLE", options)
            assertTrue(turtle.contains("@base") && turtle.contains("@prefix ex:"), turtle)
            assertFalse(turtle.contains("@prefix :"), "base must not be emitted as the empty prefix: $turtle")
        }
    }

    @Test
    fun `capabilities are honest`() {
        val caps = Rdf4jProvider().getCapabilities("memory")
        assertFalse(caps.supportsServiceDescription)
        assertEquals("1.1", caps.rdfVersion)
        assertTrue(caps.supportedInputFormats.none { it.endsWith("-1.2") }, "RDF4J does not implement RDF 1.2 syntaxes")
    }
}
