package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The RDF4J provider writes RDF 1.2 triple terms in RDF 1.2 syntax (`<<( s p o )>>`), not as RDF4J's private
 * `urn:rdf4j:triple:` IRIs that every other RDF 1.2 reader takes for plain IRIs, and reads that syntax back.
 */
class Rdf4jTripleTermSyntaxTest {

    private val ex = "http://example.org/"
    private val s = Iri("${ex}s")
    private val p = Iri("${ex}p")
    private val q = Iri("${ex}q")
    private val inner = RdfTriple(s, p, Literal("o <<( not a triple term )>> \"\"\" '''"))
    private val nested = RdfTriple(BlankNode("x"), q, TripleTerm(inner))
    private val triples = listOf(
        RdfTriple(BlankNode("r"), RDF.reifies, TripleTerm(inner)),
        RdfTriple(BlankNode("r"), q, Literal("a )>> b")),
        RdfTriple(Iri("${ex}a"), q, TripleTerm(nested)),
        RdfTriple(Iri("${ex}weird)(#"), q, Literal("multi\nline <<( )>>")),
    )

    private fun shape(graph: Collection<RdfTriple>): Set<String> = graph.map { t ->
        fun term(x: RdfTerm): String = when (x) {
            is BlankNode -> "_"
            is TripleTerm -> "<<(${term(x.triple.subject)} ${x.triple.predicate} ${term(x.triple.obj)})>>"
            else -> x.toString()
        }
        "${term(t.subject)} ${t.predicate} ${term(t.obj)}"
    }.toSet()

    /** Triple terms in RDF 1.2 syntax, and none in RDF-star syntax (the literals hold `<<(` too, so look at the terms). */
    private fun assertRdf12Syntax(format: String, text: String) {
        assertTrue(Regex("<<[(][ ]*(<|_:)").containsMatchIn(text), "$format: $text")
        assertFalse(Regex("<<[ ]*(<|_:)").containsMatchIn(text), "$format: $text")
    }

    @Test
    fun `graph formats write triple terms in RDF 1-2 syntax and read them back`() {
        for (format in listOf("TURTLE")) {
            val text = Rdf4jProvider().serializeGraph(MemoryGraph(triples), format)
            assertFalse(text.contains("urn:rdf4j:triple"), "$format:\n$text")
            assertRdf12Syntax(format, text)
            val reread = Rdf4jProvider().parseGraph(text.byteInputStream(), format).getTriples()
            assertEquals(shape(triples), shape(reread), "$format:\n$text")
            val streamed = Rdf4jProvider().openTripleStream(text.byteInputStream(), format).use { it.toList() }
            assertEquals(shape(triples), shape(streamed), "$format:\n$text")
        }
    }

    @Test
    fun `dataset formats write triple terms in RDF 1-2 syntax and read them back`() {
        val g = Iri("${ex}g")
        for (format in listOf("TRIG")) {
            val text = Rdf4jRepository.MemoryRepository().use { repo ->
                repo.editGraph(g).addTriples(triples)
                Rdf4jProvider().serializeDataset(repo, format)
            }
            assertFalse(text.contains("urn:rdf4j:triple"), "$format:\n$text")
            assertRdf12Syntax(format, text)
            Rdf4jRepository.MemoryRepository().use { repo ->
                Rdf4jProvider().parseDataset(repo, text.byteInputStream(), format)
                assertEquals(shape(triples), shape(repo.getGraph(g).getTriples()), "$format:\n$text")
            }
        }
    }

