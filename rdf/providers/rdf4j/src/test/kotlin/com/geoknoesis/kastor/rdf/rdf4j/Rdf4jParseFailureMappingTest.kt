package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Rdf4jParseFailureMappingTest {
    private val provider = Rdf4jProvider()

    @Test
    fun `unchecked exceptions of Rio on RDF 1_2 annotation syntax become RdfFormatException with the cause`() {
        // Valid RDF 1.2 TriG (W3C trig12-annotation-01); Rio 5.3.2 throws a raw NullPointerException on it.
        val trig = "PREFIX : <http://example/>\n:s :p :o {| :r :z |} .\n"
        Rdf4jRepository.MemoryRepository().use { repo ->
            val error = assertThrows(RdfFormatException::class.java) {
                provider.parseDataset(repo, trig.byteInputStream(), "TRIG")
            }
            assertInstanceOf(NullPointerException::class.java, error.cause)
        }
    }

    @Test
    fun `literals with unpaired surrogates are rejected like the Jena provider rejects them`() {
        for (escape in listOf("\\uD800", "\\uDC00", "\\U0000D800", "a\\uD83Db")) {
            val turtle = "<urn:s> <urn:p> \"$escape\" ."
            assertThrows(RdfFormatException::class.java, { provider.parseGraph(turtle.byteInputStream(), "TURTLE") }, escape)
            assertThrows(RdfFormatException::class.java, { provider.openTripleStream(turtle.byteInputStream(), "TURTLE").use { it.toList() } }, escape)
            Rdf4jRepository.MemoryRepository().use { repo ->
                assertThrows(RdfFormatException::class.java, { provider.parseDataset(repo, turtle.byteInputStream(), "TRIG") }, escape)
                assertTrue(repo.defaultGraph.getTriples().isEmpty())
            }
        }
        // A pair written as two escapes is one supplementary character, and stays valid.
        val pair = provider.parseGraph("<urn:s> <urn:p> \"\\uD83D\\uDE00\" .".byteInputStream(), "TURTLE")
        assertEquals("\uD83D\uDE00", (pair.getTriples().single().obj as Literal).lexical)
    }
}
