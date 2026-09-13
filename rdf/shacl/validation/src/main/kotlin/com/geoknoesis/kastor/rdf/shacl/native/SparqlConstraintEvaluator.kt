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
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import java.time.Duration

/**
 * Executes SHACL-SPARQL constraint queries.
 *
 * The native engine has no SPARQL processor of its own: it needs a SPARQL-capable Kastor provider with an
 * in-memory variant (`rdf-jena` or `rdf-rdf4j`) **at runtime**. Queries run with the **data graph** as default
 * graph. When a query references `$shapesGraph`, the shapes graph is loaded as the named graph
 * [SHAPES_GRAPH_IRI] and `$shapesGraph` is pre-bound to that IRI (so `GRAPH $shapesGraph { … }` works).
 * `$this` and `$currentShape` are pre-bound through the provider's initial-binding support.
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
     * One provider repository per validation run, initialized only if a SPARQL constraint is evaluated.
     * [repositoryFactory] is an internal seam (tests simulate a classpath without a SPARQL provider).
     */
    class Session(
        dataGraph: RdfGraph,
        shapesTriples: List<RdfTriple> = emptyList(),
        repositoryFactory: () -> RdfRepository = defaultRepositoryFactory,
    ) : AutoCloseable {
        private val repo: RdfRepository =
            try {
                repositoryFactory()
            } catch (e: RdfProviderException) {
                throw ShaclValidationException(MISSING_ENGINE, e)
            }

        init {
            try {
                repo.transaction {
                    editDefaultGraph().addTriples(dataGraph.getTriples())
                    if (shapesTriples.isNotEmpty()) editGraph(SHAPES_GRAPH_IRI).addTriples(shapesTriples)
                }
            } catch (e: Throwable) {
                repo.close()
                throw e
            }
        }

        fun select(query: String, bindings: Map<String, RdfTerm>, timeout: Duration): List<BindingSet> =
            try {
                repo.withSelectRows(SparqlSelectQuery(query), bindings, timeout) { it.toList() }
            } catch (e: ShaclValidationException) {
                throw e
            } catch (e: Exception) {
                throw ShaclValidationException("SPARQL constraint failed: ${e.message}", e)
            }

        fun selectReturnsRows(query: String, focus: RdfTerm?, timeout: Duration): Boolean =
            select(query, if (focus == null) emptyMap() else mapOf("this" to focus), timeout).isNotEmpty()

        override fun close() = repo.close()
    }

    fun selectReturnsRows(query: String, dataGraph: RdfGraph, focusNode: RdfTerm? = null): Boolean =
        Session(dataGraph).use { it.selectReturnsRows(query, focusNode, Duration.ofMinutes(5)) }
}
