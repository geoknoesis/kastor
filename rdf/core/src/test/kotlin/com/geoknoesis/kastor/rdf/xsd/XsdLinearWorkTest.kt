@file:OptIn(InternalKastorApi::class)

package com.geoknoesis.kastor.rdf.xsd

import com.geoknoesis.kastor.rdf.InternalKastorApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Multi-megabyte lexical forms cost work linear in their length: the [XsdWork] meter counts the characters every
 * scan, comparison and conversion reads, so these tests are independent of the machine's speed.
 */
class XsdLinearWorkTest {
    private val xsd = "http://www.w3.org/2001/XMLSchema#"
    private val n = 4_000_000

    /** Runs [block] and checks that it charged between [n] (it read the input) and [factor] × [n] units. */
    private fun <T> linear(factor: Int = 8, block: () -> T): T {
        val (result, work) = XsdWork.measure(block)
        assertTrue(work >= n, "the shared parser read the input (work $work)")
        assertTrue(work <= factor.toLong() * n, "work $work is linear in $n")
        return result
    }

    @Test
    fun `huge integers and decimals are validated and compared in linear work`() {
        val digits = "7".repeat(n)
        assertEquals(true, linear { XsdLexical.isValid(xsd + "integer", digits) })
        assertEquals(false, linear { XsdLexical.isValid(xsd + "long", digits) })
        assertEquals(true, linear { XsdLexical.isValid(xsd + "decimal", "0.$digits") })
        val a = XsdNumbers.parseDecimal("1.$digits")!!
        val b = XsdNumbers.parseDecimal("1.${digits}1")!!
        assertEquals(-1, linear { a.compareTo(b) })
        assertNull(a.toBigDecimalOrNull(), "beyond the bound no BigDecimal is built")
    }

    @Test
    fun `huge floating-point forms convert in work independent of their digits`() {
        val (value, work) = XsdWork.measure { XsdNumbers.parseFloating("0." + "3".repeat(n) + "e1")!!.toDouble() }
        assertEquals(3.3333333333333335, value)
        assertTrue(work in n.toLong()..3L * n, "work $work")
    }

    @Test
    fun `fractional seconds are compared as digits`() {
        val fraction = "1".repeat(n)
        assertEquals(true, linear { XsdLexical.isValid(xsd + "dateTime", "2020-01-01T00:00:00.$fraction") })
        assertEquals(true, linear { XsdLexical.isValid(xsd + "time", "23:59:59.${fraction}Z") })
        val x = XsdTemporals.parse(xsd + "dateTime", "2020-01-01T00:00:00.${fraction}Z")!!
        val y = XsdTemporals.parse(xsd + "dateTime", "2020-01-01T01:00:00.${fraction}2+01:00")!!
        assertEquals(-1, linear { XsdTemporals.compare(x, y) })
    }

    @Test
    fun `huge years and durations are linear too`() {
        assertEquals(true, linear { XsdLexical.isValid(xsd + "date", "9".repeat(n) + "-12-31") })
        val a = XsdTemporals.parse(xsd + "dateTime", "9".repeat(n) + "-12-31T23:00:00-02:00")!!
        val b = XsdTemporals.parse(xsd + "dateTime", "1" + "0".repeat(n) + "-01-01T01:00:00Z")!!
        assertEquals(0, linear(factor = 16) { XsdTemporals.compare(a, b) })
        assertEquals(true, linear { XsdLexical.isValid(xsd + "duration", "P" + "1".repeat(n) + "DT1." + "5".repeat(10) + "S") })
    }
}
