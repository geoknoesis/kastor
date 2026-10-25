package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.sparql.internal.SparqlInitialBindings
import java.io.IOException
import java.io.InputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts one HTTP exchange; the future completes when the response headers have arrived, and
 * cancelling it (`cancel(true)`) aborts the exchange. Production code uses the shared [HttpClient];
 * tests substitute their own to observe requests or to play a server they cannot run locally.
 */
internal fun interface HttpTransport {
    fun send(request: HttpRequest): CompletableFuture<HttpResponse<InputStream>>
}

private val LOGGER: System.Logger = System.getLogger(SparqlRepository::class.java.name)
private val INSECURE_AUTH_WARNED = ConcurrentHashMap.newKeySet<String>()

/**
 * [RdfRepository] over a remote SPARQL 1.1 Protocol endpoint.
 *
 * - SELECT/ASK results are read as `application/sparql-results+json` (the request also accepts
 *   `application/json` at lower priority). [select] buffers all rows and
 *   is capped by [SparqlEndpointConfig.maxResponseBytes]; [withSelectRows] streams rows to the
 *   consumer and is capped by [SparqlEndpointConfig.maxStreamedResponseBytes] (unbounded by default)
 *   and, as a whole call, by [SparqlEndpointConfig.streamingRequestTimeout] (one hour by default,
 *   so a server dripping one byte per read timeout cannot hold the thread forever).
 * - Timeouts: connect timeout, per-read timeout, [SparqlEndpointConfig.requestTimeout] (whole
 *   exchange for buffered calls, time to response headers for streams; without it the read timeout
 *   bounds the wait for headers), [SparqlEndpointConfig.streamingRequestTimeout] (one hour by default, `null` for none) for
 *   streams, and the per-call timeout of `withSelectRows(query, bindings, timeout)`, which bounds
 *   the whole call. Whichever deadline ends first also ends the wait for the response headers. That
 *   wait is one budget for the whole call, redirects included. The adapter enforces all of these
 *   itself and sets no timeout on the HTTP request, whose meaning differs between JDK versions.
 *   A timeout too long to be counted in nanoseconds (about 292 years, for example
 *   `Duration.ofMillis(Long.MAX_VALUE)`) means "unbounded".
 * - Requests are never retried automatically, so a failed UPDATE is not re-sent.
 * - Redirects are handled explicitly: `307`/`308` keep method and body; `301`/`302`/`303` are only
 *   followed for GET queries (a redirected POST would otherwise silently lose its body). Other
 *   origins are only followed with [SparqlEndpointConfig.followCrossOriginRedirects], and never
 *   receive custom headers or credentials. Redirects from `https` to plain `http` are always refused.
 * - A successful SELECT/ASK response must declare a JSON Content-Type (`text/plain` is also
 *   accepted for ASK); anything else, such as an HTML login page, fails with [RdfQueryException]
 *   naming the returned type. A response without a Content-Type is parsed as JSON. Legacy servers
 *   that label JSON results differently need [SparqlEndpointConfig.strictContentType] `false`.
 * - No single result row (or other JSON value) larger than
 *   [SparqlEndpointConfig.maxResultRowChars] characters is read into memory; rows are decoded one
 *   at a time, straight from the stream.
 * - Connections are pooled and reused (HTTP/1.1 keep-alive). Repositories with the same connect
 *   timeout share one HTTP client, which is shut down when the last of them is closed, and all
 *   repositories share the daemon thread that enforces read deadlines, which ends when the last of
 *   them is closed (and starts again on demand). So always [close] repositories you no longer use.
 * - Exceptions thrown by a [withSelectRows] consumer propagate unchanged; transport, HTTP and
 *   result-format failures surface as [RdfQueryException], from every operation alike. The message
 *   for an HTTP error status quotes the start of the response body as one printable line of at
 *   most 512 characters, with control characters escaped. Using a closed repository throws [IllegalStateException] from every
 *   operation.
 * - Results are decoded as SPARQL 1.1 JSON, strictly: UTF-8 only (a leading byte order mark is
 *   ignored, malformed bytes are an error), JSON white space only, no unescaped control character
 *   or unpaired surrogate in a string, no repeated member where one is read, string members only
 *   in a binding, and no variable that a preceding `head` does not declare. RDF 1.2 result terms
 *   (triple terms, literals with a base direction) are rejected with a message that says so.
 * - A row with a term the RDF model refuses (an IRI that is relative or holds a space, for example)
 *   fails the query, or is skipped with one logged warning per query when
 *   [SparqlEndpointConfig.malformedTerms] is [MalformedTermPolicy.SKIP_ROW].
 * - No transactions; CONSTRUCT/DESCRIBE are unsupported (this module has no RDF parser).
 * - A [SandboxedSelectQuery] (an untrusted query, for example from a SHACL shapes graph) is refused with
 *   [UnsupportedOperationException] by [select] and [withSelectRows] before any request: the endpoint evaluates the
 *   query itself, so the adapter cannot keep it from federating (`SERVICE`) to other hosts.
 */
