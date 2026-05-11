package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfRepository

private const val STRICT_PREVIEW = 32

/**
 * Asserts that [actual] is RDF-isomorphic to [expected] (blank nodes may differ).
 *
 * @throws AssertionError if the graphs are not isomorphic, with sorted N-Triples snippets
 * and an optional strict triple diff for debugging.
 */
fun assertGraphIsomorphic(
    expected: RdfGraph,
    actual: RdfGraph,
    message: String? = null,
) {
    if (RdfGraphIsomorphism.isIsomorphic(expected, actual)) return
    val details = buildIsomorphismFailureMessage(expected, actual, message)
    throw AssertionError(details)
}

/**
 * Parses [expectedTurtle] as Turtle and asserts isomorphism against [actual].
 */
fun assertGraphIsomorphicTurtle(
    expectedTurtle: String,
    actual: RdfGraph,
    message: String? = null,
) {
    val expected = Rdf.parse(expectedTurtle, RdfFormat.TURTLE)
    assertGraphIsomorphic(expected, actual, message)
}

/**
 * Same as [assertGraphIsomorphicTurtle] for the repository default graph.
 */
fun assertDefaultGraphIsomorphicTurtle(
    expectedTurtle: String,
    repository: RdfRepository,
    message: String? = null,
) {
    assertGraphIsomorphicTurtle(expectedTurtle, repository.defaultGraph, message)
}

private fun buildIsomorphismFailureMessage(
    expected: RdfGraph,
    actual: RdfGraph,
    message: String?,
): String = buildString {
    appendLine(
        message ?: "Graphs are not isomorphic (structural RDF mismatch; blank node labels may still differ when isomorphic).",
    )
    appendLine("Expected triple count: ${expected.size()}, actual: ${actual.size()}")
    appendLine("--- Expected (sorted N-Triples, first lines) ---")
    appendLine(RdfGraphSnapshots.formatSnippet(expected))
    appendLine("--- Actual (sorted N-Triples, first lines) ---")
    appendLine(RdfGraphSnapshots.formatSnippet(actual))
    val strict = strictGraphDiff(expected, actual)
    if (!strict.isEmpty) {
        appendLine(
            "--- Strict triple diff (exact [RdfTriple] equality; blank node ids must match) ---",
        )
        appendLine("Only in expected (${strict.onlyInLeft.size}):")
        strict.onlyInLeft.take(STRICT_PREVIEW).forEach { appendLine("  $it") }
        val moreL = strict.onlyInLeft.size - STRICT_PREVIEW
        if (moreL > 0) appendLine("  ... ($moreL more)")
        appendLine("Only in actual (${strict.onlyInRight.size}):")
        strict.onlyInRight.take(STRICT_PREVIEW).forEach { appendLine("  $it") }
        val moreR = strict.onlyInRight.size - STRICT_PREVIEW
        if (moreR > 0) appendLine("  ... ($moreR more)")
    }
}
