package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.eclipse.rdf4j.repository.Repository
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.query.QueryLanguage
import org.eclipse.rdf4j.query.TupleQuery
import org.eclipse.rdf4j.query.BooleanQuery
import org.eclipse.rdf4j.query.GraphQuery
import org.eclipse.rdf4j.query.Update
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.ValueFactory
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.eclipse.rdf4j.sail.nativerdf.NativeStore
import org.eclipse.rdf4j.sail.inferencer.fc.SchemaCachingRDFSInferencer
import org.eclipse.rdf4j.sail.shacl.ShaclSail

/**
 * RDF4J-based implementation of [RdfRepository].
 * 
 * **Note on Backend Types:**
 * This implementation uses RDF4J's `Repository` and `RepositoryConnection` types internally.
 * This is an implementation detail and does not leak into the public API. All public methods
 * return Kastor types only.
 * 
 * **Lenient reads** (opt-in, [lenientRead]): a wrapped store may hold statements Kastor cannot represent (e.g. a
 * malformed language tag written by other RDF4J code). By default a graph read that meets one fails with
 * [IllegalArgumentException]; with `lenientRead = true` such statements are skipped with a logged warning and
 * `size()` counts only the statements reads return, like the Jena provider's lenient wrapped models. SPARQL results
 * are not affected.
 *
 * **RDF-star subject tracking:** repositories created through the factory methods track whether quoted-triple
 * subjects may exist, so `size()` and reifier lookups can use RDF4J's counts and indexes. A SPARQL `UPDATE` that may
 * create quoted subjects (it uses RDF-star syntax, `TRIPLE(...)`, `LOAD` or `SERVICE`, or triple terms have been
 * written to the store) makes the state unknown until the next read re-derives it with one scan; inside a
 * `transaction { }` that scan result is reused until the transaction ends (graph writes keep it current, a further
 * update discards it). While the state is unknown or quoted subjects are nested, lookups that involve reifiers still
 * use the store indexes for a reifier's own statements and add a scan only for what the indexes cannot answer (see
 * [Rdf4jGraph]). Repositories wrapping an externally created store (whose content other code may change) are never
 * tracked: their state is always unknown, and `size()` always counts in one pass.
 *
 * **No state outside the store:** which `rdf:reifies` triples are explicit is recorded by stored statements (see
 * [Rdf4jGraph]), so several repositories may wrap one RDF4J repository, and a persistent store can be reopened.
 *
 * **Query dataset:** outside `GRAPH`, SPARQL queries (SELECT, ASK, CONSTRUCT, DESCRIBE) read the default graph only -
 * the statements without a context, as [defaultGraph] and the Jena provider do - and inside `GRAPH` the named graphs.
 * (RDF4J itself evaluates a query without a dataset against the union of all contexts.) A query with `FROM` /
 * `FROM NAMED` keeps its own dataset. A query that reads both inside and outside `GRAPH` sees the IRI-named contexts
 * ([listGraphs]) as named graphs, enumerated once per query; blank-node contexts are visible only to queries that
 * read nothing outside `GRAPH`. Wrapped repositories that are not evaluated by an RDF4J Sail (HTTP repositories,
 * SPARQL endpoints) keep the dataset of their server.
 *
 * **Update dataset:** SPARQL `UPDATE` follows the same contract, as on the Jena provider. Outside `GRAPH`, the `WHERE`
 * clause of `DELETE` / `INSERT` (and `DELETE WHERE`) matches the default graph only, and a template or a
 * `DELETE DATA` block without `GRAPH` changes the default graph only. (RDF4J itself matches such a `WHERE` clause in
 * every context and deletes from every context.) `WITH <g>` makes `g` the default graph of the templates and of
 * `WHERE`; `USING` / `USING NAMED` define the dataset of `WHERE` and leave the templates on the default graph (or the
 * `WITH` graph). Each operation of a request gets its own dataset when the update is prepared.
 *
 * @param repository RDF4J Repository instance (internal implementation detail)
 * @param inference Whether the wrapped store provides inferred statements
 * @param lenientRead skip statements that are not valid Kastor terms on graph reads instead of failing
 */
