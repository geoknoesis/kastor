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
 *    IRI for `FROM`), so every `GRAPH` pattern is rewritten to an empty pattern that keeps its variables in scope
 *    ([SparqlDatasetClauses.withoutNamedGraphs]); `GRAPH` inside `SERVICE` is left alone, because it addresses the
 *    remote endpoint's dataset. The store is never copied. A query that cannot be analysed (a `GRAPH` or `SERVICE`
 *    that is not followed by a name and a balanced group, an unterminated string, or codepoint escapes whose
 *    meaning depends on the engine, see below) is rejected with [IllegalArgumentException].
 * 3. Everything else - graphs from different repositories, untracked graphs, or the store's
 *    default graph mixed with other graphs (it has no IRI, and any FROM clause would replace it) -
 *    is materialized into a temporary repository
 *
 * **Dataset clauses:** the dataset defines the default and named graphs, so queries that declare
 * their own `FROM` / `FROM NAMED` are rejected with [IllegalArgumentException] on every path.
 * (Run in place, such a query would read any graph of the source repository and escape the dataset;
 * materialized, it would see different graphs - so there is no consistent meaning to give it.)
 *
 * **Codepoint escapes:** SPARQL decodes `\uXXXX` / `\UXXXXXXXX` before it parses a query, so an escape can spell a
 * keyword (`\u0047RAPH`). Queries are therefore analysed, and sent to the repository, in decoded form
 * ([SparqlDatasetClauses.canonical]). A query is unanalysable when an escape decodes to a quote, a backslash or a
 * line break, is not a Unicode scalar value, or follows another backslash: engines disagree on such text (some decode
 * escapes only inside strings and IRIs). Write those characters with the string escapes `\"`, `\\`, `\n` instead.
 * A dataset on path 1 materializes an unanalysable query; on path 2 it is rejected.
 *
 * **DESCRIBE:** what an engine describes is implementation-defined, and may come from graphs outside the query's
 * dataset (Jena describes a resource in the store's default graph and in every named graph of the store). Results of
 * a `DESCRIBE` run in place are therefore restricted to triples that are in a graph of this dataset (one `hasTriple`
 * lookup per described triple).
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
        val described = inPlace.repository.describe(if (inPlace.sparql == query.sparql) query else SparqlDescribeQuery(inPlace.sparql))
        // What DESCRIBE returns is implementation-defined, and an engine may describe a resource from graphs of the
        // store that are not in the query's dataset (Jena reads the store's default graph and every named graph of
        // it, whatever the dataset clauses say): keep only triples that are in a graph of this dataset.
        val graphs = listOf(defaultGraph) + namedGraphRefs.values
        return described.filter { triple -> graphs.any { it.hasTriple(triple) } }
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
        // Decoded form: what is analysed is exactly what the repository parses, whatever it does with escapes.
        val canonical = SparqlDatasetClauses.canonical(queryText)
        if (plan.storeDefaultGraphOnly) {
            if (canonical != null && !SparqlDatasetClauses.usesGraphPattern(canonical)) return canonical
            // The dataset has no named graphs, but run in place GRAPH would read every named graph of the
            // repository. Dataset clauses cannot express "the store's default graph, no named graphs": FROM NAMED
            // without FROM makes the default graph empty (SPARQL 1.1 section 13.2), and the store's default graph
            // has no IRI to name in a FROM clause. Copying the store instead would be uncached, network-heavy for
            // remote stores and need an in-memory provider, so the GRAPH patterns are rewritten to match nothing.
            return canonical?.let(SparqlDatasetClauses::withoutNamedGraphs) ?: throw IllegalArgumentException(
                "The query cannot be analysed by Kastor: GRAPH (and SERVICE) must be followed by a variable or IRI " +
                    "and a { ... } group, strings must be terminated, and codepoint escapes (\\uXXXX) must not spell " +
                    "quotes, backslashes or line breaks. This dataset has no named graphs, so GRAPH patterns must " +
                    "match nothing; fix the query or query the source repository directly."
            )
        }
        canonical ?: return null
        val clauses = (plan.from.map { "FROM <${it.value}>" } + plan.fromNamed.map { "FROM NAMED <${it.value}>" })
            .joinToString("\n")
        return SparqlDatasetClauses.insert(canonical, clauses)
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
 * `PREFIX :`, `BASE` and `VERSION` prologue declarations are supported. Codepoint escapes are decoded first
 * ([canonical]), as the SPARQL grammar requires, so an escaped keyword is still found.
 */
internal object SparqlDatasetClauses {
    private enum class Kind { WORD, IRI, STRING, VAR, PUNCT }
    private class Token(val kind: Kind, val start: Int, val end: Int)

    private val SOLUTION_MODIFIERS = setOf("ORDER", "GROUP", "HAVING", "LIMIT", "OFFSET", "VALUES")

    /** A SPARQL codepoint escape: `\u` + 4 hex digits or `\U` + 8 hex digits. */
    private val CODEPOINT_ESCAPE = Regex("""\\(?:u[0-9A-Fa-f]{4}|U[0-9A-Fa-f]{8})""")

    /**
     * [query] with its codepoint escapes (`\uXXXX`, `\UXXXXXXXX`) decoded, as SPARQL does before parsing (SPARQL 1.1
     * section 19.2); [query] itself if it has none. The result contains no codepoint escape, so it reads the same
     * for an engine that decodes escapes everywhere and for one that decodes them only in strings and IRIs.
     *
     * @return null if the query cannot be given one meaning: an escape decodes to a quote, a backslash or a line
     *   break (which would end a string or a comment only for some engines), is not a Unicode scalar value, directly
     *   follows another backslash (`\\u0041`: an escaped backslash for some engines), or decoding yields a new escape.
     */
    fun canonical(query: String): String? {
        if (!CODEPOINT_ESCAPE.containsMatchIn(query)) return query
        val out = StringBuilder(query.length)
        var last = 0
        for (match in CODEPOINT_ESCAPE.findAll(query)) {
            val start = match.range.first
            if (start > 0 && query[start - 1] == '\\') return null
            val codePoint = query.substring(start + 2, match.range.last + 1).toLong(16)
            if (codePoint > Character.MAX_CODE_POINT || codePoint in 0xD800..0xDFFF) return null
            if (codePoint.toInt() in AMBIGUOUS_WHEN_ESCAPED) return null
            out.append(query, last, start).appendCodePoint(codePoint.toInt())
            last = match.range.last + 1
        }
        out.append(query, last, query.length)
        return out.toString().takeUnless { CODEPOINT_ESCAPE.containsMatchIn(it) }
    }

    /** Characters that delimit strings and comments: `"`, `'`, `\`, line feed and carriage return. */
    private val AMBIGUOUS_WHEN_ESCAPED = setOf('"'.code, '\''.code, '\\'.code, '\n'.code, '\r'.code)

    private fun isKeyword(query: String, token: Token, keyword: String): Boolean =
        token.kind == Kind.WORD && token.end - token.start == keyword.length &&
            query.regionMatches(token.start, keyword, 0, keyword.length, ignoreCase = true)

    /**
     * True if [query] contains a `FROM` keyword, i.e. declares `FROM` or `FROM NAMED` dataset clauses.
     * `FROM` inside strings, IRIs, comments, variables, language tags and prefixed names does not count; `FROM` is
     * not used by any other SPARQL construct. False for a query that cannot be analysed (callers never run such a
     * query in place without rewriting it, which fails).
     */
    fun declaresDataset(query: String): Boolean {
        val text = canonical(query) ?: return false
        val tokens = tokenize(text) ?: return false
        return tokens.any { isKeyword(text, it, "FROM") }
    }

    /**
     * True if [query] contains the `GRAPH` keyword (a graph pattern, or a graph reference in an update).
     * `GRAPH` inside strings, IRIs, comments, variables, language tags and prefixed names does not count. A query
     * that cannot be analysed (unterminated string, ambiguous codepoint escapes) counts as using `GRAPH`.
     */
    fun usesGraphPattern(query: String): Boolean {
        val text = canonical(query) ?: return true
        val tokens = tokenize(text) ?: return true
        return tokens.any { isKeyword(text, it, "GRAPH") }
    }

    /**
     * Rewrites [query] so that every top-level-or-nested `GRAPH` pattern of its WHERE clause evaluates as it would
     * against an empty named-graph set - no solutions - while the default graph is still read in place:
     *
     * - `GRAPH ?g { P }` becomes `{ VALUES ?g { } { P } }` (an empty table keeps `?g` and the variables of `P`
     *   in scope, so `SELECT *` projects the same variables)
     * - `GRAPH <iri> { P }` (or a prefixed name) becomes `{ { P } FILTER(false) }`
     *
     * Both replacements are standard SPARQL 1.1 and produce no solutions, exactly as a GRAPH pattern does when the
     * dataset has no named graphs; joins, `OPTIONAL`, `UNION`, `MINUS` and `[NOT] EXISTS` around them therefore
     * behave as specified. A `CONSTRUCT` template is left untouched (only the pattern is rewritten), and so is
     * everything inside `SERVICE [SILENT] name { ... }`: that group is evaluated by the remote endpoint against its
     * own dataset. Codepoint escapes are decoded in the result ([canonical]).
     *
     * @return the rewritten query, or null if a `GRAPH` keyword is not followed by a variable or IRI and a
     *   balanced group, a `SERVICE` keyword is not followed by a name and a balanced group, the query has an
     *   unterminated string or ambiguous codepoint escapes, or it uses `GRAPH` in a `CONSTRUCT WHERE` short form.
     */
    fun withoutNamedGraphs(query: String): String? {
        val text = canonical(query) ?: return null
        val tokens = tokenize(text) ?: return null
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
                if (term.kind == Kind.VAR) {
                    edits.add(Edit(token.start, term.end, "{ VALUES ${text.substring(term.start, term.end)} { }"))
                    edits.add(Edit(tokens[close].end, tokens[close].end, " }"))
                } else {
                    edits.add(Edit(token.start, term.end, "{"))
                    edits.add(Edit(tokens[close].end, tokens[close].end, " FILTER(false) }"))
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
     * Inserts the dataset [clauses] into [query] (in decoded form, see [canonical]).
     *
     * @return the rewritten query; the decoded query itself if it already declares a dataset; or null if no
     *   insertion point could be found or the query cannot be analysed.
     */
    fun insert(query: String, clauses: String): String? {
        val text = canonical(query) ?: return null
        val tokens = tokenize(text) ?: return null
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
        return text.substring(0, insertAt) + "\n" + clauses + "\n" + text.substring(insertAt)
    }

    /** True if the WORD [token] is a prefixed name or blank node label (it contains a colon). */
    private fun isPrefixedName(query: String, token: Token): Boolean {
        for (k in token.start until token.end) if (query[k] == ':') return true
        return false
    }

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '·'

    /** End of the exponent (`e`, optional sign, digits) starting at [from], or -1 if there is none. */
    private fun exponentEnd(q: String, from: Int): Int {
        if (from >= q.length || (q[from] != 'e' && q[from] != 'E')) return -1
        var k = from + 1
        if (k < q.length && (q[k] == '+' || q[k] == '-')) k++
        val digits = k
        while (k < q.length && q[k] in '0'..'9') k++
        return if (k > digits) k else -1
    }

    /**
     * Tokenizes enough of SPARQL (after codepoint escapes were decoded) to find keywords and balanced groups;
     * returns null on an unterminated string.
     *
     * WORD tokens are keywords and booleans (letters, digits and `_` only), numbers, and prefixed names or blank
     * node labels (the only words that contain a colon). As in the SPARQL grammar, a dot belongs to a number only
     * when a digit or an exponent follows it and to a prefixed name only when it is not its last character, so
     * `1.GRAPH`, `true.GRAPH` and `ex:o. GRAPH` end before the dot; `\`-escaped characters belong to a local name;
     * a language tag (`@en-US`) is one token that is never a keyword.
     */
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
                    while (i < n && (q[i].isLetterOrDigit() || q[i] == '_' || q[i] == '·')) i++
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
                c.isLetter() || c == '_' || c == ':' -> {
                    val start = i
                    // A prefix (possibly empty) directly followed by a colon makes a prefixed name or blank node label.
                    var j = i
                    while (j < n && isNameChar(q[j])) j++
                    if (j < n && q[j] == ':' && (j == i || q[j - 1] != '.')) {
                        var k = j + 1
                        while (k < n) {
                            val d = q[k]
                            if (d == '\\' && k + 1 < n) k += 2 else if (isNameChar(d) || d == ':' || d == '%') k++ else break
                        }
                        // A local name does not end with an (unescaped) dot.
                        while (k > j + 1 && q[k - 1] == '.' && q[k - 2] != '\\') k--
                        i = k
                    } else {
                        while (i < n && (q[i].isLetterOrDigit() || q[i] == '_')) i++
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
