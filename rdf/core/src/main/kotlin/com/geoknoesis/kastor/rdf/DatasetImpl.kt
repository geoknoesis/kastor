package com.geoknoesis.kastor.rdf

import java.io.Closeable

/**
 * Implementation of Dataset with optimization for same-repository graphs.
 *
 * **Optimization Strategy:**
 * 1. When every graph comes from one repository and can be named with FROM / FROM NAMED
 *    (default graphs are named graphs of that repository, named graphs keep their source name),
 *    queries are rewritten with dataset clauses and run in place (no materialization)
 * 2. A dataset whose only default graph is the repository's own default graph, with no named
 *    graphs, is queried unchanged - unless the query uses the `GRAPH` keyword: run in place it would
 *    see the repository's named graphs, so it is materialized instead (and sees no named graphs)
 * 3. Everything else - graphs from different repositories, untracked graphs, or the store's
 *    default graph mixed with other graphs (it has no IRI, and any FROM clause would replace it) -
 *    is materialized into a temporary repository
 *
 * **Dataset clauses:** the dataset defines the default and named graphs, so queries that declare
 * their own `FROM` / `FROM NAMED` are rejected with [IllegalArgumentException] on every path.
 * (Run in place, such a query would read any graph of the source repository and escape the dataset;
 * materialized, it would see different graphs - so there is no consistent meaning to give it.)
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

    override val defaultGraphs: List<RdfGraph> = defaultGraphRefs
    override val namedGraphs: Map<Iri, RdfGraph> = namedGraphRefs

    /** Creates the temporary repository used by the materialized path; replaceable for tests. */
    internal var materializationRepositoryFactory: () -> RdfRepository = { Rdf.memory() }

    /** A dataset expressible as dataset clauses against a single repository. */
    private class QueryPlan(val repository: RdfRepository, val from: List<Iri>, val fromNamed: List<Iri>)

    /** null when the dataset must be materialized. */
    private val queryPlan: QueryPlan? by lazy { buildQueryPlan() }

    override val defaultGraph: RdfGraph by lazy {
        val repository = defaultGraphRefs.firstOrNull()?.sourceRepository
        val names = defaultGraphRefs.map { it.sourceGraphName }
        when {
            defaultGraphRefs.size == 1 -> defaultGraphRefs.first().getReferencedGraph()
            repository != null && defaultGraphRefs.all { it.sourceRepository == repository } && names.none { it == null } ->
                OptimizedUnionGraph(repository, names.filterNotNull().distinct())
            else -> UnionGraph(defaultGraphRefs.map { it.getReferencedGraph() })
        }
    }

    override fun getNamedGraph(name: Iri): RdfGraph? = namedGraphRefs[name]?.getReferencedGraph()

    override fun hasNamedGraph(name: Iri): Boolean = namedGraphRefs.containsKey(name)

    override fun listNamedGraphs(): List<Iri> = namedGraphRefs.keys.toList()

    override fun select(query: SparqlSelect): SparqlQueryResult {
        requireNoDatasetClauses(query.sparql)
        val plan = queryPlan
        val rewritten = plan?.let { rewrite(query.sparql, it) }
        return if (plan != null && rewritten != null) {
            plan.repository.select(if (rewritten == query.sparql) query else SparqlSelectQuery(rewritten))
        } else {
            executeOnMaterializedUnion { repo -> ListSparqlQueryResult(repo.select(query).toList()) }
        }
    }

    override fun ask(query: SparqlAsk): Boolean {
        requireNoDatasetClauses(query.sparql)
        val plan = queryPlan
        val rewritten = plan?.let { rewrite(query.sparql, it) }
        return if (plan != null && rewritten != null) {
            plan.repository.ask(if (rewritten == query.sparql) query else SparqlAskQuery(rewritten))
        } else {
            executeOnMaterializedUnion { repo -> repo.ask(query) }
        }
    }

    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> {
        requireNoDatasetClauses(query.sparql)
        val plan = queryPlan
        val rewritten = plan?.let { rewrite(query.sparql, it) }
        return if (plan != null && rewritten != null) {
            plan.repository.construct(if (rewritten == query.sparql) query else SparqlConstructQuery(rewritten))
        } else {
            // Force the lazy result to a list before the union repo is closed; some providers tie the
            // sequence to a live connection.
            executeOnMaterializedUnion { repo -> repo.construct(query).toList() }.asSequence()
        }
    }

    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> {
        requireNoDatasetClauses(query.sparql)
        val plan = queryPlan
        val rewritten = plan?.let { rewrite(query.sparql, it) }
        return if (plan != null && rewritten != null) {
            plan.repository.describe(if (rewritten == query.sparql) query else SparqlDescribeQuery(rewritten))
        } else {
            executeOnMaterializedUnion { repo -> repo.describe(query).toList() }.asSequence()
        }
    }

    override fun close() {
        val graphsToClose = LinkedHashSet<RdfGraph>()
        defaultGraphRefs.forEach { graphsToClose.add(it.getReferencedGraph()) }
        namedGraphRefs.values.forEach { graphsToClose.add(it.getReferencedGraph()) }
        graphsToClose.forEach { graph ->
            if (graph is Closeable) graph.close()
        }
    }

    private fun requireNoDatasetClauses(queryText: String) {
        require(!SparqlDatasetClauses.declaresDataset(queryText)) {
            "Queries executed against a Dataset must not declare FROM or FROM NAMED clauses: the dataset already " +
                "defines the default and named graphs. Remove the dataset clauses, or query the source repository directly."
        }
    }

    private fun buildQueryPlan(): QueryPlan? {
        val repository = defaultGraphRefs.firstOrNull()?.sourceRepository ?: return null
        if (defaultGraphRefs.any { it.sourceRepository != repository }) return null
        if (namedGraphRefs.any { (name, ref) -> ref.sourceRepository != repository || ref.sourceGraphName != name }) {
            return null
        }
        val names = defaultGraphRefs.map { it.sourceGraphName }
        return when {
            names.none { it == null } ->
                QueryPlan(repository, names.filterNotNull().distinct(), namedGraphRefs.keys.toList())
            names.all { it == null } && namedGraphRefs.isEmpty() -> QueryPlan(repository, emptyList(), emptyList())
            else -> null
        }
    }

    /**
     * Returns the query text to send to the plan's repository, or null if the query cannot be analysed
     * (the caller then materializes). Callers reject queries with their own dataset clauses first.
     */
    private fun rewrite(queryText: String, plan: QueryPlan): String? {
        if (plan.from.isEmpty() && plan.fromNamed.isEmpty()) {
            // The dataset has no named graphs, but run in place GRAPH would read every named graph of the
            // repository. There is no provider-portable way to empty the named-graph set without also replacing
            // the store's default graph (FROM NAMED needs an IRI), so such queries are materialized.
            return if (SparqlDatasetClauses.usesGraphPattern(queryText)) null else queryText
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
                defaultGraphRefs.asSequence().flatMap { it.getReferencedGraph().getTriplesSequence() }
            )
            namedGraphRefs.forEach { (name, ref) ->
                repo.editGraph(name).addTriples(ref.getReferencedGraph().getTriplesSequence())
            }
        }
    }

    /**
     * Execute on a materialized union with proper resource management. [execute] must fully
     * materialize its result before returning.
     */
    private fun <T> executeOnMaterializedUnion(execute: (RdfRepository) -> T): T {
        val unionRepo = materializationRepositoryFactory()
        try {
            materializeGraphs(unionRepo)
            return execute(unionRepo)
        } finally {
            unionRepo.close()
        }
    }
}

