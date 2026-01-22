package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.dsl.TripleDsl

/**
 * Convenience extensions for working with repository graphs.
 * Kept out of the core interface to preserve a minimal contract.
 * 
 * **API Stability:** These extensions are stable and part of the public API.
 * They follow Kotlin stdlib patterns and are designed for long-term compatibility.
 */

/**
 * Adds triples to the default graph using DSL.
 * 
 * **API Stability:** Stable
 * 
 * @param configure DSL configuration block
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

/**
 * Get all triples from a specific graph or the default graph.
 * 
 * **Performance:** O(n) where n is the number of triples in the graph.
 * For large graphs, consider using SPARQL queries with filters instead.
 * 
 * @param graphName The name of the graph, or null for the default graph
 * @return List of all triples in the specified graph
 */
fun RdfRepository.getTriples(graphName: Iri?): List<RdfTriple> =
    if (graphName == null) defaultGraph.getTriples()
    else getGraph(graphName).getTriples()

/**
 * Check if a triple exists in a specific graph or the default graph.
 * 
 * **Performance:** O(1) for most implementations (hash-based lookup).
 * 
 * @param graphName The name of the graph, or null for the default graph
 * @param triple The triple to check
 * @return true if the triple exists, false otherwise
 */
fun RdfRepository.hasTriple(graphName: Iri?, triple: RdfTriple): Boolean =
    if (graphName == null) defaultGraph.hasTriple(triple)
    else getGraph(graphName).hasTriple(triple)







