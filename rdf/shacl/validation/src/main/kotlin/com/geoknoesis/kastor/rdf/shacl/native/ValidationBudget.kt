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
    fun check(phase: String = "validation") {
        if (Thread.currentThread().isInterrupted || (allowed != null && clock() - started >= allowed)) {
            throw ShaclValidationException("SHACL $phase timed out or was cancelled")
        }
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
