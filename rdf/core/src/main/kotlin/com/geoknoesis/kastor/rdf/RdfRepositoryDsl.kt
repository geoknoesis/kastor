package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.dsl.TripleDsl

/**
 * DSL entrypoint for adding triples to a repository's default graph.
 */
fun RdfRepository.add(configure: TripleDsl.() -> Unit) {
    val dsl = TripleDsl().apply(configure)
    editDefaultGraph().addTriples(dsl.triples)
}

fun RdfRepository.addTriple(triple: RdfTriple) {
    editDefaultGraph().addTriple(triple)
}

fun RdfRepository.addTriple(subject: RdfResource, predicate: Iri, obj: RdfTerm) {
    editDefaultGraph().addTriple(RdfTriple(subject, predicate, obj))
}

fun RdfRepository.addTriple(graphName: Iri?, triple: RdfTriple) {
    if (graphName == null) {
        editDefaultGraph().addTriple(triple)
    } else {
        if (!hasGraph(graphName)) {
            createGraph(graphName)
        }
        editGraph(graphName).addTriple(triple)
    }
}

fun RdfRepository.addTriples(graphName: Iri?, triples: Collection<RdfTriple>) {
    if (graphName == null) {
        editDefaultGraph().addTriples(triples)
    } else {
        if (!hasGraph(graphName)) {
            createGraph(graphName)
        }
        editGraph(graphName).addTriples(triples)
    }
}

fun RdfRepository.addTriples(triples: Collection<RdfTriple>) {
    editDefaultGraph().addTriples(triples)
}

fun RdfRepository.removeTriple(triple: RdfTriple): Boolean {
    return editDefaultGraph().removeTriple(triple)
}

fun RdfRepository.removeTriples(triples: Collection<RdfTriple>): Boolean {
    return editDefaultGraph().removeTriples(triples)
}

fun RdfRepository.hasTriple(triple: RdfTriple): Boolean = defaultGraph.hasTriple(triple)

fun RdfRepository.addToGraph(name: Iri, configure: TripleDsl.() -> Unit) {
    val dsl = TripleDsl().apply(configure)
    if (!hasGraph(name)) {
        createGraph(name)
    }
    editGraph(name).addTriples(dsl.triples)
}

fun RdfRepository.getTriples(): List<RdfTriple> = defaultGraph.getTriples()

/**
 * DSL entrypoint for adding triples via a graph editor.
 */
fun GraphEditor.add(configure: TripleDsl.() -> Unit) {
    val dsl = TripleDsl().apply(configure)
    addTriples(dsl.triples)
}

