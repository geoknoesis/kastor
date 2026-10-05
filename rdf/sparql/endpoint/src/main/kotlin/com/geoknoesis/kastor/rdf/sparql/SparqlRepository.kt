package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.sparql.internal.SparqlInitialBindings
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val RESULTS_JSON = "application/sparql-results+json"
/** What the adapter asks for: SPARQL JSON results, or plain JSON from servers that do not know that type. */
private const val ACCEPT_RESULTS = "$RESULTS_JSON, application/json;q=0.8"
private const val FORM_ENCODED = "application/x-www-form-urlencoded"
private const val MAX_ERROR_BODY_BYTES = 4096
private const val MAX_ERROR_DETAIL_CHARS = 512
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

/**
 * One [HttpClient] (connection pool and selector thread) per connect timeout, shared by the open
 * repositories that use it and shut down when the last of them is closed.
 */
private object SharedHttpClients {
    private class Entry(val client: HttpClient) {
        var references = 0
    }

    private val entries = HashMap<Duration, Entry>()

    @Synchronized
    fun acquire(connectTimeout: Duration): HttpClient {
        val entry = entries.getOrPut(connectTimeout) {
            Entry(
                HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    // A timeout too long to count is no timeout: the JDK client fails on such a value.
                    .apply { if (!Durations.isUnbounded(connectTimeout)) connectTimeout(connectTimeout) }
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()
            )
        }
        entry.references++
        return entry.client
    }

    @Synchronized
    fun release(connectTimeout: Duration) {
        val entry = entries[connectTimeout] ?: return
        if (--entry.references == 0) {
            entries.remove(connectTimeout)
            entry.client.shutdown()
        }
    }

    @Synchronized
    fun size(): Int = entries.size
}

/**
 * What of an HTTP error body goes into an exception message (and from there into logs): one line of
 * at most [MAX_ERROR_DETAIL_CHARS] characters, followed by `...` when the body was longer. Runs of
 * white space and line breaks become one space; control characters, invisible format characters
 * (bidirectional overrides among them) and anything that is not a character are written as their
 * escape, so that a body can neither forge log lines nor hide what it says.
 */
internal fun errorDetail(body: String): String {
    val out = StringBuilder()
    var space = false
    var i = 0
    while (i < body.length) {
        val codePoint = body.codePointAt(i)
        i += Character.charCount(codePoint)
        val type = Character.getType(codePoint)
        if (codePoint in LINE_BREAKS || type == Character.SPACE_SEPARATOR.toInt()) {
            space = out.isNotEmpty()
            continue
        }
        val escaped = type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt() ||
            type == Character.PRIVATE_USE.toInt() || type == Character.UNASSIGNED.toInt()
        val text = when {
            !escaped -> String(Character.toChars(codePoint))
            codePoint <= 0xFFFF -> "%cu%04X".format(BACKSLASH, codePoint)
            else -> "%cu{%X}".format(BACKSLASH, codePoint)
        }
        if (out.length + (if (space) 1 else 0) + text.length > MAX_ERROR_DETAIL_CHARS) return out.append("...").toString()
        if (space) out.append(' ')
        space = false
        out.append(text)
    }
    return out.toString()
}

/**
 * A URL taken from a response (a `Location` header) as it may appear in a message or log: without
 * user information, query and fragment (they can carry credentials or signed tokens), and
 * sanitised and truncated like an error body by [errorDetail].
 */
internal fun urlDetail(url: String): String {
    val cut = url.indexOfFirst { it == '?' || it == '#' }
    val withoutUserInfo = (if (cut < 0) url else url.substring(0, cut)).replace(USER_INFO, "***@")
    return errorDetail(withoutUserInfo + if (cut < 0) "" else "?...")
}

internal val USER_INFO = Regex("(?<=//)[^/@]*@")

private val BACKSLASH = 92.toChar()

