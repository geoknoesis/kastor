package com.geoknoesis.kastor.rdf

import java.io.Closeable
import java.util.IdentityHashMap

/**
 * Implementation of Dataset with optimization for same-repository graphs.
 *
 * **Optimization Strategy:**
 * 1. When every graph comes from one repository and can be named with FROM / FROM NAMED
 *    (default graphs are named graphs of that repository, named graphs keep their source name),
 *    queries are rewritten with dataset clauses and run in place (no materialization)
 * 2. A dataset whose only default graph is the repository's own default graph, with no named
 *    graphs, is queried in place. Queries without `GRAPH` run unchanged. Run as written, a `GRAPH` pattern
 *    would read the repository's named graphs, and no dataset clause can express "this store's default graph
 *    and no named graphs" (`FROM NAMED` alone empties the default graph, and the store's default graph has no
 *    IRI for `FROM`), so every `GRAPH` pattern is rewritten to an empty table that keeps its variables in scope and,
 *    where those variables can be listed, is not evaluated at all ([SparqlDatasetClauses.withoutNamedGraphs]);
 *    `GRAPH` inside `SERVICE` is left alone, because it addresses the remote endpoint's dataset. The store is never
 *    copied. A query that uses `GRAPH` and cannot be analysed (a `GRAPH` or `SERVICE` that is not followed by a name
 *    and a balanced group, an unterminated string, or codepoint escapes that change how the query reads, see below)
 *    is rejected with [IllegalArgumentException].
 * 3. Everything else - graphs from different repositories, untracked graphs, the store's
 *    default graph mixed with other graphs (it has no IRI, and any FROM clause would replace it), or graphs of a
 *    repository without a SPARQL engine (the graph-only `memory` provider) -
 *    is materialized into a temporary repository
 *
 * **Dataset clauses:** the dataset defines the default and named graphs, so queries that declare
 * their own `FROM` / `FROM NAMED` are rejected with [IllegalArgumentException] on every path.
 * (Run in place, such a query would read any graph of the source repository and escape the dataset;
 * materialized, it would see different graphs - so there is no consistent meaning to give it.)
 *
 * **Codepoint escapes:** the SPARQL grammar decodes `\uXXXX` / `\UXXXXXXXX` before it parses a query, while engines
 * (Jena among them) decode them inside strings and IRIs after tokenizing. Both readings are analysed:
 * - An escape inside a string, an IRI or a comment is left as written. Nearly always it changes nothing about where
 *   that token ends (`"""a\u000Ab"""`, `"\\u0041"`, `"a\u0022b"`), the two readings have the same tokens, and the
 *   query is sent exactly as the caller wrote it unless a rewrite is needed.
 * - An escape anywhere else can only be read by the grammar's rule. It may spell part of a name (`\u0047RAPH`); the
 *   query is then analysed, and sent, with those escapes decoded. Any other character there makes the query
 *   unanalysable.
 * - When the two readings have different tokens (`"a\u0022 # " GRAPH ...`: one string followed by `GRAPH`, or a
 *   shorter string followed by a comment), a keyword counts if either reading has it. Such a query still runs in
 *   place when no rewrite depends on the difference: on path 2 when neither reading has `GRAPH`, on path 1 when the
 *   readings agree up to the place where the dataset clauses are inserted. Otherwise it is unanalysable.
 *
 * A dataset on path 1 materializes an unanalysable query; on path 2 it is rejected.
 *
 * **Sandboxed queries:** a rewritten query keeps its kind ([SparqlQueries.retarget]). A [SandboxedSelectQuery] stays
 * one, with the same function library, so the provider still checks the text it runs; the materialized path passes the
 * query on unchanged. The timed `withSelectRows` is not supported here ([UnsupportedOperationException]).
 *
 * **DESCRIBE:** the Jena and RDF4J providers describe resources from the default graph of the query's dataset. What
 * an engine describes is implementation-defined, though, and another repository may describe from graphs outside
 * the query's dataset. Results of a `DESCRIBE` run in place are therefore restricted to triples that are in a graph
 * of this dataset, unless the repository is a [DescribesQueryDataset]. The query and the lookups run in one read
 * transaction of the repository (where it has them) and the result is complete when `describe` returns. The lookups
 * are one `find` per described subject and graph, or one `hasTriple` per triple for a subject with only a few
 * described triples. They compare blank nodes by identity, so on a store that labels blank nodes anew for every
 * request, described triples with blank nodes are dropped unless the repository is a [DescribesQueryDataset].
 *
 * **Cost of the materialized path:** every query copies all referenced graphs into a fresh in-memory
 * repository (one batched write per target graph inside a single transaction) and discards it
 * afterwards. The copy is not cached, because the source graphs can change between queries without
 * notification. Prefer datasets that qualify for strategy 1 or 2 for repeated querying of large graphs.
 */
