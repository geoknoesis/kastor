package com.geoknoesis.kastor.rdf.sparql.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * A language tag is checked in linear time: a regular expression with a repeated group overflowed the stack on a data
 * literal with a very long tag (an RDF4J initial binding renders the pre-bound value with [SparqlLexical.langLiteral]).
 */
class SparqlLexicalLangTagTest {
    @Test
    fun `a very long language tag is rendered without overflowing the stack`() {
        val tag = "en" + "-ab".repeat(100_000)
        assertEquals("\"x\"@$tag", SparqlLexical.langLiteral("x", tag))
    }

    @Test
    fun `tags are checked as the SPARQL LANGTAG production`() {
        for (good in listOf("en", "EN", "en-GB", "de-CH-1996", "x-a1b2")) assertEquals(good, SparqlLexical.langTag(good))
        for (bad in listOf("", "-en", "en-", "en--GB", "1en", "e n", "en_GB", "en-Gé")) {
            assertThrows(IllegalArgumentException::class.java) { SparqlLexical.langTag(bad) }
        }
    }
}
