package com.geoknoesis.kastor.rdf.conformance

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * RDF 1.2 triple terms written by one provider are read back by the other, in every format that has a triple-term
 * syntax (Turtle, TriG, N-Triples, N-Quads, and RDF/XML on Jena), and a format without one (JSON-LD on both, RDF/XML
 * on RDF4J) refuses them with [RdfFormatException] instead of losing them.
 */
@Tag("conformance-smoke")
class CrossProviderTripleTermTest {

    private val ex = "http://example.org/"
    private val inner = RdfTriple(Iri("${ex}s"), Iri("${ex}p"), Literal("o )>> <<( \"x\""))
    private val triples = listOf(
        RdfTriple(BlankNode("r"), RDF.reifies, TripleTerm(inner)),
        RdfTriple(BlankNode("r"), Iri("${ex}q"), TypedLiteral("+01", Iri("http://www.w3.org/2001/XMLSchema#integer"))),
        RdfTriple(Iri("${ex}a"), Iri("${ex}q"), TripleTerm(RdfTriple(BlankNode("r"), Iri("${ex}q"), TripleTerm(inner)))),
    )
    private val g = Iri("${ex}g")

    /** [graph] with blank nodes replaced by the order of their first occurrence, so isomorphic graphs compare equal. */
    private fun canonical(graph: Collection<RdfTriple>): Set<String> {
        val labels = LinkedHashMap<String, Int>()
        fun term(x: RdfTerm): String = when (x) {
            is BlankNode -> "_:b" + labels.getOrPut(x.id) { labels.size }
            is TripleTerm -> "<<( ${term(x.triple.subject)} ${x.triple.predicate} ${term(x.triple.obj)} )>>"
            else -> x.toString()
        }
        return graph.sortedBy { it.predicate.value + it.obj.javaClass.name }.map { "${term(it.subject)} ${it.predicate} ${term(it.obj)}" }.toSet()
    }

    private val providers = listOf<RdfProvider>(JenaProvider(), Rdf4jProvider())

    private fun repository(provider: RdfProvider): RdfRepository =
        if (provider is JenaProvider) JenaRepository.MemoryRepository() else Rdf4jRepository.MemoryRepository()

    @Test
    fun `graph formats round-trip triple terms between the providers`() {
        for (format in listOf("TURTLE", "N-TRIPLES")) for (writer in providers) for (reader in providers) {
            val text = writer.serializeGraph(MemoryGraph(triples), format)
            assertTrue(text.contains("<<("), "${writer.id} $format:\n$text")
            val reread = reader.parseGraph(text.byteInputStream(), format).getTriples()
            assertEquals(canonical(triples), canonical(reread), "${writer.id} -> ${reader.id} $format:\n$text")
        }
    }

    @Test
    fun `dataset formats round-trip triple terms between the providers`() {
        for (format in listOf("TRIG", "N-QUADS")) for (writer in providers) for (reader in providers) {
            val text = repository(writer).use { repo ->
                repo.editGraph(g).addTriples(triples)
                writer.serializeDataset(repo, format)
            }
            repository(reader).use { repo ->
                reader.parseDataset(repo, text.byteInputStream(), format)
                assertEquals(canonical(triples), canonical(repo.getGraph(g).getTriples()), "${writer.id} -> ${reader.id} $format:\n$text")
            }
        }
    }

    @Test
    fun `formats without triple-term syntax refuse them on both providers`() {
        for (provider in providers) {
            assertThrows(RdfFormatException::class.java, { provider.serializeGraph(MemoryGraph(triples), "JSON-LD") }, provider.id)
        }
        assertThrows(RdfFormatException::class.java) { Rdf4jProvider().serializeGraph(MemoryGraph(triples), "RDF/XML") }
        // Jena writes RDF 1.2 RDF/XML (rdf:parseType="Triple"), which it reads back.
        val xml = JenaProvider().serializeGraph(MemoryGraph(triples), "RDF/XML")
        assertEquals(canonical(triples), canonical(JenaProvider().parseGraph(xml.byteInputStream(), "RDF/XML").getTriples()), xml)
    }
}
