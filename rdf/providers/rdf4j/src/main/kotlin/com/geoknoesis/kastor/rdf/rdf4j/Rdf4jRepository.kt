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
 * update discards it). While the state is unknown or quoted subjects are nested, lookups that involve reifiers widen
 * to the positions the reified form can satisfy (up to a scan). Repositories wrapping an externally created store
 * (whose content other code may change) are never tracked and always use the scanning paths.
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

    /** An explicitly added `_:r rdf:reifies <<quoted>>` triple in graph [context] (null for the default graph). */
    internal data class ReifiesKey(val context: org.eclipse.rdf4j.model.Resource?, val quoted: org.eclipse.rdf4j.model.Triple)

    /**
     * Explicit `rdf:reifies` triples that are not stored because statements about the quoted triple imply them
     * (RDF-star capable stores only). Remembered so that the triple survives the removal of the last such statement.
     */
    private val explicitReifies: MutableSet<ReifiesKey> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Changes to [explicitReifies] made by the current thread's transaction, applied when it commits. */
    private val explicitReifiesInTransaction = ThreadLocal<LinkedHashMap<ReifiesKey, Boolean>?>()

    internal fun isExplicitReifies(key: ReifiesKey): Boolean =
        explicitReifiesInTransaction.get()?.get(key) ?: (key in explicitReifies)

    internal fun setExplicitReifies(key: ReifiesKey, present: Boolean) {
        val pending = explicitReifiesInTransaction.get()
        when {
            pending != null -> pending[key] = present
            present -> explicitReifies.add(key)
            else -> explicitReifies.remove(key)
        }
    }

    /** Explicit implied `rdf:reifies` triples visible to the current thread, restricted to [filter]. */
    private fun explicitReifiesMatching(filter: (ReifiesKey) -> Boolean): List<ReifiesKey> =
        (explicitReifies + explicitReifiesInTransaction.get()?.keys.orEmpty()).filter { filter(it) && isExplicitReifies(it) }

    /** Forgets the explicit implied `rdf:reifies` triples of cleared graphs. */
    internal fun forgetExplicitReifies(filter: (ReifiesKey) -> Boolean) =
        explicitReifiesMatching(filter).forEach { setExplicitReifies(it, false) }

    /**
     * Stores every remembered explicit `rdf:reifies` triple as a plain statement, e.g. before a SPARQL update that may
     * remove the statements implying it (and that this repository cannot follow).
     */
    private fun materialiseExplicitReifies(conn: RepositoryConnection) {
        for (key in explicitReifiesMatching { true }) {
            val reifier = valueFactory.createBNode(Rdf4jTerms.reifierFor(key.quoted).id)
            conn.add(reifier, Rdf4jTerms.toRdf4jIri(com.geoknoesis.kastor.rdf.vocab.RDF.reifies), key.quoted, key.context)
            setExplicitReifies(key, false)
        }
    }

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
    private fun failureMessage(prefix: String, query: String, e: Exception): String {
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

    override fun getGraph(name: Iri): RdfGraph =
        Rdf4jGraph(this, valueFactory.createIRI(name.value))

    override fun hasGraph(name: Iri): Boolean = withConnection { conn ->
        conn.hasStatement(null, null, null, false, valueFactory.createIRI(name.value))
    }

    override fun listGraphs(): List<Iri> = withConnection { conn ->
        // RDF4J's `contextIDs` includes blank-node contexts (graph names that
        // were generated for an unnamed `GRAPH _:b { ... }` block in TriG).
        // Those are reported as `BNode` values whose string form (`genid-...-g`)
        // is not a valid absolute IRI - constructing an `Iri` from them
        // throws. RDF 1.1/1.2 only allows IRI-named graphs to be referenced
        // via `GRAPH <iri> { ... }`, so we filter out blank-node contexts here.
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
        forgetExplicitReifies { it.context == context }
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
        val result = queryOperation(query.sparql) { conn.prepareTupleQuery(QueryLanguage.SPARQL, query.sparql).evaluate() }
        result.use { consume(it.rows(query.sparql)) }
    }

    override fun <T> withConstructTriples(query: SparqlConstruct, consume: (Sequence<RdfTriple>) -> T): T = withConnection { conn ->
        val result = queryOperation(query.sparql) { conn.prepareGraphQuery(QueryLanguage.SPARQL, query.sparql).evaluate() }
        result.use {
            val seen = HashSet<org.eclipse.rdf4j.model.Triple>()
            consume(it.iterator().asSequence().flatMap { statement -> Rdf4jTerms.triplesOf(statement, seen) }.guardedBy(query.sparql))
        }
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
     * A triple term with a blank-node component cannot be written at all; it falls back to `setBinding`.
     */
    override fun <T> withSelectRows(query: SparqlSelect, bindings: Map<String, RdfTerm>, timeout: java.time.Duration,
        consume: (Sequence<BindingSet>) -> T): T {
        // Fresh variables: bound natively with setBinding, or declared with BIND for triple terms in expressions.
        val placeholders = LinkedHashMap<String, org.eclipse.rdf4j.model.Value>()
        val expressions = HashMap<String, String>()
        val constants = bindings.mapValues { (name, term) ->
            val spelled = sparqlConstant(term)
            when {
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
                val prepared = conn.prepareTupleQuery(QueryLanguage.SPARQL, sparql)
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
            // RDF4J's SPARQL-star syntax for a triple term whose components can all be spelled (triple patterns only).
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

    /** Wraps failures of the query engine itself; never used around caller-supplied consumers. */
    private inline fun <T> queryOperation(query: String, operation: () -> T): T = try {
        operation()
    } catch (e: RdfException) {
        throw e
    } catch (e: Exception) {
        throw RdfQueryException(failureMessage("SPARQL execution failed", query, e), query = query, cause = e)
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
        val prepared = try {
            conn.prepareBooleanQuery(QueryLanguage.SPARQL, query.sparql)
        } catch (e: Exception) {
            RdfDebug.logQueryError("ASK", query.sparql, "Failed to prepare: ${e.message}")
            throw RdfQueryException(
                message = failureMessage("Failed to prepare SPARQL ASK query", query.sparql, e),
                query = query.sparql,
                cause = e
            )
        }
        try {
            val result = prepared.evaluate()
            RdfDebug.logQueryTrace("ASK", query.sparql, null, System.currentTimeMillis() - startTime, if (result) 1 else 0)
            result
        } catch (e: Exception) {
            RdfDebug.logQueryError("ASK", query.sparql, "Failed to execute: ${e.message}")
            throw RdfQueryException(
                message = "Failed to execute SPARQL ASK query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
    }
    
    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> =
        graphQuery("CONSTRUCT", query.sparql)

    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> =
        graphQuery("DESCRIBE", query.sparql)

    /**
     * Shared CONSTRUCT/DESCRIBE execution. The result is materialized to a list inside
     * the connection scope because the borrowed connection (and its GraphQueryResult
     * cursor) is closed as soon as [withConnection] returns — a lazy sequence over a
     * closed connection would fail. Callers that need lazy streaming over large graphs
     * should use a scoped query API. Failures while preparing, evaluating or iterating
     * the result surface as [RdfQueryException].
     */
    private fun graphQuery(kind: String, sparql: String): Sequence<RdfTriple> = withConnection { conn ->
        val startTime = System.currentTimeMillis()
        val prepared = try {
            conn.prepareGraphQuery(QueryLanguage.SPARQL, sparql)
        } catch (e: Exception) {
            RdfDebug.logQueryError(kind, sparql, "Failed to prepare: ${e.message}")
            throw RdfQueryException(
                message = failureMessage("Failed to prepare SPARQL $kind query", sparql, e),
                query = sparql,
                cause = e
            )
        }
        val triples = try {
            prepared.evaluate().use { graphResult ->
                val seen = HashSet<org.eclipse.rdf4j.model.Triple>()
                graphResult.iterator().asSequence().flatMap { statement -> Rdf4jTerms.triplesOf(statement, seen) }.toList()
            }
        } catch (e: RdfException) {
            throw e
        } catch (e: Exception) {
            RdfDebug.logQueryError(kind, sparql, "Failed to execute: ${e.message}")
            throw RdfQueryException(message = "Failed to execute SPARQL $kind query: ${e.message}", query = sparql, cause = e)
        }
        RdfDebug.logQueryTrace(kind, sparql, null, System.currentTimeMillis() - startTime, triples.size)
        triples.asSequence()
    }
    
    override fun update(query: UpdateQuery) {
        withWriteConnection { conn ->
            val startTime = System.currentTimeMillis()
            // An update may create quoted-triple subjects (RDF-star syntax, TRIPLE(), LOAD or SERVICE data, or moving
            // stored triple terms into subject position).
            unvalidatedWrites = true
            val quotedSyntax = MAY_CREATE_TRIPLE_VALUES.containsMatchIn(query.sparql)
            if (quotedSyntax) noteQuotedWrite(QuotedLevel.UNKNOWN)
            // The update may remove the statements implying an explicit rdf:reifies triple; store those triples first.
            materialiseExplicitReifies(conn)
            try {
                conn.prepareUpdate(QueryLanguage.SPARQL, query.sparql).execute()
                // Checked after executing, so a triple value written concurrently (and visible to the update) counts.
                if (!quotedSyntax && tripleValuesMayExist) noteQuotedWrite(QuotedLevel.UNKNOWN)
                RdfDebug.logQueryTrace("UPDATE", query.sparql, null, System.currentTimeMillis() - startTime, null)
            } catch (e: Exception) {
                RdfDebug.logQueryError("UPDATE", query.sparql, "Failed to execute: ${e.message}")
                throw RdfQueryException(
                    message = failureMessage("Failed to execute SPARQL UPDATE", query.sparql, e),
                    query = query.sparql,
                    cause = e
                )
            }
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
            val pendingReifies = LinkedHashMap<ReifiesKey, Boolean>()
            explicitReifiesInTransaction.set(pendingReifies)
            try {
                conn.begin()
                operations(this)
                conn.commit()
                explicitReifiesInTransaction.remove()
                pendingReifies.forEach { (key, present) -> setExplicitReifies(key, present) }
            } catch (e: Throwable) {
                if (conn.isActive) conn.rollback()
                throw e
            } finally {
                txConnection.remove()
                readOnly.remove()
                explicitReifiesInTransaction.remove()
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
        forgetExplicitReifies { true }
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










