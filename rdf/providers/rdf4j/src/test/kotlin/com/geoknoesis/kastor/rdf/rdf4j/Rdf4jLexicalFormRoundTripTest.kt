package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The RDF4J serializers keep the lexical form of every literal: Rio's Turtle and TriG writers abbreviate numbers by
 * default, writing the canonical form (`+1` as `1`, `01` as `1`, `1.0e0` as `1.0E0`), which is another literal.
 */
class Rdf4jLexicalFormRoundTripTest {

    private val s = Iri("http://example.org/s")
    private val p = Iri("http://example.org/p")
    private val literals = listOf(
        TypedLiteral("+1", XSD.integer), TypedLiteral("01", XSD.integer), TypedLiteral("-0", XSD.integer),
        TypedLiteral("1.0e0", XSD.double), TypedLiteral("1E1", XSD.double), TypedLiteral("+1.50", XSD.decimal),
        TypedLiteral(".5", XSD.decimal), TypedLiteral("1", XSD.integer), TypedLiteral("2.5", XSD.decimal),
        TypedLiteral("true", XSD.boolean), TypedLiteral("1", XSD.boolean),
    )
    private val triples = literals.map { RdfTriple(s, p, it) }

    @Test
    fun `graph serializations keep every literal lexical form`() {
        for (format in listOf("TURTLE", "N-TRIPLES", "RDF/XML", "JSON-LD")) {
            val text = Rdf4jProvider().serializeGraph(MemoryGraph(triples), format)
            val reread = Rdf4jProvider().parseGraph(text.byteInputStream(), format).getTriples().map { it.obj }.toSet()
            assertEquals(literals.toSet(), reread, "$format:\n$text")
        }
    }

    @Test
    fun `dataset serializations keep every literal lexical form`() {
        val g = Iri("http://example.org/g")
        for (format in listOf("TRIG", "N-QUADS")) {
            val text = Rdf4jRepository.MemoryRepository().use { repo ->
                repo.editDefaultGraph().addTriples(triples)
                repo.editGraph(g).addTriples(triples)
                Rdf4jProvider().serializeDataset(repo, format)
            }
            Rdf4jRepository.MemoryRepository().use { repo ->
                Rdf4jProvider().parseDataset(repo, text.byteInputStream(), format)
                assertEquals(literals.toSet(), repo.defaultGraph.getTriples().map { it.obj }.toSet(), "$format:\n$text")
                assertEquals(literals.toSet(), repo.getGraph(g).getTriples().map { it.obj }.toSet(), "$format:\n$text")
            }
        }
    }
}
