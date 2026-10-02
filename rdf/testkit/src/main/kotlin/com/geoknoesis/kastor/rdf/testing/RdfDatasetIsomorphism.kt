package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.GraphIsomorphismLimitException
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.isIsomorphicTo
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import java.time.Duration

/**
 * Blank-node-aware RDF **dataset** isomorphism, with a budget.
 *
 * Comparing a dataset graph by graph is not enough: a blank node that occurs in two graphs of one dataset
 * must map to one blank node occurring in the corresponding two graphs of the other. Both datasets are
 * therefore compared as sets of quads (graph name as the fourth position) with a single isomorphism:
 * every quad becomes one triple that carries the graph name and, as a triple term, the triple, and the two
 * graphs of such triples are compared by Kastor's bounded isomorphism check ([isIsomorphicTo]). Blank-node
 * identity across graphs is taken from the blank node ids the graphs expose.
 *
 * **The check is bounded**, like [RdfGraphIsomorphism]: when its work budget or its wall-clock limit is used up
 * (or the thread is interrupted) it throws [GraphIsomorphismLimitException] - no answer is known then.
 */
object RdfDatasetIsomorphism {

    /**
     * True if both datasets contain the same quads up to one consistent blank node relabelling.
     *
     * @param expected graph name to graph; the `null` key is the default graph
     * @param actual graph name to graph; the `null` key is the default graph
     * @throws GraphIsomorphismLimitException if the check was stopped by a limit before an answer was found
     */
    fun isIsomorphic(expected: Map<Iri?, RdfGraph>, actual: Map<Iri?, RdfGraph>): Boolean =
        boundedIsomorphism("datasets") { quads(expected).isIsomorphicTo(quads(actual)) }

    /**
     * [isIsomorphic] with explicit limits: [maxWork] `null` scales with the size of the datasets, [timeout] `null`
     * means no wall-clock limit.
     *
     * @throws GraphIsomorphismLimitException if the check was stopped by a limit before an answer was found
     */
    fun isIsomorphic(expected: Map<Iri?, RdfGraph>, actual: Map<Iri?, RdfGraph>, maxWork: Long?, timeout: Duration?): Boolean =
        boundedIsomorphism("datasets") { quads(expected).isIsomorphicTo(quads(actual), maxWork, timeout) }

    /** True if the default graphs and all named graphs of both repositories form isomorphic datasets. */
    fun isIsomorphic(expected: RdfRepository, actual: RdfRepository): Boolean =
        isIsomorphic(graphsOf(expected), graphsOf(actual))

    private fun graphsOf(repository: RdfRepository): Map<Iri?, RdfGraph> =
        mapOf<Iri?, RdfGraph>(null to repository.defaultGraph) + repository.listGraphs().associateWith { repository.getGraph(it) }

    /** The subject of the triples that stand for the quads of the default graph. */
    private val DEFAULT_GRAPH = Iri("urn:x-kastor:testkit:default-graph")

    /** The predicates of the triples that stand for a quad: `graph inNamedGraph <<( s p o )>>`. */
    private val IN_DEFAULT_GRAPH = Iri("urn:x-kastor:testkit:inDefaultGraph")
    private val IN_NAMED_GRAPH = Iri("urn:x-kastor:testkit:inNamedGraph")

    /**
     * One triple per quad. The default graph has a predicate of its own, so a named graph that happens to have the
     * IRI of [DEFAULT_GRAPH] is not taken for it.
     */
    private fun quads(graphs: Map<Iri?, RdfGraph>): RdfGraph {
        val triples = ArrayList<RdfTriple>()
        for ((name, graph) in graphs) {
            val subject = name ?: DEFAULT_GRAPH
            val predicate = if (name == null) IN_DEFAULT_GRAPH else IN_NAMED_GRAPH
            for (triple in graph.getTriples()) triples.add(RdfTriple(subject, predicate, TripleTerm(triple)))
        }
        return MemoryGraph(triples)
    }
}
