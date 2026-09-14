package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import org.apache.jena.graph.NodeFactory
import org.apache.jena.rdf.model.Model
import org.apache.jena.sparql.core.DatasetGraph
import org.apache.jena.sparql.core.DatasetGraphFactory
import org.apache.jena.sparql.core.Quad
import org.apache.jena.sparql.util.IsoMatcher

/**
 * Blank-node-aware RDF **dataset** isomorphism.
 *
 * Comparing a dataset graph by graph is not enough: a blank node that occurs in two graphs of one dataset
 * must map to one blank node occurring in the corresponding two graphs of the other. Both datasets are
 * therefore compared as sets of quads (graph name as the fourth position) with a single isomorphism,
 * using Apache Jena's matcher. Blank-node identity across graphs is taken from the blank node ids the
 * graphs expose.
 */
object RdfDatasetIsomorphism {

    /**
     * True if both datasets contain the same quads up to one consistent blank node relabelling.
     *
     * @param expected graph name to graph; the `null` key is the default graph
     * @param actual graph name to graph; the `null` key is the default graph
     */
    fun isIsomorphic(expected: Map<Iri?, RdfGraph>, actual: Map<Iri?, RdfGraph>): Boolean {
        val copies = mutableListOf<Model>()
        try {
            return IsoMatcher.isomorphic(dataset(expected, copies), dataset(actual, copies))
        } finally {
            copies.forEach { it.close() }
        }
    }

    /** True if the default graphs and all named graphs of both repositories form isomorphic datasets. */
    fun isIsomorphic(expected: RdfRepository, actual: RdfRepository): Boolean =
        isIsomorphic(graphsOf(expected), graphsOf(actual))

    private fun graphsOf(repository: RdfRepository): Map<Iri?, RdfGraph> =
        mapOf<Iri?, RdfGraph>(null to repository.defaultGraph) + repository.listGraphs().associateWith { repository.getGraph(it) }

    private fun dataset(graphs: Map<Iri?, RdfGraph>, copies: MutableList<Model>): DatasetGraph {
        val dataset = DatasetGraphFactory.create()
        for ((name, graph) in graphs) {
            val copy = JenaBridge.copyToJenaModel(graph).also(copies::add)
            val graphNode = name?.let { NodeFactory.createURI(it.value) } ?: Quad.defaultGraphIRI
            copy.graph.find().forEachRemaining { dataset.add(Quad(graphNode, it)) }
        }
        return dataset
    }
}