class SparqlRepository internal constructor(
    val config: SparqlEndpointConfig,
    transport: HttpTransport?,
    private val watchdog: ReadWatchdog = ReadWatchdog.shared,
) : RdfRepository {

    constructor(config: SparqlEndpointConfig) : this(config, null)

    @JvmOverloads
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

    private val closed = AtomicBoolean(false)
    private val client: HttpClient = SharedHttpClients.acquire(config.connectTimeout)
    private val http = EndpointHttp(
        config,
        transport ?: HttpTransport { client.sendAsync(it, HttpResponse.BodyHandlers.ofInputStream()) },
        watchdog,
    )

    init {
        // The thread that enforces read deadlines runs while a repository is open (and for streams still being read).
        watchdog.retain()
        insecureAuthorizationWarning(config)?.let { warning ->
            if (INSECURE_AUTH_WARNED.add(HttpTarget.redact(config.endpoint) + " " + config.updateEndpoint?.let(HttpTarget::redact))) {
                LOGGER.log(System.Logger.Level.WARNING, warning)
            }
        }
    }

    override val defaultGraph: RdfGraph = SparqlGraph(this)

    override fun getGraph(name: Iri): RdfGraph = SparqlGraph(this, name)

    /**
     * Whether the endpoint holds at least one triple in the graph. SPARQL 1.1 has no way to ask
     * whether a named graph exists: stores that keep empty graphs (and those that create them on
     * `CREATE GRAPH`) are indistinguishable from stores without them, so an existing but empty
     * graph reports `false`, as does a graph just made with [createGraph]. [removeGraph] inherits
     * this and returns `false` for such a graph (it is still dropped).
     */
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

    /** A view of the graph; nothing is sent, and the graph does not show in [hasGraph] or [listGraphs] until it holds a triple. */
    override fun createGraph(name: Iri): RdfGraph = SparqlGraph(this, name)

    /** `DROP SILENT GRAPH`; returns whether the graph held triples beforehand (see [hasGraph]), checked in a separate request. */
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

    /** Buffers every row; capped by [SparqlEndpointConfig.maxResponseBytes]. A [SandboxedSelectQuery] is refused (see [withSelectRows]). */
    override fun select(query: SparqlSelect): SparqlQueryResult {
        refuseSandboxed(query)
        return selectRows(query.sparql, config.maxResponseBytes, EndpointHttp.Timeouts.buffered(config.requestTimeout)) { ListSparqlQueryResult(it.toList()) }
    }

    /**
     * Streams rows to [consume]. [SparqlEndpointConfig.requestTimeout] limits the wait for the
     * response headers; time spent consuming rows is only limited by
     * [SparqlEndpointConfig.streamingRequestTimeout] (and the per-read timeout).
     */
    override fun <T> withSelectRows(query: SparqlSelect, consume: (Sequence<BindingSet>) -> T): T {
        refuseSandboxed(query)
        return selectRows(query.sparql, config.maxStreamedResponseBytes, EndpointHttp.Timeouts(config.requestTimeout, config.streamingRequestTimeout), consume)
    }

    /**
     * Streams rows with [bindings] as initial bindings. [timeout] bounds the whole call, like the
     * Jena and RDF4J providers: the wait for the response and every row, including time the consumer
     * spends between rows; exceeding it fails with [RdfQueryException]. A shorter
     * [SparqlEndpointConfig.streamingRequestTimeout] still applies, and a shorter
     * [SparqlEndpointConfig.requestTimeout] still bounds the wait for the response headers.
     *
     * SPARQL 1.1 Protocol has no initial-bindings parameter, so the bindings are substituted into the
     * query text with the same rules as the Jena provider (see [com.geoknoesis.kastor.rdf.sparql.internal.SparqlInitialBindings]): the constants
     * restrict the WHERE clause before aggregation, LIMIT, FILTER and sub-selects, and projected
     * bound variables stay in the results. Queries that assign a bound variable (BIND/AS/VALUES) or
     * use it inside a sub-select that does not project it are rejected with
     * [IllegalArgumentException]. So is a literal bound to a variable that the query uses as a
     * predicate or as the name of a GRAPH or SERVICE: only an IRI is legal there (the Jena and RDF4J
     * providers reject it the same way). Blank nodes cannot be used as bindings.
     */
    override fun <T> withSelectRows(
        query: SparqlSelect,
        bindings: Map<String, RdfTerm>,
        timeout: Duration,
        consume: (Sequence<BindingSet>) -> T,
    ): T {
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
        refuseSandboxed(query)
        val rendered = bindings.mapValues { (_, term) ->
            SparqlTermFormat.term(term) { throw IllegalArgumentException("Blank nodes cannot be used as query bindings") }
        }
        val sparql = SparqlInitialBindings.apply(query.sparql, rendered)
        val overall = config.streamingRequestTimeout?.let { minOf(it, timeout) } ?: timeout
        // The response headers are awaited until the earliest of requestTimeout and the whole-call deadline.
        val headers = config.requestTimeout?.let { minOf(it, timeout) } ?: timeout
        return selectRows(sparql, config.maxStreamedResponseBytes, EndpointHttp.Timeouts(headers, overall), consume)
    }

    /**
     * The endpoint evaluates queries itself, so it cannot be held to [SandboxedSelectQuery]'s guarantees (no `SERVICE`):
     * such a query is refused with [UnsupportedOperationException] and is never sent. Every entry point that takes a
     * [SparqlSelect] ([select] and both [withSelectRows] overloads) calls this before anything else.
     */
    private fun refuseSandboxed(query: SparqlSelect) {
        if (query is SandboxedSelectQuery) {
            throw UnsupportedOperationException("A remote SPARQL endpoint cannot be restricted to sandboxed queries")
        }
    }

    private fun <T> selectRows(sparql: String, byteLimit: Long?, timeouts: EndpointHttp.Timeouts, consume: (Sequence<BindingSet>) -> T): T {
        ensureOpen()
        val startTime = System.currentTimeMillis()
        var rows = 0
        var decoder: JsonBindingRows? = null
        try {
            val result = http.exchange(sparql, update = false, byteLimit = byteLimit, timeouts = timeouts) { input, checkDeadline ->
                val sequence = JsonBindingRows(input, config.maxResultRowChars, config.malformedTerms).also { decoder = it }.rows()
                    .guarded(sparql, checkDeadline)
                    .onEach { rows++ }
                consume(sequence)
            }
            RdfDebug.logQueryTrace("SELECT", sparql, null, System.currentTimeMillis() - startTime, rows)
            return result
        } catch (e: RdfQueryException) {
            RdfDebug.logQueryError("SELECT", sparql, "Failed to execute: ${e.message}")
            throw e
        } finally {
            // Once per query, however it ended: the rows that were left out are not in what the caller saw.
            decoder?.let { read ->
                if (read.skippedRows > 0) {
                    LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Skipped ${read.skippedRows} SPARQL result row(s) holding terms that are not valid RDF terms for Kastor " +
                            "(malformedTerms = SKIP_ROW); first: ${read.firstSkipped}",
                    )
                }
            }
        }
    }

    override fun ask(query: SparqlAsk): Boolean {
        ensureOpen()
        val startTime = System.currentTimeMillis()
        try {
            val result = http.exchange(
                query.sparql, update = false, byteLimit = config.maxResponseBytes,
                timeouts = EndpointHttp.Timeouts.buffered(config.requestTimeout), plainTextAllowed = true,
            ) { input, _ ->
                // The SPARQL Results JSON `{ "boolean": true }` form, or the plain-text `true`/`false`
                // some endpoints return; decoded by the same strict reader as SELECT results.
                readResponse(query.sparql) { JsonBindingRows(input, config.maxResultRowChars).ask() }
            }
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
            // The body of a 2xx answer is not read under the size cap: the update is applied by then, and a
            // failure here would make a retrying caller apply it twice.
            http.exchange(query.sparql, update = true, byteLimit = null, timeouts = EndpointHttp.Timeouts.buffered(config.requestTimeout)) { input, _ ->
                discardUpdateResponse(input)
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

    /**
     * Reads and discards the body of a successful (2xx) UPDATE response, at most
     * [SparqlEndpointConfig.maxResponseBytes] of it. The server has applied the update by then, so a
     * body that is larger, slower than the read timeout or the request deadline, or cut off does not
     * fail the call: the rest is abandoned (the connection is closed) and a warning is logged.
     */
    private fun discardUpdateResponse(input: InputStream) {
        val buffer = ByteArray(8192)
        var remaining = config.maxResponseBytes
        val problem = try {
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) return
                remaining -= n
            }
            if (input.read() < 0) return
            "it exceeds ${config.maxResponseBytes} bytes"
        } catch (e: IOException) {
            "reading it failed: ${errorDetail(e.message ?: e.javaClass.simpleName)}"
        }
        LOGGER.log(
            System.Logger.Level.WARNING,
            "SPARQL UPDATE succeeded, but its response body was not read to the end ($problem); " +
                "the rest is discarded",
        )
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

    override fun isClosed(): Boolean = closed.get()

    /** What this adapter supports; identical to [SparqlProvider.getCapabilities]. */
    override fun getCapabilities(): ProviderCapabilities = SPARQL_ENDPOINT_CAPABILITIES

    /**
     * Marks the repository closed and releases its share of the HTTP client and of the thread that
     * enforces read deadlines; the client and its connection pool are shut down, and the thread
     * ends, when no open repository uses them. In-flight requests may complete, and their reads are
     * still timed. Idempotent.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            SharedHttpClients.release(config.connectTimeout)
            watchdog.release()
        }
    }

    private fun ensureOpen() = check(!closed.get()) { "Repository is closed" }

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
    private fun <R> Sequence<R>.guarded(sparql: String, checkDeadline: () -> Unit): Sequence<R> {
        val source = this
        return Sequence {
            val iterator = source.iterator()
            object : Iterator<R> {
                // Rows already buffered are served without a read, so check the deadline per row too.
                override fun hasNext(): Boolean = readResponse(sparql) { checkDeadline(); iterator.hasNext() }
                override fun next(): R = readResponse(sparql) { iterator.next() }
            }
        }
    }

    internal companion object {
        /** Number of live shared HTTP clients (one per connect timeout in use by open repositories). */
        fun sharedHttpClientCount(): Int = SharedHttpClients.size()

        /**
         * The warning logged (once per endpoint) when credentials would travel over plain http, or
         * `null`: HTTP Basic credentials (configured or embedded in the URL) and custom headers
         * whose name says that they carry a credential (see [SparqlEndpointConfig.credentialHeaders]).
         * It names the endpoints and the headers, never a value.
         */
        fun insecureAuthorizationWarning(config: SparqlEndpointConfig): String? {
            val endpoints = listOfNotNull(config.endpoint, config.updateEndpoint).distinct()
            val headers = config.credentialHeaders().map { "header '$it'" }
            val exposed = endpoints.mapNotNull { endpoint ->
                val target = HttpTarget.parse(endpoint)
                val basic = config.username != null || target.userInfoAuthorization != null
                val credentials = (if (basic) listOf("HTTP Basic authentication") else emptyList()) + headers
                if (target.isPlainHttp && credentials.isNotEmpty()) "${HttpTarget.redact(endpoint)} (${credentials.joinToString()})" else null
            }
            if (exposed.isEmpty()) return null
            return "Credentials are sent unencrypted over plain http to SPARQL endpoint ${exposed.joinToString(" and ")}. Use https."
        }
    }
}

