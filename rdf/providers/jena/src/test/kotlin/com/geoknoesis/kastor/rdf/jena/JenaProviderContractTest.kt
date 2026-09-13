package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

/** Regression tests for Jena provider audit findings (serialization, inference, parsing, query errors). */
class JenaProviderContractTest {

    private val s = Iri("http://example.org/s")
    private val p = Iri("http://example.org/p")
    private val o = Iri("http://example.org/o")

    @Test
    fun `serialization options never mutate the graph prefixes`() {
        JenaRepository.MemoryRepository().use { repo ->
            repo.editDefaultGraph().addTriple(RdfTriple(s, p, o))
            val options = SerializationOptions(baseUri = "http://example.org/", prefixMappings = mapOf("ex" to "http://example.org/"))
            val turtle = JenaProvider().serializeGraph(repo.defaultGraph, "TURTLE", options)
            assertTrue(turtle.contains("ex:"), turtle)
            assertTrue(turtle.contains("http://example.org/") && turtle.uppercase().contains("BASE"), turtle)
            repo.readTransaction {
                assertTrue(repo.getJenaDataset().defaultModel.nsPrefixMap.isEmpty(), "store prefixes must be untouched")
            }
        }
        val model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel()
        val standalone = JenaBridge.fromJenaModel(model)
        standalone.addTriple(RdfTriple(s, p, o))
        JenaBridge.toString(standalone, "TURTLE", SerializationOptions(baseUri = "http://example.org/", prefixMappings = mapOf("ex" to "http://example.org/")))
        assertTrue(model.nsPrefixMap.isEmpty(), "standalone model prefixes must be untouched: ${model.nsPrefixMap}")
    }

    @Test
    fun `dataset serialization does not mutate store prefixes`() {
        JenaRepository.MemoryRepository().use { repo ->
            repo.editGraph(Iri("http://example.org/g")).addTriple(RdfTriple(s, p, o))
            val trig = JenaProvider().serializeDataset(repo, "TRIG", SerializationOptions(prefixMappings = mapOf("ex" to "http://example.org/")))
            assertTrue(trig.contains("ex:"), trig)
            repo.readTransaction { assertTrue(repo.getJenaDataset().defaultModel.nsPrefixMap.isEmpty()) }
        }
    }

    @Test
    fun `toJenaModel on a repository graph returns a detached copy`() {
        JenaRepository.MemoryRepository().use { repo ->
            repo.editDefaultGraph().addTriple(RdfTriple(s, p, o))
            val copy = JenaBridge.toJenaModel(repo.defaultGraph)
            copy.removeAll()
            assertEquals(1, repo.defaultGraph.size())
        }
    }

    @Test
    fun `inference view is consistent across reads, serialization and writes`() {
        JenaRepository.MemoryRepositoryWithInference().use { repo ->
            val a = Iri("http://example.org/A")
            val b = Iri("http://example.org/B")
            val c = Iri("http://example.org/C")
            val graph = repo.editDefaultGraph()
            graph.addTriples(listOf(RdfTriple(a, RDFS.subClassOf, b), RdfTriple(s, RDF.type, a)))
            assertTrue(graph.hasTriple(RdfTriple(s, RDF.type, b)))
            val first = graph.getTriples()
            // Repeated reads are served from the cached inference model and agree.
            assertEquals(first.toSet(), graph.getTriples().toSet())
            // The cache is invalidated by writes.
            graph.addTriple(RdfTriple(b, RDFS.subClassOf, c))
            assertTrue(graph.hasTriple(RdfTriple(s, RDF.type, c)), "inference must reflect the committed write")
            graph.removeTriple(RdfTriple(b, RDFS.subClassOf, c))
            assertFalse(graph.hasTriple(RdfTriple(s, RDF.type, c)), "inference must reflect the removal")
            // Serialization exposes the same view as getTriples().
            assertEquals(graph.getTriples().size, graph.size(), "size() must agree with getTriples()")
            val copy = JenaBridge.copyToJenaModel(graph)
            try { assertEquals(graph.size().toLong(), copy.size()) } finally { copy.close() }
        }
    }

    @Test
    fun `parseGraph rejects quad formats instead of dropping named graphs`() {
        val trig = "<http://example.org/g> { <http://example.org/s> <http://example.org/p> <http://example.org/o> }"
        for (format in listOf("TRIG", "N-QUADS")) {
            assertThrows(RdfFormatException::class.java) { JenaProvider().parseGraph(trig.byteInputStream(), format) }
        }
    }