    @Test
    fun `RDF 1-2 triple term syntax parses in Turtle and TriG`() {
        val doc = "<${ex}s> <${ex}p> <<( <${ex}a> <${ex}b> <<( _:c <${ex}d> \"e )>> # <<(\" )>> )>> . # )>> <<(\n"
        val obj = Rdf4jProvider().parseGraph(doc.byteInputStream(), "TURTLE").getTriples().single().obj
        val term = assertInstanceOf(TripleTerm::class.java, obj)
        val innerTerm = assertInstanceOf(TripleTerm::class.java, term.triple.obj)
        assertEquals(Literal("e )>> # <<("), innerTerm.triple.obj)
        val trig = "<${ex}g> { <${ex}s> <${ex}p> <<( <${ex}a> <${ex}b> \"\"\"x\"\")>>\"\"\" )>> }"
        Rdf4jRepository.MemoryRepository().use { repo ->
            Rdf4jProvider().parseDataset(repo, trig.byteInputStream(), "TRIG")
            val parsed = assertInstanceOf(TripleTerm::class.java, repo.getGraph(Iri("${ex}g")).getTriples().single().obj)
            assertEquals(Literal("x\"\")>>"), parsed.triple.obj)
        }
    }

    @Test
    fun `N-Triples and N-Quads keep RDF4J's encoding, which RDF4J reads back`() {
        val text = Rdf4jProvider().serializeGraph(MemoryGraph(triples), "N-TRIPLES")
        assertEquals(shape(triples), shape(Rdf4jProvider().parseGraph(text.byteInputStream(), "N-TRIPLES").getTriples()), text)
    }

    @Test
    fun `a triple term where a subject or a predicate belongs is still a syntax error`() {
        val documents = listOf(
            "<<( <${ex}a> <${ex}b> <${ex}c> )>> <${ex}q> <${ex}z> .",
            "<${ex}s> <${ex}p> <${ex}o> . <<( <${ex}a> <${ex}b> <${ex}c> )>> <${ex}q> <${ex}z> .",
            "PREFIX ex: <${ex}> <<( ex:a ex:b ex:c )>> ex:q ex:z .",
            "@prefix ex: <${ex}> . ex:s ex:p ex:o ; <<( ex:a ex:b ex:c )>> ex:z .",
            "<${ex}s> <${ex}p> [ <<( <${ex}a> <${ex}b> <${ex}c> )>> <${ex}z> ] .",
            "<${ex}s> <${ex}p> << <<( <${ex}a> <${ex}b> <${ex}c> )>> <${ex}q> <${ex}z> >> .",
        )
        for (doc in documents) {
            assertThrows(RdfFormatException::class.java, { Rdf4jProvider().parseGraph(doc.byteInputStream(), "TURTLE") }, doc)
        }
    }

    @Test
    fun `a triple term object after directives, language tags, numbers and prefixed names is read`() {
        val documents = listOf(
            "PREFIX ex: <${ex}>\nBASE <${ex}>\nex:s ex:p <<( ex:a ex:b ex:c )>> .",
            "@prefix ex: <${ex}> . ex:s ex:p \"x\"@base ; ex:q <<( ex:a ex:b ex:c )>> .",
            "@prefix ex: <${ex}> . ex:s.x ex:p 1.5, .5, <<( ex:a ex:b ex:c )>> .",
            "@prefix ex: <${ex}> . ex:s ex:p '''a''b''' , <<( ex:a ex:b ex:c )>> .",
        )
        for (doc in documents) {
            val objects = Rdf4jProvider().parseGraph(doc.byteInputStream(), "TURTLE").getTriples().map { it.obj }
            assertTrue(objects.any { it is TripleTerm }, "$doc: $objects")
        }
    }

    @Test
    fun `a long string full of quotes and triple-term tokens is copied as it is`() {
        val run = "\"\")>><<(".repeat(5_000)
        val doc = "<${ex}s> <${ex}p> \"\"\"a${run}b\"\"\", <<( <${ex}a> <${ex}b> <${ex}c> )>> ."
        val objects = Rdf4jProvider().parseGraph(doc.byteInputStream(), "TURTLE").getTriples().map { it.obj }
        assertTrue(objects.any { it is TripleTerm }, doc.take(100))
    }
}
