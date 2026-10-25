package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * A graph parse of a document that names a graph (JSON-LD `@graph` under an `@id`) fails closed instead of dropping
 * the named graph's triples: parsing into one graph cannot keep them apart. The Jena provider behaves the same.
 */
class Rdf4jGraphParseNamedGraphTest {

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
        assertNamedGraphRefused { Rdf4jProvider().parseGraph(named.byteInputStream(), "JSON-LD") }
        assertNamedGraphRefused { Rdf4jProvider().parseGraph(named.byteInputStream(), "JSON-LD", "http://example.org/") }
    }

    @Test
    fun `streaming graph parses refuse a JSON-LD named graph`() {
        assertNamedGraphRefused { Rdf4jProvider().openTripleStream(named.byteInputStream(), "JSON-LD").use { it.toList() } }
        assertNamedGraphRefused { Rdf4jProvider().parseStreaming(named.byteInputStream(), "JSON-LD", "http://example.org/").toList() }
    }

    @Test
    fun `a JSON-LD document with only a default graph still parses as a graph`() {
        val triple = Rdf4jProvider().parseGraph(defaultOnly.byteInputStream(), "JSON-LD").getTriples().single()
        assertEquals(Literal("default"), triple.obj)
        assertEquals(1, Rdf4jProvider().openTripleStream(defaultOnly.byteInputStream(), "JSON-LD").use { it.toList() }.size)
    }

    @Test
    fun `parseDataset keeps the JSON-LD named graph`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            Rdf4jProvider().parseDataset(repo, named.byteInputStream(), "JSON-LD")
            assertEquals(Literal("named"), repo.getGraph(Iri("http://example.org/g")).getTriples().single().obj)
            assertEquals(Literal("default"), repo.defaultGraph.getTriples().single().obj)
        }
    }
}
