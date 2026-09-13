package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val RESULTS_JSON = "application/sparql-results+json"
private const val FORM_ENCODED = "application/x-www-form-urlencoded"
private const val MAX_ERROR_BODY_BYTES = 4096

/** Disconnects requests that outlive their overall deadline. Daemon thread; cancelled tasks are purged. */
private val DEADLINE_WATCHDOG = ScheduledThreadPoolExecutor(1) { runnable ->
    Thread(runnable, "kastor-sparql-deadline").apply { isDaemon = true }
}.apply { removeOnCancelPolicy = true }

/**
 * [RdfRepository] over a remote SPARQL 1.1 Protocol endpoint.
 *
 * - SELECT/ASK results are read as `application/sparql-results+json`. [select] buffers all rows and
 *   is capped by [SparqlEndpointConfig.maxResponseBytes]; [withSelectRows] streams rows to the
 *   consumer and is capped by [SparqlEndpointConfig.maxStreamedResponseBytes] (unbounded by default).
 * - Each request has a connect timeout, a per-read timeout and an overall deadline
 *   ([SparqlEndpointConfig.requestTimeout]) enforced by a watchdog that disconnects the request.
 * - Connections are not force-closed, so the JDK keep-alive pool reuses them.
 * - Exceptions thrown by a [withSelectRows] consumer propagate unchanged; transport, HTTP and
 *   result-format failures surface as [RdfQueryException] (HTTP error bodies are included).
 * - No transactions; CONSTRUCT/DESCRIBE are unsupported (this module has no RDF parser).
 */
class SparqlRepository(val config: SparqlEndpointConfig) : RdfRepository {

    constructor(
        endpoint: String,
        maxResponseBytes: Long = SparqlEndpointConfig.DEFAULT_MAX_RESPONSE_BYTES,
        connectTimeoutMillis: Int = SparqlEndpointConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS,
        readTimeoutMillis: Int = SparqlEndpointConfig.DEFAULT_READ_TIMEOUT_MILLIS,
    ) : this(
        SparqlEndpointConfig(
            endpoint = endpoint,
            maxResponseBytes = maxResponseBytes,
            connectTimeout = Duration.ofMillis(connectTimeoutMillis.toLong()),
            readTimeout = Duration.ofMillis(readTimeoutMillis.toLong()),
        )
    )

    @Volatile private var closed = false
    private val queryTarget = HttpTarget.parse(config.endpoint)
    private val updateTarget = config.updateEndpoint?.let(HttpTarget::parse) ?: queryTarget
    private val configuredAuthorization =
        config.username?.let { HttpTarget.basicAuthorization(it, config.password.orEmpty()) }

    override val defaultGraph: RdfGraph = SparqlGraph(this)

    override fun getGraph(name: Iri): RdfGraph = SparqlGraph(this, name)

    override fun hasGraph(name: Iri): Boolean {
        val query = SparqlAskQuery("ASK { GRAPH ${SparqlTermFormat.iriRef(name.value)} { ?s ?p ?o } }")
        return ask(query)
    }

    override fun listGraphs(): List<Iri> {
        val query = SparqlSelectQuery("SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }")
        val result = select(query)
        return result.mapNotNull { binding ->
            binding.get("g")?.let { term ->
                if (term is Iri) term else null
            }
        }
    }

    override fun createGraph(name: Iri): RdfGraph = SparqlGraph(this, name)

    override fun removeGraph(name: Iri): Boolean {
        val existed = hasGraph(name)
        val updateQuery = UpdateQuery("DROP SILENT GRAPH ${SparqlTermFormat.iriRef(name.value)}")
        update(updateQuery)
        return existed
    }

    override fun editDefaultGraph(): MutableRdfGraph {
        return defaultGraph as MutableRdfGraph
    }

    override fun editGraph(name: Iri): MutableRdfGraph {
        return getGraph(name) as MutableRdfGraph
    }

