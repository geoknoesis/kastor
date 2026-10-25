package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.TypedLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger
import java.util.concurrent.TimeUnit

/**
 * Integer decoders honour the member's declared datatype, and hostile numeric literals (millions of digits) are
 * handled in linear time.
 */
class XsdLiteralBoundsTest {

    private fun xsd(local: String) = Iri("http://www.w3.org/2001/XMLSchema#$local")
    private fun lit(lexical: String, local: String): Literal = TypedLiteral(lexical, xsd(local))

    @Test
    fun `integer decoders with a declared datatype reject values outside its range`() {
        assertEquals(300, XsdLiterals.int(lit("300", "integer")), "the plain decoder only checks the Kotlin type")
        assertNull(XsdLiterals.int(lit("300", "integer"), xsd("unsignedByte")))
        assertNull(XsdLiterals.int(lit("-1", "long"), xsd("unsignedShort")))
        assertEquals(255, XsdLiterals.int(lit("255", "integer"), xsd("unsignedByte")))
        assertNull(XsdLiterals.long(lit("-1", "integer"), xsd("unsignedInt")))
        assertEquals(4294967295L, XsdLiterals.long(lit("4294967295", "integer"), xsd("unsignedInt")))
        assertNull(XsdLiterals.bigInteger(lit("-1", "integer"), xsd("nonNegativeInteger")))
        assertNull(XsdLiterals.bigInteger(lit("0", "integer"), xsd("positiveInteger")))
        assertEquals(BigInteger.ONE, XsdLiterals.bigInteger(lit("1", "int"), xsd("positiveInteger")))
        assertEquals(BigInteger("18446744073709551615"), XsdLiterals.bigInteger(lit("18446744073709551615", "integer"), xsd("unsignedLong")))
    }

    @Test
    fun `every value a declared-type decoder returns encodes back as that datatype`() {
        val types = listOf("byte", "short", "unsignedByte", "unsignedShort")
        val samples = listOf("-129", "-128", "-1", "0", "127", "128", "255", "256", "32767", "32768", "65535", "65536")
        for (type in types) for (sample in samples) {
            val value = XsdLiterals.int(lit(sample, "integer"), xsd(type)) ?: continue
            assertEquals(lit(sample, type), XsdLiterals.encode(value, xsd(type)), "$sample as $type")
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `well-formedness of huge integers and years is decided without parsing every digit`() {
        val huge = "9".repeat(3_000_000)
        assertTrue(XsdLiterals.isWellFormed(lit(huge, "integer")))
        assertTrue(XsdLiterals.isWellFormed(lit(huge, "nonNegativeInteger")))
        assertFalse(XsdLiterals.isWellFormed(lit("-$huge", "nonNegativeInteger")))
        assertFalse(XsdLiterals.isWellFormed(lit(huge, "unsignedLong")))
        assertTrue(XsdLiterals.isWellFormed(lit("-$huge", "negativeInteger")))
        assertTrue(XsdLiterals.isWellFormed(lit("0".repeat(100) + "255", "unsignedByte")), "leading zeros do not count")
        assertTrue(XsdLiterals.isWellFormed(lit("${"1".repeat(1_000_000)}2000-02-29", "date")), "year divisible by 400")
        assertFalse(XsdLiterals.isWellFormed(lit("${"1".repeat(1_000_000)}1900-02-29", "date")), "year divisible by 100 only")
        assertTrue(XsdLiterals.isWellFormed(lit("-0400-02-29", "date")))
        assertFalse(XsdLiterals.isWellFormed(lit("-0100-02-29", "date")))
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `huge numbers are not decoded into BigInteger or BigDecimal nor compared`() {
        val huge = "9".repeat(3_000_000)
        assertNull(XsdLiterals.bigInteger(lit(huge, "integer")))
        assertNull(XsdLiterals.bigDecimal(lit("$huge.5", "decimal")))
        assertNull(XsdLiterals.compareNumeric(lit(huge, "integer"), "0"), "an undefined comparison is a violation")
        val limit = "9".repeat(10_000)
        assertEquals(BigInteger(limit), XsdLiterals.bigInteger(lit(limit, "integer")), "values up to the limit decode")
    }

    @Test
    fun `an ill-typed huge literal is abbreviated in the error message`() {
        val e = assertThrows<MaterializationException> {
            MaterializationPolicy.illTyped(lit("x".repeat(100_000), "integer"), "p", "Int")
        }
        assertTrue(e.message!!.length < 1_000, "message length ${e.message!!.length}")
        assertTrue("100000 characters" in e.message!!, e.message)
    }
}
