package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * A graph parse of a document that names a graph (JSON-LD `@graph` under an `@id`) fails closed instead of dropping
 * the named graph's triples: parsing into one graph cannot keep them apart. The RDF4J provider behaves the same.
 */
class JenaGraphParseNamedGraphTest {

    private val named = """
        [ { "@id": "http://example.org/s", "http://example.org/p": "default" },
          { "@id": "http://example.org/g", "@graph": [ { "@id": "http://example.org/s", "http://example.org/p": "named" } ] } ]
    """.trimIndent()

    private val defaultOnly = """{ "@id": "http://example.org/s", "http://example.org/p": "default" }"""

    private fun assertNamedGraphRefused(parse: () -> Any?) {
        val error = assertThrows(RdfFormatException::class.java) { parse() }
        assertTrue(error.message!!.contains("http://example.org/g") && error.message!!.contains("parseDataset"), error.message)
    }

    @Test
    fun `parseGraph refuses a JSON-LD named graph instead of dropping it`() {
        assertNamedGraphRefused { JenaProvider().parseGraph(named.byteInputStream(), "JSON-LD") }
        assertNamedGraphRefused { JenaProvider().parseGraph(named.byteInputStream(), "JSON-LD", "http://example.org/") }
        assertNamedGraphRefused { JenaBridge.fromString(named, "JSON-LD") }
    }

    @Test
    fun `streaming graph parses refuse a JSON-LD named graph`() {
        assertNamedGraphRefused { JenaProvider().openTripleStream(named.byteInputStream(), "JSON-LD").use { it.toList() } }
        assertNamedGraphRefused { JenaProvider().parseStreaming(named.byteInputStream(), "JSON-LD", "http://example.org/").toList() }
    }

    @Test
    fun `fromUrl refuses a JSON-LD named graph`() {
        val file = kotlin.io.path.createTempFile(suffix = ".jsonld").toFile()
        try {
            file.writeText(named)
            assertNamedGraphRefused { JenaBridge.fromUrl(file.toURI().toString(), "JSON-LD") }
        } finally { file.delete() }
    }

    @Test
    fun `a JSON-LD document with only a default graph still parses as a graph`() {
        val triple = JenaProvider().parseGraph(defaultOnly.byteInputStream(), "JSON-LD").getTriples().single()
        assertEquals(Literal("default"), triple.obj)
        assertEquals(1, JenaProvider().openTripleStream(defaultOnly.byteInputStream(), "JSON-LD").use { it.toList() }.size)
    }

    @Test
    fun `parseDataset keeps the JSON-LD named graph`() {
        JenaRepository.MemoryRepository().use { repo ->
            JenaProvider().parseDataset(repo, named.byteInputStream(), "JSON-LD")
            assertEquals(Literal("named"), repo.getGraph(Iri("http://example.org/g")).getTriples().single().obj)
            assertEquals(Literal("default"), repo.defaultGraph.getTriples().single().obj)
        }
    }

    @Test
    fun `the core API refuses the named graph in a graph parse and keeps it in a dataset parse`() {
        assertNamedGraphRefused { Rdf.parse(named, "JSON-LD") }
        Rdf.parseDataset(named, "JSON-LD").use { dataset ->
            assertEquals(Literal("named"), dataset.namedGraphs.getValue(Iri("http://example.org/g")).getTriples().single().obj)
        }
    }
}
