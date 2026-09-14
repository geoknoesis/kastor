package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BindingSet
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfProviderException
import com.geoknoesis.kastor.rdf.RdfProviderRegistry
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlQueryable
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import java.time.Duration

/**
 * Executes SHACL-SPARQL constraint queries.
 *
 * The native engine has no SPARQL processor of its own. When the data graph is the default graph of a
 * SPARQL-capable dataset supplied by the caller (e.g. an [RdfRepository] passed to `validateDataset`), queries run
 * against it **in place** (the provider's read scope), never copying or closing it. Otherwise a SPARQL-capable
 * Kastor provider with an in-memory variant (`rdf-jena` or `rdf-rdf4j`) is required **at runtime**, and the data
 * graph is copied into a private repository once per validation run, shared by every SPARQL constraint of that run.
 *
 * When a query references `$shapesGraph`, the shapes graph is loaded as the named graph [SHAPES_GRAPH_IRI] of the
 * private copy (the caller's dataset is never modified) and `$shapesGraph` is pre-bound to that IRI. Pre-binding
 * itself is performed by [SparqlQueryTemplate] before a query reaches the provider.
 */
internal object SparqlConstraintEvaluator {

    val SHAPES_GRAPH_IRI = Iri("urn:x-kastor:shacl:shapesGraph")

    private const val MISSING_ENGINE =
        "SHACL-SPARQL constraints (sh:sparql) require a SPARQL-capable RDF provider at runtime. " +
            "Add 'com.geoknoesis.kastor:rdf-jena' or 'com.geoknoesis.kastor:rdf-rdf4j' to the runtime classpath."

    /** Whether a SPARQL engine usable for SHACL-SPARQL is registered on the classpath. */
    fun engineAvailable(): Boolean =
        try {
            RdfProviderRegistry.supportsVariant("jena", "memory") || RdfProviderRegistry.supportsVariant("rdf4j", "memory")
        } catch (_: Exception) {
            false
        }

    /** Default repository factory: an in-memory repository of a SPARQL-capable provider. */
    val defaultRepositoryFactory: () -> RdfRepository = { Rdf.memory() }

    /**
     * SPARQL access for one validation run, initialized only if a SPARQL constraint is evaluated.
     * [repositoryFactory] is an internal seam (tests simulate a classpath without a SPARQL provider).
     */
    class Session(
        private val dataGraph: RdfGraph,
        private val shapesTriples: List<RdfTriple> = emptyList(),
        private val repositoryFactory: () -> RdfRepository = defaultRepositoryFactory,
        inPlace: SparqlQueryable? = null,
    ) : AutoCloseable {
        /** Caller-owned view of the data graph, used while it supports bound, timed queries. */
        private var inPlace: SparqlQueryable? = if (shapesTriples.isEmpty()) inPlace else null
        private var copy: RdfRepository? = null

        private fun copied(): RdfRepository {
            copy?.let { return it }
            val repo =
                try {
                    repositoryFactory()
                } catch (e: RdfProviderException) {
                    throw ShaclValidationException(MISSING_ENGINE, e)
                }
            try {
                repo.transaction {
                    editDefaultGraph().addTriples(dataGraph.getTriples())
                    if (shapesTriples.isNotEmpty()) editGraph(SHAPES_GRAPH_IRI).addTriples(shapesTriples)
                }
            } catch (e: Throwable) {
                repo.close()
                throw e
            }
            copy = repo
            return repo
        }

        fun select(query: String, bindings: Map<String, RdfTerm>, timeout: Duration): List<BindingSet> {
            inPlace?.let { dataset ->
                try {
                    return run(dataset, query, bindings, timeout)
                } catch (_: UnsupportedOperationException) {
                    inPlace = null // this dataset cannot run bound, timed queries: fall back to a private copy
                }
            }
            return run(copied(), query, bindings, timeout)
        }

        private fun run(target: SparqlQueryable, query: String, bindings: Map<String, RdfTerm>, timeout: Duration): List<BindingSet> =
            try {
                target.withSelectRows(SparqlSelectQuery(query), bindings, timeout) { it.toList() }
            } catch (e: ShaclValidationException) {
                throw e
            } catch (e: UnsupportedOperationException) {
                if (target === inPlace) throw e
                throw ShaclValidationException("SPARQL constraint failed: ${e.message}", e)
            } catch (e: Exception) {
                throw ShaclValidationException("SPARQL constraint failed: ${e.message}", e)
            }

        fun selectReturnsRows(query: String, focus: RdfTerm?, timeout: Duration): Boolean =
            select(query, if (focus == null) emptyMap() else mapOf("this" to focus), timeout).isNotEmpty()

        override fun close() {
            copy?.close()
        }
    }

    fun selectReturnsRows(query: String, dataGraph: RdfGraph, focusNode: RdfTerm? = null): Boolean =
        Session(dataGraph).use { it.selectReturnsRows(query, focusNode, Duration.ofMinutes(5)) }
}
