package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.dsl.TripleDsl

/**
 * Convenience extensions for working with repository graphs.
 * Kept out of the core interface to preserve a minimal contract.
 */
fun RdfRepository.add(configure: TripleDsl.() -> Unit) {
    val dsl = TripleDsl().apply(configure)
    editDefaultGraph().addTriples(dsl.triples)
}

fun RdfRepository.addToGraph(graphName: Iri, configure: TripleDsl.() -> Unit) {
    val dsl = TripleDsl().apply(configure)
    editGraph(graphName).addTriples(dsl.triples)
}

fun RdfRepository.addTriple(triple: RdfTriple) {
    editDefaultGraph().addTriple(triple)
}

fun RdfRepository.addTriples(triples: Collection<RdfTriple>) {
    editDefaultGraph().addTriples(triples)
}

fun RdfRepository.addTriple(graphName: Iri?, triple: RdfTriple) {
    if (graphName == null) {
        editDefaultGraph().addTriple(triple)
    } else {
        editGraph(graphName).addTriple(triple)
    }
}

fun RdfRepository.removeTriple(triple: RdfTriple): Boolean {
    return editDefaultGraph().removeTriple(triple)
}

fun RdfRepository.removeTriples(triples: Collection<RdfTriple>): Boolean {
    return editDefaultGraph().removeTriples(triples)
}

fun RdfRepository.hasTriple(triple: RdfTriple): Boolean {
    return defaultGraph.hasTriple(triple)
}

fun RdfRepository.getTriples(): List<RdfTriple> {
    return defaultGraph.getTriples()
}







