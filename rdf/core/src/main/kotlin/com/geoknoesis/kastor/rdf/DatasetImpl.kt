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

    override val defaultGraphs: List<RdfGraph> = defaultGraphRefs
    override val namedGraphs: Map<Iri, RdfGraph> = namedGraphRefs

    /** Creates the temporary repository used by the materialized path; replaceable for tests. */
    internal var materializationRepositoryFactory: () -> RdfRepository = { Rdf.memory() }

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
        val inPlace = inPlace(query.sparql)
            ?: return executeOnMaterializedUnion { repo -> ListSparqlQueryResult(repo.select(query).toList()) }
        return inPlace.repository.select(if (inPlace.sparql == query.sparql) query else SparqlSelectQuery(inPlace.sparql))
    }

    override fun ask(query: SparqlAsk): Boolean {
        val inPlace = inPlace(query.sparql) ?: return executeOnMaterializedUnion { repo -> repo.ask(query) }
        return inPlace.repository.ask(if (inPlace.sparql == query.sparql) query else SparqlAskQuery(inPlace.sparql))
    }

    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> {
        // Force the lazy result to a list before the union repo is closed; some providers tie the
        // sequence to a live connection.
        val inPlace = inPlace(query.sparql)
            ?: return executeOnMaterializedUnion { repo -> repo.construct(query).toList() }.asSequence()
        return inPlace.repository.construct(if (inPlace.sparql == query.sparql) query else SparqlConstructQuery(inPlace.sparql))
    }

    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> {
        val inPlace = inPlace(query.sparql)
            ?: return executeOnMaterializedUnion { repo -> repo.describe(query).toList() }.asSequence()
        val repository = inPlace.repository
        val toRun = if (inPlace.sparql == query.sparql) query else SparqlDescribeQuery(inPlace.sparql)
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
        // A repository without a SPARQL engine cannot run the query in place: its graphs are read through the graph
        // API and queried in the temporary repository, as the graphs of several repositories are.
        if (repository is com.geoknoesis.kastor.rdf.provider.MemoryRepository) return null
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
                    "change where a string, an IRI or a comment ends. This dataset has no named graphs, so GRAPH " +
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
 * Analyses and rewrites the dataset-related parts of a SPARQL query: detects `FROM` and `GRAPH`, inserts dataset
 * clauses (FROM / FROM NAMED) at the grammatically correct place - after the SELECT projection, CONSTRUCT template,
 * DESCRIBE targets or ASK keyword, and before the WHERE clause (SPARQL 1.1 section 13.2) - and rewrites `GRAPH`
 * patterns for a dataset without named graphs.
 *
 * The scanner is token-aware: string literals, IRIs, comments, language tags and prefixed names (including escaped
 * characters in local names) are skipped, numbers and booleans are separated from a keyword that follows them
 * (`1.GRAPH`), only the top-level query form is considered when inserting (sub-selects are left untouched), and
 * `PREFIX :`, `BASE` and `VERSION` prologue declarations are supported.
 *
 * Codepoint escapes (`\uXXXX`, `\UXXXXXXXX`) are read both ways engines read them: decoded before tokenizing, as
 * the SPARQL grammar says (the "early" reading), and decoded inside strings and IRIs after tokenizing, as Jena does
 * (the "late" reading). See [canonical] and the notes on [DatasetImpl].
 */
internal object SparqlDatasetClauses {
    private enum class Kind { WORD, IRI, STRING, VAR, PUNCT }
    private class Token(val kind: Kind, val start: Int, val end: Int)

    private val SOLUTION_MODIFIERS = setOf("ORDER", "GROUP", "HAVING", "LIMIT", "OFFSET", "VALUES")

    /** Words, besides `a`, numbers and prefixed names, of a pattern whose written variables are all in scope. */
    private val SIMPLE_PATTERN_WORDS = setOf("OPTIONAL", "UNION", "GRAPH", "TRUE", "FALSE")

    /** A SPARQL codepoint escape: `\u` + 4 hex digits or `\U` + 8 hex digits. */
    private val CODEPOINT_ESCAPE = Regex("""\\(?:u[0-9A-Fa-f]{4}|U[0-9A-Fa-f]{8})""")

    /** Stands in the early reading for an escape above U+10FFFF, which has no character. */
    private val REPLACEMENT_CHARACTER = 0xFFFD.toChar()

    /**
     * A query in the two readings of its codepoint escapes.
     *
     * - [text] is the query with the escapes outside strings, IRIs and comments decoded (they spell parts of
     *   names), and [tokens] are its tokens when the remaining escapes stay inside their tokens: the late reading.
     * - [earlyText] is [text] with every escape decoded and [earlyTokens] are its tokens: the early reading. They
     *   are null when that text has an unterminated string, which no engine reading the query that way accepts, so
     *   only the late reading can run. [origin] maps an offset of [earlyText] to the offset of [text] it came from.
     *
     * For a query without escapes the two readings are the same object.
     */
    private class Analysis(
        val text: String,
        val tokens: List<Token>,
        val earlyText: String,
        val earlyTokens: List<Token>?,
        private val origin: IntArray?,
    ) {
        /** True if both readings have the same tokens before offset [before] of [text] (by default: everywhere). */
        fun agree(before: Int = Int.MAX_VALUE): Boolean {
            val early = earlyTokens ?: return true
            if (early === tokens) return true
            val map = origin ?: return false
            var k = 0
            while (k < tokens.size && tokens[k].start < before) {
                val late = tokens[k]
                val other = early.getOrNull(k) ?: return false
                if (other.kind != late.kind || map[other.start] != late.start || map[other.end] != late.end) return false
                k++
            }
            val next = early.getOrNull(k) ?: return true
            return map[next.start] >= before
        }

        /** True if either reading has [keyword] as a keyword token. */
        fun hasKeyword(keyword: String): Boolean =
            tokens.any { isKeyword(text, it, keyword) } ||
                (earlyTokens != null && earlyTokens !== tokens && earlyTokens.any { isKeyword(earlyText, it, keyword) })
    }

    /** The two readings of [query], or null if it has an unterminated string or an escape that cannot be placed. */
    private fun analyse(query: String): Analysis? {
        if (!CODEPOINT_ESCAPE.containsMatchIn(query)) {
            val tokens = tokenize(query) ?: return null
            return Analysis(query, tokens, query, tokens, null)
        }
        // 1. Escapes outside strings, IRIs and comments are decoded: only the grammar's rule can read them.
        val opaque = ArrayList<Token>()
        tokenize(query, opaque) ?: return null
        val text = decodeOutside(query, opaque) ?: return null
        opaque.clear()
        val tokens = tokenize(text, opaque) ?: return null
        val escapes = CODEPOINT_ESCAPE.findAll(text).toList()
        if (escapes.isEmpty()) return Analysis(text, tokens, text, tokens, null)
        // Decoding must not have put a new escape together (a decoded 'u' after a backslash).
        if (escapes.any { !inside(opaque, it.range) }) return null
        // 2. The early reading: every remaining escape decoded, with the way back to the offsets of the text.
        val early = StringBuilder(text.length)
        val origin = IntArray(text.length + 1)
        var copied = 0
        for (escape in escapes) {
            for (k in copied until escape.range.first) {
                origin[early.length] = k
                early.append(text[k])
            }
            val codePoint = text.substring(escape.range.first + 2, escape.range.last + 1).toLong(16)
            val start = early.length
            if (codePoint > Character.MAX_CODE_POINT) early.append(REPLACEMENT_CHARACTER) else early.appendCodePoint(codePoint.toInt())
            for (k in start until early.length) origin[k] = escape.range.first
            copied = escape.range.last + 1
        }
        for (k in copied until text.length) {
            origin[early.length] = k
            early.append(text[k])
        }
        origin[early.length] = text.length
        val earlyText = early.toString()
        return Analysis(text, tokens, earlyText, tokenize(earlyText), origin)
    }

    /** True if [range] lies within one of the [regions] (strings, IRIs and comments). */
    private fun inside(regions: List<Token>, range: IntRange): Boolean =
        regions.any { it.start <= range.first && range.last < it.end }

    /**
     * [query] with the escapes outside the [opaque] regions decoded; [query] itself if there are none. Null if such
     * an escape is anything but a letter, a digit or `_`: there it can only spell part of a keyword or a name.
     */
    private fun decodeOutside(query: String, opaque: List<Token>): String? {
        var out: StringBuilder? = null
        var copied = 0
        for (escape in CODEPOINT_ESCAPE.findAll(query)) {
            if (inside(opaque, escape.range)) continue
            val codePoint = query.substring(escape.range.first + 2, escape.range.last + 1).toLong(16)
            if (codePoint > Character.MAX_CODE_POINT) return null
            if (codePoint.toInt() != '_'.code && !Character.isLetterOrDigit(codePoint.toInt())) return null
            val builder = out ?: StringBuilder(query.length).also { out = it }
            builder.append(query, copied, escape.range.first).appendCodePoint(codePoint.toInt())
            copied = escape.range.last + 1
        }
        return out?.append(query, copied, query.length)?.toString() ?: query
    }

    /**
     * The one text both readings of [query] agree on: [query] itself when its codepoint escapes are all inside
     * strings, IRIs and comments (they are left as written), otherwise [query] with the escapes outside them
     * decoded (`\u0047RAPH` becomes `GRAPH`).
     *
     * @return null if the readings differ - an escape ends a string, an IRI or a comment early for an engine that
     *   decodes escapes first (`"a\u0022 # "`), or lets a string run on (`"\u005C"`) - if an escape outside
     *   strings, IRIs and comments is not a letter, a digit or `_`, or if the query has an unterminated string.
     */
    fun canonical(query: String): String? = analyse(query)?.takeIf { it.agree() }?.text

    private fun isKeyword(query: String, token: Token, keyword: String): Boolean =
        token.kind == Kind.WORD && token.end - token.start == keyword.length &&
            query.regionMatches(token.start, keyword, 0, keyword.length, ignoreCase = true)

    /**
     * True if [query] contains a `FROM` keyword in either reading of its escapes, i.e. declares `FROM` or
     * `FROM NAMED` dataset clauses. `FROM` inside strings, IRIs, comments, variables, language tags and prefixed
     * names does not count; `FROM` is not used by any other SPARQL construct. False for a query that cannot be
     * analysed (callers never run such a query in place without rewriting it, which fails).
     */
    fun declaresDataset(query: String): Boolean = analyse(query)?.hasKeyword("FROM") ?: false

    /**
     * True if [query] contains the `GRAPH` keyword (a graph pattern, or a graph reference in an update) in either
     * reading of its escapes. `GRAPH` inside strings, IRIs, comments, variables, language tags and prefixed names
     * does not count. A query that cannot be analysed (unterminated string, misplaced escapes) counts as using
     * `GRAPH`.
     */
    fun usesGraphPattern(query: String): Boolean = analyse(query)?.hasKeyword("GRAPH") ?: true

    /**
     * Rewrites [query] so that every top-level-or-nested `GRAPH` pattern of its WHERE clause evaluates as it would
     * against an empty named-graph set - no solutions - while the default graph is still read in place:
     *
     * - `GRAPH name { P }`, where the variables in scope of `P` can be told ([patternVariables]: `P` consists of
     *   triple patterns, `OPTIONAL`, `UNION`, nested groups, `GRAPH`, `FILTER`, `BIND`, `MINUS`, `VALUES` and
     *   sub-selects), becomes `{ VALUES (vars) { } FILTER EXISTS { P } }` over the graph variable and the variables
     *   in scope of `P`: exactly the variables `GRAPH name { P }` has in scope. The empty table has no solution for
     *   the filter to test, so `P` is never evaluated; it stays in the query, so the engine still checks its syntax.
     * - Any other `GRAPH ?g { P }` (with `SERVICE`, a sub-select followed by `VALUES`, an expression the scanner
     *   cannot read reliably, or a word it does not know) becomes `{ VALUES ?g { } { P } }`, which evaluates `P`.
     * - Any other `GRAPH <iri> { P }` (or a prefixed name), including one without variables in scope, becomes
     *   `{ { P } FILTER(false) }`.
     *
     * All replacements are standard SPARQL 1.1 and produce no solutions, exactly as a GRAPH pattern does when the
     * dataset has no named graphs; joins, `OPTIONAL`, `UNION`, `MINUS` and `[NOT] EXISTS` around them therefore
     * behave as specified. A `CONSTRUCT` template is left untouched (only the pattern is rewritten), and so is
     * everything inside `SERVICE [SILENT] name { ... }`: that group is evaluated by the remote endpoint against its
     * own dataset.
     *
     * @return [query] itself if it has no `GRAPH` in either reading of its escapes (with escapes outside strings,
     *   IRIs and comments decoded, see [canonical]); the rewritten query; or null if a `GRAPH` keyword is not
     *   followed by a variable or IRI and a balanced group, a `SERVICE` keyword is not followed by a name and a
     *   balanced group, the query has an unterminated string or its readings differ, or it uses `GRAPH` in a
     *   `CONSTRUCT WHERE` short form.
     */
    fun withoutNamedGraphs(query: String): String? {
        val analysis = analyse(query) ?: return null
        val text = analysis.text
        if (!analysis.hasKeyword("GRAPH")) return if (text == query) query else text
        if (!analysis.agree()) return null
        val tokens = analysis.tokens
        fun isGraph(t: Token) = isKeyword(text, t, "GRAPH")
        fun punct(t: Token, c: Char) = t.kind == Kind.PUNCT && text[t.start] == c
        fun isName(t: Token) = t.kind == Kind.VAR || t.kind == Kind.IRI || (t.kind == Kind.WORD && isPrefixedName(text, t))

        // Skip a CONSTRUCT template: GRAPH there (a Jena quad-template extension) describes output, not a pattern.
        var firstPatternToken = 0
        val construct = tokens.indexOfFirst { isKeyword(text, it, "CONSTRUCT") }
        if (construct >= 0 && tokens.subList(0, construct).none { punct(it, '{') }) {
            val next = tokens.getOrNull(construct + 1)
            if (next != null && isKeyword(text, next, "WHERE")) {
                if (tokens.any(::isGraph)) return null
            } else if (next != null && punct(next, '{')) {
                val close = matchingBrace(tokens, construct + 1, text) ?: return null
                firstPatternToken = close + 1
            }
        }

        class Edit(val start: Int, val end: Int, val text: String)
        val edits = ArrayList<Edit>()
        var i = firstPatternToken
        while (i < tokens.size) {
            val token = tokens[i]
            if (isKeyword(text, token, "SERVICE")) {
                // The remote endpoint evaluates this group against its own dataset: leave it exactly as written.
                val nameIndex = if (tokens.getOrNull(i + 1)?.let { isKeyword(text, it, "SILENT") } == true) i + 2 else i + 1
                val name = tokens.getOrNull(nameIndex) ?: return null
                val open = tokens.getOrNull(nameIndex + 1) ?: return null
                if (!isName(name) || !punct(open, '{')) return null
                i = (matchingBrace(tokens, nameIndex + 1, text) ?: return null) + 1
                continue
            }
            if (isGraph(token)) {
                val term = tokens.getOrNull(i + 1) ?: return null
                val open = tokens.getOrNull(i + 2) ?: return null
                if (!isName(term) || !punct(open, '{')) return null
                val close = matchingBrace(tokens, i + 2, text) ?: return null
                val graphVariable = if (term.kind == Kind.VAR) text.substring(term.start, term.end) else null
                val variables = patternVariables(text, tokens, i + 3, close)
                    ?.let { written -> (listOfNotNull(graphVariable) + written).distinctBy { it.substring(1) } }
                when {
                    !variables.isNullOrEmpty() -> {
                        // An empty table over the variables in scope. A filter is asked once per solution, of which
                        // there are none, so P is parsed but never evaluated. Nested GRAPH patterns are still
                        // rewritten below, like everywhere else.
                        val table = if (variables.size == 1) variables[0] else variables.joinToString(" ", "(", ")")
                        edits.add(Edit(token.start, open.end, "{ VALUES $table { } FILTER EXISTS {"))
                        edits.add(Edit(tokens[close].end, tokens[close].end, " }"))
                    }
                    graphVariable != null -> {
                        edits.add(Edit(token.start, term.end, "{ VALUES $graphVariable { }"))
                        edits.add(Edit(tokens[close].end, tokens[close].end, " }"))
                    }
                    else -> {
                        edits.add(Edit(token.start, term.end, "{"))
                        edits.add(Edit(tokens[close].end, tokens[close].end, " FILTER(false) }"))
                    }
                }
            }
            i++
        }
        // One left-to-right pass over the original offsets. Where a group closes exactly where the next GRAPH starts
        // (`}GRAPH`), the insertion that closes the first pattern comes before the replacement that opens the next.
        val out = StringBuilder(text.length + edits.size * 16)
        var copied = 0
        for (edit in edits.sortedWith(compareBy<Edit> { it.start }.thenBy { it.end })) {
            out.append(text, copied, edit.start).append(edit.text)
            copied = edit.end
        }
        return out.append(text, copied, text.length).toString()
    }

    /**
     * The variables in scope of the group whose inside is the tokens [from] until [until] (SPARQL 1.1 section
     * 18.2.1), in order of first appearance, or null if they cannot be told with certainty.
     *
     * - Triple patterns (with paths, blank node property lists, collections and triple terms), nested groups,
     *   `OPTIONAL`, `UNION` and `GRAPH`: every variable written is in scope.
     * - `FILTER`: no variable of the constraint is in scope.
     * - `MINUS { ... }`: no variable of its group is in scope.
     * - `BIND(expr AS ?v)`: `?v` is in scope, the variables of `expr` are not.
     * - `VALUES ?v { ... }` and `VALUES (?v ?w) { ... }`: the variables of the table are in scope.
     * - A sub-select: the variables it projects are in scope - those listed, the `?v` of each `(expr AS ?v)`, or for
     *   `SELECT *` the variables in scope of its own pattern.
     *
     * Null for anything else: `SERVICE` (the remote group is not analysed), a sub-select with a trailing `VALUES`
     * clause, a word that is none of the above (a keyword this scanner does not know), an unbalanced bracket, and an
     * expression in which a `<` may have been read as the start of an IRI (`?a<?b&&?c>1`).
     */
    private fun patternVariables(text: String, tokens: List<Token>, from: Int, until: Int): List<String>? {
        val variables = LinkedHashSet<String>()
        return if (Scope(text, tokens).group(from, until, variables)) variables.toList() else null
    }

    /** The scope analysis of [patternVariables] over the [tokens] of [text]. */
    private class Scope(private val text: String, private val tokens: List<Token>) {
        private fun punct(index: Int, c: Char) = tokens[index].kind == Kind.PUNCT && text[tokens[index].start] == c
        private fun keyword(index: Int, word: String) = isKeyword(text, tokens[index], word)
        private fun variable(index: Int) = text.substring(tokens[index].start, tokens[index].end)

        /** Adds the variables in scope of the group content [from] until [until] to [out]; false if not certain. */
        fun group(from: Int, until: Int, out: MutableSet<String>): Boolean {
            // The content of a group is a sub-select, or patterns.
            if (from < until && keyword(from, "SELECT")) return subSelect(from, until, out)
            var k = from
            while (k < until) {
                val token = tokens[k]
                when (token.kind) {
                    Kind.VAR -> { out.add(variable(k)); k++ }
                    Kind.PUNCT -> {
                        if (punct(k, '{')) {
                            val close = closing(k, until, '{', '}') ?: return false
                            if (!group(k + 1, close, out)) return false
                            k = close + 1
                        } else k++
                    }
                    Kind.WORD -> {
                        val first = text[token.start]
                        if (first in '0'..'9' || first == '.' || isPrefixedName(text, token)) { k++; continue }
                        val word = text.substring(token.start, token.end)
                        k = when {
                            word == "a" || word.uppercase() in SIMPLE_PATTERN_WORDS -> k + 1
                            word.equals("FILTER", ignoreCase = true) -> constraintEnd(k + 1, until)
                            word.equals("MINUS", ignoreCase = true) -> groupEnd(k + 1, until)
                            word.equals("BIND", ignoreCase = true) -> {
                                val assigned = assignment(k + 1, until) ?: return false
                                out.add(variable(assigned.first))
                                assigned.second
                            }
                            word.equals("VALUES", ignoreCase = true) -> inlineData(k + 1, until, out)
                            else -> null
                        } ?: return false
                    }
                    else -> k++
                }
            }
            return true
        }

        /** Index of the token closing the bracket [open] at [openIndex], before [until]; null if there is none. */
        private fun closing(openIndex: Int, until: Int, open: Char, close: Char): Int? {
            var depth = 0
            for (j in openIndex until until) {
                if (tokens[j].kind != Kind.PUNCT) continue
                val c = text[tokens[j].start]
                if (c == open) depth++ else if (c == close && --depth == 0) return j
            }
            return null
        }

        /** Index after the `{ ... }` group that must start at [index]; null if there is none. */
        private fun groupEnd(index: Int, until: Int): Int? {
            if (index >= until || !punct(index, '{')) return null
            return closing(index, until, '{', '}')?.plus(1)
        }

        /**
         * Index of the `)` closing the `(` at [openIndex], if the expression between them was certainly read as it
         * is written. An IRI token that follows an operand (`?a<?b&&?c>1` reads as the IRI `<?b&&?c>`) may be a
         * comparison that swallowed brackets and variables: such an expression is not certain.
         */
        private fun expressionEnd(openIndex: Int, until: Int): Int? {
            val close = closing(openIndex, until, '(', ')') ?: return null
            for (j in openIndex + 1 until close) {
                if (tokens[j].kind != Kind.IRI) continue
                val before = tokens[j - 1]
                val operand = before.kind == Kind.VAR || before.kind == Kind.WORD || before.kind == Kind.STRING ||
                    before.kind == Kind.IRI || punct(j - 1, ')')
                if (operand) return null
            }
            return close
        }

        /**
         * Index after the constraint of a `FILTER` that starts at [index]: `( expr )`, `[NOT] EXISTS { ... }`, or a
         * built-in or function call `name( ... )`.
         */
        private fun constraintEnd(index: Int, until: Int): Int? {
            var j = index
            if (j >= until) return null
            if (punct(j, '(')) return expressionEnd(j, until)?.plus(1)
            if (keyword(j, "NOT")) j++
            if (j < until && keyword(j, "EXISTS")) return groupEnd(j + 1, until)
            if (j != index) return null
            val callable = tokens[j].kind == Kind.IRI || tokens[j].kind == Kind.WORD
            if (!callable || j + 1 >= until || !punct(j + 1, '(')) return null
            return expressionEnd(j + 1, until)?.plus(1)
        }

        /**
         * For `( expr AS ?v )` starting at [index]: the index of `?v` and the index after the closing bracket.
         */
        private fun assignment(index: Int, until: Int): Pair<Int, Int>? {
            if (index >= until || !punct(index, '(')) return null
            val close = expressionEnd(index, until) ?: return null
            if (close - 2 <= index || tokens[close - 1].kind != Kind.VAR || !keyword(close - 2, "AS")) return null
            return (close - 1) to (close + 1)
        }

        /** Adds the variables of `VALUES ?v { ... }` or `VALUES ( ?v ... ) { ... }` at [index]; index after it. */
        private fun inlineData(index: Int, until: Int, out: MutableSet<String>): Int? {
            var j = index
            if (j >= until) return null
            if (tokens[j].kind == Kind.VAR) {
                out.add(variable(j))
                j++
            } else if (punct(j, '(')) {
                j++
                while (j < until && tokens[j].kind == Kind.VAR) { out.add(variable(j)); j++ }
                if (j >= until || !punct(j, ')')) return null
                j++
            } else return null
            return groupEnd(j, until)
        }

        /** The projected variables of the sub-select that fills the group [from] until [until]. */
        private fun subSelect(from: Int, until: Int, out: MutableSet<String>): Boolean {
            var j = from + 1
            if (j < until && (keyword(j, "DISTINCT") || keyword(j, "REDUCED"))) j++
            val projected = ArrayList<String>()
            var star = false
            while (true) {
                if (j >= until) return false
                when {
                    tokens[j].kind == Kind.VAR -> { projected.add(variable(j)); j++ }
                    punct(j, '*') -> { star = true; j++ }
                    punct(j, '(') -> {
                        val assigned = assignment(j, until) ?: return false
                        projected.add(variable(assigned.first))
                        j = assigned.second
                    }
                    keyword(j, "WHERE") -> { j++; break }
                    punct(j, '{') -> break
                    else -> return false
                }
            }
            if (j >= until || !punct(j, '{')) return false
            val close = closing(j, until, '{', '}') ?: return false
            if (star) {
                if (projected.isNotEmpty() || !group(j + 1, close, out)) return false
            } else {
                if (projected.isEmpty()) return false
                out.addAll(projected)
            }
            // Solution modifiers may follow; a trailing VALUES clause joins its variables in, so it is not certain,
            // and neither is an expression with an IRI the scanner may have misread.
            for (m in close + 1 until until) {
                val token = tokens[m]
                if (token.kind == Kind.IRI || punct(m, '{') || punct(m, '}') || keyword(m, "VALUES")) return false
            }
            return true
        }
    }

    /** Index of the `}` token closing the `{` at [openIndex], or null if unbalanced. */
    private fun matchingBrace(tokens: List<Token>, openIndex: Int, query: String): Int? {
        var depth = 0
        for (j in openIndex until tokens.size) {
            val t = tokens[j]
            if (t.kind != Kind.PUNCT) continue
            when (query[t.start]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return j
            }
        }
        return null
    }

    /**
     * Inserts the dataset [clauses] into [query] (with escapes outside strings, IRIs and comments decoded, see
     * [canonical]).
     *
     * @return the rewritten query; the query itself if it already declares a dataset; or null if no insertion point
     *   could be found, the query cannot be analysed, or the two readings of its escapes differ before the insertion
     *   point or about a `FROM` keyword.
     */
    fun insert(query: String, clauses: String): String? {
        val analysis = analyse(query) ?: return null
        val text = analysis.text
        val tokens = analysis.tokens
        fun word(t: Token) = if (t.kind == Kind.WORD) text.substring(t.start, t.end).uppercase() else null
        fun punct(t: Token, c: Char) = t.kind == Kind.PUNCT && text[t.start] == c

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
                w == "FROM" -> return text
                w == "WHERE" || punct(t, '{') -> { insertAt = t.start; break }
                form == "DESCRIBE" && w in SOLUTION_MODIFIERS -> { insertAt = t.start; break }
            }
            i++
        }
        if (insertAt < 0) {
            if (form != "DESCRIBE") return null
            insertAt = text.length
        }
        // What follows the clauses may read differently (the engine then accepts one reading or none), but not what
        // decides where they go, and neither reading may bring dataset clauses of its own.
        if (analysis.hasKeyword("FROM") || !analysis.agree(insertAt)) return null
        return text.substring(0, insertAt) + "\n" + clauses + "\n" + text.substring(insertAt)
    }

    /** True if the WORD [token] is a prefixed name or blank node label (it contains a colon). */
    private fun isPrefixedName(query: String, token: Token): Boolean {
        for (k in token.start until token.end) if (query[k] == ':') return true
        return false
    }

    /** The SPARQL production `PN_CHARS_BASE`: the letters a name or a variable name may start with. */
    private fun isPnCharsBase(codePoint: Int): Boolean =
        codePoint in 'A'.code..'Z'.code || codePoint in 'a'.code..'z'.code ||
            codePoint in 0x00C0..0x00D6 || codePoint in 0x00D8..0x00F6 || codePoint in 0x00F8..0x02FF ||
            codePoint in 0x0370..0x037D || codePoint in 0x037F..0x1FFF || codePoint in 0x200C..0x200D ||
            codePoint in 0x2070..0x218F || codePoint in 0x2C00..0x2FEF || codePoint in 0x3001..0xD7FF ||
            codePoint in 0xF900..0xFDCF || codePoint in 0xFDF0..0xFFFD || codePoint in 0x10000..0xEFFFF

    /** First character of the SPARQL production `VARNAME`: `PN_CHARS_U | [0-9]`. */
    private fun isVarNameStart(codePoint: Int): Boolean =
        isPnCharsBase(codePoint) || codePoint == '_'.code || codePoint in '0'.code..'9'.code

    /**
     * Later characters of `VARNAME`: `PN_CHARS_U | [0-9] | U+00B7 | [U+0300-U+036F] | [U+203F-U+2040]` - the
     * middle dot, the combining diacritical marks, and the undertie and character tie.
     */
    private fun isVarNamePart(codePoint: Int): Boolean =
        isVarNameStart(codePoint) || codePoint == 0x00B7 || codePoint in 0x0300..0x036F || codePoint in 0x203F..0x2040

    /**
     * Index after the name character at [index] of [q] - `PN_CHARS` (what [isVarNamePart] allows, and `-`) or a dot -
     * or [index] itself if there is none.
     */
    private fun nameCharEnd(q: String, index: Int): Int {
        val codePoint = q.codePointAt(index)
        return if (isVarNamePart(codePoint) || codePoint == '-'.code || codePoint == '.'.code) {
            index + Character.charCount(codePoint)
        } else index
    }

    /** End of the exponent (`e`, optional sign, digits) starting at [from], or -1 if there is none. */
    private fun exponentEnd(q: String, from: Int): Int {
        if (from >= q.length || (q[from] != 'e' && q[from] != 'E')) return -1
        var k = from + 1
        if (k < q.length && (q[k] == '+' || q[k] == '-')) k++
        val digits = k
        while (k < q.length && q[k] in '0'..'9') k++
        return if (k > digits) k else -1
    }

    /** End of the codepoint escape that starts with the backslash at [from], or -1 if there is none. */
    private fun escapeEnd(q: String, from: Int): Int {
        val digits = when (q.getOrNull(from + 1)) {
            'u' -> 4
            'U' -> 8
            else -> return -1
        }
        val end = from + 2 + digits
        if (end > q.length) return -1
        for (k in from + 2 until end) if (Character.digit(q[k], 16) < 0) return -1
        return end
    }

    /**
     * Tokenizes enough of SPARQL to find keywords and balanced groups; returns null on an unterminated string.
     * Codepoint escapes are left alone: one inside a string or an IRI belongs to it, like any other character.
     *
     * WORD tokens are keywords and booleans (letters, digits and `_` only), numbers, and prefixed names or blank
     * node labels (the only words that contain a colon). As in the SPARQL grammar, a dot belongs to a number only
     * when a digit or an exponent follows it and to a prefixed name only when it is not its last character, so
     * `1.GRAPH`, `true.GRAPH` and `ex:o. GRAPH` end before the dot; `\`-escaped characters belong to a local name;
     * a language tag (`@en-US`) is one token that is never a keyword.
     *
     * [opaque], when given, receives the regions whose text is not SPARQL syntax: strings, IRIs and comments.
     */
    private fun tokenize(q: String, opaque: MutableList<Token>? = null): List<Token>? {
        val tokens = ArrayList<Token>()
        var i = 0
        val n = q.length
        while (i < n) {
            val c = q[i]
            when {
                c.isWhitespace() -> i++
                c == '#' -> {
                    val start = i
                    while (i < n && q[i] != '\n' && q[i] != '\r') i++
                    opaque?.add(Token(Kind.PUNCT, start, i))
                }
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
                    tokens.add(Token(Kind.STRING, start, i).also { opaque?.add(it) })
                }
                c == '<' -> {
                    var j = i + 1
                    while (j < n) {
                        val d = q[j]
                        if (d == '\\') {
                            val end = escapeEnd(q, j)
                            if (end < 0) break
                            j = end
                        } else if (d > ' ' && d !in "<>\"{}|^`") j++ else break
                    }
                    if (j < n && q[j] == '>') {
                        tokens.add(Token(Kind.IRI, i, j + 1).also { opaque?.add(it) }); i = j + 1
                    } else {
                        tokens.add(Token(Kind.PUNCT, i, i + 1)); i++
                    }
                }
                (c == '?' || c == '$') && i + 1 < n && isVarNameStart(q.codePointAt(i + 1)) -> {
                    // VAR1 / VAR2: the sign and a VARNAME.
                    val start = i
                    i++
                    while (i < n) {
                        val codePoint = q.codePointAt(i)
                        if (!isVarNamePart(codePoint)) break
                        i += Character.charCount(codePoint)
                    }
                    tokens.add(Token(Kind.VAR, start, i))
                }
                c == '@' && i + 1 < n && q[i + 1].isLetter() -> {
                    // Language tag: letters, then '-' separated alphanumeric subtags (and a '--' direction).
                    val start = i
                    i++
                    while (i < n && q[i].isLetter()) i++
                    while (i < n && q[i] == '-') {
                        var k = i + 1
                        if (k < n && q[k] == '-') k++
                        val subtag = k
                        while (k < n && q[k].isLetterOrDigit()) k++
                        if (k == subtag) break
                        i = k
                    }
                    tokens.add(Token(Kind.PUNCT, start, i))
                }
                c in '0'..'9' || (c == '.' && i + 1 < n && q[i + 1] in '0'..'9') -> {
                    val start = i
                    while (i < n && q[i] in '0'..'9') i++
                    if (i < n && q[i] == '.') {
                        var k = i + 1
                        while (k < n && q[k] in '0'..'9') k++
                        val exponent = exponentEnd(q, k)
                        if (exponent > 0) i = exponent else if (k > i + 1) i = k
                    } else {
                        val exponent = exponentEnd(q, i)
                        if (exponent > 0) i = exponent
                    }
                    tokens.add(Token(Kind.WORD, start, i))
                }
                isPnCharsBase(q.codePointAt(i)) || c == '_' || c == ':' -> {
                    val start = i
                    // A prefix (possibly empty) directly followed by a colon makes a prefixed name or blank node label.
                    var j = i
                    while (j < n) {
                        val next = nameCharEnd(q, j)
                        if (next == j) break
                        j = next
                    }
                    if (j < n && q[j] == ':' && (j == i || q[j - 1] != '.')) {
                        var k = j + 1
                        while (k < n) {
                            val d = q[k]
                            if (d == '\\' && k + 1 < n) { k += 2; continue }
                            if (d == ':' || d == '%') { k++; continue }
                            val next = nameCharEnd(q, k)
                            if (next == k) break
                            k = next
                        }
                        // A local name does not end with an (unescaped) dot.
                        while (k > j + 1 && q[k - 1] == '.' && q[k - 2] != '\\') k--
                        i = k
                    } else {
                        // A keyword or a boolean: letters, digits and '_'.
                        while (i < n) {
                            val codePoint = q.codePointAt(i)
                            if (!(isPnCharsBase(codePoint) || codePoint == '_'.code || codePoint in '0'.code..'9'.code)) break
                            i += Character.charCount(codePoint)
                        }
                    }
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
