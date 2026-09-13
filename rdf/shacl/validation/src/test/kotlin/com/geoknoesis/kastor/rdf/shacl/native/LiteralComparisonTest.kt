package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiteralComparisonTest {
    private fun lit(lex: String, dt: Iri) = TypedLiteral(lex, dt)
    private fun cmp(a: RdfTerm, b: RdfTerm) = tryCompareLiterals(a, b)

    @Test fun `dates and times are comparable with timezones`() {
        assertEquals(-1, cmp(lit("2020-01-01", XSD.date), lit("2020-01-02", XSD.date))!!.coerceIn(-1, 1))
        assertEquals(-1, cmp(lit("2020-01-01Z", XSD.date), lit("2020-01-02Z", XSD.date))!!.coerceIn(-1, 1))
        assertEquals(1, cmp(lit("10:00:00Z", XSD.time), lit("09:59:59Z", XSD.time))!!.coerceIn(-1, 1))
        assertEquals(0, cmp(lit("2020-01-01T10:00:00Z", XSD.dateTime), lit("2020-01-01T12:00:00+02:00", XSD.dateTime)))
        // Mixed timezone presence within 14h is indeterminate per XSD.
        assertNull(cmp(lit("2020-01-01T10:00:00Z", XSD.dateTime), lit("2020-01-01T10:00:00", XSD.dateTime)))
        assertEquals(-1, cmp(lit("2020-01-01T10:00:00Z", XSD.dateTime), lit("2020-01-03T10:00:00", XSD.dateTime))!!.coerceIn(-1, 1))
        // Different families are incomparable.
        assertNull(cmp(lit("2020-01-01", XSD.date), lit("2020-01-01T00:00:00", XSD.dateTime)))
    }

    @Test fun `strings booleans and all numeric types are comparable`() {
        assertTrue(cmp(lit("abc", XSD.string), lit("abd", XSD.string))!! < 0)
        assertTrue(cmp(lit("false", XSD.boolean), lit("true", XSD.boolean))!! < 0)
        assertTrue(cmp(lit("7", XSD.unsignedInt), lit("8", XSD.integer))!! < 0)
        assertTrue(cmp(lit("2.5", XSD.float), lit("3", XSD.unsignedByte))!! < 0)
        assertEquals(0, cmp(lit("1.0", XSD.decimal), lit("1", XSD.integer)))
        assertNull(cmp(lit("1", XSD.integer), lit("1", XSD.string)))
        assertNull(cmp(LangString("a", "en"), LangString("b", "en")))
        assertNull(cmp(lit("NaN", XSD.double), lit("1", XSD.double)))
        assertNull(cmp(lit("abc", XSD.integer), lit("1", XSD.integer)))
        assertFalse(satisfiesMinInclusive(lit("x", XSD.string), lit("1", XSD.integer)))
    }

    @Test fun `xsd lexical forms are validated`() {
        fun ok(lex: String, dt: Iri) = typedLiteralLexicallyValidForShaclDatatype(lit(lex, dt))
        assertTrue(ok("2020-01-01Z", XSD.date))
        assertTrue(ok("10:00:00Z", XSD.time))
        assertTrue(ok("2020-02-29T24:00:00", XSD.dateTime))
        assertFalse(ok("2021-02-29", XSD.date))
        assertFalse(ok("2020-01-01T10:00", XSD.dateTime))
        assertFalse(ok("2020-01-01T10:00:00", XSD.dateTimeStamp))
        assertFalse(ok("TRUE", XSD.boolean))
        assertTrue(ok("1", XSD.boolean))
        assertFalse(ok("1d", XSD.double))
        assertFalse(ok("0x1p3", XSD.double))
        assertFalse(ok("Infinity", XSD.double))
        assertTrue(ok("-INF", XSD.double))
        assertTrue(ok("1.5E-3", XSD.float))
        assertFalse(ok("256", XSD.unsignedByte))
        assertFalse(ok("-1", XSD.nonNegativeInteger))
        assertFalse(ok("0", XSD.positiveInteger))
        assertFalse(ok(" 1", XSD.integer))
        assertTrue(ok("2020", Iri(XSD.namespace + "gYear")))
        assertFalse(ok("20", Iri(XSD.namespace + "gYear")))
        assertTrue(ok("P1Y2M3DT4H5M6.5S", Iri(XSD.namespace + "duration")))
        assertFalse(ok("P", Iri(XSD.namespace + "duration")))
        assertFalse(ok("P1YT", Iri(XSD.namespace + "duration")))
        assertTrue(ok("--02-29", Iri(XSD.namespace + "gMonthDay")))
        assertFalse(ok("--02-30", Iri(XSD.namespace + "gMonthDay")))
        assertTrue(ok("en-US", Iri(XSD.namespace + "language")))
        assertFalse(ok("abc", Iri(XSD.namespace + "hexBinary")))
        assertTrue(ok("SGVsbG8=", Iri(XSD.namespace + "base64Binary")))
        assertTrue(ok("anything", Iri("http://example.org/customDatatype")))
    }
}
