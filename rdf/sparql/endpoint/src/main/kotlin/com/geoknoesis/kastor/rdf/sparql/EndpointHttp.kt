package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.RdfQueryException
import com.geoknoesis.kastor.rdf.UrlAddressPolicy
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val RESULTS_JSON = "application/sparql-results+json"

/** What the adapter asks for: SPARQL JSON results, or plain JSON from servers that do not know that type. */
private const val ACCEPT_RESULTS = "$RESULTS_JSON, application/json;q=0.8"
private const val FORM_ENCODED = "application/x-www-form-urlencoded"
private const val MAX_ERROR_BODY_BYTES = 4096
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
private val LOGGER: System.Logger = System.getLogger(SparqlRepository::class.java.name)

/**
 * The HTTP side of a [SparqlRepository]: one SPARQL Protocol exchange per call, with the redirect policy, the header
 * wait and the checks on the response. What the body holds is the caller's business ([exchange]'s `handle`).
 */
internal class EndpointHttp(
    private val config: SparqlEndpointConfig,
    private val transport: HttpTransport,
    private val watchdog: ReadWatchdog,
    /** The clock of the header budget ([HeaderBudget]); tests advance their own instead of sleeping. */
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val queryTarget = HttpTarget.parse(config.endpoint)
    private val updateTarget = config.updateEndpoint?.let(HttpTarget::parse) ?: queryTarget
    private val configuredAuthorization =
        config.username?.let { HttpTarget.basicAuthorization(it, config.password.orEmpty()) }

    /**
     * [headers] limits the wait for response headers (all redirect hops included); when it is `null`
     * the read timeout bounds that wait, also once for all hops. [overall] limits the whole exchange
     * including reading the body, so it also bounds the header wait when it ends first. All are
     * measured from the start of the call.
     */
    class Timeouts(val headers: Duration?, val overall: Duration?) {
        companion object {
            fun buffered(timeout: Duration?) = Timeouts(timeout, timeout)
        }
    }

    private class Request(val uri: URI, val contentType: String?, val body: ByteArray?)

    /** Where the next request of a redirect chain goes, and what it may carry. */
    private class Hop(var request: Request, var authorization: String?) {
        var sendCustomHeaders = true
        var redirects = 0
    }

    /**
     * The one budget for the wait for response headers, measured from its creation across all redirect hops: the
     * earlier of the header deadline and the overall deadline, or the read timeout when there is no header deadline
     * (and it ends first).
     */
    private inner class HeaderBudget(timeouts: Timeouts, val sparql: String) {
        private val startNanos = nanoTime()
        private val deadline = listOfNotNull(timeouts.headers, timeouts.overall).minOrNull()
        private val boundedByReadTimeout = timeouts.headers == null && (deadline == null || config.readTimeout < deadline)
        private val limit = if (boundedByReadTimeout) config.readTimeout else deadline!!
        private val limitNanos = Durations.nanos(limit)

        /** What is left of the budget; throws when nothing is. */
        fun remainingNanos(): Long {
            val remaining = if (limitNanos == Durations.UNBOUNDED) Durations.UNBOUNDED else limitNanos - (nanoTime() - startNanos)
            if (remaining <= 0) throw exceeded(null)
            return remaining
        }

        fun exceeded(cause: Throwable?) = RdfQueryException(
            if (boundedByReadTimeout) "SPARQL request timed out after ${Durations.millis(limit)} ms waiting for the response"
            else "SPARQL request exceeded its ${Durations.millis(limit)} ms deadline waiting for the response",
            query = sparql,
            cause = cause,
        )
    }

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
    fun <T> exchange(
        sparql: String,
        update: Boolean,
        byteLimit: Long?,
        timeouts: Timeouts,
        plainTextAllowed: Boolean = false,
        handle: (InputStream, checkDeadline: () -> Unit) -> T,
    ): T {
        val startNanos = System.nanoTime()
        val target = if (update) updateTarget else queryTarget
        val hop = Hop(request(sparql, update), configuredAuthorization ?: target.userInfoAuthorization)
        val budget = HeaderBudget(timeouts, sparql)
        val overallDeadlineNanos = timeouts.overall?.let { Durations.deadline(startNanos, Durations.nanos(it)) }
        while (true) {
            val remainingNanos = budget.remainingNanos()
            val httpRequest = build(hop.request, update, if (hop.sendCustomHeaders) config.headers else emptyMap(), hop.authorization, sparql)
            val response = send(httpRequest, sparql, remainingNanos, budget::exceeded)
            val input = openBody(response, hop, update, plainTextAllowed, sparql, overallDeadlineNanos, timeouts.overall, budget) ?: continue
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

    /**
     * The body of [response] under the read deadlines, once it is known to be a successful result; or `null` when
     * the response is a redirect that [hop] now follows. Whatever fails, the body is closed before the failure is
     * reported.
     */
    private fun openBody(
        response: HttpResponse<InputStream>,
        hop: Hop,
        update: Boolean,
        plainTextAllowed: Boolean,
        sparql: String,
        overallDeadlineNanos: Long?,
        overall: Duration?,
        budget: HeaderBudget,
    ): GuardedInputStream? {
        var open: InputStream? = null
        try {
            open = response.body()
            val status = response.statusCode()
            if (status in REDIRECT_STATUSES) {
                val location = response.headers().firstValue("Location").orElse(null)
                closeQuietly(open)
                follow(hop, status, location, sparql, budget)
                return null
            }
            val guarded = GuardedInputStream(open, watchdog, config.readTimeout, overallDeadlineNanos, overall)
            open = guarded
            checkResult(response, guarded, status, update, plainTextAllowed, sparql)
            return guarded
        } catch (failure: Throwable) {
            open?.let(::closeQuietly)
            throw if (failure is Exception && failure !is RdfQueryException) requestFailed(failure, sparql) else failure
        }
    }

    /** Points [hop] at the target of a redirect, or throws when the redirect must not be followed. */
    private fun follow(hop: Hop, status: Int, location: String?, sparql: String, budget: HeaderBudget) {
        val current = hop.request
        val next = redirectTarget(current, status, location, sparql)
        if (++hop.redirects > config.maxRedirects) {
            throw RdfQueryException("SPARQL endpoint redirected more than ${config.maxRedirects} times", query = sparql)
        }
        Redirects.refusal(current.uri, next, config.followCrossOriginRedirects)?.let { refusal ->
            throw RdfQueryException("SPARQL endpoint redirected (HTTP $status) $refusal", query = sparql)
        }
        if (!Redirects.sameOrigin(current.uri, next)) {
            if (!addressAllowed(next, budget)) {
                throw RdfQueryException(
                    "SPARQL endpoint redirected (HTTP $status) to ${Redirects.origin(next)}, which redirectAddressPolicy " +
                        "(${config.redirectAddressPolicy}) refuses (by default only public addresses: loopback, private and link-local " +
                        "addresses and host names that do not resolve are refused). " +
                        "Configure that endpoint URL directly, or set redirectAddressPolicy = UrlAddressPolicy.ALLOW_ALL if the " +
                        "redirect is trusted",
                    query = sparql,
                )
            }
            if (hop.sendCustomHeaders && (config.headers.isNotEmpty() || hop.authorization != null)) {
                LOGGER.log(
                    System.Logger.Level.WARNING,
                    "SPARQL endpoint redirected (HTTP $status) from ${Redirects.origin(current.uri)} to ${Redirects.origin(next)}; " +
                        "custom headers ${config.headers.keys} and credentials are not sent to the other origin",
                )
            }
            hop.sendCustomHeaders = false
            hop.authorization = null
        }
        hop.request = Request(next, current.contentType, current.body)
    }

    /**
     * Whether [config]'s redirect address policy lets a request go to [target]. The policy may look the host name up,
     * which the JDK cannot interrupt: it runs on a daemon thread and is waited for only as long as the header [budget]
     * allows, so a resolver that hangs ends the call with the header-wait timeout instead of holding it.
     */
    private fun addressAllowed(target: URI, budget: HeaderBudget): Boolean {
        val policy = config.redirectAddressPolicy
        if (policy === UrlAddressPolicy.ALLOW_ALL) return true
        val check = CompletableFuture.supplyAsync({ policy.allows(target) }, AddressChecks.executor)
        return try {
            check.get(budget.remainingNanos(), TimeUnit.NANOSECONDS)
        } catch (e: TimeoutException) {
            check.cancel(true)
            throw budget.exceeded(e)
        } catch (e: InterruptedException) {
            check.cancel(true)
            Thread.currentThread().interrupt()
            throw RdfQueryException("SPARQL request was interrupted", query = budget.sparql, cause = e)
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is Error) throw cause
            throw RdfQueryException("SPARQL redirect address check failed: ${errorDetail(cause.message ?: cause.javaClass.simpleName)}", query = budget.sparql, cause = cause)
        }
    }

    /** Throws unless [response] is a success whose Content-Type can hold the results asked for. */
    private fun checkResult(
        response: HttpResponse<InputStream>,
        body: InputStream,
        status: Int,
        update: Boolean,
        plainTextAllowed: Boolean,
        sparql: String,
    ) {
        if (status !in 200..299) {
            val detail = readErrorBody(body)
            throw RdfQueryException("SPARQL endpoint returned HTTP $status${if (detail.isEmpty()) "" else ": $detail"}", query = sparql)
        }
        if (update || !config.strictContentType) return
        val contentType = response.headers().firstValue("Content-Type").orElse(null)
        if (!acceptableResultType(contentType, plainTextAllowed)) {
            throw RdfQueryException(
                "SPARQL endpoint returned Content-Type '${errorDetail(contentType.orEmpty())}' instead of SPARQL JSON results ($RESULTS_JSON); " +
                    "if the body is such JSON under another label, set strictContentType = false",
                query = sparql,
            )
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
            // The parser's exception quotes the whole Location (signed query tokens included); only a
            // copy that names the sanitised URL and the parser's reason is chained.
            val detail = urlDetail(location)
            val cause = if (e is java.net.URISyntaxException) java.net.URISyntaxException(detail, e.reason) else null
            throw RdfQueryException("SPARQL endpoint returned an invalid redirect Location: $detail", query = sparql, cause = cause)
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
}

/** The daemon threads that run redirect address checks (host name lookups), shared by all repositories. */
private object AddressChecks {
    private val counter = java.util.concurrent.atomic.AtomicInteger()
    val executor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newCachedThreadPool { task ->
        Thread(task, "kastor-sparql-address-check-${counter.incrementAndGet()}").apply { isDaemon = true }
    }
}

/** The redirect policy: which redirects are followed, and what an origin is. */
internal object Redirects {
    /**
     * Why a redirect from [from] to [to] must not be followed (completing "redirected (HTTP n) ..."),
     * or `null` when it may be. An `https` to `http` downgrade is always refused: the query (and, for
     * `307`/`308`, the request body) would travel in cleartext. Other cross-origin redirects are
     * refused unless [followCrossOriginRedirects].
     */
    fun refusal(from: URI, to: URI, followCrossOriginRedirects: Boolean): String? = when {
        from.scheme.equals("https", ignoreCase = true) && to.scheme.equals("http", ignoreCase = true) ->
            "from https to plain http (${origin(to)}); the request would be sent unencrypted. " +
                "Configure an https endpoint, or the http URL directly if cleartext is intended"
        sameOrigin(from, to) || followCrossOriginRedirects -> null
        else -> "to another origin (${origin(to)}); configure that endpoint URL directly or enable followCrossOriginRedirects"
    }

    fun origin(uri: URI) = "${uri.scheme}://${uri.host}:${effectivePort(uri)}"

    private fun effectivePort(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    fun sameOrigin(a: URI, b: URI) =
        a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) && effectivePort(a) == effectivePort(b)
}
