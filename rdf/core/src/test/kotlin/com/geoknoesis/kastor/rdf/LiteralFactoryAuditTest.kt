package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** `decimal(Float)` keeps the decimal digits of the float, and a language-string datatype needs its language tag. */
class LiteralFactoryAuditTest {
    @Test
    fun `decimal of a float uses the float's shortest decimal form, not its widened double`() {
        assertEquals(Literal("0.1", XSD.decimal), decimal(0.1f))
        assertEquals(Literal("3.14", XSD.decimal), decimal(3.14f))
        assertEquals(Literal("175.5", XSD.decimal), decimal(175.5f))
        assertEquals(Literal("-0.7", XSD.decimal), decimal(-0.7f))
        assertEquals(Literal("0", XSD.decimal), decimal(0.0f))
        assertEquals(Literal("100", XSD.decimal), decimal(100f))
        // Exponent forms of Float.toString are written out: xsd:decimal has no exponent.
        assertEquals(Literal("12300000000", XSD.decimal), decimal(1.23e10f))
        assertEquals(Literal("0.00001", XSD.decimal), decimal(1.0e-5f))
        // The value is the float's own decimal form.
        assertEquals(BigDecimal("16777216"), BigDecimal(decimal(16777216f).lexical))
        // Doubles are unchanged.
        assertEquals(Literal("0.1", XSD.decimal), decimal(0.1))
        assertThrows(NumberFormatException::class.java) { decimal(Float.NaN) }
        assertThrows(NumberFormatException::class.java) { decimal(Float.POSITIVE_INFINITY) }
    }

    @Test
    fun `a language string datatype without a language tag is rejected by every constructor`() {
        val lang = assertThrows(IllegalArgumentException::class.java) { Literal("x", RDF.langString) }
        assertTrue(lang.message!!.contains("language tag"), lang.message)
        assertThrows(IllegalArgumentException::class.java) { Literal("x", RDF.dirLangString) }
        assertThrows(IllegalArgumentException::class.java) { TypedLiteral("x", RDF.langString) }
        assertThrows(IllegalArgumentException::class.java) { TypedLiteral("x", RDF.dirLangString) }
        // The well-formed ways to build them.
        assertEquals(RDF.langString, Literal("x", "en").datatype)
        assertEquals(RDF.dirLangString, Literal("x", "ar", Direction.RTL).datatype)
        // Ill-typed literals of other datatypes stay legal RDF terms.
        assertEquals("maybe", Literal("maybe", XSD.boolean).lexical)
        assertEquals("x", Literal("x", XSD.integer).lexical)
    }
}
