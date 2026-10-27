package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BindingSet
import com.geoknoesis.kastor.rdf.Dataset
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfProviderException
import com.geoknoesis.kastor.rdf.RdfProviderRegistry
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlQueryable
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlIriSubstitution
import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Executes SHACL-SPARQL constraint queries.
 *
 * The native engine has no SPARQL processor of its own. When the data graph is the default graph of a
 * SPARQL-capable dataset supplied by the caller (e.g. an [RdfRepository] passed to `validateDataset`) **that has no
 * named graphs**, queries run against it **in place** (the provider's read scope), never copying or closing it. The
 * Kastor providers read the default graph only outside `GRAPH` (Jena and RDF4J alike), but a constraint query that
 * uses `GRAPH`, or a third-party provider with a union default graph, would still read named graphs, so a dataset
 * with named graphs is not queried in place: SHACL-SPARQL constraints must see exactly the data graph the other
 * constraints validate. Otherwise a SPARQL-capable Kastor provider with an
 * in-memory variant (`rdf-jena` or `rdf-rdf4j`) is required **at runtime**, and the data graph is copied into a
 * private repository once per validation run, shared by every SPARQL constraint of that run.
 *
 * The copy is deliberately not reused across runs: [RdfGraph] exposes no modification stamp, so a cached copy could
 * silently serve stale data. Callers validating a large graph repeatedly should validate a repository in place.
 *
 * The evaluator serves `sh:sparql` constraints, SPARQL-based constraint component validators and SPARQL node
 * expressions. Pre-binding is performed by [SparqlQueryTemplate] before a query reaches the provider. As in SHACL 1.2,
 * `$currentShape` and `$shapesGraph` are not pre-bound: queries see only the data graph.
 */
internal object SparqlConstraintEvaluator {

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

    private val log = LoggerFactory.getLogger(SparqlConstraintEvaluator::class.java)
    private val loggedFallbacks: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Logs each kind of fallback once per dataset class, so a silent change of evaluation strategy stays visible. */
    private fun logOnce(kind: String, dataset: SparqlQueryable, warn: Boolean, message: () -> String) {
        if (!loggedFallbacks.add("$kind:${dataset.javaClass.name}")) return
        if (warn) log.warn(message()) else log.info(message())
    }

    /**
     * Whether queries on [dataset] without a dataset clause see exactly its default graph. Kastor datasets carry no
     * provider guarantee about the default graph of such queries, so this only holds when no named graph is listed.
     */
    private fun queriesSeeOnlyDefaultGraph(dataset: SparqlQueryable): Boolean {
        val named = try {
            (dataset as? Dataset)?.listNamedGraphs()
        } catch (e: RuntimeException) {
            null
        }
        if (named != null && named.isEmpty()) return true
        logOnce("named-graphs", dataset, warn = false) {
            "SHACL-SPARQL constraints on ${dataset.javaClass.simpleName} run on a private copy of its default graph: " +
                "the dataset has (or does not list) named graphs, which a query without a dataset clause may see"
        }
        return false
    }

    /**
     * SPARQL access for one validation run, initialized only if a SPARQL constraint is evaluated.
     * [repositoryFactory] is an internal seam (tests simulate a classpath without a SPARQL provider).
     * Every query carries [functions], the SHACL 1.2 SPARQL functions of the shapes graph for this run;
     * [declaresFunctions] tells whether the shapes graph declares any (the per-run library is a new object, so it
     * cannot be compared with [SparqlFunctionLibrary.EMPTY]).
     */
    class Session(
        private val dataGraph: RdfGraph,
        private val repositoryFactory: () -> RdfRepository = defaultRepositoryFactory,
        inPlace: SparqlQueryable? = null,
        private val functions: SparqlFunctionLibrary = SparqlFunctionLibrary.EMPTY,
        declaresFunctions: Boolean = functions !== SparqlFunctionLibrary.EMPTY,
    ) : AutoCloseable {
        /**
         * Caller-owned view of the data graph, used while it supports bound, timed queries. Never used when the
         * shapes graph declares functions: a caller's dataset may be a store the provider cannot evaluate custom
         * functions on (an RDF4J repository over a store Kastor did not create), so queries run on the private copy.
         */
        private var inPlace: SparqlQueryable? =
            if (inPlace != null && !declaresFunctions && queriesSeeOnlyDefaultGraph(inPlace)) inPlace else null
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
                repo.transaction { editDefaultGraph().addTriples(dataGraph.getTriples()) }
            } catch (e: Throwable) {
                repo.close()
                throw e
            }
            copy = repo
            return repo
        }

        /**
         * Runs [query] and hands its solutions to [consume], which may stop reading them at any point: the result is
         * closed when [consume] returns or throws, so solutions it does not read are never computed or kept.
         */
        fun <T> select(
            query: String,
            bindings: Map<String, RdfTerm>,
            timeout: Duration,
            consume: (Sequence<BindingSet>) -> T,
        ): T = select(query, bindings, timeout, functions, null, consume)

        /** As [select], with the [substitution] the text was written by, which lets a provider parse its template once. */
        fun <T> select(
            query: String,
            bindings: Map<String, RdfTerm>,
            timeout: Duration,
            substitution: SparqlIriSubstitution?,
            consume: (Sequence<BindingSet>) -> T,
        ): T = select(query, bindings, timeout, functions, substitution, consume)

        /** As [select], with the function library [library] instead of the shapes graph's (e.g. empty for built-ins only). */
        fun <T> select(
            query: String,
            bindings: Map<String, RdfTerm>,
            timeout: Duration,
            library: SparqlFunctionLibrary,
            consume: (Sequence<BindingSet>) -> T,
        ): T = select(query, bindings, timeout, library, null, consume)

        private fun <T> select(
            query: String,
            bindings: Map<String, RdfTerm>,
            timeout: Duration,
            library: SparqlFunctionLibrary,
            substitution: SparqlIriSubstitution?,
            consume: (Sequence<BindingSet>) -> T,
        ): T {
            inPlace?.let { dataset ->
                try {
                    return run(dataset, query, bindings, timeout, library, substitution, consume)
                } catch (_: UnsupportedOperationException) {
                    logOnce("unsupported-bound-queries", dataset, warn = true) {
                        "${dataset.javaClass.simpleName} does not support bound, timed, sandboxed SPARQL queries: SHACL-SPARQL " +
                            "constraints fall back to a private copy of the data graph"
                    }
                    inPlace = null
                }
            }
            return run(copied(), query, bindings, timeout, library, substitution, consume)
        }

        private fun <T> run(
            target: SparqlQueryable,
            query: String,
            bindings: Map<String, RdfTerm>,
            timeout: Duration,
            library: SparqlFunctionLibrary,
            substitution: SparqlIriSubstitution?,
            consume: (Sequence<BindingSet>) -> T,
        ): T =
            try {
                // Queries come from the shapes graph: the provider refuses SERVICE and code loading on its own parse of them.
                target.withSelectRows(SandboxedSelectQuery(query, library, substitution), bindings, timeout, consume)
            } catch (e: ShaclValidationException) {
                throw e
            } catch (e: UnsupportedOperationException) {
                if (target === inPlace) throw e
                throw ShaclValidationException("SPARQL constraint failed: ${e.message}", e)
            } catch (e: Exception) {
                throw ShaclValidationException("SPARQL constraint failed: ${e.message}", e)
            }

        override fun close() {
            copy?.close()
        }
    }
}
