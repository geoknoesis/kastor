package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import java.time.Duration

/** One monotonic, cooperative budget shared by preparation and evaluation. */
internal class ValidationBudget(
    timeout: Duration?,
    private val clock: () -> Long = System::nanoTime,
) {
    private val started = clock()
    private val allowed = timeout?.toNanos()
    private val limitMillis = timeout?.toMillis()

    /** Throws [ShaclValidationException] when the time is up or the thread was interrupted. */
    fun check(phase: String = "validation") {
        failure(phase, null)?.let { throw it }
    }

    /**
     * The failure [check] would throw, with [cause] attached (a query that the time limit stopped, say), or null when
     * there is time left. The message says which of the two ended the run, and names the time limit.
     */
    fun failure(phase: String, cause: Throwable?): ShaclValidationException? = when {
        Thread.currentThread().isInterrupted -> ShaclValidationException("SHACL $phase was cancelled: the thread was interrupted", cause)
        allowed != null && clock() - started >= allowed -> ShaclValidationException(
            "SHACL $phase timed out: it reached the validation time limit of $limitMillis ms (ValidationConfig.timeout)",
            cause,
        )
        else -> null
    }
    private var ticks = 0

    /**
     * Cheap cooperative check for hot loops: consults the clock and interrupt flag only every 64 calls.
     * Not thread-safe by design (one budget per validation run).
     */
    fun tick(phase: String = "validation") {
        if ((++ticks and 63) == 0) check(phase)
    }

    fun remainingNanos(): Long {
        check()
        return allowed?.let { (it - (clock() - started)).coerceAtLeast(1) } ?: Long.MAX_VALUE
    }
    fun snapshot(graph: RdfGraph, phase: String): List<RdfTriple> {
        check(phase)
        val triples = graph.getTriples()
        check(phase)
        return triples
    }
    companion object { val NONE = ValidationBudget(null) }
}