/**
 * Inserts SPARQL dataset clauses (FROM / FROM NAMED) into a query at the grammatically correct
 * place: after the SELECT projection, CONSTRUCT template, DESCRIBE targets or ASK keyword, and
 * before the WHERE clause (SPARQL 1.1 section 13.2).
 *
 * The scanner is token-aware: string literals, IRIs and comments are skipped, only the top-level
 * query form is considered (sub-selects are left untouched), and `PREFIX :`, `BASE` and `VERSION`
 * prologue declarations are supported.
 */
internal object SparqlDatasetClauses {
    private enum class Kind { WORD, IRI, STRING, VAR, PUNCT }
    private class Token(val kind: Kind, val start: Int, val end: Int)

    private val SOLUTION_MODIFIERS = setOf("ORDER", "GROUP", "HAVING", "LIMIT", "OFFSET", "VALUES")

    /**
     * True if [query] contains a `FROM` keyword, i.e. declares `FROM` or `FROM NAMED` dataset clauses.
     * `FROM` inside strings, IRIs, comments, variables and prefixed names does not count; `FROM` is not
     * used by any other SPARQL construct.
     */
    fun declaresDataset(query: String): Boolean {
        val tokens = tokenize(query) ?: return false
        return tokens.any { it.kind == Kind.WORD && it.end - it.start == 4 && query.regionMatches(it.start, "FROM", 0, 4, ignoreCase = true) }
    }