    override fun select(query: SparqlSelect): SparqlQueryResult =
        selectRows(query.sparql, config.maxResponseBytes, config.requestTimeout) { ListSparqlQueryResult(it.toList()) }

    override fun <T> withSelectRows(query: SparqlSelect, consume: (Sequence<BindingSet>) -> T): T =
        selectRows(query.sparql, config.maxStreamedResponseBytes, config.requestTimeout, consume)

    /**
     * Streams rows with [bindings] applied and [timeout] as the overall request deadline.
     *
     * SPARQL 1.1 Protocol has no initial-bindings parameter, so the bindings are sent as a trailing
     * `VALUES` block, i.e. joined with the query's solutions. Queries that already end with a
     * `VALUES` clause cannot take additional bindings.
     */
    override fun <T> withSelectRows(
        query: SparqlSelect,
        bindings: Map<String, RdfTerm>,
        timeout: Duration,
        consume: (Sequence<BindingSet>) -> T,
    ): T {
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
        val sparql = if (bindings.isEmpty()) {
            query.sparql
        } else {
            val names = bindings.keys.joinToString(" ") { "?${SparqlTermFormat.varName(it)}" }
            val values = bindings.values.joinToString(" ") { term ->
                SparqlTermFormat.term(term) { throw IllegalArgumentException("Blank nodes cannot be used as query bindings") }
            }
            "${query.sparql.trimEnd()}\nVALUES ($names) { ($values) }\n"
        }
        return selectRows(sparql, config.maxStreamedResponseBytes, timeout, consume)
    }

    private fun <T> selectRows(sparql: String, byteLimit: Long?, timeout: Duration?, consume: (Sequence<BindingSet>) -> T): T {
        val startTime = System.currentTimeMillis()
        var rows = 0
        try {
            val result = exchange(sparql, update = false, byteLimit = byteLimit, timeout = timeout) { input ->
                val sequence = JsonBindingRows(input).rows()
                    .map(SparqlJsonResults::row)
                    .guarded(sparql)
                    .onEach { rows++ }
                consume(sequence)
            }
            RdfDebug.logQueryTrace("SELECT", sparql, null, System.currentTimeMillis() - startTime, rows)
            return result
        } catch (e: RdfQueryException) {
            RdfDebug.logQueryError("SELECT", sparql, "Failed to execute: ${e.message}")
            throw e
        }
    }

