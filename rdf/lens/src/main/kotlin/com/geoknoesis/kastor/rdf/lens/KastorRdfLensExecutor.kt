package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfResource
import org.geoknoesis.lens.LensRdfLoader
import org.geoknoesis.lens.NetworkLensCompiler
import org.geoknoesis.lens.SparqlCompiler

/**
 * Public entry point for ontology-authored lens execution using Kastor providers.
 *
 * Definitions are supplied as a trusted, already-loaded RDF graph. This does not
 * dereference imports or remotely load definitions. The application's authorization
 * layer MUST restrict data access before calling this facade.
 */
class KastorRdfLensExecutor(
    private val repository: RdfRepository,
    private val definitions: RdfGraph
) {
    sealed interface Result {
        data class Nodes(val nodes: Set<com.geoknoesis.kastor.rdf.Iri>) : Result
        data class Network(val graph: KastorLensProviderBridge.Graph) : Result
    }

    fun execute(
        lensIri: RdfResource,
        limits: KastorLensProviderBridge.Limits = KastorLensProviderBridge.Limits()
    ): Result {
        val loaded = LensRdfLoader(definitions).read(lensIri)
        val bridge = KastorLensProviderBridge(repository)
        return when (loaded) {
            is LensRdfLoader.Loaded.Node ->
                Result.Nodes(bridge.nodes(SparqlCompiler().select(loaded.expression), limits))
            is LensRdfLoader.Loaded.Network -> {
                val queries = NetworkLensCompiler().compile(loaded.expression)
                Result.Network(bridge.network(queries.nodes, queries.edges, limits))
            }
        }
    }
}
