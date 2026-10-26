@file:OptIn(InternalKastorApi::class)

package com.geoknoesis.kastor.rdf.xsd

import com.geoknoesis.kastor.rdf.InternalKastorApi
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XsdNumbersTest {
    private val xsd = "http://www.w3.org/2001/XMLSchema#"

    private fun decimal(s: String) = assertNotNull(XsdNumbers.parseDecimal(s), s)

    @Test
    fun `decimal lexical space follows XSD 1_1`() {
        for (ok in listOf("0", "-0", "+0", "1", "+1.", ".5", "-.5", "00012.3400", "1.", "007")) {
            assertNotNull(XsdNumbers.parseDecimal(ok), ok)
        }
        for (bad in listOf("", "+", "-", ".", "1e3", "1E3", "INF", "NaN", " 1", "1 ", "1..2", "1.2.3", "0x10", "\u0661", "1_0", "--1")) {
            assertNull(XsdNumbers.parseDecimal(bad), bad)
        }
    }

    @Test
    fun `canonical forms strip signs and zeros and drop the point of whole numbers`() {
        val table = mapOf(
            "0" to "0", "-0" to "0", "+0.000" to "0", "-0.0" to "0", "1" to "1", "+1" to "1", "1.0" to "1",
            "001.500" to "1.5", "-.5" to "-0.5", "1000" to "1000", "1000.00" to "1000", "0.00120" to "0.0012",
            "-12.34" to "-12.34", "123456789012345678901234567890" to "123456789012345678901234567890",
        )
        for ((lexical, canonical) in table) assertEquals(canonical, decimal(lexical).toPlainString(), lexical)
    }

    @Test
    fun `values compare by sign then magnitude then digits`() {
        val ascending = listOf("-1000", "-999.999", "-1", "-0.5", "-0.05", "0", "0.0001", "0.1", "0.15", "0.2", "1", "9.99", "10", "10.0001", "100")
        for (i in ascending.indices) for (j in ascending.indices) {
            val c = decimal(ascending[i]).compareTo(decimal(ascending[j]))
            assertEquals(i.compareTo(j), Integer.signum(c), "${ascending[i]} vs ${ascending[j]}")
        }
        assertEquals(decimal("1000"), decimal("1000.000"))
        assertEquals(decimal("1000").hashCode(), decimal("+01000.0").hashCode())
        assertEquals(decimal("0"), decimal("-0"))
    }

    @Test
    fun `values are independent of the BigDecimal scale`() {
        val thousand = decimal("1000")
        assertEquals(thousand, XsdDecimal.of(BigDecimal("1E+3")))
        assertEquals(thousand, XsdDecimal.of(BigDecimal("1000").stripTrailingZeros()))
        assertEquals(thousand, XsdDecimal.of(BigDecimal("1000.000")))
        assertEquals("1000", XsdDecimal.of(BigDecimal("1E+3")).toPlainString())
        assertEquals("0.00001", XsdDecimal.of(BigDecimal("1E-5")).toPlainString())
        assertEquals(XsdDecimal.ZERO, XsdDecimal.of(BigDecimal("0E+7")))
        assertEquals(decimal("-9223372036854775808"), XsdDecimal.of(Long.MIN_VALUE))
    }

    @Test
    fun `conversions to JDK numbers are exact or refused`() {
        assertEquals(BigDecimal("12.5"), decimal("+012.500").toBigDecimalOrNull())
        assertEquals(BigInteger("-1000"), decimal("-1000").toBigIntegerOrNull())
        assertNull(decimal("1.5").toBigIntegerOrNull())
        assertEquals(Long.MAX_VALUE, decimal("9223372036854775807").toLongOrNull())
        assertEquals(Long.MIN_VALUE, decimal("-9223372036854775808").toLongOrNull())
        assertNull(decimal("9223372036854775808").toLongOrNull())
        assertNull(decimal("-9223372036854775809").toLongOrNull())
        assertEquals(Int.MIN_VALUE, decimal("-2147483648").toIntOrNull())
        assertNull(decimal("2147483648").toIntOrNull())
        assertEquals(0L, decimal("-0.000").toLongOrNull())
        // Beyond the documented bound no BigDecimal is built.
        val long = "9".repeat(10_001)
        assertNull(decimal(long).toBigDecimalOrNull())
        assertNull(decimal(long).toBigIntegerOrNull())
        assertNotNull(decimal("9".repeat(10_000)).toBigIntegerOrNull())
    }

    @Test
    fun `integer types check their value ranges by comparison`() {
        val cases = listOf(
            Triple("byte", "-128", true), Triple("byte", "127", true), Triple("byte", "128", false), Triple("byte", "-129", false),
            Triple("short", "32767", true), Triple("short", "-32769", false),
            Triple("int", "2147483647", true), Triple("int", "-2147483649", false),
            Triple("long", "-9223372036854775808", true), Triple("long", "9223372036854775808", false),
            Triple("unsignedByte", "255", true), Triple("unsignedByte", "256", false), Triple("unsignedByte", "-0", true),
            Triple("unsignedByte", "-1", false), Triple("unsignedLong", "18446744073709551615", true),
            Triple("unsignedLong", "18446744073709551616", false), Triple("unsignedInt", "4294967296", false),
            Triple("unsignedShort", "65535", true), Triple("positiveInteger", "0", false), Triple("positiveInteger", "+1", true),
            Triple("nonNegativeInteger", "-0", true), Triple("negativeInteger", "-0", false), Triple("negativeInteger", "-1", true),
            Triple("nonPositiveInteger", "0", true), Triple("nonPositiveInteger", "1", false),
            Triple("integer", "-" + "9".repeat(50_000), true), Triple("positiveInteger", "9".repeat(50_000), true),
            Triple("long", "9".repeat(50_000), false), Triple("integer", "1.0", false), Triple("integer", "1e3", false),
        )
        for ((type, lexical, valid) in cases) {
            assertEquals(valid, XsdNumbers.parseIntegerOf(xsd + type, lexical) != null, "$lexical as $type")
            assertEquals(valid, XsdLexical.isValid(xsd + type, lexical), "$lexical as $type")
        }
        assertNull(XsdNumbers.parseIntegerOf(xsd + "decimal", "1"))
    }

    @Test
    fun `floating point lexical forms and values`() {
        for (ok in listOf("INF", "+INF", "-INF", "NaN", "1", "-0", "1.5e3", "1E-3", ".5e+2", "5.E7", "-0.0e0", "1e999999999999999999999")) {
            assertNotNull(XsdNumbers.parseFloating(ok), ok)
        }
        for (bad in listOf("inf", "-NaN", "+NaN", "Infinity", "1e", "1e+", "e3", ".e3", "1.5f", "1d", "0x1p3", "", " 1")) {
            assertNull(XsdNumbers.parseFloating(bad), bad)
        }
        fun double(s: String) = XsdNumbers.parseFloating(s)!!.toDouble()
        fun float(s: String) = XsdNumbers.parseFloating(s)!!.toFloat()
        assertEquals(1500.0, double("1.5e3"))
        assertEquals(-0.0, double("-0"))
        assertTrue(1.0 / double("-0.0e5") < 0, "negative zero keeps its sign")
        assertEquals(Double.POSITIVE_INFINITY, double("1e400"))
        assertEquals(Double.POSITIVE_INFINITY, double("1e999999999999999999999"))
        assertEquals(-0.0, double("-1e-999999999999999999999"))
        assertEquals(4.9E-324, double("4.9e-324"))
        assertEquals(Double.MAX_VALUE, double("1.7976931348623157e308"))
        assertEquals(0.1f, float("0.1"))
        assertEquals(Float.POSITIVE_INFINITY, float("3.5e38"))
        assertTrue(double("NaN").isNaN())
    }

    @Test
    fun `long significands round correctly through the sticky digit`() {
        // 2^-1074 * 1.5 is a midpoint between the two smallest subnormals; a digit far beyond it decides the rounding.
        val midpoint = BigDecimal(Double.MIN_VALUE).multiply(BigDecimal("1.5")).toPlainString()
        val above = midpoint + "0".repeat(5_000) + "1"
        assertEquals(Double.MIN_VALUE * 2, XsdNumbers.parseFloating(above)!!.toDouble())
        assertEquals(Double.MIN_VALUE * 2, XsdNumbers.parseFloating(midpoint)!!.toDouble(), "ties round to even")
        // 1 + 2^-53 is the midpoint between 1 and the next double; anything above it rounds up.
        val oneMid = BigDecimal.ONE.add(BigDecimal.ONE.divide(BigDecimal(2).pow(53))).toPlainString()
        assertEquals(1.0, decimal(oneMid).toDouble(), "ties round to even")
        assertEquals(Math.nextUp(1.0), decimal(oneMid + "0".repeat(2_000) + "1").toDouble())
        assertEquals(1.0f, decimal("1.000000059604644775390625").toFloat(), "float midpoint ties to even")
        assertEquals(Math.nextUp(1.0f), decimal("1.000000059604644775390625" + "0".repeat(900) + "1").toFloat())
    }

    @Test
    fun `numeric comparison promotes per XPath`() {
        fun n(type: String, s: String) = assertNotNull(XsdNumeric.parse(xsd + type, s))
        assertEquals(0, XsdNumeric.compare(n("float", "0.1"), n("decimal", "0.1")), "decimal becomes the nearest float")
        assertEquals(1, XsdNumeric.compare(n("float", "0.1"), n("double", "0.1")), "widening a float is exact")
        assertEquals(0, XsdNumeric.compare(n("double", "-0"), n("integer", "0")))
        assertNull(XsdNumeric.compare(n("double", "NaN"), n("double", "NaN")))
        assertEquals(-1, XsdNumeric.compare(n("integer", "9".repeat(300)), n("double", "INF")))
        assertEquals(0, XsdNumeric.compare(n("integer", "9".repeat(400)), n("double", "INF")), "promoted beyond the double range")
        assertEquals(1, XsdNumeric.compare(n("decimal", "0.30000000000000000001"), n("decimal", "0.3")))
        assertNull(XsdNumeric.parse(xsd + "unsignedByte", "256"))
        assertNull(XsdNumeric.parse(xsd + "string", "1"))
    }

    @Test
    fun `BigDecimal values get the canonical decimal form and an over-long one is refused before it is built`() {
        assertEquals("1000", XsdDecimal.canonicalLexical(BigDecimal("1E+3")))
        assertEquals("1.5", XsdDecimal.canonicalLexical(BigDecimal("1.50")))
        assertEquals("0", XsdDecimal.canonicalLexical(BigDecimal("0E-9")))
        assertEquals("-0.001", XsdDecimal.canonicalLexical(BigDecimal("-1E-3")))
        assertEquals("1000", com.geoknoesis.kastor.rdf.decimal(BigDecimal("1E+3")).lexical)
        kotlin.test.assertFailsWith<IllegalArgumentException> { XsdDecimal.canonicalLexical(BigDecimal("1E+1000000000")) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { com.geoknoesis.kastor.rdf.decimal(BigDecimal("1E-1000000000")) }
    }
}
