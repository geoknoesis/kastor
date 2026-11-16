package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfResource
import org.geoknoesis.lens.CompiledNetwork
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

    /**
     * A lens loaded and compiled once. Reuse it to run the same definition
     * repeatedly without reloading, re-optimizing or re-validating it. It is a
     * snapshot: later changes to the definitions graph are not picked up.
     */
    class Prepared internal constructor(
        private val bridge: KastorLensProviderBridge,
        private val nodeQuery: String?,
        private val network: CompiledNetwork?
    ) {
        fun execute(
            limits: KastorLensProviderBridge.Limits = KastorLensProviderBridge.Limits()
        ): Result =
            if (network == null) {
                Result.Nodes(bridge.nodes(limited(checkNotNull(nodeQuery), limits.maxNodes), limits))
            } else {
                Result.Network(bridge.network(
                    limited(network.nodes, limits.maxNodes),
                    limited(network.edges, limits.maxEdges),
                    limits
                ))
            }

        /**
         * Push the bound to the provider so it can stop early instead of sorting
         * a full result. One extra row keeps overflow detection fail-closed.
         */
        private fun limited(sparql: String, max: Int): String = sparql + "\nLIMIT " + (max + 1)
    }

    /** Validate the entire definition graph with trusted SHACL shapes, then compile once. */
    fun prepareValidated(
        lensIri: RdfResource,
        shapes: RdfGraph = LensDefinitionShaclValidation.packagedShapes()
    ): Prepared {
        LensDefinitionShaclValidation.validateOrThrow(definitions, shapes)
        return prepare(lensIri)
    }

    fun prepare(lensIri: RdfResource): Prepared {
        val bridge = KastorLensProviderBridge(repository)
        return when (val loaded = LensRdfLoader(definitions).read(lensIri)) {
            is LensRdfLoader.Loaded.Node ->
                Prepared(bridge, SparqlCompiler().select(loaded.expression), null)
            is LensRdfLoader.Loaded.Network ->
                Prepared(bridge, null, NetworkLensCompiler().compile(loaded.expression))
        }
    }

    /** Validate the entire definition graph with trusted SHACL shapes before execution. */
    fun executeValidated(
        lensIri: RdfResource,
        shapes: RdfGraph = LensDefinitionShaclValidation.packagedShapes(),
        limits: KastorLensProviderBridge.Limits = KastorLensProviderBridge.Limits()
    ): Result = prepareValidated(lensIri, shapes).execute(limits)

    fun execute(
        lensIri: RdfResource,
        limits: KastorLensProviderBridge.Limits = KastorLensProviderBridge.Limits()
    ): Result = prepare(lensIri).execute(limits)
}
