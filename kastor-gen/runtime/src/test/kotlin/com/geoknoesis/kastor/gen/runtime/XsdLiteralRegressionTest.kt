package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class XsdLiteralRegressionTest {
    @Test fun `numeric promotion uses both operand types and preserves float rounding`() {
        val low = TypedLiteral("16777216", XSD.float)
        assertEquals(-1, XsdLiterals.compareNumeric(low, "16777217", XSD.double))
        assertEquals(0, XsdLiterals.compareNumeric(low, "16777217", XSD.float))
        assertEquals(0, XsdLiterals.compareNumeric(low, "16777217", XSD.decimal))
        assertEquals(1, XsdLiterals.compareNumeric(TypedLiteral("16777217", XSD.double), "16777217", XSD.float))
        assertEquals(-1, XsdLiterals.compareNumeric(TypedLiteral("16777217", XSD.float), "16777217", XSD.double))
        assertEquals(0, XsdLiterals.compareNumeric(TypedLiteral("16777217", XSD.integer), "16777216", XSD.float))
        assertEquals(1, XsdLiterals.compareNumeric(TypedLiteral("9007199254740993", XSD.integer), "9007199254740992", XSD.integer))
    }

    @Test fun `typed bounds reject undefined comparisons and retain infinity and zero semantics`() {
        val zero = TypedLiteral("0", XSD.integer)
        assertNull(XsdLiterals.compareNumeric(zero, "NaN", XSD.double))
        assertNull(XsdLiterals.compareNumeric(zero, "bogus", XSD.float))
        assertNull(XsdLiterals.compareNumeric(zero, "1", XSD.string))
        assertEquals(0, XsdLiterals.compareNumeric(zero, "-0", XSD.float))
        assertEquals(0, XsdLiterals.compareNumeric(TypedLiteral("INF", XSD.double), "1e400", XSD.double))
        assertEquals(1, XsdLiterals.compareNumeric(zero, "-INF", XSD.float))
        assertEquals(-1, XsdLiterals.compareNumeric(zero, "INF", XSD.double))
    }

    @Test fun `invalid date timezone is not a well formed literal`() {
        val value = TypedLiteral("2026-10-03+99:99", XSD.date)
        assertFalse(XsdLiterals.isWellFormed(value), "Timezone must be validated before it is dropped")
    }

    @Test fun `numeric comparison uses float value rather than decimal spelling`() {
        val value = TypedLiteral("16777217", XSD.float)
        assertEquals(16777216f, XsdLiterals.float(value))
        assertEquals(0, XsdLiterals.compareNumeric(value, "16777216"),
            "Both operands compare as float 16777216 after promotion")
    }

    @Test fun `date timezone boundaries are range checked`() {
        for (zone in listOf("", "Z", "+00:00", "-00:00", "+13:59", "-13:59", "+14:00", "-14:00")) {
            assertTrue(XsdLiterals.isWellFormed(TypedLiteral("2026-10-03$zone", XSD.date)), zone)
        }
        for (zone in listOf("+14:01", "-14:01", "+15:00", "-15:00", "+00:60", "-00:60")) {
            assertFalse(XsdLiterals.isWellFormed(TypedLiteral("2026-10-03$zone", XSD.date)), zone)
        }
    }

    @Test fun `floating comparisons promote bounds and preserve numeric equality`() {
        for (datatype in listOf(XSD.float, XSD.double)) {
            assertEquals(0, XsdLiterals.compareNumeric(TypedLiteral("-0", datatype), "0"))
            assertNull(XsdLiterals.compareNumeric(TypedLiteral("NaN", datatype), "0"))
            assertEquals(1, XsdLiterals.compareNumeric(TypedLiteral("INF", datatype), "1"))
            assertEquals(-1, XsdLiterals.compareNumeric(TypedLiteral("-INF", datatype), "1"))
        }
        assertEquals(0, XsdLiterals.compareNumeric(TypedLiteral("16777216", XSD.float), "16777217"))
        assertEquals(0, XsdLiterals.compareNumeric(TypedLiteral("9007199254740993", XSD.double), "9007199254740992"))
        assertEquals(0, XsdLiterals.compareNumeric(TypedLiteral("1e-50", XSD.float), "0"))
        assertEquals(0, XsdLiterals.compareNumeric(TypedLiteral("INF", XSD.float), "1e50"))
        assertEquals(1, XsdLiterals.compareNumeric(TypedLiteral("9007199254740993", XSD.integer), "9007199254740992"))
        assertEquals(-1, XsdLiterals.compareNumeric(TypedLiteral("0.10000000000000000001", XSD.decimal), "0.10000000000000000002"))
    }
}
