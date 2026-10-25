package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate

/**
 * Decoders, [XsdLiterals.isWellFormed] and [XsdLiterals.encode] agree: a literal is decoded only when it is well
 * formed for its own datatype (including the value ranges of the derived integer types), and `encode` never
 * produces a literal that `isWellFormed` rejects.
 */
class XsdLiteralStrictnessTest {

    private fun xsd(local: String) = Iri("http://www.w3.org/2001/XMLSchema#$local")
    private fun lit(lexical: String, local: String): Literal = TypedLiteral(lexical, xsd(local))

    @Test
    fun `integer decoders reject values outside the range of the literal's derived type`() {
        val outOfRange = listOf(
            lit("300", "unsignedByte"), lit("-1", "unsignedByte"), lit("200", "byte"), lit("-129", "byte"),
            lit("40000", "short"), lit("70000", "unsignedShort"), lit("-1", "unsignedInt"), lit("4294967296", "unsignedInt"),
            lit("-5", "positiveInteger"), lit("0", "positiveInteger"), lit("-1", "nonNegativeInteger"),
            lit("1", "nonPositiveInteger"), lit("0", "negativeInteger"), lit("-1", "unsignedLong"),
            lit("18446744073709551616", "unsignedLong"), lit("2147483648", "int"), lit("9223372036854775808", "long"),
        )
        for (value in outOfRange) {
            assertFalse(XsdLiterals.isWellFormed(value), "$value")
            assertNull(XsdLiterals.int(value), "int $value")
            assertNull(XsdLiterals.long(value), "long $value")
            assertNull(XsdLiterals.bigInteger(value), "bigInteger $value")
            assertNull(XsdLiterals.bigDecimal(value), "bigDecimal $value")
            assertNull(XsdLiterals.float(value), "float $value")
            assertNull(XsdLiterals.double(value), "double $value")
        }
    }

    @Test
    fun `integer decoders accept the bounds of each derived type`() {
        assertEquals(255, XsdLiterals.int(lit("255", "unsignedByte")))
        assertEquals(-128, XsdLiterals.int(lit("-128", "byte")))
        assertEquals(4294967295L, XsdLiterals.long(lit("4294967295", "unsignedInt")))
        assertEquals(BigInteger.ONE, XsdLiterals.bigInteger(lit("+1", "positiveInteger")))
        assertEquals(BigInteger("18446744073709551615"), XsdLiterals.bigInteger(lit("18446744073709551615", "unsignedLong")))
        assertEquals(BigInteger("-7"), XsdLiterals.bigInteger(lit(" -7 ", "negativeInteger")))
        assertEquals(3.0, XsdLiterals.double(lit("3", "byte")))
    }

    @Test
    fun `floating decoders reject forms that are ill typed for the literal's own datatype`() {
        for (value in listOf(lit("INF", "decimal"), lit("NaN", "integer"), lit("1e5", "integer"), lit("1e5", "decimal"), lit("1.5", "integer"))) {
            assertFalse(XsdLiterals.isWellFormed(value), "$value")
            assertNull(XsdLiterals.float(value), "float $value")
            assertNull(XsdLiterals.double(value), "double $value")
        }
        assertEquals(1.0e5, XsdLiterals.double(lit("1e5", "double")))
        assertEquals(Float.NEGATIVE_INFINITY, XsdLiterals.float(lit("-INF", "float")))
        assertEquals(0.5, XsdLiterals.double(lit(".5", "decimal")))
        assertNull(XsdLiterals.double(lit("0x10", "double")))
        assertNull(XsdLiterals.double(lit("1d", "double")), "Kotlin's 'd' suffix is not XSD syntax")
        assertNull(XsdLiterals.float(lit("Infinity", "float")))
    }

    @Test
    fun `temporal datatypes are checked by isWellFormed`() {
        assertTrue(XsdLiterals.isWellFormed(lit("2020-01-01T10:00:00Z", "dateTime")))
        assertTrue(XsdLiterals.isWellFormed(lit("2020-01-01T24:00:00", "dateTime")))
        assertFalse(XsdLiterals.isWellFormed(lit("2020", "dateTime")))
        assertFalse(XsdLiterals.isWellFormed(lit("2020-02-30T10:00:00", "dateTime")))
        assertFalse(XsdLiterals.isWellFormed(lit("2020-01-01T10:00:00", "dateTimeStamp")), "dateTimeStamp needs a timezone")
        assertTrue(XsdLiterals.isWellFormed(lit("10:00:00.5+01:00", "time")))
        assertFalse(XsdLiterals.isWellFormed(lit("25:00:00", "time")))
        assertTrue(XsdLiterals.isWellFormed(lit("2020", "gYear")))
        assertFalse(XsdLiterals.isWellFormed(lit("20", "gYear")))
        assertTrue(XsdLiterals.isWellFormed(lit("2020-12", "gYearMonth")))
        assertFalse(XsdLiterals.isWellFormed(lit("2020-13", "gYearMonth")))
        assertTrue(XsdLiterals.isWellFormed(lit("P1Y2M3DT4H5M6.5S", "duration")))
        assertFalse(XsdLiterals.isWellFormed(lit("P", "duration")))
        assertFalse(XsdLiterals.isWellFormed(lit("PT", "duration")))
        assertTrue(XsdLiterals.isWellFormed(Literal("anything", Iri("https://example.test/custom"))), "non-XSD datatypes are accepted")
    }

    @Test
    fun `lexical decodes a String-mapped value only from a well formed literal of the declared datatype`() {
        assertEquals("2020-01-01T10:00:00Z", XsdLiterals.lexical(lit("2020-01-01T10:00:00Z", "dateTime"), XSD.dateTime))
        assertNull(XsdLiterals.lexical(lit("2020", "gYear"), XSD.dateTime), "another datatype is ill typed")
        assertNull(XsdLiterals.lexical(lit("not a date", "dateTime"), XSD.dateTime), "an ill-formed lexical form is ill typed")
        assertNull(XsdLiterals.lexical(Literal("2020-01-01T10:00:00Z"), XSD.dateTime))
        assertEquals("chat", XsdLiterals.string(LangString("chat", "fr")), "string() still reads any literal's lexical form")
    }

    @Test
    fun `encode rejects values that are not well formed for the declared datatype`() {
        val rejected = listOf<Pair<Any, String>>(
            300 to "unsignedByte", -1 to "unsignedInt", BigInteger.ZERO to "positiveInteger", 200 to "byte",
            "yesterday" to "dateTime", "maybe" to "boolean", Double.NaN to "decimal", 1.5 to "integer",
            LocalDate.of(2020, 1, 1) to "integer",
        )
        for ((value, local) in rejected) {
            val e = assertThrows<IllegalArgumentException>("$value as xsd:$local") { XsdLiterals.encode(value, xsd(local)) }
            assertTrue(local in e.message.orEmpty() && value.toString() in e.message.orEmpty(), e.message)
        }
        assertEquals(lit("255", "unsignedByte"), XsdLiterals.encode(255, xsd("unsignedByte")))
        assertEquals(lit("10.50", "decimal"), XsdLiterals.encode(BigDecimal("10.50"), xsd("decimal")))
        assertEquals(lit("INF", "double"), XsdLiterals.encode(Double.POSITIVE_INFINITY, xsd("double")))
        assertEquals(lit("2020-01-01T10:00:00Z", "dateTime"), XsdLiterals.encode("2020-01-01T10:00:00Z", xsd("dateTime")))
        val tagged = LangString("chat", "fr")
        assertEquals(tagged, XsdLiterals.encode(tagged, XSD.string), "literals are returned unchanged")
    }
}
