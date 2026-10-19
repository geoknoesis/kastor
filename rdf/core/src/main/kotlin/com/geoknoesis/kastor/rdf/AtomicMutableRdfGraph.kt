package com.geoknoesis.kastor.rdf

/**
 * A graph that can apply a compound edit under one write transaction or exclusive lock.
 * Other readers cannot see intermediate changes; an escaping exception rolls the edit back.
 * Nested calls join the outer edit or repository transaction (no savepoint): a failure must escape
 * that outer block for rollback; catching it there retains its pending writes.
 * Implementations that cannot supply these guarantees must reject the call before running `operations`.
 */
interface AtomicMutableRdfGraph : MutableRdfGraph {
    fun mutateAtomically(operations: MutableRdfGraph.() -> Unit)
}
