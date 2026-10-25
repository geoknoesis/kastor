package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Triple terms nested up to [JenaParsing.MAX_TRIPLE_TERM_NESTING] parse (the RDF4J provider's limit); deeper ones,
 * and documents nested deeper than Jena's recursive parsers can follow, fail fast with [RdfFormatException]
 * instead of a [StackOverflowError], on every parse path.
 */
class JenaNestingBoundTest {

    private val ex = "http://example.org/"
    private val limit = JenaParsing.MAX_TRIPLE_TERM_NESTING

    private fun nested(depth: Int) =
        "<${ex}s> <${ex}p> " + "<<( <${ex}a> <${ex}b> ".repeat(depth) + "<${ex}c>" + " )>>".repeat(depth) + " .\n"

    private fun depthOf(term: RdfTerm): Int = if (term is TripleTerm) 1 + depthOf(term.triple.obj) else 0

    @Test
    @Timeout(60)
    fun `triple terms nested up to the limit parse and deeper ones fail fast`() {
        for (format in listOf("TURTLE", "N-TRIPLES")) {
            assertEquals(limit, depthOf(JenaProvider().parseGraph(nested(limit).byteInputStream(), format).getTriples().single().obj))
            for (depth in listOf(limit + 1, 200_000)) {
                val doc = nested(depth)
                assertThrows(RdfFormatException::class.java, { JenaProvider().parseGraph(doc.byteInputStream(), format) }, "$format $depth")
                assertThrows(RdfFormatException::class.java, { JenaProvider().openTripleStream(doc.byteInputStream(), format).use { it.toList() } }, "$format $depth")
                JenaRepository.MemoryRepository().use { repo ->
                    assertThrows(RdfFormatException::class.java, { JenaProvider().parseDataset(repo, doc.byteInputStream(), format) }, "$format $depth")
                }
            }
        }
        val brackets = "<${ex}s> <${ex}p> " + "[ <${ex}q> ".repeat(200_000) + "1" + " ]".repeat(200_000) + " ."
        assertThrows(RdfFormatException::class.java) { JenaProvider().parseGraph(brackets.byteInputStream(), "TURTLE") }
    }
}
