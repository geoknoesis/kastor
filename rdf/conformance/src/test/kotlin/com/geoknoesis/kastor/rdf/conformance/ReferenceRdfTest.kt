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
        assertEquals(Rdf12ConformanceRunner.EVAL_MISMATCH, Rdf12ConformanceRunner.proposedPattern("${Rdf12ConformanceRunner.EVAL_MISMATCH}: expected 2"))
    }
}
