package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.Year
import java.time.YearMonth
import java.time.LocalDate

class TermSemanticsRegressionTest {

    // --- Boolean literal factory preserves lexical form (RDF allows ill-typed literals) ---

    @Test
    fun `boolean factory keeps non-canonical and ill-typed lexical forms`() {
        assertSame(TrueLiteral, Literal("true", XSD.boolean))
        assertSame(FalseLiteral, Literal("false", XSD.boolean))
        assertEquals(TypedLiteral("1", XSD.boolean), Literal("1", XSD.boolean))
        assertEquals("1", Literal("1", XSD.boolean).lexical)
        assertEquals("yes", Literal("yes", XSD.boolean).lexical)
        assertNotEquals(TrueLiteral, Literal("1", XSD.boolean))
    }

    @Test
    fun `typed boolean literal equals the singleton with consistent hashCode`() {
        val typed = TypedLiteral("true", XSD.boolean)
        assertEquals(TrueLiteral, typed)
        assertEquals(typed, TrueLiteral)
        assertEquals(TrueLiteral.hashCode(), typed.hashCode())
        assertEquals(FalseLiteral, TypedLiteral("false", XSD.boolean))
        assertEquals(FalseLiteral.hashCode(), TypedLiteral("false", XSD.boolean).hashCode())
        assertEquals(1, setOf<RdfTerm>(typed, TrueLiteral).size)
    }

    @Test
    fun `boolean value normalisation is a separate function`() {
        assertEquals(true, Literal("1", XSD.boolean).booleanValue())
        assertEquals(false, Literal("0", XSD.boolean).booleanValue())
        assertEquals(true, TrueLiteral.booleanValue())
        assertNull(Literal("yes", XSD.boolean).booleanValue())
        assertNull(Literal("true").booleanValue())
    }

    // --- Temporal / floating point lexical forms ---

    @Test
    fun `temporal factories always emit valid XSD lexical forms`() {
        assertEquals("10:15:00", Literal(LocalTime.of(10, 15)).lexical)
        assertEquals("10:15:00", LocalTime.of(10, 15).toLiteral().lexical)
        assertEquals("10:15:30.5", Literal(LocalTime.of(10, 15, 30, 500_000_000)).lexical)
        assertEquals("2024-01-02T03:04:00", Literal(LocalDateTime.of(2024, 1, 2, 3, 4)).lexical)
        assertEquals("2024-01-02T03:04:00", LocalDateTime.of(2024, 1, 2, 3, 4).toLiteral().lexical)
        assertEquals(
            "2024-01-02T03:04:00Z",
            Literal(OffsetDateTime.of(2024, 1, 2, 3, 4, 0, 0, ZoneOffset.UTC)).lexical,
        )
        assertEquals("0005", Literal(Year.of(5)).lexical)
        assertEquals("0005", Year.of(5).toLiteral().lexical)
        assertEquals("-0005", Literal(Year.of(-5)).lexical)
        assertEquals("12345", Literal(Year.of(12345)).lexical)
        assertEquals("12345-03", Literal(YearMonth.of(12345, 3)).lexical)
        assertEquals("12345-03-04", Literal(LocalDate.of(12345, 3, 4)).lexical)
    }

    @Test
    fun `floating point special values use XSD spellings`() {
        assertEquals("INF", Literal(Double.POSITIVE_INFINITY).lexical)
        assertEquals("-INF", Double.NEGATIVE_INFINITY.toLiteral().lexical)
        assertEquals("NaN", Literal(Double.NaN).lexical)
        assertEquals("INF", Literal(Float.POSITIVE_INFINITY).lexical)
        assertEquals("-INF", Float.NEGATIVE_INFINITY.toLiteral().lexical)
        assertEquals("3.14", Literal(3.14).lexical)
    }

    @Test
    fun `binding set numeric and boolean accessors follow XSD lexical space`() {
        val row = MapBindingSet(
            mapOf(
                "inf" to TypedLiteral("INF", XSD.double),
                "ninf" to TypedLiteral("-INF", XSD.double),
                "nan" to TypedLiteral("NaN", XSD.double),
                "javaInf" to TypedLiteral("Infinity", XSD.double),
                "hex" to TypedLiteral("0x10", XSD.double),
                "num" to TypedLiteral("1.5e3", XSD.double),
                "upper" to TypedLiteral("TRUE", XSD.boolean),
                "one" to TypedLiteral("1", XSD.boolean),
            )
        )
        assertEquals(Double.POSITIVE_INFINITY, row.getDouble("inf"))
        assertEquals(Double.NEGATIVE_INFINITY, row.getDouble("ninf"))
        assertTrue(row.getDouble("nan")!!.isNaN())
        assertNull(row.getDouble("javaInf"))
        assertNull(row.getDouble("hex"))
        assertEquals(1500.0, row.getDouble("num"))
        assertNull(row.getBoolean("upper"))
        assertEquals(true, row.getBoolean("one"))
    }