    override fun ask(query: SparqlAsk): Boolean {
        val startTime = System.currentTimeMillis()
        try {
            val response = exchange(query.sparql, update = false, byteLimit = config.maxResponseBytes, timeout = config.requestTimeout) { input ->
                readResponse(query.sparql) { input.reader(Charsets.UTF_8).readText() }
            }
            // Parse the SPARQL Results JSON `{ "boolean": true }` form; fall back to
            // the plain-text `true`/`false` some endpoints return.
            val result = SparqlJsonResults.parseAsk(response)
            val executionTime = System.currentTimeMillis() - startTime
            RdfDebug.logQueryTrace("ASK", query.sparql, null, executionTime, if (result) 1 else 0)
            return result
        } catch (e: Exception) {
            RdfDebug.logQueryError("ASK", query.sparql, "Failed to execute: ${e.message}")
            if (e is RdfQueryException) throw e
            throw RdfQueryException(
                message = "Failed to execute SPARQL ASK query: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
    }

    /**
     * CONSTRUCT/DESCRIBE return an RDF graph serialization (Turtle/N-Triples/…),
     * which requires an RDF parser. This endpoint adapter intentionally has no
     * parser, so we fail loudly rather than silently returning an empty graph.
     * Run CONSTRUCT/DESCRIBE through a provider-backed repository (Jena/RDF4J) instead.
     */
    override fun construct(query: SparqlConstruct): Sequence<RdfTriple> =
        throw UnsupportedOperationException(
            "CONSTRUCT over a remote SPARQL endpoint is not supported by the core-only HTTP adapter; " +
                "use a Jena/RDF4J-backed repository to parse the returned RDF graph."
        )

    override fun describe(query: SparqlDescribe): Sequence<RdfTriple> =
        throw UnsupportedOperationException(
            "DESCRIBE over a remote SPARQL endpoint is not supported by the core-only HTTP adapter; " +
                "use a Jena/RDF4J-backed repository to parse the returned RDF graph."
        )

    override fun update(query: UpdateQuery) {
        val startTime = System.currentTimeMillis()
        try {
            exchange(query.sparql, update = true, byteLimit = config.maxResponseBytes, timeout = config.requestTimeout) { input ->
                readResponse(query.sparql) { input.readAllBytes() }
            }
            val executionTime = System.currentTimeMillis() - startTime
            RdfDebug.logQueryTrace("UPDATE", query.sparql, null, executionTime, null)
        } catch (e: Exception) {
            RdfDebug.logQueryError("UPDATE", query.sparql, "Failed to execute: ${e.message}")
            if (e is RdfQueryException) throw e
            throw RdfQueryException(
                message = "Failed to execute SPARQL UPDATE: ${e.message}",
                query = query.sparql,
                cause = e
            )
        }
    }

    override fun transaction(operations: RdfRepository.() -> Unit) {
        throw UnsupportedOperationException("The HTTP endpoint does not provide atomic transactions")
    }

    override fun readTransaction(operations: RdfRepository.() -> Unit) {
        throw UnsupportedOperationException("The HTTP endpoint does not provide atomic transactions")
    }

    override fun clear(): Boolean {
        val existed = ask(SparqlAskQuery("ASK { { ?s ?p ?o } UNION { GRAPH ?g { ?s ?p ?o } } }"))
        val updateQuery = UpdateQuery("CLEAR ALL")
        update(updateQuery)
        return existed
    }

    override fun isClosed(): Boolean = closed

    /** What this adapter supports; identical to [SparqlProvider.getCapabilities]. */
    override fun getCapabilities(): ProviderCapabilities = SPARQL_ENDPOINT_CAPABILITIES

    override fun close() {
        closed = true
    }

    // ------------------------------------------------------------------------ HTTP

    private class Request(val url: URL, val contentType: String?, val body: ByteArray?)

    private fun request(sparql: String, update: Boolean): Request {
        val target = if (update) updateTarget else queryTarget
        return if (update) when (config.updateMethod) {
            SparqlUpdateMethod.POST -> Request(target.url, "application/sparql-update", sparql.toByteArray(Charsets.UTF_8))
            SparqlUpdateMethod.POST_FORM -> Request(target.url, FORM_ENCODED, "update=${HttpTarget.formEncode(sparql)}".toByteArray(Charsets.US_ASCII))
        } else when (config.queryMethod) {
            SparqlQueryMethod.POST -> Request(target.url, "application/sparql-query", sparql.toByteArray(Charsets.UTF_8))
            SparqlQueryMethod.POST_FORM -> Request(target.url, FORM_ENCODED, "query=${HttpTarget.formEncode(sparql)}".toByteArray(Charsets.US_ASCII))
            SparqlQueryMethod.GET -> Request(target.withParameter("query", sparql), null, null)
        }
    }

    /**
     * Perform one request and hand the (bounded) response body to [handle]. Exceptions thrown by
     * [handle] propagate unchanged; [RdfQueryException]s raised after the deadline fired are
     * reported as a deadline failure.
     */
    private fun <T> exchange(
        sparql: String,
        update: Boolean,
        byteLimit: Long?,
        timeout: Duration?,
        handle: (InputStream) -> T,
    ): T {
        check(!closed) { "Repository is closed" }
        val target = if (update) updateTarget else queryTarget
        val request = request(sparql, update)
        val connection = (request.url.openConnection() as HttpURLConnection).apply {
            requestMethod = if (request.body == null) "GET" else "POST"
            connectTimeout = config.connectTimeout.toMillis().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            readTimeout = config.readTimeout.toMillis().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            useCaches = false
            if (!update) setRequestProperty("Accept", RESULTS_JSON)
            request.contentType?.let { setRequestProperty("Content-Type", it) }
            config.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            (configuredAuthorization ?: target.userInfoAuthorization)?.let { setRequestProperty("Authorization", it) }
            doOutput = request.body != null
        }
        val timedOut = AtomicBoolean(false)
        val watchdog = timeout?.let { limit ->
            DEADLINE_WATCHDOG.schedule({
                timedOut.set(true)
                connection.disconnect()
            }, limit.toMillis(), TimeUnit.MILLISECONDS)
        }
        try {
            val status = try {
                request.body?.let { body -> connection.outputStream.use { it.write(body) } }
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw RdfQueryException("SPARQL request failed: ${e.message}", query = sparql, cause = e)
            }
            if (status !in 200..299) {
                val detail = readErrorBody(connection)
                throw RdfQueryException(
                    "SPARQL endpoint returned HTTP $status${if (detail.isEmpty()) "" else ": $detail"}",
                    query = sparql,
                )
            }
            val input = try {
                connection.inputStream
            } catch (e: IOException) {
                connection.disconnect()
                throw RdfQueryException("SPARQL response failed: ${e.message}", query = sparql, cause = e)
            }
            return input.use { raw -> handle(if (byteLimit == null) raw else BoundedInputStream(raw, byteLimit)) }
        } catch (e: RdfQueryException) {
            if (timedOut.get()) {
                throw RdfQueryException("SPARQL request exceeded its ${timeout?.toMillis()} ms deadline", query = sparql, cause = e)
            }
            throw e
        } finally {
            watchdog?.cancel(false)
        }
    }

    private fun readErrorBody(connection: HttpURLConnection): String = try {
        connection.errorStream?.use { String(it.readNBytes(MAX_ERROR_BODY_BYTES), Charsets.UTF_8) }.orEmpty().trim()
    } catch (_: IOException) {
        ""
    }

    /** Run an internal response reader, reporting I/O and format problems as [RdfQueryException]. */
    private inline fun <R> readResponse(sparql: String, read: () -> R): R = try {
        read()
    } catch (e: RdfQueryException) {
        throw e
    } catch (e: Exception) {
        throw RdfQueryException("Failed to read SPARQL response: ${e.message}", query = sparql, cause = e)
    }

    /**
     * Wrap failures while producing rows (I/O, size cap, malformed JSON) as [RdfQueryException],
     * without touching exceptions thrown by the code consuming the rows.
     */
    private fun <R> Sequence<R>.guarded(sparql: String): Sequence<R> {
        val source = this
        return Sequence {
            val iterator = source.iterator()
            object : Iterator<R> {
                override fun hasNext(): Boolean = readResponse(sparql) { iterator.hasNext() }
                override fun next(): R = readResponse(sparql) { iterator.next() }
            }
        }
    }

    private class BoundedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L

        private fun counted(n: Long): Long {
            if (n > 0) {
                count += n
                if (count > limit) throw IOException("SPARQL response exceeds $limit bytes")
            }
            return n
        }

        override fun read(): Int {
            val b = `in`.read()
            if (b >= 0) counted(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = counted(`in`.read(b, off, len).toLong()).toInt()

        override fun skip(n: Long): Long = counted(`in`.skip(n))
    }
}

/**
 * Graph view over a [SparqlRepository] (or any [SparqlMutable]).
 *
 * ## Blank nodes
 * Blank-node labels in SPARQL Update are scoped to a single request, so:
 * - [addTriple]/[addTriples] accept blank nodes. A call containing blank nodes is sent as one
 *   request (never split into batches) and its labels are re-issued, so every call creates fresh
 *   blank nodes on the endpoint. Endpoint-assigned identifiers read back from results (for example
 *   Virtuoso's `nodeID://b1`) can therefore be copied into another graph.
 * - [hasTriple], [find], [removeTriple] and [removeTriples] cannot address an existing blank node
 *   by label and throw [IllegalArgumentException]; use an explicit `DELETE WHERE` pattern instead.
 */
class SparqlGraph(
    private val repository: SparqlMutable,
    private val graphName: Iri? = null
) : MutableRdfGraph, SourceTrackedGraph {

    override val sourceRepository: RdfRepository?
        get() = repository as? RdfRepository
    override val sourceGraphName: Iri? = graphName

    private val batchSize: Int
        get() = (repository as? SparqlRepository)?.config?.insertBatchSize ?: SparqlEndpointConfig.DEFAULT_INSERT_BATCH_SIZE

    private fun pattern(body: String): String =
        if (graphName == null) body else "GRAPH ${SparqlTermFormat.iriRef(graphName.value)} { $body }"
    private fun hasBlank(t: RdfTriple): Boolean = t.subject is BlankNode || t.obj is BlankNode
    private fun rendered(t: RdfTriple, blankNode: (BlankNode) -> String): String =
        "${SparqlTermFormat.term(t.subject, blankNode)} ${SparqlTermFormat.iriRef(t.predicate.value)} ${SparqlTermFormat.term(t.obj, blankNode)} ."

    /** Same as `addTriples(listOf(triple))`. */
    override fun addTriple(triple: RdfTriple) {
        addTriples(listOf(triple))
    }

    /**
     * `INSERT DATA`. Triples without blank nodes are sent in batches of
     * [SparqlEndpointConfig.insertBatchSize]; see the class documentation for blank nodes.
     */
    override fun addTriples(triples: Collection<RdfTriple>) {
        if (triples.isEmpty()) return
        if (triples.any(::hasBlank)) {
            val labels = HashMap<String, String>()
            insertData(triples) { node -> "_:" + labels.getOrPut(node.id) { "b${labels.size}" } }
        } else {
            triples.chunked(batchSize).forEach { batch -> insertData(batch, ::rejectBlankNode) }
        }
    }

    private fun insertData(triples: Collection<RdfTriple>, blankNode: (BlankNode) -> String) {
        val body = triples.joinToString("\n") { rendered(it, blankNode) }
        repository.update(UpdateQuery("INSERT DATA { ${pattern(body)} }"))
    }

    override fun removeTriple(triple: RdfTriple): Boolean = removeTriples(listOf(triple))

    /**
     * `DELETE DATA` in batches of [SparqlEndpointConfig.insertBatchSize]. Returns `true` if at least
     * one triple existed before deletion; this is determined with a single `ASK ... VALUES` per batch
     * (skipped once a match is known) rather than one request per triple. The check and the delete
     * are separate requests, so concurrent writers can race with them.
     */
    override fun removeTriples(triples: Collection<RdfTriple>): Boolean {
        if (triples.isEmpty()) return false
        var existed = false
        triples.chunked(batchSize).forEach { batch ->
            val body = batch.joinToString("\n") { rendered(it, ::rejectBlankNode) }
            if (!existed) existed = anyExists(batch)
            repository.update(UpdateQuery("DELETE DATA { ${pattern(body)} }"))
        }
        return existed
    }

    private fun anyExists(batch: List<RdfTriple>): Boolean {
        val rows = batch.joinToString(" ") { t ->
            "(${SparqlTermFormat.term(t.subject, ::rejectBlankNode)} ${SparqlTermFormat.iriRef(t.predicate.value)} " +
                "${SparqlTermFormat.term(t.obj, ::rejectBlankNode)})"
        }
        return repository.ask(SparqlAskQuery("ASK { VALUES (?s ?p ?o) { $rows } ${pattern("?s ?p ?o")} }"))
    }

    override fun hasTriple(triple: RdfTriple): Boolean {
        val body = "${SparqlTermFormat.term(triple.subject, ::rejectBlankNode)} ${SparqlTermFormat.iriRef(triple.predicate.value)} " +
            SparqlTermFormat.term(triple.obj, ::rejectBlankNode)
        return repository.ask(SparqlAskQuery("ASK { ${pattern(body)} }"))
    }

    override fun getTriples(): List<RdfTriple> = find()
    fun getTriples(subject: RdfResource? = null, predicate: Iri? = null, obj: RdfTerm? = null): List<RdfTriple> = find(subject, predicate, obj)
    override fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> {
        val body = "${subject?.let { SparqlTermFormat.term(it, ::rejectBlankNode) } ?: "?s"} " +
            "${predicate?.let { SparqlTermFormat.iriRef(it.value) } ?: "?p"} " +
            "${obj?.let { SparqlTermFormat.term(it, ::rejectBlankNode) } ?: "?o"} ."
        val result = repository.select(SparqlSelectQuery("SELECT * WHERE { ${pattern(body)} }"))
        return result.map { row -> RdfTriple(subject ?: row.get("s") as RdfResource,
            predicate ?: row.get("p") as Iri, obj ?: row.get("o") as RdfTerm) }
    }

    override fun clear(): Boolean {
        val existed = repository.ask(SparqlAskQuery("ASK { ${pattern("?s ?p ?o")} }"))
        repository.update(UpdateQuery(if (graphName == null) "CLEAR DEFAULT" else "CLEAR SILENT GRAPH ${SparqlTermFormat.iriRef(graphName.value)}"))
        return existed
    }

    override fun size(): Int {
        val query = "SELECT (COUNT(*) AS ?count) WHERE { ${pattern("?s ?p ?o")} }"
        val result = repository.select(SparqlSelectQuery(query))
        return result.firstOrNull()?.get("count")?.let { term ->
            if (term is Literal) Math.toIntExact(term.lexical.toLong()) else 0
        } ?: 0
    }

    private fun rejectBlankNode(node: BlankNode): String = throw IllegalArgumentException(
        "Blank node '${node.id}' cannot be addressed over SPARQL: blank-node labels are scoped to one request " +
            "and endpoint-assigned identifiers are not valid query constants; use an explicit DELETE WHERE pattern instead"
    )
}

/**
 * Parser for the SPARQL 1.1 Query Results JSON Format (application/sparql-results+json).
 */
private object SparqlJsonResults {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parseAsk(response: String): Boolean {
        val trimmed = response.trim()
        // Some endpoints return a bare `true`/`false` for ASK.
        if (trimmed.equals("true", ignoreCase = true)) return true
        if (trimmed.equals("false", ignoreCase = true)) return false
        val root = json.parseToJsonElement(trimmed).jsonObject
        return root["boolean"]?.jsonPrimitive?.booleanOrNull
            ?: error("SPARQL ASK response missing 'boolean' field")
    }

    fun row(row: JsonObject): BindingSet = MapBindingSet(row.mapValues { (_, value) -> termFromBinding(value.jsonObject) })

    private fun termFromBinding(binding: JsonObject): RdfTerm {
        val type = binding["type"]?.jsonPrimitive?.contentOrNull ?: error("SPARQL binding missing required field")
        val value = binding["value"]?.jsonPrimitive?.contentOrNull ?: error("SPARQL binding missing required field")
        return when (type) {
            "uri" -> Iri(value)
            // Kept verbatim (e.g. Virtuoso `nodeID://b1`); see SparqlGraph for what can be done with it.
            "bnode" -> BlankNode(value)
            "literal", "typed-literal" -> {
                require(binding["its:dir"] == null && binding["direction"] == null) { "Directional result literals are unsupported" }
                val lang = binding["xml:lang"]?.jsonPrimitive?.contentOrNull
                val datatype = binding["datatype"]?.jsonPrimitive?.contentOrNull
                when {
                    !lang.isNullOrEmpty() -> LangString(value, lang)
                    datatype != null -> Literal(value, Iri(datatype))
                    else -> Literal(value, XSD.string)
                }
            }
            else -> error("Unsupported SPARQL result binding type: $type")
        }
    }
}
