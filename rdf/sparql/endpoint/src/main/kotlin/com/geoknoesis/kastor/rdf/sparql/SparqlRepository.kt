package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val RESULTS_JSON = "application/sparql-results+json"
private const val FORM_ENCODED = "application/x-www-form-urlencoded"
private const val MAX_ERROR_BODY_BYTES = 4096
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

/**
 * Closes response streams whose read stalls past the read timeout or the request deadline. Closing
 * a `java.net.http` response stream from this thread unblocks a blocked read immediately (unlike
 * `HttpURLConnection.disconnect()`, which waits for the reading thread's stream lock). Daemon
 * thread; cancelled tasks are purged.
 */
private val DEADLINE_WATCHDOG = ScheduledThreadPoolExecutor(1) { runnable ->
    Thread(runnable, "kastor-sparql-deadline").apply { isDaemon = true }
}.apply { removeOnCancelPolicy = true }

private val LOGGER: System.Logger = System.getLogger(SparqlRepository::class.java.name)
private val INSECURE_AUTH_WARNED = ConcurrentHashMap.newKeySet<String>()

/**
 * [RdfRepository] over a remote SPARQL 1.1 Protocol endpoint.
 *
 * - SELECT/ASK results are read as `application/sparql-results+json`. [select] buffers all rows and
 *   is capped by [SparqlEndpointConfig.maxResponseBytes]; [withSelectRows] streams rows to the
 *   consumer and is capped by [SparqlEndpointConfig.maxStreamedResponseBytes] (unbounded by default).
 * - Timeouts: connect timeout, per-read timeout, [SparqlEndpointConfig.requestTimeout] (whole
 *   exchange for buffered calls, time to response headers for streams) and the optional
 *   [SparqlEndpointConfig.streamingRequestTimeout] for streams.
 * - Requests are never retried automatically, so a failed UPDATE is not re-sent.
 * - Redirects are handled explicitly: `307`/`308` keep method and body; `301`/`302`/`303` are only
 *   followed for GET queries (a redirected POST would otherwise silently lose its body). Other
 *   origins are only followed with [SparqlEndpointConfig.followCrossOriginRedirects], and never
 *   receive custom headers or credentials.
 * - Connections are pooled and reused (HTTP/1.1 keep-alive).
 * - Exceptions thrown by a [withSelectRows] consumer propagate unchanged; transport, HTTP and
 *   result-format failures surface as [RdfQueryException] (HTTP error bodies are included). Using a
 *   closed repository throws [IllegalStateException] from every operation.
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
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(config.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    init {
        insecureAuthorizationWarning(config)?.let { warning ->
            if (INSECURE_AUTH_WARNED.add(HttpTarget.redact(config.endpoint) + " " + config.updateEndpoint?.let(HttpTarget::redact))) {
                LOGGER.log(System.Logger.Level.WARNING, warning)
            }
        }
    }

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
        selectRows(query.sparql, config.maxResponseBytes, Timeouts.buffered(config.requestTimeout)) { ListSparqlQueryResult(it.toList()) }

    /**
     * Streams rows to [consume]. [SparqlEndpointConfig.requestTimeout] limits the wait for the
     * response headers; time spent consuming rows is only limited by
     * [SparqlEndpointConfig.streamingRequestTimeout] (and the per-read timeout).
     */
    override fun <T> withSelectRows(query: SparqlSelect, consume: (Sequence<BindingSet>) -> T): T =
        selectRows(query.sparql, config.maxStreamedResponseBytes, Timeouts(config.requestTimeout, config.streamingRequestTimeout), consume)

    /**
     * Streams rows with [bindings] as initial bindings and [timeout] as the deadline for the response
     * headers ([SparqlEndpointConfig.streamingRequestTimeout] still bounds the whole stream).
     *
     * SPARQL 1.1 Protocol has no initial-bindings parameter, so the bindings are substituted into the
     * query text with the same rules as the Jena provider (see [InitialBindings]): the constants
     * restrict the WHERE clause before aggregation, LIMIT, FILTER and sub-selects, and projected
     * bound variables stay in the results. Queries that assign a bound variable (BIND/AS/VALUES) or
     * use it inside a sub-select that does not project it are rejected with
     * [IllegalArgumentException]. Blank nodes cannot be used as bindings.
     */
    override fun <T> withSelectRows(
        query: SparqlSelect,
        bindings: Map<String, RdfTerm>,
        timeout: Duration,
        consume: (Sequence<BindingSet>) -> T,
    ): T {
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
        val rendered = bindings.mapValues { (_, term) ->
            SparqlTermFormat.term(term) { throw IllegalArgumentException("Blank nodes cannot be used as query bindings") }
        }
        val sparql = InitialBindings.apply(query.sparql, rendered)
        return selectRows(sparql, config.maxStreamedResponseBytes, Timeouts(timeout, config.streamingRequestTimeout), consume)
    }

    private fun <T> selectRows(sparql: String, byteLimit: Long?, timeouts: Timeouts, consume: (Sequence<BindingSet>) -> T): T {
        ensureOpen()
        val startTime = System.currentTimeMillis()
        var rows = 0
        try {
            val result = exchange(sparql, update = false, byteLimit = byteLimit, timeouts = timeouts) { input ->
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
        ensureOpen()
        val startTime = System.currentTimeMillis()
        try {
            val response = exchange(query.sparql, update = false, byteLimit = config.maxResponseBytes, timeouts = Timeouts.buffered(config.requestTimeout)) { input ->
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
        ensureOpen()
        val startTime = System.currentTimeMillis()
        try {
            exchange(query.sparql, update = true, byteLimit = config.maxResponseBytes, timeouts = Timeouts.buffered(config.requestTimeout)) { input ->
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

    /** Marks the repository closed and releases the connection pool; in-flight requests may complete. */
    override fun close() {
        closed = true
        client.shutdown()
    }

    private fun ensureOpen() = check(!closed) { "Repository is closed" }

    // ------------------------------------------------------------------------ HTTP

    /**
     * [headers] limits the wait for response headers (all redirect hops included); [overall] limits
     * the whole exchange including reading the body. Both are measured from the start of the call.
     */
    private class Timeouts(val headers: Duration?, val overall: Duration?) {
        companion object {
            fun buffered(timeout: Duration?) = Timeouts(timeout, timeout)
        }
    }

    private class Request(val uri: URI, val contentType: String?, val body: ByteArray?)

    private fun request(sparql: String, update: Boolean): Request {
        val target = if (update) updateTarget else queryTarget
        val url = target.url.toURI()
        return if (update) when (config.updateMethod) {
            SparqlUpdateMethod.POST -> Request(url, "application/sparql-update", sparql.toByteArray(Charsets.UTF_8))
            SparqlUpdateMethod.POST_FORM -> Request(url, FORM_ENCODED, "update=${HttpTarget.formEncode(sparql)}".toByteArray(Charsets.US_ASCII))
        } else when (config.queryMethod) {
            SparqlQueryMethod.POST -> Request(url, "application/sparql-query", sparql.toByteArray(Charsets.UTF_8))
            SparqlQueryMethod.POST_FORM -> Request(url, FORM_ENCODED, "query=${HttpTarget.formEncode(sparql)}".toByteArray(Charsets.US_ASCII))
            SparqlQueryMethod.GET -> {
                val get = target.withParameter("query", sparql).toURI()
                if (get.toString().length <= config.maxGetUrlLength) Request(get, null, null)
                else Request(url, FORM_ENCODED, "query=${HttpTarget.formEncode(sparql)}".toByteArray(Charsets.US_ASCII))
            }
        }
    }

    /**
     * Perform one request (following permitted redirects) and hand the (bounded) response body to
     * [handle]. Exceptions thrown by [handle] propagate unchanged; [RdfQueryException]s raised after
     * a timeout closed the stream are reported as that timeout.
     */
    private fun <T> exchange(
        sparql: String,
        update: Boolean,
        byteLimit: Long?,
        timeouts: Timeouts,
        handle: (InputStream) -> T,
    ): T {
        val startNanos = System.nanoTime()
        val target = if (update) updateTarget else queryTarget
        var request = request(sparql, update)
        var authorization = configuredAuthorization ?: target.userInfoAuthorization
        var sendCustomHeaders = true
        var redirects = 0
        while (true) {
            val builder = HttpRequest.newBuilder(request.uri)
            timeouts.headers?.let { limit ->
                val remaining = limit.minusNanos(System.nanoTime() - startNanos)
                if (remaining.isNegative || remaining.isZero) throw deadlineExceeded(sparql, limit)
                builder.timeout(remaining)
            }
            if (!update) builder.setHeader("Accept", RESULTS_JSON)
            request.contentType?.let { builder.setHeader("Content-Type", it) }
            if (sendCustomHeaders) config.headers.forEach { (name, value) -> builder.setHeader(name, value) }
            authorization?.let { builder.setHeader("Authorization", it) }
            val body = request.body
            if (body == null) builder.GET() else builder.POST(HttpRequest.BodyPublishers.ofByteArray(body))

            val response = send(builder.build(), sparql, timeouts.headers)
            val status = response.statusCode()
            if (status in REDIRECT_STATUSES) {
                val location = response.headers().firstValue("Location").orElse(null)
                closeQuietly(response.body())
                val next = redirectTarget(request, status, location, sparql)
                if (++redirects > config.maxRedirects) {
                    throw RdfQueryException("SPARQL endpoint redirected more than ${config.maxRedirects} times", query = sparql)
                }
                if (!sameOrigin(request.uri, next)) {
                    if (!config.followCrossOriginRedirects) {
                        throw RdfQueryException(
                            "SPARQL endpoint redirected (HTTP $status) to another origin (${origin(next)}); " +
                                "configure that endpoint URL directly or enable followCrossOriginRedirects",
                            query = sparql,
                        )
                    }
                    sendCustomHeaders = false
                    authorization = null
                }
                request = Request(next, request.contentType, request.body)
                continue
            }
            val deadlineNanos = timeouts.overall?.let { startNanos + it.toNanos() }
            val input = GuardedInputStream(response.body(), config.readTimeout, deadlineNanos, timeouts.overall)
            if (status !in 200..299) {
                val detail = input.use { readErrorBody(it) }
                throw RdfQueryException(
                    "SPARQL endpoint returned HTTP $status${if (detail.isEmpty()) "" else ": $detail"}",
                    query = sparql,
                )
            }
            try {
                return input.use { guarded -> handle(if (byteLimit == null) guarded else BoundedInputStream(guarded, byteLimit)) }
            } catch (e: RdfQueryException) {
                input.failure?.let { throw RdfQueryException(it, query = sparql, cause = e) }
                throw e
            }
        }
    }

    private fun send(request: HttpRequest, sparql: String, headerTimeout: Duration?): HttpResponse<InputStream> = try {
        client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    } catch (e: HttpConnectTimeoutException) {
        throw RdfQueryException("SPARQL request failed: connect timed out after ${config.connectTimeout.toMillis()} ms", query = sparql, cause = e)
    } catch (e: HttpTimeoutException) {
        throw RdfQueryException(
            "SPARQL request exceeded its ${headerTimeout?.toMillis()} ms deadline waiting for the response",
            query = sparql,
            cause = e,
        )
    } catch (e: IOException) {
        throw RdfQueryException("SPARQL request failed: ${e.message}", query = sparql, cause = e)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw RdfQueryException("SPARQL request was interrupted", query = sparql, cause = e)
    }

    private fun redirectTarget(current: Request, status: Int, location: String?, sparql: String): URI {
        if (location.isNullOrBlank()) throw RdfQueryException("SPARQL endpoint returned HTTP $status without a Location header", query = sparql)
        if (current.body != null && status !in setOf(307, 308)) {
            throw RdfQueryException(
                "SPARQL endpoint answered a POST with HTTP $status; following it would silently turn the request into a GET " +
                    "without the query. Configure the redirect target ($location) as the endpoint URL",
                query = sparql,
            )
        }
        val next = try {
            current.uri.resolve(URI(location))
        } catch (e: Exception) {
            throw RdfQueryException("SPARQL endpoint returned an invalid redirect Location: $location", query = sparql, cause = e)
        }
        val scheme = next.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || next.host.isNullOrEmpty() || next.rawUserInfo != null) {
            throw RdfQueryException("SPARQL endpoint redirected to an unsupported URL: ${origin(next)}", query = sparql)
        }
        return next
    }

    private fun origin(uri: URI) = "${uri.scheme}://${uri.host}:${effectivePort(uri)}"

    private fun effectivePort(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    private fun sameOrigin(a: URI, b: URI) =
        a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) && effectivePort(a) == effectivePort(b)

    private fun deadlineExceeded(sparql: String, limit: Duration) =
        RdfQueryException("SPARQL request exceeded its ${limit.toMillis()} ms deadline", query = sparql)

    private fun readErrorBody(input: InputStream): String = try {
        String(input.readNBytes(MAX_ERROR_BODY_BYTES), Charsets.UTF_8).trim()
    } catch (_: IOException) {
        ""
    }

    private fun closeQuietly(input: InputStream) {
        try { input.close() } catch (_: IOException) { }
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

    /**
     * Enforces the per-read timeout and the overall deadline on a response body: a watchdog closes
     * the underlying stream when one read waits too long, which unblocks the reader. Time between
     * reads (the consumer's own work) only counts towards the overall deadline, checked on the next
     * read.
     */
    private class GuardedInputStream(
        private val raw: InputStream,
        readTimeout: Duration,
        private val deadlineNanos: Long?,
        private val overall: Duration?,
    ) : InputStream() {
        private val readTimeoutNanos = readTimeout.toNanos()
        private val readTimeoutMessage = "SPARQL response read timed out after ${readTimeout.toMillis()} ms"
        private val deadlineMessage = "SPARQL request exceeded its ${overall?.toMillis()} ms deadline"

        @Volatile var failure: String? = null
            private set

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            failure?.let { throw IOException(it) }
            val untilDeadline = deadlineNanos?.let { it - System.nanoTime() }
            if (untilDeadline != null && untilDeadline <= 0) {
                failure = deadlineMessage
                closeRaw()
                throw IOException(deadlineMessage)
            }
            val byDeadline = untilDeadline != null && untilDeadline < readTimeoutNanos
            val wait = if (byDeadline) untilDeadline!! else readTimeoutNanos
            val message = if (byDeadline) deadlineMessage else readTimeoutMessage
            val watchdog = DEADLINE_WATCHDOG.schedule({
                failure = message
                closeRaw()
            }, wait, TimeUnit.NANOSECONDS)
            try {
                return raw.read(b, off, len)
            } catch (e: IOException) {
                failure?.let { throw IOException(it, e) }
                throw e
            } finally {
                watchdog.cancel(false)
            }
        }

        private fun closeRaw() {
            try { raw.close() } catch (_: IOException) { }
        }

        override fun close() = raw.close()
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

    internal companion object {
        /** The warning logged (once per endpoint) when Basic credentials would travel over plain http, or `null`. */
        fun insecureAuthorizationWarning(config: SparqlEndpointConfig): String? {
            val query = HttpTarget.parse(config.endpoint)
            val update = config.updateEndpoint?.let(HttpTarget::parse) ?: query
            val exposed = listOf(query, update).filter { target ->
                target.isPlainHttp && (config.username != null || target.userInfoAuthorization != null)
            }
            if (exposed.isEmpty()) return null
            return "SPARQL endpoint ${exposed.joinToString { HttpTarget.redact(it.url.toString()) }} uses HTTP Basic " +
                "authentication over plain http; credentials are sent unencrypted. Use https."
        }
    }
}

/**
 * Graph view over a [SparqlRepository] (or any [SparqlMutable]).
 *
 * ## Blank nodes
 * Blank-node labels in SPARQL Update are scoped to a single request, so:
 * - [addTriple]/[addTriples] accept blank nodes. Triples connected through blank nodes form a
 *   component that is always sent in one request (never split); components and triples without
 *   blank nodes are packed into requests of up to [SparqlEndpointConfig.insertBatchSize] triples (a
 *   larger component is sent alone). Components larger than
 *   [SparqlEndpointConfig.maxBlankNodeComponentTriples] are rejected before anything is sent. Labels
 *   are re-issued per request, so every call creates fresh blank nodes on the endpoint.
 *   Endpoint-assigned identifiers read back from results (for example Virtuoso's `nodeID://b1`) can
 *   therefore be copied into another graph.
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

    private val endpointConfig: SparqlEndpointConfig?
        get() = (repository as? SparqlRepository)?.config

    private val batchSize: Int
        get() = endpointConfig?.insertBatchSize ?: SparqlEndpointConfig.DEFAULT_INSERT_BATCH_SIZE

    private fun pattern(body: String): String =
        if (graphName == null) body else "GRAPH ${SparqlTermFormat.iriRef(graphName.value)} { $body }"
    private fun rendered(t: RdfTriple, blankNode: (BlankNode) -> String): String =
        "${SparqlTermFormat.term(t.subject, blankNode)} ${SparqlTermFormat.iriRef(t.predicate.value)} ${SparqlTermFormat.term(t.obj, blankNode)} ."

    /** Same as `addTriples(listOf(triple))`. */
    override fun addTriple(triple: RdfTriple) {
        addTriples(listOf(triple))
    }

    /** `INSERT DATA` in batches; see the class documentation for blank nodes. */
    override fun addTriples(triples: Collection<RdfTriple>) {
        if (triples.isEmpty()) return
        val maxComponent = endpointConfig?.maxBlankNodeComponentTriples ?: SparqlEndpointConfig.DEFAULT_MAX_BLANK_NODE_COMPONENT_TRIPLES
        val units = blankNodeComponents(triples)
        units.firstOrNull { it.size > maxComponent }?.let { component ->
            throw IllegalArgumentException(
                "${component.size} triples are connected through blank nodes and must be sent in one request, " +
                    "which exceeds maxBlankNodeComponentTriples ($maxComponent); nothing was inserted"
            )
        }
        val batch = ArrayList<RdfTriple>()
        for (unit in units) {
            if (batch.isNotEmpty() && batch.size + unit.size > batchSize) {
                insertData(batch)
                batch.clear()
            }
            batch.addAll(unit)
        }
        if (batch.isNotEmpty()) insertData(batch)
    }

    /**
     * Groups [triples] into units that must travel together: each connected component of triples
     * sharing blank nodes, and each triple without blank nodes on its own. Order of first occurrence
     * is kept.
     */
    private fun blankNodeComponents(triples: Collection<RdfTriple>): List<List<RdfTriple>> {
        val parent = HashMap<String, String>()
        fun find(id: String): String {
            var root = id
            while (parent.getValue(root) != root) root = parent.getValue(root)
            var node = id
            while (parent.getValue(node) != root) {
                val next = parent.getValue(node)
                parent[node] = root
                node = next
            }
            return root
        }
        fun blankIds(t: RdfTriple) = listOfNotNull((t.subject as? BlankNode)?.id, (t.obj as? BlankNode)?.id)
        for (t in triples) {
            val ids = blankIds(t)
            ids.forEach { parent.putIfAbsent(it, it) }
            if (ids.size == 2) {
                val a = find(ids[0])
                val b = find(ids[1])
                if (a != b) parent[b] = a
            }
        }
        val components = LinkedHashMap<Any, MutableList<RdfTriple>>()
        for (t in triples) {
            val ids = blankIds(t)
            val key: Any = if (ids.isEmpty()) Any() else find(ids[0])
            components.getOrPut(key) { ArrayList() }.add(t)
        }
        return components.values.toList()
    }

    private fun insertData(triples: Collection<RdfTriple>) {
        val labels = HashMap<String, String>()
        val body = triples.joinToString("\n") { t -> rendered(t) { node -> "_:" + labels.getOrPut(node.id) { "b${labels.size}" } } }
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
