package com.geoknoesis.kastor.rdf

import java.util.IdentityHashMap

/** One blank-node namespace per independent source, shared by every view and query of a dataset. */
internal class DatasetGraphScopes(refs: List<GraphRef>) {
    private val sources = IdentityHashMap<Any, Boolean>()
    private val scopes = BlankNodeScopes()

    init {
        refs.forEach { sources[it.sourceRepository ?: it.getReferencedGraph()] = true }
    }

    fun graph(ref: GraphRef): RdfGraph =
        if (sources.size <= 1) ref.getReferencedGraph() else scopes.graph(ref)
}
