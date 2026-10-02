package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.GraphIsomorphismLimitException
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.isIsomorphicTo
import java.time.Duration

/**
 * Blank-node-aware RDF graph isomorphism (W3C RDF Concepts-style structural equivalence), with a budget.
 *
 * Both graphs are compared through what [RdfGraph.getTriples] exposes (so inference views of repository graphs are
 * included, and stores are only read), by Kastor's own bounded isomorphism check ([isIsomorphicTo]).
 *
 * **The check is bounded.** Graph isomorphism is expensive for highly symmetric graphs (many blank nodes that look
 * alike), and a test must fail rather than hang: the check stops when its work budget or its wall-clock limit is
 * used up, or when the thread is interrupted, and throws [GraphIsomorphismLimitException] saying which limit it was.
 * That is not a "not isomorphic" answer - no answer is known. The default limits are those of [isIsomorphicTo]: a
 * work budget that scales with the size of the graphs and 60 seconds; the overload with `maxWork` and `timeout`
 * sets others.
 *
 * Add module `com.geoknoesis.kastor:rdf-testkit` to your test source set together with
 * a concrete RDF provider (`rdf-jena` is pulled transitively by this module).
 */
object RdfGraphIsomorphism {

    /**
     * True if [expected] and [actual] contain the same RDF up to blank node relabelling.
     *
     * @throws GraphIsomorphismLimitException if the check was stopped by a limit before an answer was found
     */
    fun isIsomorphic(expected: RdfGraph, actual: RdfGraph): Boolean =
        boundedIsomorphism("graphs") { expected.isIsomorphicTo(actual) }

    /**
     * [isIsomorphic] with explicit limits: [maxWork] `null` scales with the size of the graphs, [timeout] `null`
     * means no wall-clock limit.
     *
     * @throws GraphIsomorphismLimitException if the check was stopped by a limit before an answer was found
     */
    fun isIsomorphic(expected: RdfGraph, actual: RdfGraph, maxWork: Long?, timeout: Duration?): Boolean =
        boundedIsomorphism("graphs") { expected.isIsomorphicTo(actual, maxWork, timeout) }
}

/**
 * Runs an isomorphism [check] of the [what] being compared and says, when a limit stops it, that no answer is known
 * and why.
 */
internal inline fun <T> boundedIsomorphism(what: String, check: () -> T): T = try {
    check()
} catch (e: GraphIsomorphismLimitException) {
    throw GraphIsomorphismLimitException(
        e.reason,
        "Could not decide whether the $what are isomorphic: ${e.message} (limit: ${e.reason}). This is not a " +
            "\"not isomorphic\" answer. Compare smaller or less symmetric data, or pass a larger maxWork / timeout.",
    ).apply { initCause(e) }
}