    @Test
    fun `RDF-XML honours the declared document encoding`() {
        val xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns:ex=\"http://example.org/\">" +
            "<rdf:Description rdf:about=\"http://example.org/s\"><ex:p>café</ex:p></rdf:Description></rdf:RDF>"
        val graph = JenaProvider().parseGraph(xml.toByteArray(Charsets.ISO_8859_1).inputStream(), "RDF/XML")
        assertEquals("café", (graph.getTriples().single().obj as Literal).lexical)
    }

    @Test
    fun `relative IRIs need an explicit base`() {
        val ttl = "<rel> <http://example.org/p> <http://example.org/o> ."
        assertThrows(RdfFormatException::class.java) { JenaProvider().parseGraph(ttl.byteInputStream(), "TURTLE") }
        val graph = JenaProvider().parseGraph(ttl.byteInputStream(), "TURTLE", "http://example.org/")
        assertEquals(Iri("http://example.org/rel"), graph.getTriples().single().subject)
    }

    @Test
    fun `syntax errors surface as RdfFormatException`() {
        assertThrows(RdfFormatException::class.java) { JenaProvider().parseGraph("<a> <b".byteInputStream(), "TURTLE") }
        assertThrows(RdfFormatException::class.java) {
            JenaProvider().openTripleStream("<http://e/s> <http://e/p> .".byteInputStream(), "N-TRIPLES").use { it.toList() }
        }
    }

    @Test
    fun `consumer exceptions propagate unwrapped while engine errors are RdfQueryException`() {
        JenaRepository.MemoryRepository().use { repo ->
            repo.editDefaultGraph().addTriple(RdfTriple(s, p, o))
            val select = SparqlSelectQuery("SELECT ?s WHERE { ?s ?p ?o }")
            val marker = IllegalStateException("consumer failure")
            assertSame(marker, assertThrows(IllegalStateException::class.java) { repo.withSelectRows(select) { it.toList(); throw marker } })
            assertSame(marker, assertThrows(IllegalStateException::class.java) {
                repo.withSelectRows(select, emptyMap(), Duration.ofSeconds(10)) { it.toList(); throw marker }
            })
            assertSame(marker, assertThrows(IllegalStateException::class.java) {
                repo.withConstructTriples(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")) { it.toList(); throw marker }
            })
            assertThrows(RdfQueryException::class.java) { repo.withSelectRows(SparqlSelectQuery("SELECT WHERE {")) { it.toList() } }
            assertThrows(RdfQueryException::class.java) {
                repo.withSelectRows(SparqlSelectQuery("SELECT WHERE {"), emptyMap(), Duration.ofSeconds(1)) { it.toList() }
            }
        }
    }

    @Test
    fun `capabilities do not advertise unimplemented service descriptions`() {
        assertFalse(JenaProvider().getCapabilities("memory").supportsServiceDescription)
        assertNull(JenaProvider().generateServiceDescription("http://example.org/sparql"))
    }

    @Test
    fun `parsing is spec-strict and rejects terms that are not valid RDF`() {
        val invalid = listOf(
            "<http://e/s> <http://e/p> <http://e/o>" to "TURTLE", // missing final DOT
            "( 1 2 3 ) ." to "TURTLE", // collection without predicate-object list
            "<s> <http://e/p> <http://e/o> ." to "N-TRIPLES", // relative IRI in N-Triples
            "<http://e/s> <http://e/p> \"x\"@cantbethislong ." to "N-TRIPLES", // malformed language tag
            "<http://e/s> <http://e/p> \"x\"^^<http://www.w3.org/1999/02/22-rdf-syntax-ns#langString> ." to "N-TRIPLES",
        )
        for ((document, format) in invalid) {
            assertThrows(RdfFormatException::class.java, { JenaProvider().parseGraph(document.byteInputStream(), format) }, document)
        }
        JenaRepository.MemoryRepository().use { repo ->
            assertThrows(RdfFormatException::class.java) {
                JenaProvider().parseDataset(repo, "<http://e/g> { <http://e/s> <http://e/p> \"x\"@en--unk }".byteInputStream(), "TRIG")
            }
        }
    }
}
