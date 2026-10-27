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
}