    // --- Language tags ---

    @Test
    fun `language tags are validated, preserved as given and compared case-insensitively`() {
        val british = LangString("x", "en-GB")
        assertEquals(british, LangString("x", "en-gb"))
        assertEquals(british.hashCode(), LangString("x", "en-gb").hashCode())
        assertEquals("en-GB", british.lang)
        assertEquals("en-gb", british.normalizedLang)
        assertEquals("\"x\"@en-GB", british.toString())
        assertEquals(LangString("x", "en-us"), LangString("x", "EN-US"))
        assertEquals("en-us", normalizeLanguageTag("EN-us"))

        // Subtags are capped at 8 characters (BCP 47 well-formedness; W3C RDF 1.2 ntriples-langdir-bad-4).
        assertEquals("abcdefgh", LangString("x", "abcdefgh").lang)
        assertEquals("en-abcdefgh", LangString("x", "en-abcdefgh").lang)
        assertEquals("abcdefgh", normalizeLanguageTag("ABCDEFGH"))
        listOf("", "en US", "en-", "-en", "1en", "en_GB", "en--rtl", "e\"n", "\u00E9", "abcdefghi", "en-abcdefghijk", "cantbethislong").forEach { tag ->
            assertThrows(IllegalArgumentException::class.java, { LangString("x", tag) }, tag)
            assertThrows(IllegalArgumentException::class.java, { normalizeLanguageTag(tag) }, tag)
        }
        assertThrows(IllegalArgumentException::class.java) { Literal("x", "e\"n") }

        // Lexical form and direction still compare exactly.
        assertNotEquals(LangString("x", "en"), LangString("X", "en"))
        assertNotEquals(LangString("x", "en", Direction.LTR), LangString("x", "EN"))
        assertEquals(LangString("x", "en", Direction.LTR), LangString("x", "EN", Direction.LTR))
        assertEquals(RDF.dirLangString, LangString("x", "ar", Direction.RTL).datatype)
        val copied = LangString("y", "FR").copy(lexical = "x")
        assertEquals(LangString("x", "fr"), copied)
        assertEquals("FR", copied.lang)
    }

    // --- toString escaping ---

    @Test
    fun `literal toString escapes N-Triples special characters`() {
        assertEquals(
            "\"a\\\"b\\\\c\\nd\"^^<http://www.w3.org/2001/XMLSchema#string>",
            TypedLiteral("a\"b\\c\nd", XSD.string).toString(),
        )
        assertEquals("\"say \\\"hi\\\"\"@en--ltr", LangString("say \"hi\"", "en", Direction.LTR).toString())
    }

    // --- equivalentTo ---

    @Test
    fun `equivalentTo respects language tag and direction`() {
        assertFalse(LangString("a", "en") equivalentTo LangString("a", "fr"))
        assertFalse(LangString("a", "ar", Direction.RTL) equivalentTo LangString("a", "ar"))
        assertTrue(LangString("a", "EN") equivalentTo LangString("a", "en"))
        assertTrue(TypedLiteral("true", XSD.boolean) equivalentTo TrueLiteral)
    }

    // --- IRI validation ---

    @Test
    fun `IRI validation rejects structurally malformed values`() {
        val bad = listOf(
            "http://[::1",            // unterminated IP literal
            "http://[::1]x/",         // garbage after IP literal
            "http://example.org:80a/", // non-numeric port
            "http://exa[mple.org/",   // bracket in reg-name
            "http://example.org/%zz", // bad percent-encoding
            "http://example.org/%4",  // truncated percent-encoding
            "http://example.org/a#b#c", // two fragments
            "1http://example.org/",   // scheme must start with a letter
            "ht_tp://example.org/",   // illegal scheme char
            // "http:" is NOT listed: scheme ":" path-empty is a valid absolute URI (RFC 3986 section 4.3).
            "relative/path",
            "",
        )
        for (v in bad) {
            assertThrows(IllegalArgumentException::class.java, { Iri(v) }, "should reject '$v'")
        }
    }

    @Test
    fun `IRI validation accepts well-formed values`() {
        val good = listOf(
            "http://[::1]/",
            "http://[::1]:8080/path?q=1#frag",
            "http://user:pw@example.org:8080/a/b;c?d=e&f=g#h",
            "https://example.org",
            "http://example.org/%E4%BE%8B",
            "mailto:someone@example.org",
            "urn:isbn:0451450523",
            "file:///tmp/x.ttl",
            "tag:example.org,2024:x",
            "http://例え.jp/パス?クエリ#断片",
            "http://example.org/a/[b]",
            "http://example.org:/",
        )
        for (v in good) {
            assertEquals(v, Iri(v).value)
        }
    }
}
