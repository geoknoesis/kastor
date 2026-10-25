package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.RdfDebug
import com.geoknoesis.kastor.rdf.RdfQueryException

/**
 * The time limit of one query (`withSelectRows(query, bindings, timeout)`): [limit] as the caller gave it, and the
 * [deadline] it ends at on [clock].
 */
internal class Rdf4jTimeLimit(val limit: java.time.Duration, val deadline: Long, private val clock: () -> Long) {
    fun reached(): Boolean = clock() - deadline >= 0
}

/**
 * How failures of RDF4J's query engine become [RdfQueryException] (see `Rdf4jRepository.queryOperation`).
 *
 * A query stopped by its time limit gets a message that says so and names the limit, the same message on every path:
 * RDF4J's own `maxExecutionTime` ("Query evaluation took too long", rounded up to whole seconds) and Kastor's exact
 * deadline of a sandboxed query and of its nested queries ("The sandboxed query ran out of time") both report a
 * [org.eclipse.rdf4j.query.QueryInterruptedException]. It counts as the time limit only once the deadline has passed.
 */
internal object Rdf4jQueryFailures {
    /** A closing quote followed by an RDF 1.2 directional language tag, e.g. `"x"@ar--rtl`. */
    private val DIRECTIONAL_LITERAL = Regex("[\"']@[A-Za-z]+(?:-[A-Za-z0-9]+)*--(?:ltr|rtl)(?![A-Za-z0-9-])")

    /** The failure [e] of an operation described by [what], reported to [RdfDebug] as a failed [kind] when one is given. */
    fun failure(what: String, kind: String?, query: String, e: Throwable, timeLimit: Rdf4jTimeLimit?): RdfQueryException {
        val message = if (timedOut(e, timeLimit)) timeLimitMessage(timeLimit!!.limit) else failureMessage(what, query, e)
        if (kind != null) RdfDebug.logQueryError(kind, query, message)
        return RdfQueryException(message, query = query, cause = e)
    }

    private fun timedOut(e: Throwable, timeLimit: Rdf4jTimeLimit?): Boolean =
        timeLimit != null && timeLimit.reached() &&
            generateSequence(e) { it.cause }.take(16).any { it is org.eclipse.rdf4j.query.QueryInterruptedException }

    /** The message of a query stopped by its time limit [limit] (the Jena provider's message). */
    fun timeLimitMessage(limit: java.time.Duration): String =
        "SPARQL query stopped: it reached its time limit of ${limit.toMillis()} ms"

    /**
     * Error message for a failed query. RDF4J's SPARQL 1.1 parser cannot read RDF 1.2 directional language
     * literals (`"x"@ar--rtl`); that case gets an explicit explanation instead of a bare lexer error.
     */
    fun failureMessage(prefix: String, query: String, e: Throwable): String {
        val malformed = generateSequence<Throwable>(e) { it.cause }.any { it is org.eclipse.rdf4j.query.MalformedQueryException }
        return if (malformed && DIRECTIONAL_LITERAL.containsMatchIn(query)) {
            "$prefix: RDF4J's SPARQL 1.1 parser cannot read RDF 1.2 directional language literals (\"...\"@lang--dir). " +
                "Pass such literals as query bindings (withSelectRows(query, bindings, timeout)) or use the graph API. (${e.message})"
        } else {
            "$prefix: ${e.message}"
        }
    }
}
