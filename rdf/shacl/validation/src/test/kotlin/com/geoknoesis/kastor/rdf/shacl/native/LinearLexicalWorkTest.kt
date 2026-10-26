@file:OptIn(InternalKastorApi::class)

package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.InternalKastorApi
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.xsd.XsdWork
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hostile lexical forms cost the SHACL validator work linear in their length, counted by the shared parser's
 * [XsdWork] meter (no timing): `BigDecimal(String)` on a million fraction digits took 21 s (round 13, P2-2).
 */
class LinearLexicalWorkTest {
    private val n = 100_000
    private val fraction = "1".repeat(n)

    /** Runs [block], checking that the shared parser read the input and did at most [factor] × [n] work. */
    private fun <T> linear(factor: Int = 8, block: () -> T): T {
        val (result, work) = XsdWork.measure(block)
        assertTrue(work >= n, "the shared linear parser read the input (work $work)")
        assertTrue(work <= factor.toLong() * n, "work $work is linear in $n")
        return result
    }

    @Test
    fun `sh_datatype checks fractional seconds in linear work`() {
        assertTrue(linear { literalLexicallyValid(TypedLiteral("2020-01-01T00:00:00.$fraction", XSD.dateTime)) })
        assertTrue(linear { literalLexicallyValid(TypedLiteral("2020-01-01T00:00:00.${fraction}Z", XSD.dateTimeStamp)) })
        assertTrue(linear { literalLexicallyValid(TypedLiteral("23:59:59.$fraction", XSD.time)) })
        assertFalse(linear { literalLexicallyValid(TypedLiteral("24:00:00.$fraction", XSD.time)) })
    }

    @Test
    fun `value ranges compare fractional seconds as digits`() {
        val value = TypedLiteral("2020-01-01T00:00:00.${fraction}2Z", XSD.dateTime)
        val bound = TypedLiteral("2020-01-01T01:00:00.$fraction+01:00", XSD.dateTime)
        assertTrue(linear { satisfiesMinExclusive(value, bound) })
        assertFalse(linear { satisfiesMaxInclusive(value, bound) })
        assertTrue(linear { literalLess(TypedLiteral("10:00:00.$fraction", XSD.time), TypedLiteral("10:00:00.${fraction}1", XSD.time)) })
    }

    @Test
    fun `huge numbers and durations are linear`() {
        val digits = "7".repeat(n)
        assertTrue(linear { literalLexicallyValid(TypedLiteral(digits, XSD.positiveInteger)) })
        assertTrue(linear { literalLexicallyValid(TypedLiteral("$digits.$digits", XSD.decimal)) })
        assertTrue(linear { literalLexicallyValid(TypedLiteral("$digits.${digits}e-5", XSD.double)) })
        assertTrue(linear { satisfiesMaxExclusive(TypedLiteral("1.$digits", XSD.decimal), TypedLiteral("1.${digits}1", XSD.decimal)) })
        assertTrue(linear { literalLexicallyValid(TypedLiteral("P${digits}D", com.geoknoesis.kastor.rdf.Iri(XSD.namespace + "dayTimeDuration"))) })
    }

    @Test
    fun `sh_languageIn reads a bounded prefix of a long tag`() {
        val ranges = LanguageRanges(listOf("en", "de-ch-*", "x-private"))
        val tag = "a-".repeat(2_000_000) + "a"
        var work = 0L
        assertFalse(ranges.matches(tag) { work += it })
        assertTrue(work <= 64, "work $work is bounded by the longest range, not the tag")
        work = 0
        assertTrue(ranges.matches("de-CH-" + "x-".repeat(2_000_000) + "x") { work += it })
        assertTrue(work <= 64, "work $work")
        assertEquals(true, ranges.matches("EN"))
    }

    @Test
    fun `long binary and language values are scanned without regular-expression recursion`() {
        // The former patterns repeated a group per character and overflowed the stack near 300,000 characters.
        fun ok(lex: String, local: String) = literalLexicallyValid(TypedLiteral(lex, com.geoknoesis.kastor.rdf.Iri(XSD.namespace + local)))
        assertTrue(ok("QUJD".repeat(1_000_000), "base64Binary"))
        assertTrue(ok("a-".repeat(1_000_000) + "a", "language"))
        assertTrue(ok("ab".repeat(1_000_000), "hexBinary"))
        for (good in listOf("", "SGVsbG8=", "SGVs bG8=", "QQ==", "QQ= =", "Q Q = =", "AAAA")) assertTrue(ok(good, "base64Binary"), good)
        for (bad in listOf("QR==", "SGVsbG9=", "SGV", " QUJD", "QUJD ", "QU  JD", "QUJ=D", "====", "Q===", "QUJD=", "QU-D")) {
            assertFalse(ok(bad, "base64Binary"), bad)
        }
        assertFalse(ok("abc", "hexBinary"))
        assertFalse(ok("ag", "hexBinary"))
        assertFalse(ok("toolongsubtag", "language"))
    }
}
