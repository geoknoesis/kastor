package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph

/**
 * DSL for creating standalone RDF graphs.
 * Provides all the same syntax options as [TripleDsl] (see [TripleBuilderDsl]) but builds a complete RdfGraph.
 */
class GraphDsl : TripleBuilderDsl<GraphDsl>() {

    // === GRAPH BUILDING ===

    /**
     * Build the final RdfGraph from the collected triples.
     */
    fun build(): MutableRdfGraph {
        return MemoryGraph(triples.toList())
    }
}
