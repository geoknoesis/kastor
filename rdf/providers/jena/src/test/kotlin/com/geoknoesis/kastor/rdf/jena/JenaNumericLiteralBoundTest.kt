package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Jena computes the value of every `xsd:integer` / `xsd:decimal` literal it parses, in time quadratic in the number of
 * digits (`BigInteger(String)`: about 12 s for 800,000 digits). Kastor refuses a numeric lexical form longer than
 * [JenaLiteralBounds.MAX_NUMERIC_LEXICAL_LENGTH] before Jena builds the node, on every parse path.
 */
class JenaNumericLiteralBoundTest {

    private val limit = JenaLiteralBounds.MAX_NUMERIC_LEXICAL_LENGTH
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
        val documents = listOf(
            turtle(tooLong),
            turtle("$tooLong.5"),
            turtle("\"$tooLong\"^^<${xsd}integer>"),
            turtle("\"$tooLong\"^^<${xsd}long>"),
            turtle("\"$tooLong\"^^<${xsd}nonNegativeInteger>"),
            turtle("\"$tooLong\"^^<${xsd}decimal>"),
        )
        for (doc in documents) {
            assertRefused { JenaProvider().parseGraph(doc.byteInputStream(), "TURTLE") }
            assertRefused { JenaProvider().openTripleStream(doc.byteInputStream(), "TURTLE").use { it.toList() } }
            JenaRepository.MemoryRepository().use { repo -> assertRefused { JenaProvider().parseDataset(repo, doc.byteInputStream(), "TURTLE") } }
        }
        assertRefused { JenaProvider().parseGraph("<http://e/s> <http://e/p> \"$tooLong\"^^<${xsd}integer> .".byteInputStream(), "N-TRIPLES") }
    }

    @Test
    fun `numeric literals up to the limit, and long literals of other datatypes, parse`() {
        val documents = mapOf(
            turtle(longest) to TypedLiteral(longest, Iri("${xsd}integer")),
            turtle("\"$tooLong\"") to Literal(tooLong),
            turtle("\"$tooLong\"^^<${xsd}double>") to TypedLiteral(tooLong, Iri("${xsd}double")),
        )
        for ((doc, expected) in documents) {
            assertEquals(expected, JenaProvider().parseGraph(doc.byteInputStream(), "TURTLE").getTriples().single().obj)
        }
    }
}
