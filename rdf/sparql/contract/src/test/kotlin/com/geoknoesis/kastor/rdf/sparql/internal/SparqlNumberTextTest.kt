package com.geoknoesis.kastor.rdf.sparql.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SparqlNumberTextTest {

    @Test
    fun `finds the first number longer than the limit, counting escapes, points and exponents`() {
        assertEquals(-1, SparqlNumberText.numberLongerThan("SELECT * { ?s ?p 12345 }", 5))
        assertEquals(17, SparqlNumberText.numberLongerThan("SELECT * { ?s ?p 123456 }", 5))
        assertEquals(1, SparqlNumberText.numberLongerThan("\"123.456\"", 5))
        assertEquals(0, SparqlNumberText.numberLongerThan("12e+3456", 5))
        assertEquals(1, SparqlNumberText.numberLongerThan("\"\\u0037\\u0037\\U00000037777\"", 5))
        assertEquals(-1, SparqlNumberText.numberLongerThan("\"777\\u0020777\"", 5))
        assertEquals(-1, SparqlNumberText.numberLongerThan("1.2.3.4.5.6", 5))
    }

    @Test
    fun `counts every Unicode decimal digit, as BigInteger accepts them`() {
        // Arabic-Indic, fullwidth and Devanagari digits, and a mix with ASCII ones.
        for (digit in listOf("١", "１", "१")) {
            assertEquals(1, SparqlNumberText.numberLongerThan("\"${digit.repeat(6)}\"", 5), digit)
            assertEquals(-1, SparqlNumberText.numberLongerThan("\"${digit.repeat(5)}\"", 5), digit)
        }
        assertEquals(1, SparqlNumberText.numberLongerThan("\"12٣４१56\"", 5))
        // A supplementary-plane digit (MATHEMATICAL BOLD DIGIT ONE) counts once, as one code point.
        assertEquals(-1, SparqlNumberText.numberLongerThan("\"${"𝟏".repeat(5)}\"", 5))
        assertEquals(1, SparqlNumberText.numberLongerThan("\"${"𝟏".repeat(6)}\"", 5))
    }
}
