package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple

/**
 * Symmetric difference using exact [RdfTriple] equality (including blank node ids).
 * Useful for debugging when you expect identical blank node identities, or alongside
 * [RdfGraphIsomorphism] to show why two graphs differ at the literal triple level.
 */
data class StrictGraphDiff(
    val onlyInLeft: List<RdfTriple>,
    val onlyInRight: List<RdfTriple>,
) {
    val isEmpty: Boolean get() = onlyInLeft.isEmpty() && onlyInRight.isEmpty()
}

fun strictGraphDiff(left: RdfGraph, right: RdfGraph): StrictGraphDiff {
    val ls = left.getTriples().toSet()
    val rs = right.getTriples().toSet()
    val cmp = compareBy<RdfTriple> { it.subject.toString() }
        .thenBy { it.predicate.value }
        .thenBy { it.obj.toString() }
    return StrictGraphDiff(
        onlyInLeft = (ls - rs).sortedWith(cmp),
        onlyInRight = (rs - ls).sortedWith(cmp),
    )
}
