package com.geoknoesis.kastor.rdf.conformance

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Harness self-tests: dataset isomorphism and allowlist signature matching. */
@Tag("conformance-smoke")
class ReferenceRdfTest {
    private val g1 = "http://example.org/g1"
    private val g2 = "http://example.org/g2"
    private val p = Iri("http://example.org/p")
    private val q = Iri("http://example.org/q")
    private val o = Iri("http://example.org/o")

    private fun quads(first: String, second: String) = ReferenceRdf.dataset(listOf(
        KastorQuad(g1, RdfTriple(BlankNode(first), p, o)),
        KastorQuad(g2, RdfTriple(BlankNode(second), q, o)),
    ))

    @Test
    fun `blank nodes shared across graphs must correspond across graphs`() {
        assertTrue(ReferenceRdf.isomorphic(quads("a", "a"), quads("x", "x")), "relabelled shared blank node")
        assertTrue(ReferenceRdf.isomorphic(quads("a", "b"), quads("x", "y")), "relabelled distinct blank nodes")
        // Each graph is isomorphic on its own, but one dataset shares the node across graphs and the other does not.
        assertFalse(ReferenceRdf.isomorphic(quads("a", "a"), quads("x", "y")))
        assertFalse(ReferenceRdf.isomorphic(quads("a", "b"), quads("x", "x")))
    }

    @Test
    fun `proposed allowlist patterns match the observed failure but not other failures`() {
        val parseError = RuntimeException("Failed to parse", IllegalStateException("Expected '>', found '~' [line 3]"))
        val pattern = Regex(Rdf12ConformanceRunner.proposedPattern(Rdf12ConformanceRunner.failureSignature(parseError)))
        assertTrue(pattern.containsMatchIn(Rdf12ConformanceRunner.failureSignature(parseError)))
        val other = RuntimeException("Failed to parse", IllegalStateException("Unexpected character U+3C [line 1]"))
        assertFalse(pattern.containsMatchIn(Rdf12ConformanceRunner.failureSignature(other)))

        val mismatch = "${Rdf12ConformanceRunner.EVAL_MISMATCH}: expected 2 quads, actual 1 quads, difference 0123456789abcdef"
        val mismatchPattern = Regex(Rdf12ConformanceRunner.proposedPattern(mismatch))
        assertTrue(mismatchPattern.containsMatchIn(mismatch))
        assertFalse(mismatchPattern.containsMatchIn(mismatch.replace("0123456789abcdef", "fedcba9876543210")), "a different mismatch must not match")
    }

    /** Thrown from a named frame, like an NPE raised inside a parser. */
    private fun npeFrom(className: String): NullPointerException = NullPointerException("statement may not be null").apply {
        stackTrace = arrayOf(
            StackTraceElement("java.util.Objects", "requireNonNull", "Objects.java", 1),
            StackTraceElement(className, "handleStatement", "X.java", 1),
        )
    }

    @Test
    fun `JDK exceptions keep their throwing frame in proposed patterns even with a message`() {
        val rdf4j = npeFrom("org.eclipse.rdf4j.rio.turtle.TurtleParser")
        val pattern = Regex(Rdf12ConformanceRunner.proposedPattern(Rdf12ConformanceRunner.failureSignature(rdf4j)))
        assertTrue(pattern.pattern.contains("org.eclipse.rdf4j.rio.turtle.TurtleParser"), pattern.pattern)
        assertTrue(pattern.containsMatchIn(Rdf12ConformanceRunner.failureSignature(rdf4j)))
        val kastor = npeFrom("com.geoknoesis.kastor.rdf.rdf4j.Rdf4jFormatSupport")
        assertFalse(pattern.containsMatchIn(Rdf12ConformanceRunner.failureSignature(kastor)), "the same NPE from Kastor code must not be skipped")
    }

    @Test
    fun `language tag case differences are detected`() {
        val s = Iri("http://example.org/s")
        fun data(tag: String) = ReferenceRdf.dataset(listOf(KastorQuad(null, RdfTriple(s, p, com.geoknoesis.kastor.rdf.LangString("x", tag)))))
        assertTrue(ReferenceRdf.isomorphic(data("en-GB"), data("en-GB")))
        assertFalse(ReferenceRdf.isomorphic(data("en-GB"), data("en-gb")), "tag case must be compared exactly")

        val file = kotlin.io.path.createTempFile(suffix = ".nt")
        try {
            java.nio.file.Files.writeString(file, "<http://example.org/s> <http://example.org/p> \"x\"@en-gb .\n")
            val expected = ReferenceRdf.parse(file, TestFormat.N_TRIPLES, null)
            assertTrue(ReferenceRdf.isomorphic(expected, data("en-gb")), "the expected file's tag keeps its lexical case")
            assertFalse(ReferenceRdf.isomorphic(expected, data("en-GB")))
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun `mismatch fingerprints ignore blank node labels but distinguish different results`() {
        val expected = quads("a", "b")
        assertEquals(ReferenceRdf.mismatchFingerprint(expected, quads("x", "x")), ReferenceRdf.mismatchFingerprint(quads("c", "d"), quads("y", "y")))
        val missingOne = ReferenceRdf.dataset(listOf(KastorQuad(g1, RdfTriple(BlankNode("z"), p, o))))
        assertFalse(ReferenceRdf.mismatchFingerprint(expected, missingOne) == ReferenceRdf.mismatchFingerprint(expected, quads("x", "x")))
    }
}