internal class DatasetImpl(
    private val defaultGraphRefs: List<GraphRef>,
    private val namedGraphRefs: Map<Iri, GraphRef>
) : Dataset {

    private val scopes = DatasetGraphScopes(defaultGraphRefs + namedGraphRefs.values)
    override val defaultGraphs: List<RdfGraph> = defaultGraphRefs.map(scopes::graph)
    override val namedGraphs: Map<Iri, RdfGraph> = namedGraphRefs.mapValues { scopes.graph(it.value) }

    /** Creates the temporary repository used by the materialized path; replaceable for tests. */
    internal var materializationRepositoryFactory: () -> RdfRepository = { DatasetRepositories.create(namedGraphs.keys) }

    /** A dataset expressible as dataset clauses against a single repository. */
    private class QueryPlan(val repository: RdfRepository, val from: List<Iri>, val fromNamed: List<Iri>) {
        /** The dataset is the store's own default graph and no named graphs (no dataset clause can say that). */
        val storeDefaultGraphOnly: Boolean get() = from.isEmpty() && fromNamed.isEmpty()
    }

    private companion object {
        /** Fewest described triples of one subject that are checked with one `find` instead of one lookup each. */
        const val DESCRIBE_BATCH_THRESHOLD = 4
    }

    /** A query to run against the source repository itself. */
    private class InPlaceQuery(val repository: RdfRepository, val sparql: String)

    /**
     * The query to run in place for [queryText], or null if the dataset has to be materialized for it.
     * Rejects queries that declare their own dataset.
     */
    private fun inPlace(queryText: String): InPlaceQuery? {
        requireNoDatasetClauses(queryText)
        val plan = queryPlan ?: return null
        val rewritten = rewrite(queryText, plan) ?: return null
        return InPlaceQuery(plan.repository, rewritten)
    }

    /** null when the dataset must be materialized. */
    private val queryPlan: QueryPlan? by lazy { buildQueryPlan() }

    override val defaultGraph: RdfGraph by lazy {
        val repository = defaultGraphRefs.firstOrNull()?.sourceRepository
        val names = defaultGraphRefs.map { it.sourceGraphName }
        when {
            defaultGraphRefs.size == 1 -> defaultGraphs.first()
            repository != null && defaultGraphRefs.all { it.sourceRepository === repository } && names.none { it == null } &&
                defaultGraphs.zip(defaultGraphRefs).all { (graph, ref) -> graph === ref.getReferencedGraph() } ->
                OptimizedUnionGraph(repository, names.filterNotNull().distinct())
            else -> UnionGraph(defaultGraphs)
        }
    }

    override fun getNamedGraph(name: Iri): RdfGraph? = namedGraphs[name]

    override fun hasNamedGraph(name: Iri): Boolean = namedGraphRefs.containsKey(name)

    override fun listNamedGraphs(): List<Iri> = namedGraphRefs.keys.toList()

    override fun select(query: SparqlSelect): SparqlQueryResult {
        val inPlace = inPlace(query.sparql)
            ?: return executeOnMaterializedUnion { repo -> ListSparqlQueryResult(repo.select(query).toList()) }
        // The rewritten query keeps the kind of [query]: a SandboxedSelectQuery keeps its sandbox and function library.
        return inPlace.repository.select(SparqlQueries.retarget(query, inPlace.sparql))
    }

    override fun ask(query: SparqlAsk): Boolean {
        val inPlace = inPlace(query.sparql) ?: return executeOnMaterializedUnion { repo -> repo.ask(query) }
        return inPlace.repository.ask(SparqlQueries.retarget(query, inPlace.sparql))
    }

    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> {
        // Force the lazy result to a list before the union repo is closed; some providers tie the
        // sequence to a live connection.
        val inPlace = inPlace(query.sparql)
            ?: return executeOnMaterializedUnion { repo -> repo.construct(query).toList() }.asSequence()
        return inPlace.repository.construct(SparqlQueries.retarget(query, inPlace.sparql))
    }

    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> {
        val inPlace = inPlace(query.sparql)
            ?: return executeOnMaterializedUnion { repo -> repo.describe(query).toList() }.asSequence()
        val repository = inPlace.repository
        val toRun = SparqlQueries.retarget(query, inPlace.sparql)
        // What DESCRIBE returns is implementation-defined, and an engine may describe a resource from graphs of the
        // store that are not in the query's dataset (Jena's own describe handler reads the store's default graph and
        // every named graph of it, whatever the dataset clauses say; the Jena and RDF4J providers describe from the
        // default graph of the query's dataset): keep only triples that are in a graph of this dataset.
        fun run(): List<RdfTriple> {
            val described = repository.describe(toRun).toList()
            return if (repository is DescribesQueryDataset) described else withinDataset(described)
        }
        // One read transaction for the query and the lookups, so both see the same state of the store.
        var result: List<RdfTriple>? = null
        var entered = false
        try {
            repository.readTransaction {
                entered = true
                result = run()
            }
        } catch (e: UnsupportedOperationException) {
            // A repository without read transactions (a remote endpoint): there is no atomicity to be had.
            if (entered) throw e
            result = run()
        }
        return result.orEmpty().asSequence()
    }

    /**
     * The triples of [described] that are in a graph of this dataset, in their order. A subject with several
     * described triples costs one `find` per graph (and a `hasTriple` for each triple that `find` did not return, so
     * `hasTriple` stays the reference); a subject with only a few costs one `hasTriple` per triple.
     */
    private fun withinDataset(described: List<RdfTriple>): List<RdfTriple> {
        if (described.isEmpty()) return described
        val graphs = listOf(defaultGraph) + namedGraphRefs.values
        val confirmed = HashSet<RdfTriple>()
        val bySubject = described.groupByTo(LinkedHashMap()) { it.subject }
        for (graph in graphs) {
            for ((subject, triples) in bySubject) {
                val open = triples.filterNot { it in confirmed }.distinct()
                if (open.isEmpty()) continue
                val unseen = if (open.size < DESCRIBE_BATCH_THRESHOLD) open else {
                    val present = graph.find(subject, null, null).toHashSet()
                    open.filterNot { triple -> (triple in present).also { if (it) confirmed.add(triple) } }
                }
                unseen.filterTo(confirmed) { graph.hasTriple(it) }
            }
        }
        return described.filter { it in confirmed }
    }

    override fun close() {
        val seen = IdentityHashMap<RdfGraph, Boolean>()
        var failure: Throwable? = null
        for (ref in defaultGraphRefs.asSequence() + namedGraphRefs.values.asSequence()) {
            val graph = ref.getReferencedGraph()
            if (seen.put(graph, true) != null || graph !is Closeable) continue
            try {
                graph.close()
            } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun requireNoDatasetClauses(queryText: String) {
        require(!SparqlDatasetClauses.declaresDataset(queryText)) {
            "Queries executed against a Dataset must not declare FROM or FROM NAMED clauses: the dataset already " +
                "defines the default and named graphs. Remove the dataset clauses, or query the source repository directly."
        }
    }

    private fun buildQueryPlan(): QueryPlan? {
        val repository = defaultGraphRefs.firstOrNull()?.sourceRepository ?: return null
        // A repository without a SPARQL engine cannot run the query in place: its graphs are read through the graph
        // API and queried in the temporary repository, as the graphs of several repositories are.
        if (repository is com.geoknoesis.kastor.rdf.provider.MemoryRepository) return null
        if (defaultGraphRefs.any { it.sourceRepository !== repository }) return null
        if (namedGraphRefs.any { (name, ref) -> ref.sourceRepository !== repository || ref.sourceGraphName != name }) {
            return null
        }
        val names = defaultGraphRefs.map { it.sourceGraphName }
        val clauseSupport = repository as? DatasetClauseSupport
        if (clauseSupport != null &&
            (names.filterNotNull() + namedGraphRefs.keys).any { !clauseSupport.supportsDatasetGraph(it) }
        ) return null
        return when {
            names.none { it == null } ->
                QueryPlan(repository, names.filterNotNull().distinct(), namedGraphRefs.keys.toList())
            names.all { it == null } && namedGraphRefs.isEmpty() -> QueryPlan(repository, emptyList(), emptyList())
            else -> null
        }
    }

    /**
     * Returns the query text to send to the plan's repository - [queryText] itself when nothing has to change - or
     * null if the query cannot be analysed (the caller then materializes). Callers reject queries with their own
     * dataset clauses first.
     */
    private fun rewrite(queryText: String, plan: QueryPlan): String? {
        if (plan.storeDefaultGraphOnly) {
            // The dataset has no named graphs, but run in place GRAPH would read every named graph of the
            // repository. Dataset clauses cannot express "the store's default graph, no named graphs": FROM NAMED
            // without FROM makes the default graph empty (SPARQL 1.1 section 13.2), and the store's default graph
            // has no IRI to name in a FROM clause. Copying the store instead would be uncached, network-heavy for
            // remote stores and need an in-memory provider, so the GRAPH patterns are rewritten to match nothing.
            // A query without GRAPH comes back as it is.
            return SparqlDatasetClauses.withoutNamedGraphs(queryText) ?: throw IllegalArgumentException(
                "The query cannot be analysed by Kastor: GRAPH (and SERVICE) must be followed by a variable or IRI " +
                    "and a { ... } group, strings must be terminated, and codepoint escapes (\\uXXXX) must not " +
                    "change where a string, an IRI or a comment ends; groups may nest at most " +
                    "${SparqlDatasetClauses.MAX_GROUP_DEPTH} deep. This dataset has no named graphs, so GRAPH " +
                    "patterns must match nothing; fix the query or query the source repository directly."
            )
        }
        val clauses = (plan.from.map { "FROM <${it.value}>" } + plan.fromNamed.map { "FROM NAMED <${it.value}>" })
            .joinToString("\n")
        return SparqlDatasetClauses.insert(queryText, clauses)
    }

    // Materialized execution fallback

    /**
     * Copies the dataset's graphs into [repo]: one batched write per target graph, in one transaction
     * (a per-triple write would be a separate transaction on transactional providers).
     */
    private fun materializeGraphs(repo: RdfRepository) {
        repo.transaction {
            repo.editDefaultGraph().addTriples(
                this@DatasetImpl.defaultGraphs.asSequence().flatMap { it.getTriplesSequence() }
            )
            this@DatasetImpl.namedGraphs.forEach { (name, graph) ->
                repo.editGraph(name).addTriples(graph.getTriplesSequence())
            }
        }
    }

    /**
     * Execute on a materialized union with proper resource management. [execute] must fully
     * materialize its result before returning.
     */
    private fun <T> executeOnMaterializedUnion(execute: (RdfRepository) -> T): T {
        return materializationRepositoryFactory().use { unionRepo ->
            checkMaterializationSupport(unionRepo)
            materializeGraphs(unionRepo)
            execute(unionRepo)
        }
    }

    private fun checkMaterializationSupport(repo: RdfRepository) {
        if (!DatasetRepositories.supports(repo, namedGraphs.keys)) throw UnsupportedOperationException(
            "The query provider reserves a selected named graph IRI. Add a compatible in-memory SPARQL provider " +
                "(for example rdf-jena) to query this dataset without changing its graph identities.",
        )
    }

}

/**
 * Union of named graphs of one repository. All reads use the graph API of each member graph (no
 * SPARQL), so the union works on graph-only providers such as `memory`, and terms (blank nodes,
 * boolean and directional literals, triple terms) are matched exactly. Triples in several member
 * graphs are reported once, in first-seen order.
 */
internal class OptimizedUnionGraph(
    private val repository: RdfRepository,
    private val graphNames: List<Iri>
) : RdfGraph {

    override fun hasTriple(triple: RdfTriple): Boolean = graphNames.any { repository.getGraph(it).hasTriple(triple) }

    override fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> =
        graphNames.flatMap { repository.getGraph(it).find(subject, predicate, obj) }.distinct()

    override fun getTriples(): List<RdfTriple> {
        val union = LinkedHashSet<RdfTriple>()
        graphNames.forEach { union.addAll(repository.getGraph(it).getTriples()) }
        return union.toList()
    }

    override fun getTriplesSequence(): Sequence<RdfTriple> = sequence {
        val seen = HashSet<RdfTriple>()
        for (name in graphNames) {
            for (triple in repository.getGraph(name).getTriplesSequence()) {
                if (seen.add(triple)) yield(triple)
            }
        }
    }

    override fun size(): Int = if (graphNames.size == 1) repository.getGraph(graphNames[0]).size() else getTriples().size
}

/**
 * Materialized union graph (fallback when optimization not possible).
 */
internal class UnionGraph(private val graphs: List<RdfGraph>) : RdfGraph {
    override fun hasTriple(triple: RdfTriple): Boolean {
        return graphs.any { it.hasTriple(triple) }
    }

    override fun getTriples(): List<RdfTriple> {
        val deduped = LinkedHashSet<RdfTriple>()
        graphs.forEach { graph -> deduped.addAll(graph.getTriples()) }
        return deduped.toList()
    }

    override fun getTriplesSequence(): Sequence<RdfTriple> {
        return sequence {
            val seen = HashSet<RdfTriple>()
            graphs.asSequence().flatMap { it.getTriplesSequence() }.forEach { triple ->
                if (seen.add(triple)) {
                    yield(triple)
                }
            }
        }
    }

    override fun size(): Int {
        return getTriples().size
    }
}
