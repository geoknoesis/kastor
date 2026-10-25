package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The RDF4J provider's parses apply the numeric literal limit the Jena provider needs, for parity between them. */
class Rdf4jNumericLiteralBoundTest {

    private val limit = LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH
    private val xsd = "http://www.w3.org/2001/XMLSchema#"
    private val tooLong = "7".repeat(limit + 1)
    private val longest = "7".repeat(limit)

    private fun turtle(obj: String) = "<http://example.org/s> <http://example.org/p> $obj ."

    private fun assertRefused(parse: () -> Any?) {
        val error = assertThrows(RdfFormatException::class.java) { parse() }
        assertTrue(error.message!!.contains("$limit"), error.message)
    }

    @Test
    fun `numeric literals longer than the limit are refused on every parse path`() {
        for (doc in listOf(turtle(tooLong), turtle("$tooLong.5"), turtle("\"$tooLong\"^^<${xsd}long>"), turtle("\"$tooLong\"^^<${xsd}decimal>"))) {
            assertRefused { Rdf4jProvider().parseGraph(doc.byteInputStream(), "TURTLE") }
            assertRefused { Rdf4jProvider().openTripleStream(doc.byteInputStream(), "TURTLE").use { it.toList() } }
            Rdf4jRepository.MemoryRepository().use { repo -> assertRefused { Rdf4jProvider().parseDataset(repo, doc.byteInputStream(), "TURTLE") } }
        }
        assertRefused { Rdf4jProvider().parseGraph(turtle("<<( <http://e/a> <http://e/b> $tooLong )>>").byteInputStream(), "TURTLE") }
    }

    @Test
    fun `numeric literals up to the limit, and long literals of other datatypes, parse`() {
        for ((doc, expected) in mapOf(
            turtle(longest) to TypedLiteral(longest, Iri("${xsd}integer")),
            turtle("\"$tooLong\"") to Literal(tooLong),
            turtle("\"$tooLong\"^^<${xsd}double>") to TypedLiteral(tooLong, Iri("${xsd}double")),
        )) {
            assertEquals(expected, Rdf4jProvider().parseGraph(doc.byteInputStream(), "TURTLE").getTriples().single().obj)
        }
    }
}
