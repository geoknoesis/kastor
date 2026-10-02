package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Numeric comparison of the value range and property pair constraints follows the XPath / SPARQL operator mapping:
 * the operands are promoted to the smallest common type (decimal to float when the other operand is a float, to double
 * when it is a double; float to double only when the other operand is a double) and compared in that type, where
 * negative zero equals positive zero and NaN is not comparable to anything.
 */
class NumericPromotionTest {
    private val integer = XSD.integer
    private val decimal = XSD.decimal
    private val float = XSD.float
    private val double = XSD.double

    private fun lit(lex: String, dt: Iri): RdfTerm = TypedLiteral(lex, dt)

    private class Case(val a: String, val ta: Iri, val b: String, val tb: Iri, val expected: Int?, val why: String)

    private val cases = listOf(
        Case("0.1", float, "0.1", decimal, 0, "the decimal is promoted to float: both are the float nearest to 0.1"),
        Case("0.1", decimal, "0.1", float, 0, "the decimal is promoted to float"),
        Case("0.1", float, "0.1", float, 0, "same type"),
        Case("0.1", float, "0.1", double, 1, "the float is widened to double: 0.100000001490116 > 0.1"),
        Case("0.1", double, "0.1", float, -1, "the float is widened to double"),
        Case("0.1", double, "0.1", decimal, 0, "the decimal is promoted to double"),
        Case("0.3", float, "0.2", decimal, 1, "ordinary order in float"),
        Case("0.1", float, "0.2", decimal, -1, "ordinary order in float"),
        Case("16777217", integer, "16777216", float, 0, "the integer is promoted to float, which cannot represent 2^24 + 1"),
        Case("16777217", integer, "16777216", double, 1, "a double represents 2^24 + 1 exactly"),
        Case("16777217", integer, "16777216", decimal, 1, "exact types are compared exactly"),
        Case("1.5", float, "1.5", double, 0, "1.5 is exact in both types"),
        Case("-0.0", double, "0", integer, 0, "negative zero equals zero"),
        Case("0", integer, "-0.0", double, 0, "negative zero equals zero"),
        Case("-0.0", double, "0.0", double, 0, "negative zero equals positive zero"),
        Case("0.0", double, "-0.0", double, 0, "negative zero equals positive zero"),
        Case("-0.0", float, "0.0", float, 0, "negative zero equals positive zero"),
        Case("-0", float, "0", decimal, 0, "negative zero equals zero"),
        Case("-0.0", float, "0.0", double, 0, "negative zero equals positive zero"),
        Case("-0.0", double, "1E-320", double, -1, "zero is below the smallest positive doubles"),
        Case("INF", float, "INF", double, 0, "infinities are equal across types"),
        Case("-INF", double, "-1.0E308", double, -1, "negative infinity is below every number"),
        Case("INF", double, "99999999999999999999", integer, 1, "infinity is above every number"),
        Case("NaN", double, "NaN", double, null, "NaN is not comparable, even to itself"),
        Case("NaN", float, "1", integer, null, "NaN is not comparable"),
        Case("1", decimal, "NaN", double, null, "NaN is not comparable"),
        Case("NaN", float, "INF", double, null, "NaN is not comparable"),
    )

    @Test
    fun `three-way comparison promotes the operands and compares in the promoted type`() {
        for (c in cases) {
            val label = "\"${c.a}\"^^${c.ta.value.substringAfter('#')} vs \"${c.b}\"^^${c.tb.value.substringAfter('#')}: ${c.why}"
            assertEquals(c.expected, tryCompareLiterals(lit(c.a, c.ta), lit(c.b, c.tb))?.coerceIn(-1, 1), label)
        }
    }

    @Test
    fun `range bounds and order constraints agree with the comparison`() {
        for (c in cases) {
            val value = lit(c.a, c.ta)
            val bound = lit(c.b, c.tb)
            val label = "\"${c.a}\"^^${c.ta.value.substringAfter('#')} vs \"${c.b}\"^^${c.tb.value.substringAfter('#')}: ${c.why}"
            val e = c.expected
            // An incomparable pair satisfies none of the constraints: the comparison cannot be made true.
            assertEquals(e != null && e >= 0, satisfiesMinInclusive(value, bound), "sh:minInclusive $label")
            assertEquals(e != null && e <= 0, satisfiesMaxInclusive(value, bound), "sh:maxInclusive $label")
            assertEquals(e != null && e > 0, satisfiesMinExclusive(value, bound), "sh:minExclusive $label")
            assertEquals(e != null && e < 0, satisfiesMaxExclusive(value, bound), "sh:maxExclusive $label")
            assertEquals(e != null && e < 0, literalLess(value, bound), "sh:lessThan $label")
            assertEquals(e != null && e <= 0, literalLessOrEqual(value, bound), "sh:lessThanOrEquals $label")
            assertEquals(if (e != null && e < 0) 0 else 1, orderViolations(listOf(value), listOf(bound), strict = true).size, "sh:lessThan pairs $label")
            assertEquals(if (e != null && e <= 0) 0 else 1, orderViolations(listOf(value), listOf(bound), strict = false).size, "sh:lessThanOrEquals pairs $label")
        }
    }

    @Test
    fun `sorted order violations treat the two zeros as equal`() {
        val values = listOf(lit("-0.0", double), lit("0.0", double), lit("1.0", double))
        val others = listOf(lit("0.0", double), lit("-0.0", double), lit("2", double))
        // Strict: both zeros are not below either zero, 1.0 is not below either zero.
        assertEquals(6, orderViolations(values, others, strict = true).size)
        // Not strict: only 1.0 against the two zeros.
        assertEquals(2, orderViolations(values, others, strict = false).size)
    }
}