/** Tab, line feed, vertical tab, form feed, carriage return, next line, line separator, paragraph separator. */
private val LINE_BREAKS = setOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029)

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
 */
class SparqlRepository internal constructor(
    val config: SparqlEndpointConfig,
    transport: HttpTransport?,
    private val watchdog: ReadWatchdog = ReadWatchdog.shared,
) : RdfRepository {

    constructor(config: SparqlEndpointConfig) : this(config, null)

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
    private val queryTarget = HttpTarget.parse(config.endpoint)
    private val updateTarget = config.updateEndpoint?.let(HttpTarget::parse) ?: queryTarget
    private val configuredAuthorization =
        config.username?.let { HttpTarget.basicAuthorization(it, config.password.orEmpty()) }
    private val client: HttpClient = SharedHttpClients.acquire(config.connectTimeout)
    private val transport: HttpTransport = transport ?: HttpTransport { client.sendAsync(it, HttpResponse.BodyHandlers.ofInputStream()) }

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
     * Streams rows with [bindings] as initial bindings. [timeout] bounds the whole call, like the
     * Jena and RDF4J providers: the wait for the response and every row, including time the consumer
     * spends between rows; exceeding it fails with [RdfQueryException]. A shorter
     * [SparqlEndpointConfig.streamingRequestTimeout] still applies.
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
        val rendered = bindings.mapValues { (_, term) ->
            SparqlTermFormat.term(term) { throw IllegalArgumentException("Blank nodes cannot be used as query bindings") }
        }
        val sparql = SparqlInitialBindings.apply(query.sparql, rendered)
        val overall = config.streamingRequestTimeout?.let { minOf(it, timeout) } ?: timeout
        return selectRows(sparql, config.maxStreamedResponseBytes, Timeouts(timeout, overall), consume)
    }

    private fun <T> selectRows(sparql: String, byteLimit: Long?, timeouts: Timeouts, consume: (Sequence<BindingSet>) -> T): T {
        ensureOpen()
        val startTime = System.currentTimeMillis()
        var rows = 0
        var decoder: JsonBindingRows? = null
        try {
            val result = exchange(sparql, update = false, byteLimit = byteLimit, timeouts = timeouts) { input, checkDeadline ->
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
            val result = exchange(
                query.sparql, update = false, byteLimit = config.maxResponseBytes,
                timeouts = Timeouts.buffered(config.requestTimeout), plainTextAllowed = true,
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
            exchange(query.sparql, update = true, byteLimit = config.maxResponseBytes, timeouts = Timeouts.buffered(config.requestTimeout)) { input, _ ->
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

    // ------------------------------------------------------------------------ HTTP

    /**
     * [headers] limits the wait for response headers (all redirect hops included); when it is `null`
     * the read timeout bounds that wait, also once for all hops. [overall] limits the whole exchange
     * including reading the body, so it also bounds the header wait when it ends first. All are
     * measured from the start of the call.
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
     * [handle], with a function that fails once the overall deadline has passed (for work that does
     * not read from the stream). A successful query response must be JSON (or, when
     * [plainTextAllowed], `text/plain`); a missing Content-Type is accepted. Exceptions thrown by
     * [handle] propagate unchanged;
     * [RdfQueryException]s raised after a timeout closed the stream are reported as that timeout.
     */
    private fun <T> exchange(
        sparql: String,
        update: Boolean,
        byteLimit: Long?,
        timeouts: Timeouts,
        plainTextAllowed: Boolean = false,
        handle: (InputStream, checkDeadline: () -> Unit) -> T,
    ): T {
        val startNanos = System.nanoTime()
        val target = if (update) updateTarget else queryTarget
        var request = request(sparql, update)
        var authorization = configuredAuthorization ?: target.userInfoAuthorization
        var sendCustomHeaders = true
        var redirects = 0
        // The wait for response headers ends at the earlier of the header deadline and the overall
        // deadline; without a header deadline it is also bounded like any read. It is one budget,
        // measured from the start of the call, for all redirect hops together.
        val deadline = listOfNotNull(timeouts.headers, timeouts.overall).minOrNull()
        val boundedByReadTimeout = timeouts.headers == null && (deadline == null || config.readTimeout < deadline)
        val headerLimit = if (boundedByReadTimeout) config.readTimeout else deadline!!
        val headerLimitNanos = Durations.nanos(headerLimit)
        val headerWaitExceeded = { cause: Throwable? ->
            RdfQueryException(
                if (boundedByReadTimeout) "SPARQL request timed out after ${Durations.millis(headerLimit)} ms waiting for the response"
                else "SPARQL request exceeded its ${Durations.millis(headerLimit)} ms deadline waiting for the response",
                query = sparql,
                cause = cause,
            )
        }
        val overallDeadlineNanos = timeouts.overall?.let { Durations.deadline(startNanos, Durations.nanos(it)) }
        while (true) {
            val remainingNanos =
                if (headerLimitNanos == Durations.UNBOUNDED) Durations.UNBOUNDED else headerLimitNanos - (System.nanoTime() - startNanos)
            if (remainingNanos <= 0) throw headerWaitExceeded(null)
            val httpRequest = build(request, update, if (sendCustomHeaders) config.headers else emptyMap(), authorization, sparql)
            val response = send(httpRequest, sparql, remainingNanos, headerWaitExceeded)
            // From here on the response body is open: whatever fails, it is closed before the failure is reported.
            var open: InputStream? = null
            val input: GuardedInputStream
            try {
                open = response.body()
                val status = response.statusCode()
                if (status in REDIRECT_STATUSES) {
                    val location = response.headers().firstValue("Location").orElse(null)
                    closeQuietly(open)
                    val next = redirectTarget(request, status, location, sparql)
                    if (++redirects > config.maxRedirects) {
                        throw RdfQueryException("SPARQL endpoint redirected more than ${config.maxRedirects} times", query = sparql)
                    }
                    redirectRefusal(request.uri, next, config.followCrossOriginRedirects)?.let { refusal ->
                        throw RdfQueryException("SPARQL endpoint redirected (HTTP $status) $refusal", query = sparql)
                    }
                    if (!sameOrigin(request.uri, next)) {
                        if (sendCustomHeaders && (config.headers.isNotEmpty() || authorization != null)) {
                            LOGGER.log(
                                System.Logger.Level.WARNING,
                                "SPARQL endpoint redirected (HTTP $status) from ${origin(request.uri)} to ${origin(next)}; " +
                                    "custom headers ${config.headers.keys} and credentials are not sent to the other origin",
                            )
                        }
                        sendCustomHeaders = false
                        authorization = null
                    }
                    request = Request(next, request.contentType, request.body)
                    continue
                }
                val guarded = GuardedInputStream(open, watchdog, config.readTimeout, overallDeadlineNanos, timeouts.overall)
                open = guarded
                if (status !in 200..299) {
                    val detail = readErrorBody(guarded)
                    throw RdfQueryException(
                        "SPARQL endpoint returned HTTP $status${if (detail.isEmpty()) "" else ": $detail"}",
                        query = sparql,
                    )
                }
                if (!update && config.strictContentType) {
                    val contentType = response.headers().firstValue("Content-Type").orElse(null)
                    if (!acceptableResultType(contentType, plainTextAllowed)) {
                        throw RdfQueryException(
                            "SPARQL endpoint returned Content-Type '${errorDetail(contentType.orEmpty())}' instead of SPARQL JSON results ($RESULTS_JSON); " +
                                "if the body is such JSON under another label, set strictContentType = false",
                            query = sparql,
                        )
                    }
                }
                input = guarded
            } catch (failure: Throwable) {
                open?.let(::closeQuietly)
                throw if (failure is Exception && failure !is RdfQueryException) requestFailed(failure, sparql) else failure
            }
            try {
                return handle(if (byteLimit == null) input else BoundedInputStream(input, byteLimit), input::checkDeadline)
            } catch (e: RdfQueryException) {
                input.failure?.let { throw RdfQueryException(it, query = sparql, cause = e) }
                throw e
            } finally {
                // The outcome is decided by now; a failure to close must not replace it.
                closeQuietly(input)
            }
        }
    }

    /** Builds the HTTP request; a refusal by the JDK's request builder is a request failure like any other. */
    private fun build(request: Request, update: Boolean, headers: Map<String, String>, authorization: String?, sparql: String): HttpRequest = try {
        // No timeout is set on the request: the JDK applies it to the header wait only up to JDK 25
        // and to the whole exchange, body included, from JDK 26 (JDK-8208693). The header wait is
        // bounded by send() and the body by GuardedInputStream, on every JDK.
        val builder = HttpRequest.newBuilder(request.uri)
        // The configuration refuses custom headers with the names set here, so none of these is ever replaced.
        headers.forEach { (name, value) -> builder.setHeader(name, value) }
        if (!update) builder.setHeader("Accept", ACCEPT_RESULTS)
        request.contentType?.let { builder.setHeader("Content-Type", it) }
        authorization?.let { builder.setHeader("Authorization", it) }
        val body = request.body
        if (body == null) builder.GET() else builder.POST(HttpRequest.BodyPublishers.ofByteArray(body))
        builder.build()
    } catch (e: RuntimeException) {
        throw requestFailed(e, sparql)
    }

    /** The one way a request that could not be sent, or whose exchange failed, is reported. */
    private fun requestFailed(cause: Throwable, sparql: String) = RdfQueryException(
        if (cause is HttpConnectTimeoutException) {
            "SPARQL request failed: connect timed out" +
                if (Durations.isUnbounded(config.connectTimeout)) "" else " after ${Durations.millis(config.connectTimeout)} ms"
        } else {
            "SPARQL request failed: ${errorDetail(cause.message ?: cause.javaClass.simpleName)}"
        },
        query = sparql,
        cause = cause,
    )

    /**
     * Starts the exchange and waits at most [waitNanos] for its response headers. When the wait ends
     * without them the exchange is cancelled, which closes its connection. A transport that fails
     * before it returns an exchange is reported like an exchange that failed.
     */
    private fun send(
        request: HttpRequest,
        sparql: String,
        waitNanos: Long,
        headerWaitExceeded: (Throwable?) -> RdfQueryException,
    ): HttpResponse<InputStream> {
        val exchange = try {
            transport.send(request)
        } catch (e: Exception) {
            throw requestFailed(e, sparql)
        }
        try {
            return exchange.get(waitNanos, TimeUnit.NANOSECONDS)
        } catch (e: TimeoutException) {
            abandon(exchange)
            throw headerWaitExceeded(e)
        } catch (e: InterruptedException) {
            abandon(exchange)
            Thread.currentThread().interrupt()
            throw RdfQueryException("SPARQL request was interrupted", query = sparql, cause = e)
        } catch (e: CancellationException) {
            throw RdfQueryException("SPARQL request was cancelled", query = sparql, cause = e)
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is Error) throw cause
            throw requestFailed(cause, sparql)
        }
    }

    /** Cancels an exchange nobody waits for any more; a response that arrived in the meantime is closed. */
    private fun abandon(exchange: CompletableFuture<HttpResponse<InputStream>>) {
        if (exchange.cancel(true)) return
        try {
            exchange.getNow(null)?.body()?.close()
        } catch (_: Exception) {
            // It failed on its own; nothing to release.
        }
    }

    private fun redirectTarget(current: Request, status: Int, location: String?, sparql: String): URI {
        if (location.isNullOrBlank()) throw RdfQueryException("SPARQL endpoint returned HTTP $status without a Location header", query = sparql)
        if (current.body != null && status !in setOf(307, 308)) {
            throw RdfQueryException(
                "SPARQL endpoint answered a POST with HTTP $status; following it would silently turn the request into a GET " +
                    "without the query. Configure the redirect target (${urlDetail(location)}) as the endpoint URL",
                query = sparql,
            )
        }
        val next = try {
            current.uri.resolve(URI(location))
        } catch (e: Exception) {
            throw RdfQueryException("SPARQL endpoint returned an invalid redirect Location: ${urlDetail(location)}", query = sparql, cause = e)
        }
        val scheme = next.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw RdfQueryException("SPARQL endpoint redirected to an unsupported URL: ${urlDetail(next.toString())}", query = sparql)
        }
        HttpTarget.hostProblem(next)?.let { problem ->
            throw RdfQueryException("SPARQL endpoint redirected to a URL that $problem: ${urlDetail(next.toString())}", query = sparql)
        }
        if (next.rawUserInfo != null) {
            throw RdfQueryException("SPARQL endpoint redirected to an unsupported URL (it carries credentials): ${urlDetail(next.toString())}", query = sparql)
        }
        return next
    }

    /** Whether [contentType] (a response header value, possibly `null`) can hold SPARQL JSON results. */
    private fun acceptableResultType(contentType: String?, plainTextAllowed: Boolean): Boolean {
        val media = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (media.isNullOrEmpty()) return true
        return media == "application/json" || media.endsWith("+json") || (plainTextAllowed && media == "text/plain")
    }

    /** The start of an error body, as [errorDetail] lets it into a message. */
    private fun readErrorBody(input: InputStream): String = try {
        errorDetail(String(input.readNBytes(MAX_ERROR_BODY_BYTES), Charsets.UTF_8))
    } catch (_: IOException) {
        ""
    }

    private fun closeQuietly(input: InputStream) {
        try { input.close() } catch (_: Exception) { }
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

    /**
     * Enforces the per-read timeout and the overall deadline on a response body: every read
     * publishes its deadline and [ReadWatchdog] closes the underlying stream when the read waits
     * past it, which unblocks the reader. Time between reads (the consumer's own work) counts
     * towards the overall deadline, checked on every read and by [checkDeadline].
     */
    internal class GuardedInputStream(
        private val raw: InputStream,
        private val watchdog: ReadWatchdog,
        readTimeout: Duration,
        private val deadlineNanos: Long?,
        overall: Duration?,
    ) : InputStream(), ReadWatchdog.Watched {
        private val readTimeoutNanos = Durations.nanos(readTimeout)
        private val readTimeoutMessage = "SPARQL response read timed out after ${Durations.millis(readTimeout)} ms"
        private val deadlineMessage = "SPARQL request exceeded its ${overall?.let(Durations::millis)} ms deadline"

        /** The deadline of a read, with the message that says what bounds it. */
        private class PendingRead(val deadline: Long, val message: String)

        /**
         * The read in progress, or `null` between reads and once the watchdog claimed it. Whoever
         * replaces it (the read when it ends, the watchdog when it is overdue) wins, so a read is
         * either left alone or expired, never both, and the message that expires it is its own.
         */
        private val pending = AtomicReference<PendingRead?>(null)

        @Volatile var failure: String? = null
            private set

        init {
            watchdog.register(this)
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        /** Throws if the stream already failed or the overall deadline has passed. */
        fun checkDeadline() {
            failure?.let { throw IOException(it) }
            if (deadlineNanos != null && deadlineNanos - System.nanoTime() <= 0) {
                failure = deadlineMessage
                closeRaw()
                throw IOException(deadlineMessage)
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkDeadline()
            val now = System.nanoTime()
            val untilDeadline = deadlineNanos?.let { it - now } ?: Durations.UNBOUNDED
            val byDeadline = untilDeadline < readTimeoutNanos
            // Nothing is published for a read that neither the read timeout nor a deadline bounds.
            val deadline = Durations.deadline(now, if (byDeadline) untilDeadline else readTimeoutNanos)
            val mine = deadline?.let { PendingRead(it, if (byDeadline) deadlineMessage else readTimeoutMessage) }
            if (mine != null) {
                pending.set(mine)
                watchdog.published(mine.deadline)
            }
            try {
                return raw.read(b, off, len)
            } catch (e: IOException) {
                failure?.let { throw IOException(it, e) }
                throw e
            } finally {
                // Losing this exchange means the watchdog claimed the read just as it ended: the stream is
                // failed (or about to be) for the read's own reason, so the next read cannot start unnoticed.
                if (mine != null && !pending.compareAndSet(mine, null)) {
                    failure = failure ?: mine.message
                    closeRaw()
                }
            }
        }

        override fun expireIfDue(now: Long): Long {
            val read = pending.get() ?: return Long.MAX_VALUE
            if (now < read.deadline) return read.deadline
            if (pending.compareAndSet(read, null)) {
                failure = failure ?: read.message
                closeRaw()
            }
            return Long.MAX_VALUE
        }

        /** Closing is what unblocks the reader; whatever the stream's own close() throws changes nothing about the timeout. */
        private fun closeRaw() {
            try { raw.close() } catch (_: Exception) { }
        }

        override fun close() {
            watchdog.unregister(this)
            raw.close()
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

    internal companion object {
        /** Number of live shared HTTP clients (one per connect timeout in use by open repositories). */
        fun sharedHttpClientCount(): Int = SharedHttpClients.size()

        /**
         * Why a redirect from [from] to [to] must not be followed (completing "redirected (HTTP n) ..."),
         * or `null` when it may be. An `https` to `http` downgrade is always refused: the query (and, for
         * `307`/`308`, the request body) would travel in cleartext. Other cross-origin redirects are
         * refused unless [followCrossOriginRedirects].
         */
        fun redirectRefusal(from: URI, to: URI, followCrossOriginRedirects: Boolean): String? = when {
            from.scheme.equals("https", ignoreCase = true) && to.scheme.equals("http", ignoreCase = true) ->
                "from https to plain http (${origin(to)}); the request would be sent unencrypted. " +
                    "Configure an https endpoint, or the http URL directly if cleartext is intended"
            sameOrigin(from, to) || followCrossOriginRedirects -> null
            else -> "to another origin (${origin(to)}); configure that endpoint URL directly or enable followCrossOriginRedirects"
        }

        private fun origin(uri: URI) = "${uri.scheme}://${uri.host}:${effectivePort(uri)}"

        private fun effectivePort(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

        private fun sameOrigin(a: URI, b: URI) =
            a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) && effectivePort(a) == effectivePort(b)

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
 *
 * ## Reads
 * [find] and [getTriples] check every row the endpoint returns: a row that is not a triple (a
 * variable left unbound, a literal as subject, anything but an IRI as predicate) fails with
 * [RdfQueryException]. With [MalformedTermPolicy.SKIP_ROW] rows with malformed terms are left out,
 * while [size] is counted by the endpoint and still includes them.
 *
 * ## Batched writes are not atomic
 * [addTriples] and [removeTriples] send one request per batch. Every triple is validated and
 * rendered before the first request, so an invalid term never leaves earlier batches applied. A
 * transport or server failure after earlier batches succeeded cannot be undone; it is reported as
 * an [RdfQueryException] that states how many triples the earlier requests wrote.
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
        validate(triples) { "_:b" }
        var written = 0
        val batch = ArrayList<RdfTriple>()
        fun send() {
            reportingProgress("INSERT DATA", written, triples.size) { insertData(batch) }
            written += batch.size
            batch.clear()
        }
        for (unit in units) {
            if (batch.isNotEmpty() && batch.size + unit.size > batchSize) send()
            batch.addAll(unit)
        }
        if (batch.isNotEmpty()) send()
    }

    /** Render every triple (and the graph name) once, so invalid terms fail before any request. */
    private fun validate(triples: Collection<RdfTriple>, blankNode: (BlankNode) -> String) {
        pattern("")
        triples.forEach { rendered(it, blankNode) }
    }

    /** Run one batch request; a failure after earlier batches succeeded says how much was written. */
    private inline fun reportingProgress(operation: String, written: Int, total: Int, request: () -> Unit) {
        try {
            request()
        } catch (e: RdfQueryException) {
            if (written == 0) throw e
            throw RdfQueryException(
                "SPARQL $operation failed after earlier requests had written $written of $total triples: ${e.message}",
                query = e.query,
                cause = e,
            )
        }
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
     * are separate requests, so concurrent writers can race with them: the result is best-effort.
     */
    override fun removeTriples(triples: Collection<RdfTriple>): Boolean {
        if (triples.isEmpty()) return false
        validate(triples, ::rejectBlankNode)
        var existed = false
        var written = 0
        triples.chunked(batchSize).forEach { batch ->
            val body = batch.joinToString("\n") { rendered(it, ::rejectBlankNode) }
            reportingProgress("DELETE DATA", written, triples.size) {
                if (!existed) existed = anyExists(batch)
                repository.update(UpdateQuery("DELETE DATA { ${pattern(body)} }"))
            }
            written += batch.size
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
        val query = "SELECT * WHERE { ${pattern(body)} }"
        val result = repository.select(SparqlSelectQuery(query))
        // The endpoint is not trusted to answer with triples: a row that is none is reported, not cast.
        fun notATriple(variable: String, term: RdfTerm?, expected: String): Nothing = throw RdfQueryException(
            "SPARQL endpoint returned a row that is not a triple: ?$variable is " +
                when (term) {
                    null -> "unbound"
                    is Literal -> "a literal, not $expected"
                    is BlankNode -> "a blank node, not $expected"
                    is Iri -> "an IRI, not $expected"
                    else -> "a ${term.javaClass.simpleName}, not $expected"
                },
            query = query,
        )
        return result.map { row ->
            val s = subject ?: row.get("s").let { it as? RdfResource ?: notATriple("s", it, "an IRI or a blank node") }
            val p = predicate ?: row.get("p").let { it as? Iri ?: notATriple("p", it, "an IRI") }
            val o = obj ?: row.get("o") ?: notATriple("o", null, "an RDF term")
            RdfTriple(s, p, o)
        }
    }

    override fun clear(): Boolean {
        val existed = repository.ask(SparqlAskQuery("ASK { ${pattern("?s ?p ?o")} }"))
        repository.update(UpdateQuery(if (graphName == null) "CLEAR DEFAULT" else "CLEAR SILENT GRAPH ${SparqlTermFormat.iriRef(graphName.value)}"))
        return existed
    }

    /**
     * Number of triples, counted by the endpoint. Throws [RdfQueryException] when the count is not an
     * integer or does not fit in an [Int].
     */
    override fun size(): Int {
        val query = "SELECT (COUNT(*) AS ?count) WHERE { ${pattern("?s ?p ?o")} }"
        val result = repository.select(SparqlSelectQuery(query))
        val term = result.firstOrNull()?.get("count") as? Literal ?: throw RdfQueryException(
            "SPARQL endpoint returned a missing or non-literal triple count",
            query = query,
        )
        val count = term.lexical.trim().toLongOrNull()
        if (count == null || count < 0 || count > Int.MAX_VALUE) {
            throw RdfQueryException("SPARQL endpoint returned a triple count of '${term.lexical}', which is not a valid Int size", query = query)
        }
        return count.toInt()
    }

    private fun rejectBlankNode(node: BlankNode): String = throw IllegalArgumentException(
        "Blank node '${node.id}' cannot be addressed over SPARQL: blank-node labels are scoped to one request " +
            "and endpoint-assigned identifiers are not valid query constants; use an explicit DELETE WHERE pattern instead"
    )
}