    /**
     * True if [query] contains the `GRAPH` keyword (a graph pattern, or a graph reference in an update).
     * `GRAPH` inside strings, IRIs, comments, variables and prefixed names does not count. Unterminated
     * strings make the query unanalysable, which counts as using `GRAPH`.
     */
    fun usesGraphPattern(query: String): Boolean {
        val tokens = tokenize(query) ?: return true
        return tokens.any { it.kind == Kind.WORD && it.end - it.start == 5 && query.regionMatches(it.start, "GRAPH", 0, 5, ignoreCase = true) }
    }

    /**
     * @return the rewritten query; [query] itself if it already declares a dataset; or null if no
     *   insertion point could be found.
     */
    fun insert(query: String, clauses: String): String? {
        val tokens = tokenize(query) ?: return null
        fun word(t: Token) = if (t.kind == Kind.WORD) query.substring(t.start, t.end).uppercase() else null
        fun punct(t: Token, c: Char) = t.kind == Kind.PUNCT && query[t.start] == c

        var i = tokens.indexOfFirst { word(it) in setOf("SELECT", "CONSTRUCT", "DESCRIBE", "ASK") }
        if (i < 0 || tokens.subList(0, i).any { punct(it, '{') }) return null
        val form = word(tokens[i])!!
        i++
        if (form == "CONSTRUCT" && i < tokens.size && punct(tokens[i], '{')) {
            var depth = 0
            while (i < tokens.size) {
                if (punct(tokens[i], '{')) depth++
                if (punct(tokens[i], '}') && --depth == 0) break
                i++
            }
            if (i >= tokens.size) return null
            i++
        }
        var parens = 0
        var insertAt = -1
        while (i < tokens.size) {
            val t = tokens[i]
            val w = word(t)
            when {
                punct(t, '(') -> parens++
                punct(t, ')') -> parens--
                parens > 0 -> Unit
                w == "FROM" -> return query
                w == "WHERE" || punct(t, '{') -> { insertAt = t.start; break }
                form == "DESCRIBE" && w in SOLUTION_MODIFIERS -> { insertAt = t.start; break }
            }
            i++
        }
        if (insertAt < 0) {
            if (form != "DESCRIBE") return null
            insertAt = query.length
        }
        return query.substring(0, insertAt) + "\n" + clauses + "\n" + query.substring(insertAt)
    }

    /** Tokenizes enough of SPARQL to find top-level keywords; returns null on an unterminated string. */
    private fun tokenize(q: String): List<Token>? {
        val tokens = ArrayList<Token>()
        var i = 0
        val n = q.length
        while (i < n) {
            val c = q[i]
            when {
                c.isWhitespace() -> i++
                c == '#' -> while (i < n && q[i] != '\n' && q[i] != '\r') i++
                c == '"' || c == '\'' -> {
                    val start = i
                    val long = i + 2 < n && q[i + 1] == c && q[i + 2] == c
                    i += if (long) 3 else 1
                    var closed = false
                    while (i < n) {
                        val d = q[i]
                        if (d == '\\') { i += 2; continue }
                        if (long) {
                            if (d == c && i + 2 < n && q[i + 1] == c && q[i + 2] == c) { i += 3; closed = true; break }
                        } else {
                            if (d == c) { i++; closed = true; break }
                            if (d == '\n' || d == '\r') break
                        }
                        i++
                    }
                    if (!closed) return null
                    tokens.add(Token(Kind.STRING, start, i))
                }
                c == '<' -> {
                    var j = i + 1
                    while (j < n && q[j] > ' ' && q[j] !in "<>\"{}|^`\\") j++
                    if (j < n && q[j] == '>') {
                        tokens.add(Token(Kind.IRI, i, j + 1)); i = j + 1
                    } else {
                        tokens.add(Token(Kind.PUNCT, i, i + 1)); i++
                    }
                }
                (c == '?' || c == '$') && i + 1 < n && (q[i + 1].isLetterOrDigit() || q[i + 1] == '_') -> {
                    val start = i
                    i++
                    while (i < n && (q[i].isLetterOrDigit() || q[i] == '_')) i++
                    tokens.add(Token(Kind.VAR, start, i))
                }
                c.isLetterOrDigit() || c == '_' || c == ':' -> {
                    val start = i
                    while (i < n && (q[i].isLetterOrDigit() || q[i] in "_:-.%")) i++
                    tokens.add(Token(Kind.WORD, start, i))
                }
                else -> { tokens.add(Token(Kind.PUNCT, i, i + 1)); i++ }
            }
        }
        return tokens
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
