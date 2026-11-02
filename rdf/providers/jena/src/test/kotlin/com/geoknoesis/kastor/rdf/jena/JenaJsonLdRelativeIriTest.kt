package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Without a base IRI, a JSON-LD document with a relative IRI reference fails the parse with [RdfFormatException],
 * as Turtle and RDF/XML do. JSON-LD's toRdf algorithm (and so Jena's JSON-LD reader) would instead skip every
 * statement that holds a relative IRI, with only a logged warning: a document of relative `@id`s parsed to zero
 * triples. With a base IRI, relative references resolve against it. The RDF4J provider behaves the same.
 */
class JenaJsonLdRelativeIriTest {
    private val provider = JenaProvider()

    private val relativeSubject = """{"@id": "s", "http://ex/p": {"@id": "o"}}"""
    private val relativeObjectOnly = """{"@id": "http://ex/s", "http://ex/p": {"@id": "o"}, "http://ex/q": "v"}"""
    private val relativeGraphName = """{"@id": "g", "@graph": [{"@id": "http://ex/s", "http://ex/p": "v"}]}"""

    private fun assertRelativeRefused(parse: () -> Any?) {
        val error = assertThrows(RdfFormatException::class.java) { parse() }
        assertTrue(error.message!!.contains("relative", ignoreCase = true), error.message)
        assertFalse(error.message!!.contains("x-kastor-no-base"), error.message)
    }

    @Test
    fun `graph parses refuse relative IRIs without a base`() {
        for (doc in listOf(relativeSubject, relativeObjectOnly, relativeGraphName)) {
            assertRelativeRefused { provider.parseGraph(doc.byteInputStream(), "JSON-LD") }
            assertRelativeRefused { provider.parseGraph(doc.byteInputStream(), "JSON-LD", null) }
            assertRelativeRefused { Rdf.parse(doc, RdfFormat.JSON_LD) }
            assertRelativeRefused { JenaBridge.fromString(doc, "JSON-LD") }
            assertRelativeRefused { provider.openTripleStream(doc.byteInputStream(), "JSON-LD").use { it.toList() } }
        }
    }

    @Test
    fun `dataset parses refuse relative IRIs without a base`() {
        for (doc in listOf(relativeSubject, relativeObjectOnly, relativeGraphName)) {
            Rdf.memory().use { repo ->
                assertRelativeRefused { provider.parseDataset(repo, doc.byteInputStream(), "JSON-LD") }
                assertEquals(0, repo.defaultGraph.size())
                assertEquals(emptyList<Iri>(), repo.listGraphs())
            }
        }
    }

    @Test
    fun `relative types, datatypes, vocabularies and in-document bases are refused too`() {
        val docs = listOf(
            """{"@id": "http://ex/s", "@type": "T"}""",
            """{"@id": "http://ex/s", "http://ex/p": {"@value": "v", "@type": "dt"}}""",
            """{"@context": {"@base": "x/"}, "@id": "s", "http://ex/p": "v"}""",
            """{"@id": "/abs", "http://ex/p": "v"}""",
            """{"@id": "//host/x", "http://ex/p": "v"}""",
            """{"@id": "#me", "http://ex/p": "v"}""",
            """{"@id": "", "http://ex/p": "v"}""",
        )
        for (doc in docs) assertRelativeRefused { provider.parseGraph(doc.byteInputStream(), "JSON-LD") }
        // An empty (document-relative) vocabulary without a base: the JSON-LD processor refuses the mapping itself.
        val vocabulary = assertThrows(RdfFormatException::class.java) {
            provider.parseGraph("""{"@context": {"@vocab": ""}, "@id": "http://ex/s", "p": "v"}""".byteInputStream(), "JSON-LD")
        }
        assertTrue(vocabulary.message!!.contains("vocab"), vocabulary.message)
    }

    @Test
    fun `relative IRIs resolve against a given base`() {
        val graph = provider.parseGraph(relativeSubject.byteInputStream(), "JSON-LD", "http://base.example/dir/")
        assertEquals(
            listOf(RdfTriple(Iri("http://base.example/dir/s"), Iri("http://ex/p"), Iri("http://base.example/dir/o"))),
            graph.getTriples(),
        )
        Rdf.memory().use { repo ->
            provider.parseDataset(repo, relativeGraphName.byteInputStream(), "JSON-LD", "http://base.example/")
            assertEquals(listOf(Iri("http://base.example/g")), repo.listGraphs())
        }
    }

    @Test
    fun `absolute IRIs and an absolute in-document base parse without a base`() {
        val graph = provider.parseGraph(
            """{"@context": {"@base": "http://b.example/"}, "@id": "s", "http://ex/p": "v"}""".byteInputStream(),
            "JSON-LD",
        )
        assertEquals(listOf(RdfTriple(Iri("http://b.example/s"), Iri("http://ex/p"), string("v"))), graph.getTriples())
        assertEquals(1, provider.parseGraph("""{"@id": "http://ex/s", "http://ex/p": "v"}""".byteInputStream(), "JSON-LD").size())
    }
}
