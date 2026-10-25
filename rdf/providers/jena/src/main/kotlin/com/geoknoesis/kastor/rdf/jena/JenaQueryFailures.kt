package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.RdfException
import com.geoknoesis.kastor.rdf.RdfQueryException
import org.apache.jena.query.QueryCancelledException

/**
 * The time limit of one query (`withSelectRows(query, bindings, timeout)`): [limit] as the caller gave it, and the
 * [deadline] it ends at on [clock].
 */
internal class JenaTimeLimit(val limit: java.time.Duration, val deadline: Long, private val clock: () -> Long) {
    fun reached(): Boolean = clock() - deadline >= 0
}

/**
 * How failures of Jena's query engine become [RdfQueryException]: `"SPARQL execution failed: <reason>"`, or, for a
 * query stopped by its time limit, a message that says so and names the limit. Jena reports a timeout as a
 * [QueryCancelledException] without a message, which read `"SPARQL execution failed: null"`. A cancellation counts
 * as the time limit only once the deadline has passed (not an interrupt, and not a sandbox limit, whose
 * [JenaSandboxLimitExceeded] names that limit).
 */
internal object JenaQueryFailures {

    fun wrap(query: String, failure: Exception, timeLimit: JenaTimeLimit?): RdfQueryException {
        val cancellations = generateSequence<Throwable>(failure) { it.cause }.take(16).filterIsInstance<QueryCancelledException>()
        val timedOut = timeLimit != null && timeLimit.reached() && cancellations.any() && cancellations.none { it is JenaSandboxLimitExceeded }
        val message = if (timedOut) timeLimitMessage(timeLimit!!.limit) else "SPARQL execution failed: ${failure.message}"
        return RdfQueryException(message, query = query, cause = failure)
    }

    /** The message of a query stopped by its time limit [limit]. */
    fun timeLimitMessage(limit: java.time.Duration): String =
        "SPARQL query stopped: it reached its time limit of ${limit.toMillis()} ms"
}

/** Runs [operation], an operation of Jena's query engine (never a caller's consumer), mapping its failures (see [JenaQueryFailures]). */
internal inline fun <T> jenaQueryOperation(query: String, timeLimit: JenaTimeLimit?, operation: () -> T): T = try {
    operation()
} catch (e: RdfException) {
    throw e
} catch (e: Exception) {
    throw JenaQueryFailures.wrap(query, e, timeLimit)
}

/**
 * [this] sequence of query results with the engine's failures raised while *iterating* them (timeouts, evaluation
 * errors) wrapped as [RdfQueryException] ([jenaQueryOperation]); exceptions thrown by the consumer's own code are
 * left untouched. With a [timeLimit], every step also runs under its deadline ([InferenceCancellation]).
 */
internal fun <T> Sequence<T>.guardedBy(query: String, timeLimit: JenaTimeLimit?, clock: () -> Long): Sequence<T> {
    val source = this
    fun <R> step(block: () -> R): R = jenaQueryOperation(query, timeLimit) {
        if (timeLimit == null) block() else InferenceCancellation.withDeadline(timeLimit.deadline, clock) { block() }
    }
    return Sequence {
        val iterator = step { source.iterator() }
        object : Iterator<T> {
            override fun hasNext(): Boolean = step { iterator.hasNext() }
            override fun next(): T = step { iterator.next() }
        }
    }.constrainOnce()
}