class Rdf4jRepository(
    private val repository: Repository,
    internal val inference: Boolean,
    lenientRead: Boolean,
) : RdfRepository {

    /** Wraps [repository]; graph reads are strict (see [lenientRead]). */
    constructor(repository: Repository, inference: Boolean = false) : this(repository, inference, false)

    /** Skip statements that are not valid Kastor terms on graph reads (with a warning) instead of failing. */
    @Volatile internal var lenientRead: Boolean = lenientRead
        private set

    /** Switches graph reads to lenient mode (used for the provider's `lenientRead` option). */
    internal fun lenient(): Rdf4jRepository = also { lenientRead = true }

    /** Connection pinned to the current thread's active `transaction { }`, if any. */
    private val txConnection = ThreadLocal<RepositoryConnection?>()
    private val readOnly = ThreadLocal<Boolean>()

    /**
     * True when the store is (or wraps) a `NativeStore`: it cannot hold RDF-star triples, and it resolves literals to
     * stored ids by exact language-tag bytes once its value cache misses.
     */
    internal val nativeBase: Boolean = run {
        val base = generateSequence(repository) { (it as? org.eclipse.rdf4j.repository.DelegatingRepository)?.delegate }.last()
        val sail = (base as? SailRepository)?.sail
        generateSequence(sail) { (it as? org.eclipse.rdf4j.sail.helpers.SailWrapper)?.baseSail }.any { it is NativeStore }
    }

    /** Whether writes may store RDF-star quoted triples (memory stores). */
    internal val starCapable: Boolean get() = !nativeBase

    /** Trusted knowledge about quoted-triple subjects; only lowered by a scan when [trackQuotedSubjects] is set. */
    @Volatile private var quotedState: QuotedLevel = if (nativeBase) QuotedLevel.NONE else QuotedLevel.UNKNOWN
    @Volatile private var trackQuotedSubjects = false
    private val quotedLock = Any()
    private val quotedModifications = java.util.concurrent.atomic.AtomicLong()
    private val quotedWritersInFlight = java.util.concurrent.atomic.AtomicInteger()

    /** Result of the quoted-subject scan made inside the current thread's transaction, reused until it ends. */
    private val quotedScanInTransaction = ThreadLocal<QuotedLevel?>()

    /**
     * False while no RDF-star triple value (quoted subject or triple-term object) can have been written through this
     * repository; a SPARQL update without RDF-star syntax, `LOAD` or `SERVICE` then cannot create quoted subjects.
     * Only meaningful for tracked repositories; never lowered.
     */
    @Volatile private var tripleValuesMayExist = false

    /** Set once a SPARQL update ran: it may have written statements that are not valid Kastor terms. */
    @Volatile private var unvalidatedWrites = false

    /**
     * True when every statement is known to convert to Kastor terms, so a lenient read skips nothing: the repository
     * is tracked (created by a factory method, changed only through this repository) and has only been written through
     * the graph API and the (validating) parsers, not through SPARQL updates.
     */
    internal val allStatementsConvertible: Boolean get() = trackQuotedSubjects && !unvalidatedWrites

    /** Records a write of a triple value (e.g. a triple-term object), see [tripleValuesMayExist]. */
    internal fun noteTripleValue() {
        tripleValuesMayExist = true
    }

    /** Highest quoted-subject level written by the current thread's outermost transaction, if any. */
    private val quotedWrittenInTransaction = ThreadLocal<QuotedLevel?>()

    /**
     * Records a write that may add quoted-triple subjects at [level]. The state is raised immediately (for reads in the
     * same transaction) and again when the transaction ends, and a concurrent scan cannot lower it meanwhile.
     */
    internal fun noteQuotedWrite(level: QuotedLevel) {
        if (nativeBase || level == QuotedLevel.NONE) return
        tripleValuesMayExist = true
        quotedScanInTransaction.get()?.let { cached ->
            if (level == QuotedLevel.UNKNOWN) quotedScanInTransaction.remove()
            else if (level.ordinal > cached.ordinal) quotedScanInTransaction.set(level)
        }
        val previous = quotedWrittenInTransaction.get()
        if (previous == null) quotedWritersInFlight.incrementAndGet()
        if (previous == null || level.ordinal > previous.ordinal) quotedWrittenInTransaction.set(level)
        raiseQuoted(level)
    }

    private fun raiseQuoted(level: QuotedLevel) = synchronized(quotedLock) {
        quotedModifications.incrementAndGet()
        if (level.ordinal > quotedState.ordinal) quotedState = level
    }

    /**
     * Where quoted-triple subjects may occur. For tracked repositories an unknown state is re-derived with one scan
     * (and remembered when no write raced with it); untracked repositories report [QuotedLevel.UNKNOWN].
     */
    internal fun quotedSubjects(conn: RepositoryConnection): QuotedLevel {
        val state = quotedState
        if (state != QuotedLevel.UNKNOWN || !trackQuotedSubjects) return state
        val inTransaction = txConnection.get() != null
        if (inTransaction) quotedScanInTransaction.get()?.let { return it }
        val start = quotedModifications.get()
        var level = QuotedLevel.NONE
        conn.getStatements(null, null, null, false).use { result ->
            for (statement in result) {
                val found = Rdf4jTerms.quotedLevel(statement.subject, statement.`object`)
                if (found.ordinal > level.ordinal) level = found
                if (level == QuotedLevel.NESTED) break
            }
        }
        // A scan inside a transaction may see uncommitted changes: remember it for the rest of that transaction only
        // (graph writes keep it up to date, a SPARQL update discards it); remember results over committed data globally.
        if (inTransaction) {
            quotedScanInTransaction.set(level)
        } else {
            synchronized(quotedLock) {
                if (quotedModifications.get() == start && quotedWritersInFlight.get() == 0 && quotedState == QuotedLevel.UNKNOWN) {
                    quotedState = level
                }
            }
        }
        return level
    }

    /**
     * True when the store may hold RDF-star triple values at all (as a subject or as an object): it can store them,
     * and it is not a tracked repository through which none has been written yet.
     */
    internal fun tripleValuesPossible(): Boolean = starCapable && (!trackQuotedSubjects || tripleValuesMayExist)

    internal fun <T> withWriteConnection(block: (RepositoryConnection) -> T): T {
        check(readOnly.get() != true) { "Cannot write inside a read transaction" }
        var result: Any? = null
        runInTransaction(false) { result = withConnection(block) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /**
     * Runs [block] with a RepositoryConnection. Inside a `transaction { }` on the
     * current thread it reuses that transaction's connection; otherwise it borrows a
     * fresh connection from the repository (RDF4J manages a connection pool) and closes
     * it when [block] returns. RDF4J connections are not thread-safe, so borrowing one
     * per operation is what makes concurrent reads/writes safe.
     */
    internal fun <T> withConnection(block: (RepositoryConnection) -> T): T {
        check(!closed.get()) { "Repository is closed" }
        val tx = txConnection.get()
        return if (tx != null) block(tx) else repository.connection.use { block(it) }
    }

    /**
     * Internal method to access the underlying RDF4J Repository.
     * Used by Rdf4jProvider for dataset operations.
     */
    internal fun getRdf4jRepository(): Repository = repository
    
    companion object {
        fun MemoryRepository(): Rdf4jRepository {
            val repository = SailRepository(MemoryStore())
            repository.init()
            return Rdf4jRepository(repository).withVariant("memory")
        }

        fun NativeRepository(location: String): Rdf4jRepository {
            val repository = SailRepository(NativeStore(java.io.File(location)))
            repository.init()
            return Rdf4jRepository(repository).withVariant("native")
        }

        /**
         * In-memory store with RDF-star explicitly enabled.
         *
         * RDF4J's `MemoryStore` supports RDF-star by default; this factory exists so
         * that the variant identifier is honored and the capability is advertised.
         */
        fun MemoryStarRepository(): Rdf4jRepository {
            val repository = SailRepository(MemoryStore())
            repository.init()
            return Rdf4jRepository(repository).withVariant("memory-star")
        }

        /**
         * Native (persistent) store; alias of [NativeRepository]. RDF4J's `NativeStore` cannot store
         * RDF-star triple terms, so this variant does not advertise them.
         */
        fun NativeStarRepository(location: String): Rdf4jRepository {
            val repository = SailRepository(NativeStore(java.io.File(location)))
            repository.init()
            return Rdf4jRepository(repository).withVariant("native-star")
        }

        /**
         * In-memory store wrapped with [SchemaCachingRDFSInferencer] so that RDFS
         * entailment is materialized at query time.
         */
        fun MemoryRdfsRepository(): Rdf4jRepository {
            val repository = SailRepository(SchemaCachingRDFSInferencer(MemoryStore()))
            repository.init()
            return Rdf4jRepository(repository, true).withVariant("memory-rdfs")
        }

        /**
         * Native (persistent) store wrapped with [SchemaCachingRDFSInferencer].
         */
        fun NativeRdfsRepository(location: String): Rdf4jRepository {
            val repository = SailRepository(SchemaCachingRDFSInferencer(NativeStore(java.io.File(location))))
            repository.init()
            return Rdf4jRepository(repository, true).withVariant("native-rdfs")
        }

        /**
         * In-memory [ShaclSail] that validates writes against shapes loaded into the
         * `RDF4J.SHACL_SHAPE_GRAPH` named graph. SHACL violations surface as
         * `ShaclSailValidationException` (wrapped in a `RepositoryException`) at commit time.
         */
        fun MemoryShaclRepository(): Rdf4jRepository {
            val repository = SailRepository(ShaclSail(MemoryStore()))
            repository.init()
            return Rdf4jRepository(repository).withVariant("memory-shacl")
        }

        /**
         * Native (persistent) [ShaclSail] backed by [NativeStore].
         */
        fun NativeShaclRepository(location: String): Rdf4jRepository {
            val repository = SailRepository(ShaclSail(NativeStore(java.io.File(location))))
            repository.init()
            return Rdf4jRepository(repository).withVariant("native-shacl")
        }

        /** A closing quote followed by an RDF 1.2 directional language tag, e.g. `"x"@ar--rtl`. */
        /** Update text that may introduce RDF-star triple values without them already being stored. */
        private val MAY_CREATE_TRIPLE_VALUES = Regex("<<|(?i)\\btriple\\s*\\(|\\bload\\b|\\bservice\\b")

        /**
         * For every operation of the update request [sparql], in order, whether it has a `USING` / `USING NAMED` clause
         * of its own. Read from the syntax tree of RDF4J's SPARQL parser (the one the update was prepared with), so
         * the word in a literal, an IRI or a comment, or a clause of another operation, is never taken for one.
         */
        private fun usingClauses(sparql: String): List<Boolean> =
            org.eclipse.rdf4j.query.parser.sparql.ast.SyntaxTreeBuilder.parseUpdateSequence(sparql).updateContainers
                .mapNotNull { it.update }
                .map { operation ->
                    val with = (operation as? org.eclipse.rdf4j.query.parser.sparql.ast.ASTModify)?.withClause
                    operation.datasetClauseList.any { it !== with }
                }

        private val DIRECTIONAL_LITERAL =Regex("[\"']@[A-Za-z]+(?:-[A-Za-z0-9]+)*--(?:ltr|rtl)(?![A-Za-z0-9-])")
    }

    /** Provider variant this repository was created as (null for a wrapped, externally created repository). */
    private var variantId: String? = null

    /**
     * Marks a repository created by a factory method: its store was empty when created and is only changed through
     * this repository, so quoted-subject tracking starts from [QuotedLevel.NONE] and can be trusted.
     */
    internal fun withVariant(id: String): Rdf4jRepository = also {
        variantId = id
        synchronized(quotedLock) {
            trackQuotedSubjects = true
            if (quotedState == QuotedLevel.UNKNOWN) quotedState = QuotedLevel.NONE
        }
    }

    /**
     * Error message for a failed query. RDF4J's SPARQL 1.1 parser cannot read RDF 1.2 directional language
     * literals (`"x"@ar--rtl`); that case gets an explicit explanation instead of a bare lexer error.
     */
    private fun failureMessage(prefix: String, query: String, e: Throwable): String {
        val malformed = generateSequence<Throwable>(e) { it.cause }.any { it is org.eclipse.rdf4j.query.MalformedQueryException }
        return if (malformed && DIRECTIONAL_LITERAL.containsMatchIn(query)) {
            "$prefix: RDF4J's SPARQL 1.1 parser cannot read RDF 1.2 directional language literals (\"...\"@lang--dir). " +
                "Pass such literals as query bindings (withSelectRows(query, bindings, timeout)) or use the graph API. (${e.message})"
        } else {
            "$prefix: ${e.message}"
        }
    }
    
    private val valueFactory: ValueFactory = SimpleValueFactory.getInstance()

    // Guards against double close() (e.g. two threads, or close() inside a use{}
    // after an explicit close) shutting the repository down twice.
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    
    override val defaultGraph: RdfGraph = Rdf4jGraph(this, null)

    /** A handle of the graph stored in [context] (the default graph for null). */
    private fun graphHandle(context: org.eclipse.rdf4j.model.Resource?): Rdf4jGraph = Rdf4jGraph(this, context)

    override fun getGraph(name: Iri): RdfGraph =
        Rdf4jGraph(this, valueFactory.createIRI(name.value))

    override fun hasGraph(name: Iri): Boolean = withConnection { conn ->
        conn.hasStatement(null, null, null, false, valueFactory.createIRI(name.value))
    }

    override fun listGraphs(): List<Iri> = withConnection { conn ->
        // Datasets loaded through this provider never have blank-node contexts: `parseDataset` skolemizes
        // blank-node graph names to `urn:kastor:skolem:` IRIs (see Rdf4jFormatSupport.parseDataset). A store
        // written by other RDF4J code (or by SPARQL `LOAD`) may still report `BNode` contexts in `contextIDs`;
        // their string form (`genid-...`) is not a valid absolute IRI and they cannot be named in
        // `GRAPH <iri> { ... }`, so they are left out here.
        conn.contextIDs.use { iter ->
            val out = mutableListOf<Iri>()
            while (iter.hasNext()) {
                val ctx = iter.next()
                if (ctx is org.eclipse.rdf4j.model.IRI) {
                    out.add(Iri(ctx.stringValue()))
                }
            }
            out
        }
    }

    override fun createGraph(name: Iri): RdfGraph =
        Rdf4jGraph(this, valueFactory.createIRI(name.value))

    override fun removeGraph(name: Iri): Boolean = withWriteConnection { conn ->
        val context = valueFactory.createIRI(name.value)
        val had = conn.hasStatement(null, null, null, false, context)
        conn.remove(null as org.eclipse.rdf4j.model.Resource?, null as org.eclipse.rdf4j.model.IRI?, null as org.eclipse.rdf4j.model.Value?, context)
        had
    }

    override fun editDefaultGraph(): MutableRdfGraph {
        return defaultGraph as MutableRdfGraph
    }

    override fun editGraph(name: Iri): MutableRdfGraph {
        return getGraph(name) as MutableRdfGraph
    }
    
    override fun select(query: SparqlSelect): SparqlQueryResult = withSelectRows(query) { Rdf4jResultSet(it.toList()) }

    /**
     * Streams SELECT rows to [consume]. Failures while preparing or evaluating the query (including
     * while iterating rows) surface as [RdfQueryException]; exceptions thrown by [consume] itself
     * propagate unchanged.
     */
    override fun <T> withSelectRows(query: SparqlSelect, consume: (Sequence<BindingSet>) -> T): T = withConnection { conn ->
        val result = queryOperation(query.sparql) { conn.prepareTupleQuery(QueryLanguage.SPARQL, query.sparql).onKastorDataset(conn).evaluate() }
        result.use { consume(it.rows(query.sparql)) }
    }

    override fun <T> withConstructTriples(query: SparqlConstruct, consume: (Sequence<RdfTriple>) -> T): T = withConnection { conn ->
        val result = queryOperation(query.sparql) { conn.prepareGraphQuery(QueryLanguage.SPARQL, query.sparql).onKastorDataset(conn).evaluate() }
        result.use { consume(it.viewTriples().guardedBy(query.sparql)) }
    }

    /**
     * The statements of a graph query result as RDF 1.2 triples (see [Rdf4jTerms.triplesOf]). Every
     * `_:r rdf:reifies <<( s p o )>>` triple is returned once, whether it comes from a stored statement (an explicit
     * one), is implied by a statement about the quoted triple, or both; only those triples are remembered for that.
     */
    private fun org.eclipse.rdf4j.query.GraphQueryResult.viewTriples(): Sequence<RdfTriple> {
        val reifies = HashSet<RdfTriple>()
        return iterator().asSequence()
            .flatMap { statement -> Rdf4jTerms.triplesOf(statement) }
            .filter { it.predicate != com.geoknoesis.kastor.rdf.vocab.RDF.reifies || it.obj !is TripleTerm || reifies.add(it) }
    }

    /**
     * Timed SELECT with initial bindings. RDF4J's `maxExecutionTime` has whole-second granularity, so
     * [timeout] is rounded **up** to the next second (minimum 1 s): a query is never cut off earlier than
     * requested, but may run up to one second longer.
     *
     * Bindings follow the initial-bindings contract shared by every provider
     * ([com.geoknoesis.kastor.rdf.sparql.internal.SparqlInitialBindings], the rules of Jena's `substitution`), so every
     * provider returns the same rows: the constant restricts the query before aggregation, LIMIT and FILTER, a
     * projected bound variable is bound in every row, and `SELECT *` does not return it. Queries that assign a bound
     * variable (`BIND(... AS ?v)`, `(expr AS ?v)`, `VALUES ?v`) or use it inside a sub-select that does not project it
     * are rejected with [IllegalArgumentException].
     *
     * IRIs and literals are written into the query text. Blank nodes and directional language strings (which RDF4J's
     * SPARQL parser cannot spell) go through the same rewrite with a fresh placeholder variable in place of the
     * constant, which is then bound with RDF4J's native `setBinding`; the placeholder is never returned in rows. The
     * query therefore has the same shape (and the same results) as for a spelled-out constant. A triple term is written
     * as `<< s p o >>` in triple patterns and, in expressions (where RDF4J's parser does not accept that syntax), as a
     * fresh variable assigned with `BIND(<< s p o >> AS ?fresh)` at the start of each group that reads it. (RDF4J 5
     * cannot bind a triple value natively: `setBinding` with one trips an internal assertion of its evaluator.)
     * A triple term with a component that SPARQL text cannot spell (a blank node or a directional language string, at
     * any depth) can therefore not be bound at all on RDF4J: such a binding is rejected with
     * [IllegalArgumentException] before the query is prepared.
     */
    override fun <T> withSelectRows(query: SparqlSelect, bindings: Map<String, RdfTerm>, timeout: java.time.Duration,
        consume: (Sequence<BindingSet>) -> T): T {
        // Fresh variables: bound natively with setBinding, or declared with BIND for triple terms in expressions.
        val placeholders = LinkedHashMap<String, org.eclipse.rdf4j.model.Value>()
        val expressions = HashMap<String, String>()
        val constants = bindings.mapValues { (name, term) ->
            val spelled = sparqlConstant(term)
            when {
                spelled == null && term is TripleTerm -> throw IllegalArgumentException(
                    "Initial binding ?$name is a triple term that contains a blank node or a directional language string. " +
                        "RDF4J can neither write such a triple term into the query text nor bind a triple value natively; " +
                        "bind its components to separate variables and match the triple term in the query " +
                        "(e.g. ?x ?y << ?s ?p ?o >>) instead."
                )
                spelled == null -> placeholderFor(name, query.sparql, placeholders.keys + expressions.values)
                    .also { placeholders[it] = Rdf4jTerms.toRdf4jValue(term) }.let { "?$it" }
                term is TripleTerm -> spelled.also {
                    expressions[name] = placeholderFor(name, query.sparql, placeholders.keys + expressions.values)
                }
                else -> spelled
            }
        }
        // Outside queryOperation: a query the substitution rejects is the caller's error (IllegalArgumentException).
        val sparql = com.geoknoesis.kastor.rdf.sparql.internal.SparqlInitialBindings.apply(query.sparql, constants, expressions)
        return withConnection { conn ->
            val result = queryOperation(sparql) {
                val prepared = conn.prepareTupleQuery(QueryLanguage.SPARQL, sparql).onKastorDataset(conn)
                placeholders.forEach { (name, value) -> prepared.setBinding(name, value) }
                prepared.maxExecutionTime = ((timeout.toMillis() + 999) / 1000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                prepared.evaluate()
            }
            result.use { consume(it.rows(sparql, hidden = placeholders.keys + expressions.values)) }
        }
    }

    /** A variable name for a natively bound term that occurs nowhere in [sparql] and is not in [taken]. */
    private fun placeholderFor(name: String, sparql: String, taken: Set<String>): String {
        var candidate = "kastorInitial_$name"
        var n = 0
        while (sparql.contains(candidate) || candidate in taken) candidate = "kastorInitial_${name}_${++n}"
        return candidate
    }

    /**
     * SPARQL text for a binding substituted into the query (as the SPARQL endpoint adapter renders terms),
     * or null for terms bound natively.
     */
    private fun sparqlConstant(term: RdfTerm): String? {
        val lexical = com.geoknoesis.kastor.rdf.sparql.internal.SparqlLexical
        return when (term) {
            is Iri -> lexical.iriRef(term.value)
            is LangString -> if (term.direction == null) lexical.langLiteral(term.lexical, term.lang) else null
            is TypedLiteral ->
                if (term.datatype == com.geoknoesis.kastor.rdf.vocab.XSD.string) lexical.quoted(term.lexical)
                else lexical.typedLiteral(term.lexical, term.datatype.value)
            is TrueLiteral -> "\"true\"^^" + lexical.iriRef(com.geoknoesis.kastor.rdf.vocab.XSD.boolean.value)
            is FalseLiteral -> "\"false\"^^" + lexical.iriRef(com.geoknoesis.kastor.rdf.vocab.XSD.boolean.value)
            // RDF4J's SPARQL-star syntax for a triple term whose components can all be spelled (triple patterns only);
            // null when a component is a blank node or a directional language string (the caller rejects the binding).
            is TripleTerm -> {
                val subject = (term.triple.subject as? Iri)?.let { sparqlConstant(it) } ?: return null
                val obj = sparqlConstant(term.triple.obj) ?: return null
                "<< $subject ${lexical.iriRef(term.triple.predicate.value)} $obj >>"
            }
            else -> null
        }
    }

    private fun org.eclipse.rdf4j.query.TupleQueryResult.rows(sparql: String, hidden: Set<String> = emptySet()): Sequence<BindingSet> =
        iterator().asSequence().map { row ->
            val names = if (hidden.isEmpty()) row.bindingNames else row.bindingNames.filterNot { it in hidden }
            MapBindingSet(names.associateWith { Rdf4jTerms.fromRdf4jValue(row.getValue(it)) }) as BindingSet
        }.guardedBy(sparql)

    /**
     * Gives a prepared query Kastor's dataset: outside `GRAPH`, patterns read the repository's default graph only (the
     * statements without a context), as [defaultGraph] and the Jena provider do; inside `GRAPH` they read the named
     * graphs. Without this, RDF4J evaluates a query that has no dataset against the union of all contexts, so the
     * triples of every named graph would also match default-graph patterns.
     *
     * - A query with its own `FROM` / `FROM NAMED` clauses keeps the dataset it declares.
     * - A query without `GRAPH` (and any `DESCRIBE`) gets the default graph `RDF4J.NIL` and no named graphs.
     * - A query that only reads inside `GRAPH` runs without a dataset, exactly as before (every context, including
     *   blank-node contexts, is a named graph).
     * - A query that reads both gets `RDF4J.NIL` as its default graph and the repository's IRI-named contexts (the
     *   graphs of [listGraphs]) as its named graphs; they are enumerated once per query, and blank-node contexts are
     *   not visible to such a query.
     * - Only queries evaluated by an RDF4J Sail are changed; a wrapped remote repository (HTTP or SPARQL endpoint)
     *   keeps the dataset its server defines.
     *
     * SPARQL `UPDATE` gets the equivalent dataset per operation, see [onKastorUpdateDataset].
     */
    private fun <Q : org.eclipse.rdf4j.query.Query> Q.onKastorDataset(conn: RepositoryConnection): Q {
        val parsed = (this as? org.eclipse.rdf4j.repository.sail.SailQuery)?.parsedQuery ?: return this
        if (parsed.dataset != null) return this
        val reads = GraphReads.of(parsed.tupleExpr)
        if (reads.named && !reads.default) return this
        val dataset = org.eclipse.rdf4j.query.impl.SimpleDataset()
        dataset.addDefaultGraph(org.eclipse.rdf4j.model.vocabulary.RDF4J.NIL)
        if (reads.named) iriContexts(conn).forEach(dataset::addNamedGraph)
        setDataset(dataset)
        return this
    }

    /** The IRI-named contexts of the store as [conn] sees them (the graphs of [listGraphs]). */
    private fun iriContexts(conn: RepositoryConnection): Set<IRI> {
        val out = LinkedHashSet<IRI>()
        conn.contextIDs.use { contexts -> while (contexts.hasNext()) (contexts.next() as? IRI)?.let(out::add) }
        return out
    }

    /** Whether the patterns of a query (or of the `WHERE` clause of an update) read outside and inside `GRAPH`. */
    private class GraphReads(val default: Boolean, val named: Boolean) {
        companion object {
            fun of(expr: org.eclipse.rdf4j.query.algebra.TupleExpr?): GraphReads {
                var readsDefault = false
                var readsNamed = false
                fun note(scope: org.eclipse.rdf4j.query.algebra.StatementPattern.Scope) {
                    if (scope == org.eclipse.rdf4j.query.algebra.StatementPattern.Scope.NAMED_CONTEXTS) readsNamed = true else readsDefault = true
                }
                expr?.visit(object : org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor<RuntimeException>() {
                    override fun meet(node: org.eclipse.rdf4j.query.algebra.StatementPattern) { note(node.scope); super.meet(node) }
                    override fun meet(node: org.eclipse.rdf4j.query.algebra.ArbitraryLengthPath) { note(node.scope); super.meet(node) }
                    override fun meet(node: org.eclipse.rdf4j.query.algebra.ZeroLengthPath) { note(node.scope); super.meet(node) }
                    override fun meet(node: org.eclipse.rdf4j.query.algebra.DescribeOperator) { readsDefault = true; super.meet(node) }
                    // The patterns of a SERVICE clause are evaluated by the remote endpoint against its own dataset.
                    override fun meet(node: org.eclipse.rdf4j.query.algebra.Service) = Unit
                })
                return GraphReads(readsDefault, readsNamed)
            }
        }
    }

    /**
     * Gives every operation of a prepared SPARQL `UPDATE` Kastor's dataset, the one the Jena provider uses: outside
     * `GRAPH`, the `WHERE` clause matches the default graph only (the statements without a context), and triples of a
     * `DELETE` / `INSERT` template or of a `DELETE DATA` block that are not inside `GRAPH` are removed from / added to
     * the default graph only. Without this, RDF4J matches the `WHERE` clause of an update that declares no dataset in
     * every context and removes a template triple from **every** context.
     *
     * RDF4J keeps one dataset per operation of the request (`ParsedUpdate.getDatasetMapping`: the `WITH` / `USING` /
     * `USING NAMED` clauses of that operation, or none). Each is completed here, per operation, instead of setting one
     * dataset on the whole update, which would override the clauses of every operation:
     *
     * - `DELETE` / `INSERT ... WHERE` and `DELETE WHERE` **without `WITH` / `USING`**: default graph `RDF4J.NIL` for
     *   `WHERE` and for default removals; insertions without `GRAPH` go to the default graph as before. As for queries,
     *   a `WHERE` that only reads inside `GRAPH` keeps reading every context (blank-node contexts included), and one
     *   that reads both inside and outside `GRAPH` gets the IRI-named contexts as named graphs. They are enumerated when
     *   the operation starts, so it sees graphs created by earlier operations of the same request or transaction.
     * - `WITH <g>` (without `USING`): `g` is the default graph of `WHERE` and of the templates, as RDF4J parses it;
     *   `GRAPH` inside `WHERE` still reads the IRI-named graphs of the store (RDF4J alone would give it none).
     * - `USING` / `USING NAMED`: the declared dataset is kept for `WHERE` (with only `USING NAMED`, the default graph
     *   is empty; with only `USING`, there are no named graphs). Default removals go to the `WITH` graph, or else to
     *   the default graph (RDF4J alone would remove from every context).
     * - `DELETE DATA` without `GRAPH` removes from the default graph only (RDF4J alone would remove the triple from
     *   every context).
     * - `INSERT DATA`, `LOAD`, `CLEAR`, `DROP`, `CREATE`, `COPY`, `MOVE` and `ADD` name their graphs explicitly and
     *   are not changed.
     * - Only updates executed by an RDF4J Sail are changed; a wrapped remote repository keeps its server's behaviour.
     *
     * @param sparql the update text. RDF4J's dataset of `WITH <g>` alone equals the one of `WITH <g> ... USING <g>`, so
     *   whether an operation has a `USING` clause of its own is read from the parser's syntax tree of this text
     *   ([usingClauses]); the text is parsed that second time only for a request with such a `WITH` operation.
     */
    private fun Update.onKastorUpdateDataset(conn: RepositoryConnection, sparql: String): Update {
        val parsed = (this as? org.eclipse.rdf4j.repository.sail.SailUpdate)?.parsedUpdate ?: return this
        val nil = org.eclipse.rdf4j.model.vocabulary.RDF4J.NIL
        // Asked when the operation is evaluated, not now: earlier operations of the request may create named graphs.
        val storeGraphs = { iriContexts(conn) }
        val using: List<Boolean> by lazy { usingClauses(sparql).takeIf { it.size == parsed.updateExprs.size }.orEmpty() }
        for ((index, expr) in parsed.updateExprs.withIndex()) {
            val declared = parsed.datasetMapping[expr]
            val dataset: org.eclipse.rdf4j.query.Dataset? = when (expr) {
                // RDF4J's DELETE DATA passes its default remove graphs to the Sail as they are (it does not translate
                // RDF4J.NIL as DELETE ... WHERE does), and the Sail's name for the default graph is the null context.
                is org.eclipse.rdf4j.query.algebra.DeleteData ->
                    if (declared == null) UpdateDataset(removeGraphs = java.util.Collections.singleton<IRI?>(null)) else null
                is org.eclipse.rdf4j.query.algebra.Modify -> {
                    val reads = GraphReads.of(expr.whereExpr)
                    when {
                        declared == null && reads.named && !reads.default -> UpdateDataset(removeGraphs = setOf(nil))
                        declared == null -> UpdateDataset(
                            defaultGraphs = setOf(nil),
                            removeGraphs = setOf(nil),
                            storeGraphs = if (reads.named) storeGraphs else null,
                        )
                        else -> {
                            // WITH <g> alone is parsed as the default graph g, also for insertions and removals.
                            val withGraphOnly = declared.defaultInsertGraph != null && declared.namedGraphs.isEmpty() &&
                                declared.defaultGraphs == setOf(declared.defaultInsertGraph)
                            // Without a USING clause of its own, GRAPH in WHERE reads the store's named graphs. (An
                            // operation the syntax tree does not account for is left as RDF4J parsed it.)
                            val needsNamed = withGraphOnly && reads.named && !using.getOrElse(index) { true }
                            val needsRemove = declared.defaultRemoveGraphs.isEmpty()
                            if (!needsNamed && !needsRemove) {
                                null
                            } else {
                                UpdateDataset(
                                    defaultGraphs = declared.defaultGraphs,
                                    removeGraphs = if (needsRemove) setOf(nil) else declared.defaultRemoveGraphs,
                                    insertGraph = declared.defaultInsertGraph,
                                    storeGraphs = if (needsNamed) storeGraphs else null,
                                    declaredNamedGraphs = declared.namedGraphs,
                                )
                            }
                        }
                    }
                }
                else -> null
            }
            if (dataset != null) parsed.map(expr, dataset)
        }
        return this
    }

    /**
     * Dataset of one update operation. [storeGraphs], when given, supplies the named graphs and is asked once, the
     * first time the operation's `WHERE` clause needs them (when the operation starts, after the operations before it).
     */
    private class UpdateDataset(
        private val defaultGraphs: Set<IRI> = emptySet(),
        private val removeGraphs: Set<IRI?> = emptySet(),
        private val insertGraph: IRI? = null,
        storeGraphs: (() -> Set<IRI>)? = null,
        declaredNamedGraphs: Set<IRI> = emptySet(),
    ) : org.eclipse.rdf4j.query.Dataset {
        private val named: Lazy<Set<IRI>> = if (storeGraphs != null) lazy(storeGraphs) else lazyOf(declaredNamedGraphs)
        override fun getDefaultGraphs(): Set<IRI> = defaultGraphs
        override fun getNamedGraphs(): Set<IRI> = named.value
        @Suppress("UNCHECKED_CAST")
        override fun getDefaultRemoveGraphs(): Set<IRI> = removeGraphs as Set<IRI>
        override fun getDefaultInsertGraph(): IRI? = insertGraph
        override fun toString(): String =
            "UpdateDataset(default=$defaultGraphs, named=${if (named.isInitialized()) named.value else "<store graphs>"}, " +
                "remove=$removeGraphs, insert=$insertGraph)"
    }

    /**
     * Wraps failures of the query engine itself; never used around caller-supplied consumers. Besides exceptions this
     * covers an [AssertionError]: RDF4J's evaluator checks some of its invariants with `assert`, which (with assertions
     * enabled) reports an unsupported query as an `Error`. Every other `Error` (out of memory, stack overflow, linkage
     * errors) propagates unchanged. Every operation of this repository that prepares or evaluates SPARQL (SELECT, ASK,
     * CONSTRUCT, DESCRIBE and UPDATE) goes through here.
     *
     * @param what start of the exception message, e.g. `Failed to prepare SPARQL ASK query`.
     * @param kind when given, the failure is also reported to [RdfDebug] as a failed operation of that kind.
     */
    private inline fun <T> queryOperation(
        query: String,
        what: String = "SPARQL execution failed",
        kind: String? = null,
        operation: () -> T,
    ): T = try {
        operation()
    } catch (e: RdfException) {
        throw e
    } catch (e: Exception) {
        throw queryFailure(what, kind, query, e)
    } catch (e: AssertionError) {
        throw queryFailure("$what (RDF4J internal assertion)", kind, query, e)
    }

    private fun queryFailure(what: String, kind: String?, query: String, e: Throwable): RdfQueryException {
        if (kind != null) RdfDebug.logQueryError(kind, query, "$what: ${e.message}")
        return RdfQueryException(failureMessage(what, query, e), query = query, cause = e)
    }

    /** Engine failures raised while iterating results become [RdfQueryException]; consumer code is not wrapped. */
    private fun <T> Sequence<T>.guardedBy(query: String): Sequence<T> {
        val source = this
        return Sequence {
            val iterator = queryOperation(query) { source.iterator() }
            object : Iterator<T> {
                override fun hasNext(): Boolean = queryOperation(query) { iterator.hasNext() }
                override fun next(): T = queryOperation(query) { iterator.next() }
            }
        }.constrainOnce()
    }

    override fun ask(query: SparqlAsk): Boolean = withConnection { conn ->
        val startTime = System.currentTimeMillis()
        val prepared = queryOperation(query.sparql, "Failed to prepare SPARQL ASK query", "ASK") {
            conn.prepareBooleanQuery(QueryLanguage.SPARQL, query.sparql).onKastorDataset(conn)
        }
        val result = queryOperation(query.sparql, "Failed to execute SPARQL ASK query", "ASK") { prepared.evaluate() }
        RdfDebug.logQueryTrace("ASK", query.sparql, null, System.currentTimeMillis() - startTime, if (result) 1 else 0)
        result
    }

    /**
     * The result is materialized to a list inside the connection scope because the borrowed connection (and its
     * GraphQueryResult cursor) is closed as soon as [withConnection] returns - a lazy sequence over a closed
     * connection would fail. Callers that need lazy streaming over large graphs should use [withConstructTriples].
     * Failures while preparing, evaluating or iterating the result surface as [RdfQueryException] (see
     * [queryOperation]).
     */
    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> = withConnection { conn ->
        val sparql = query.sparql
        val startTime = System.currentTimeMillis()
        val prepared = queryOperation(sparql, "Failed to prepare SPARQL CONSTRUCT query", "CONSTRUCT") {
            conn.prepareGraphQuery(QueryLanguage.SPARQL, sparql).onKastorDataset(conn)
        }
        val triples = queryOperation(sparql, "Failed to execute SPARQL CONSTRUCT query", "CONSTRUCT") {
            prepared.evaluate().use { it.viewTriples().toList() }
        }
        RdfDebug.logQueryTrace("CONSTRUCT", sparql, null, System.currentTimeMillis() - startTime, triples.size)
        triples.asSequence()
    }

    /**
     * Describes the resources a `DESCRIBE` query selects with their **concise bounded description**, exactly as the
     * Jena provider does: the statements whose subject is the resource and, recursively, the statements whose subject
     * is a blank node that is the object of a described statement. Statements that merely point at the resource are
     * not part of it. (RDF4J's own `DESCRIBE` is symmetric: it also returns the incoming statements, so it is not
     * used.)
     *
     * - **Resources:** the IRIs listed in the `DESCRIBE` clause (whatever the `WHERE` clause matches) and the IRIs and
     *   blank nodes that the `WHERE` clause, with its solution modifiers, binds to the described variables (every
     *   variable for `DESCRIBE *`). It is evaluated by RDF4J against the dataset every query gets (see
     *   [onKastorDataset]). Literals and triple terms are not resources and are skipped.
     * - **Source:** the description is read from the default graph of the query's dataset: the repository's default
     *   graph, or the merge of the `FROM` graphs (nothing with only `FROM NAMED`). Named graphs are read by the
     *   `WHERE` clause inside `GRAPH`, never by the description.
     * - **Triples:** the ones the graph API returns for that graph ([Rdf4jGraph]): an RDF-star subject is read as its
     *   reifier blank node with its `rdf:reifies` triple (once), triple terms are objects and are not followed, and
     *   an inference repository includes entailed statements. Blank node cycles end.
     *
     * A wrapped repository that is not evaluated by an RDF4J Sail (HTTP repository, SPARQL endpoint) returns the
     * description its server computes.
     */
    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> = withConnection { conn ->
        val sparql = query.sparql
        val startTime = System.currentTimeMillis()
        val prepared = queryOperation(sparql, "Failed to prepare SPARQL DESCRIBE query", "DESCRIBE") {
            conn.prepareGraphQuery(QueryLanguage.SPARQL, sparql)
        }
        val triples = queryOperation(sparql, "Failed to execute SPARQL DESCRIBE query", "DESCRIBE") {
            boundedDescription(conn, sparql, prepared) ?: prepared.onKastorDataset(conn).evaluate().use { it.viewTriples().toList() }
        }
        RdfDebug.logQueryTrace("DESCRIBE", sparql, null, System.currentTimeMillis() - startTime, triples.size)
        triples.asSequence()
    }

    /** The description [describe] documents, or null when [prepared] is not a `DESCRIBE` query evaluated by a Sail. */
    private fun boundedDescription(conn: RepositoryConnection, sparql: String, prepared: GraphQuery): List<RdfTriple>? {
        val parsed = (prepared as? org.eclipse.rdf4j.repository.sail.SailQuery)?.parsedQuery ?: return null
        var operator: org.eclipse.rdf4j.query.algebra.DescribeOperator? = null
        parsed.tupleExpr.visit(object : org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor<RuntimeException>() {
            override fun meet(node: org.eclipse.rdf4j.query.algebra.DescribeOperator) { operator = node }
        })
        // The rows of this expression hold the resources to describe: RDF4J parses `DESCRIBE <iri> ?v WHERE { P }` as
        // DescribeOperator(Projection(Extension(P, <iri> AS _describe_N), [_describe_N, v])).
        val selection = operator?.arg ?: return null
        val select = conn.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * {}")
        val selectParsed = (select as? org.eclipse.rdf4j.repository.sail.SailQuery)?.parsedQuery ?: return null

        val resources = LinkedHashSet<RdfResource>()
        // The IRIs of the DESCRIBE clause are described also when the WHERE clause has no solution. Their generated
        // names cannot be variables of the query: those occur in its text.
        ((selection as? org.eclipse.rdf4j.query.algebra.Projection)?.arg as? org.eclipse.rdf4j.query.algebra.Extension)?.elements?.forEach { element ->
            val constant = (element.expr as? org.eclipse.rdf4j.query.algebra.ValueConstant)?.value
            if (constant is IRI && element.name.startsWith("_describe_") && !sparql.contains(element.name)) {
                resources.add(Iri(constant.stringValue()))
            }
        }
        selectParsed.tupleExpr = selection.clone()
        val declared = parsed.dataset
        if (declared != null) select.dataset = declared else select.onKastorDataset(conn)
        select.evaluate().use { rows ->
            for (row in rows) {
                for (binding in row) {
                    when (val value = binding.value) {
                        is IRI -> resources.add(Iri(value.stringValue()))
                        is org.eclipse.rdf4j.model.BNode -> resources.add(BlankNode(value.id))
                        else -> Unit
                    }
                }
            }
        }

        val nil = setOf<IRI>(org.eclipse.rdf4j.model.vocabulary.RDF4J.NIL, org.eclipse.rdf4j.model.vocabulary.SESAME.NIL)
        val sources: List<Rdf4jGraph> =
            if (declared == null) listOf(graphHandle(null)) else declared.defaultGraphs.map { graphHandle(if (it in nil) null else it) }
        val description = LinkedHashSet<RdfTriple>()
        val visited = HashSet<RdfResource>()
        val pending = ArrayDeque<RdfResource>(resources)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (!visited.add(node)) continue
            for (source in sources) {
                for (triple in source.outgoing(conn, node)) {
                    description.add(triple)
                    val obj = triple.obj
                    if (obj is BlankNode && obj !in visited) pending.addLast(obj)
                }
            }
        }
        return description.toList()
    }

    /**
     * Runs a SPARQL `UPDATE` with Kastor's dataset (see [onKastorUpdateDataset]). Failures while preparing or
     * executing it surface as [RdfQueryException] (see [queryOperation]).
     */
    override fun update(query: UpdateQuery) {
        withWriteConnection { conn ->
            val startTime = System.currentTimeMillis()
            // An update may create quoted-triple subjects (RDF-star syntax, TRIPLE(), LOAD or SERVICE data, or moving
            // stored triple terms into subject position).
            unvalidatedWrites = true
            val quotedSyntax = MAY_CREATE_TRIPLE_VALUES.containsMatchIn(query.sparql)
            if (quotedSyntax) noteQuotedWrite(QuotedLevel.UNKNOWN)
            queryOperation(query.sparql, "Failed to execute SPARQL UPDATE", "UPDATE") {
                conn.prepareUpdate(QueryLanguage.SPARQL, query.sparql).onKastorUpdateDataset(conn, query.sparql).execute()
            }
            // Checked after executing, so a triple value written concurrently (and visible to the update) counts.
            if (!quotedSyntax && tripleValuesMayExist) noteQuotedWrite(QuotedLevel.UNKNOWN)
            RdfDebug.logQueryTrace("UPDATE", query.sparql, null, System.currentTimeMillis() - startTime, null)
        }
    }

    override fun transaction(operations: RdfRepository.() -> Unit) = runInTransaction(false, operations)

    override fun readTransaction(operations: RdfRepository.() -> Unit) = runInTransaction(true, operations)

    /**
     * Runs [operations] inside a single RDF4J transaction. A fresh connection is
     * borrowed and pinned to the current thread (via [txConnection]) so every
     * operation in the block — including those on graphs obtained from this
     * repository — shares the same transaction. A nested `transaction { }` on the
     * same thread joins the outer one instead of calling `begin()` again (which RDF4J
     * rejects); only the outermost call begins/commits/rolls back.
     */
    private fun runInTransaction(read: Boolean, operations: RdfRepository.() -> Unit) {
        check(!closed.get()) { "Repository is closed" }
        check(read || readOnly.get() != true) { "Cannot write inside a read transaction" }
        if (txConnection.get() != null) {
            // Already inside a transaction on this thread — join it.
            operations(this)
            return
        }
        repository.connection.use { conn ->
            txConnection.set(conn)
            readOnly.set(read)
            try {
                conn.begin()
                operations(this)
                conn.commit()
            } catch (e: Throwable) {
                if (conn.isActive) conn.rollback()
                throw e
            } finally {
                txConnection.remove()
                readOnly.remove()
                quotedScanInTransaction.remove()
                quotedWrittenInTransaction.get()?.let { level ->
                    // Raise again after commit/rollback, so a scan that ran while the write was invisible is discarded.
                    quotedWrittenInTransaction.remove()
                    raiseQuoted(level)
                    quotedWritersInFlight.decrementAndGet()
                }
            }
        }
    }

    override fun clear(): Boolean = withWriteConnection { conn ->
        val wasEmpty = conn.isEmpty
        conn.clear()
        !wasEmpty
    }

    override fun isClosed(): Boolean = closed.get() || !repository.isInitialized
    
    /** Capabilities of the variant this repository was created as; wrapped repositories are classified by their Sail. */
    override fun getCapabilities(): ProviderCapabilities = Rdf4jProvider().getCapabilities(
        variantId ?: run {
            val sail = (repository as? SailRepository)?.sail
            val base = if (generateSequence(sail) { (it as? org.eclipse.rdf4j.sail.helpers.SailWrapper)?.baseSail }.any { it is NativeStore }) "native" else "memory"
            when {
                inference -> "$base-rdfs"
                sail is ShaclSail -> "$base-shacl"
                else -> base
            }
        }
    )

    override fun close() {
        check(txConnection.get() == null) { "Cannot close inside a transaction" }
        if (!closed.compareAndSet(false, true)) return
        // No long-lived connection to close (connections are per-operation); just shut
        // down the repository. Guarded by the AtomicBoolean against double-close.
        repository.shutDown()
    }
}










