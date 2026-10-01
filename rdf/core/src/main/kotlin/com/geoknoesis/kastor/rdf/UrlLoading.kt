package com.geoknoesis.kastor.rdf

import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Safety limits for loading RDF from a URL ([Rdf.parseFromUrl], [Rdf.parseFromUrlAsync],
 * [Rdf.parseDatasetFromUrl]).
 *
 * By default only `http` and `https` URLs are loaded, so URLs taken from untrusted input cannot
 * read local files (`file:`) or class-path resources (`jar:`); other schemes must be opted into
 * explicitly. Bodies larger than [maxBytes] are rejected with [RdfInputTooLargeException].
 *
 * @property allowedSchemes URL schemes that may be loaded (case-insensitive)
 * @property maxBytes Maximum number of bytes read from the response body
 * @property connectTimeoutMillis Connect timeout passed to the URL connection
 * @property readTimeoutMillis Read timeout passed to the URL connection (the longest a single read may block)
 * @property totalTimeoutMillis Overall deadline for connecting and reading the whole body, measured from
 *   when the connection is opened; a server that trickles bytes just faster than [readTimeoutMillis] is
 *   stopped with [RdfLoadTimeoutException]. `0` disables the overall deadline. The deadline is hard and covers the
 *   whole load, including every redirect hop: connect and read timeouts are lowered to the time remaining when each
 *   hop starts, each HTTP request (host name lookup, connect, TLS handshake and response headers) runs on a helper
 *   thread that the caller waits for only until the deadline, and a body read that could block past the deadline is
 *   abandoned when it passes. An abandoned request or read keeps its helper thread until its capped socket timeout
 *   (or the system's host name lookup) returns, and then releases its connection. Helper threads are daemon threads
 *   in a pool bounded to 32; when all are busy the work runs on the calling thread instead, bounded only by the capped
 *   socket timeouts (a slow host name lookup is then not cut short). A read that reaches the end of the body is never
 *   reported as a timeout. Default: 5 minutes.
 *
 * HTTP(S) requests send an `Accept` header for the requested format and `Connection: close`, and the connection is
 * closed when the load ends. Redirects (301, 302, 303, 307, 308) are followed by Kastor, up to 10 hops, only to a URL
 * with an allowed scheme that keeps the scheme or upgrades `http` to `https`, only while
 * [java.net.HttpURLConnection.getFollowRedirects] is true, and only if [redirectPolicy] allows the target. A redirect
 * may lead to another host; pass a [redirectPolicy] (for example [UrlRedirectPolicy.PUBLIC_ADDRESSES] or
 * [UrlRedirectPolicy.SAME_HOST]) when the first URL comes from untrusted input and the process can reach hosts the
 * caller must not (loopback, cloud metadata or other internal addresses). A relative `Location` is resolved against
 * the redirecting URL; characters a URL may not contain unencoded (spaces, `|`, non-ASCII) are percent-encoded in
 * its path, query and fragment, as lenient HTTP clients do. Non-2xx responses, including redirects that are not
 * followed (the `Location` is missing or not a usable URL, the scheme or the policy refuses it, or the hop limit is
 * reached), fail with [RdfHttpStatusException] before anything is parsed.
 *
 * @property redirectPolicy Decides, for each redirect that passed the scheme rules, whether it is followed.
 *   Default: [UrlRedirectPolicy.ALLOW_ALL]. It is not consulted for the URL the load starts with.
 */
data class UrlLoadOptions(
    val allowedSchemes: Set<String> = setOf("http", "https"),
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val connectTimeoutMillis: Int = 30_000,
    val readTimeoutMillis: Int = 30_000,
    val totalTimeoutMillis: Long = DEFAULT_TOTAL_TIMEOUT_MILLIS,
    val redirectPolicy: UrlRedirectPolicy = UrlRedirectPolicy.ALLOW_ALL,
) {
    /** Pre-[totalTimeoutMillis] constructor, kept for binary compatibility; uses the default overall deadline. */
    constructor(allowedSchemes: Set<String>, maxBytes: Long, connectTimeoutMillis: Int, readTimeoutMillis: Int) :
        this(allowedSchemes, maxBytes, connectTimeoutMillis, readTimeoutMillis, DEFAULT_TOTAL_TIMEOUT_MILLIS)

    /** Pre-[redirectPolicy] constructor, kept for binary compatibility; follows redirects to any host. */
    constructor(
        allowedSchemes: Set<String>,
        maxBytes: Long,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        totalTimeoutMillis: Long,
    ) : this(allowedSchemes, maxBytes, connectTimeoutMillis, readTimeoutMillis, totalTimeoutMillis, UrlRedirectPolicy.ALLOW_ALL)

    init {
        require(allowedSchemes.isNotEmpty()) { "allowedSchemes must not be empty" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(connectTimeoutMillis >= 0 && readTimeoutMillis >= 0 && totalTimeoutMillis >= 0) {
            "timeouts must not be negative"
        }
    }

    companion object {
        /** Default body size limit: 64 MiB. */
        const val DEFAULT_MAX_BYTES: Long = 64L * 1024 * 1024

        /** Default overall deadline for one URL load: 5 minutes. */
        const val DEFAULT_TOTAL_TIMEOUT_MILLIS: Long = 5L * 60 * 1000

        @JvmField
        val DEFAULT = UrlLoadOptions()
    }
}

/**
 * Decides whether URL loading ([UrlLoadOptions.redirectPolicy]) follows an HTTP redirect. It is asked once per
 * redirect, after the scheme rules of [UrlLoadOptions] accepted the target, with absolute `http`/`https` URLs; a
 * refused redirect fails the load with [RdfHttpStatusException] carrying the redirect status. The call counts
 * against [UrlLoadOptions.totalTimeoutMillis]; an exception it throws fails the load with that exception.
 */
fun interface UrlRedirectPolicy {
    /** True if the redirect from the URL [from] to the resolved target URL [to] may be followed. */
    fun allows(from: java.net.URI, to: java.net.URI): Boolean

    companion object {
        /** Follows every redirect (the default). */
        @JvmField
        val ALLOW_ALL: UrlRedirectPolicy = Named("ALLOW_ALL") { _, _ -> true }

        /** Follows a redirect only to the same host (ignoring case) and the same effective port. */
        @JvmField
        val SAME_HOST: UrlRedirectPolicy = Named("SAME_HOST") { from, to ->
            from.host.equals(to.host, ignoreCase = true) && effectivePort(from) == effectivePort(to)
        }

        /**
         * Refuses a redirect to a host that is, or whose name resolves to, a loopback, link-local, site-local
         * (private), unique-local, wildcard or multicast address, or that cannot be resolved. This looks the host
         * name up; the connection that follows looks it up again, so a name whose answers change between the two
         * lookups (DNS rebinding) is not caught - use network-level controls where that matters.
         */
        @JvmField
        val PUBLIC_ADDRESSES: UrlRedirectPolicy = Named("PUBLIC_ADDRESSES") { _, to -> resolvesToPublicAddresses(to) }

        private fun effectivePort(uri: java.net.URI): Int = when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }

        private fun resolvesToPublicAddresses(uri: java.net.URI): Boolean {
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() } ?: return false
            val addresses = try {
                java.net.InetAddress.getAllByName(host)
            } catch (_: java.net.UnknownHostException) {
                return false
            }
            return addresses.isNotEmpty() && addresses.none { address ->
                val bytes = address.address
                address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
                    address.isSiteLocalAddress || address.isMulticastAddress ||
                    // 0.0.0.0/8 ("this network") and IPv6 unique-local addresses fc00::/7
                    (bytes.size == 4 && bytes[0].toInt() == 0) ||
                    (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC)
            }
        }
    }

    private class Named(private val name: String, private val policy: UrlRedirectPolicy) : UrlRedirectPolicy by policy {
        override fun toString(): String = "UrlRedirectPolicy.$name"
    }
}

/**
 * Thrown when loading RDF over HTTP(S) returns a status outside 200-299; the body is not parsed.
 *
 * @property url The requested URL
 * @property statusCode The HTTP status code (for example 404, or 302 for an unfollowed redirect)
 */
class RdfHttpStatusException(val url: String, val statusCode: Int, message: String) : java.io.IOException(message)

/** Thrown when loading RDF from a URL exceeds [UrlLoadOptions.totalTimeoutMillis]. */
class RdfLoadTimeoutException(val timeoutMillis: Long) :
    java.io.InterruptedIOException("Loading RDF from a URL exceeded the overall timeout of $timeoutMillis ms")

/** Thrown when RDF input read from a URL exceeds [UrlLoadOptions.maxBytes]. */
class RdfInputTooLargeException(val limitBytes: Long) :
    java.io.IOException("RDF input exceeds the configured limit of $limitBytes bytes")

/** A stream that fails once a URL-loading limit is exceeded and remembers the failure it threw. */
internal interface LimitedStream {
    val failure: java.io.IOException?
}

/** An opened URL response body and the connection it came from. Closing [stream] releases the connection. */
internal class RdfUrlBody(
    val connection: java.net.URLConnection,
    val stream: InputStream,
    private val limits: List<LimitedStream>,
) {
    /** IRI that relative references in the body resolve against: the final URL, after redirects. */
    val baseIri: String get() = connection.url.toString()

    /**
     * Runs [block] on the body and closes it. Parsers may report a tripped limit only in their own message (Jena
     * wraps it in a RiotException without a cause), so a failure after a limit tripped is replaced by that limit's
     * exception, with the parser failure attached as suppressed.
     */
    fun <T> read(block: (InputStream) -> T): T = try {
        stream.use(block)
    } catch (e: Throwable) {
        val limit = limits.firstNotNullOfOrNull { it.failure }
        if (limit == null || limit === e) throw e
        limit.addSuppressed(e)
        throw limit
    }
}

/** IANA media type of this format, used in the HTTP `Accept` header. */
internal fun RdfFormat.mediaType(): String = when (this) {
    RdfFormat.TURTLE -> "text/turtle"
    RdfFormat.JSON_LD -> "application/ld+json"
    RdfFormat.RDF_XML -> "application/rdf+xml"
    RdfFormat.N_TRIPLES -> "application/n-triples"
    RdfFormat.TRIG -> "application/trig"
    RdfFormat.N_QUADS -> "application/n-quads"
}

/** Redirect statuses that are followed (all are safe to repeat as a GET without a body). */
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

/** Maximum number of redirects followed for one load. */
internal const val MAX_URL_REDIRECTS = 10

/**
 * Validates [url] against [options], connects, and returns the response body.
 *
 * - The scheme must be allowed by [UrlLoadOptions.allowedSchemes].
 * - HTTP(S): sends `Accept` for [format] (any type when null), follows redirects as described on
 *   [UrlLoadOptions], and fails with [RdfHttpStatusException] on a non-2xx status.
 * - A declared `Content-Length` above [UrlLoadOptions.maxBytes] fails fast; the body is bounded to it.
 * - [UrlLoadOptions.totalTimeoutMillis] is enforced from the moment the first connection is opened, across hops.
 *
 * [onConnection] receives each connection (one per redirect hop) before it connects, so a caller can disconnect it
 * to cancel. On failure the connection is released before the exception propagates (or, if a request was abandoned
 * at the deadline, once that request returns).
 */
internal fun openRdfUrlStream(
    url: String,
    format: RdfFormat?,
    options: UrlLoadOptions,
    onConnection: (java.net.URLConnection) -> Unit = {},
): RdfUrlBody {
    var uri = checkedUri(url, options)
    val started = System.nanoTime()
    val total = options.totalTimeoutMillis
    var redirects = 0
    while (true) {
        // When both socket timeouts of this hop are the time left, a socket timeout is the deadline expiring.
        val hopRemaining = remainingMillis(started, total)
        val timeoutsAreDeadline = total > 0 &&
            listOf(options.connectTimeoutMillis, options.readTimeoutMillis).all { it == 0 || it >= hopRemaining }
        val connection = uri.toURL().openConnection().apply {
            connectTimeout = cappedTimeout(options.connectTimeoutMillis, remainingMillis(started, total))
            readTimeout = cappedTimeout(options.readTimeoutMillis, remainingMillis(started, total))
            setRequestProperty("Accept", format?.let { "${it.mediaType()}, */*;q=0.1" } ?: "*/*")
        }
        val http = connection as? java.net.HttpURLConnection
        val followRedirects = http?.instanceFollowRedirects ?: false
        http?.apply {
            instanceFollowRedirects = false
            // Every load disconnects when it ends, so the connection is never kept alive for reuse.
            setRequestProperty("Connection", "close")
        }
        onConnection(connection)
        // Set when the connection is released elsewhere: by an abandoned request once it returns, or by the body.
        var releasedElsewhere = false
        try {
            if (http != null) {
                val status = withinDeadline(started, total, onAbandon = { release(http) }) {
                    http.connect()
                    http.responseCode
                }.getOrElse { e ->
                    releasedElsewhere = e is RdfLoadTimeoutException || e is java.io.InterruptedIOException
                    throw e
                }
                if (total > 0 && elapsedMillis(started) >= total) throw RdfLoadTimeoutException(total)
                if (status in REDIRECT_STATUSES) {
                    val location: String? = http.getHeaderField("Location")
                    val resolved: Any = when {
                        !followRedirects -> "redirects are disabled"
                        location.isNullOrBlank() -> "no Location header"
                        redirects >= MAX_URL_REDIRECTS -> "more than $MAX_URL_REDIRECTS redirects"
                        else -> redirectTarget(uri, location, options)
                    }
                    val from = uri
                    val policy = options.redirectPolicy
                    // The policy may look host names up, so it runs within the deadline like the request itself.
                    val target: Any = if (resolved is java.net.URI && policy !== UrlRedirectPolicy.ALLOW_ALL &&
                        !withinDeadline(started, total, onAbandon = {}) { policy.allows(from, resolved) }.getOrThrow()
                    ) "refused by the redirect policy" else resolved
                    if (target is java.net.URI) {
                        release(http)
                        redirects++
                        uri = target
                        continue
                    }
                    throw RdfHttpStatusException(
                        uri.toString(), status,
                        "HTTP $status redirect${location?.let { " to $it" } ?: ""} not followed ($target) " +
                            "while loading RDF from $uri",
                    )
                }
                if (status !in 200..299) {
                    val reason = http.responseMessage?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
                    throw RdfHttpStatusException(uri.toString(), status, "HTTP $status$reason while loading RDF from $uri")
                }
            }
            if (connection.contentLengthLong > options.maxBytes) throw RdfInputTooLargeException(options.maxBytes)
            val bounded = BoundedInputStream(connection.getInputStream(), options.maxBytes)
            val releaseConnection = { release(connection) }
            if (total > 0) {
                // The socket keeps the read timeout it had when the request was sent, so a body read may block that
                // long; the stream waits for such reads only as long as the deadline allows.
                val socketReadMillis = http?.readTimeout?.toLong()
                val deadline = DeadlineInputStream(bounded, started, total, socketReadMillis, releaseConnection)
                releasedElsewhere = true
                try {
                    deadline.checkDeadline()
                } catch (e: Throwable) {
                    runCatching { deadline.close() }
                    throw e
                }
                return RdfUrlBody(connection, deadline, listOf(deadline, bounded))
            }
            return RdfUrlBody(connection, ReleasingInputStream(bounded, releaseConnection), listOf(bounded))
        } catch (e: Throwable) {
            if (!releasedElsewhere) release(connection)
            val timedOut = total > 0 && e is java.io.IOException && e !is RdfLoadTimeoutException &&
                e !is RdfHttpStatusException && e !is RdfInputTooLargeException &&
                (elapsedMillis(started) >= total || (e is java.net.SocketTimeoutException && timeoutsAreDeadline))
            if (timedOut) throw RdfLoadTimeoutException(total).also { it.addSuppressed(e) }
            throw e
        }
    }
}

/** Parses [url] and checks that it is absolute with an allowed scheme. */
private fun checkedUri(url: String, options: UrlLoadOptions): java.net.URI {
    val uri = try {
        java.net.URI(url)
    } catch (e: java.net.URISyntaxException) {
        throw IllegalArgumentException("Invalid URL: $url", e)
    }
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: throw IllegalArgumentException("URL must be absolute: $url")
    require(options.allowedSchemes.any { it.equals(scheme, ignoreCase = true) }) {
        "URL scheme '$scheme' is not allowed for RDF loading (allowed: ${options.allowedSchemes.sorted()}); " +
            "pass UrlLoadOptions(allowedSchemes = ...) to opt in"
    }
    return uri
}

/**
 * The URI a redirect from [from] to [location] leads to, or a String saying why it is not followed: the target must
 * be a valid absolute URL with a host, a port in range and an allowed scheme that is the same as the scheme of [from]
 * or upgrades `http` to `https`.
 */
internal fun redirectTarget(from: java.net.URI, location: String, options: UrlLoadOptions): Any {
    val target = try {
        val reference = java.net.URI(encodeLocation(location.trim()) ?: return "invalid Location")
        // RFC 3986 section 5.2.3: a base with an authority and an empty path merges as "/". (java.net.URI did not
        // do this on every JDK: "http://example.com" + "data.ttl" became "http://example.comdata.ttl".)
        val base = if (from.rawPath.isNullOrEmpty() && from.rawAuthority != null) {
            java.net.URI(from.scheme + "://" + from.rawAuthority + "/" + (from.rawQuery?.let { "?$it" } ?: ""))
        } else from
        base.resolve(reference).also { it.toURL() }
    } catch (_: Exception) {
        return "invalid Location"
    }
    val fromScheme = from.scheme.lowercase(Locale.ROOT)
    val toScheme = target.scheme?.lowercase(Locale.ROOT) ?: return "invalid Location"
    return when {
        options.allowedSchemes.none { it.equals(toScheme, ignoreCase = true) } -> "scheme '$toScheme' is not allowed"
        toScheme != fromScheme && !(fromScheme == "http" && toScheme == "https") -> "scheme change $fromScheme to $toScheme"
        target.host.isNullOrEmpty() || target.port > 65535 -> "invalid Location"
        else -> target
    }
}

/**
 * [location] with the characters a URI may not contain unencoded - spaces and other control characters, `"`, `<`,
 * `>`, `\`, `^`, `` ` ``, `{`, `|`, `}`, non-ASCII characters and a `%` that does not start an escape -
 * percent-encoded (UTF-8), as browsers and the JDK's own redirect handling accept them. Returns null if such a
 * character occurs in the scheme or authority: encoding it there would name a different host.
 */
private fun encodeLocation(location: String): String? {
    val authority = LOCATION_AUTHORITY_PREFIX.find(location)
    val pathStart = if (authority == null) 0 else {
        location.indexOfAny(charArrayOf('/', '?', '#'), authority.range.last + 1).let { if (it < 0) location.length else it }
    }
    fun isHex(index: Int) = index < location.length && Character.digit(location[index], 16) >= 0
    var out: StringBuilder? = null
    var i = 0
    while (i < location.length) {
        val c = location[i]
        val legal = c.code in 0x21..0x7E && c !in ILLEGAL_URI_CHARACTERS && (c != '%' || (isHex(i + 1) && isHex(i + 2)))
        if (legal) {
            out?.append(c)
            i++
            continue
        }
        if (i < pathStart) return null
        val builder = out ?: StringBuilder(location.length + 16).append(location, 0, i).also { out = it }
        val codePoint = location.codePointAt(i)
        for (byte in String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)) {
            builder.append('%').append(HEX_DIGITS[(byte.toInt() shr 4) and 0xF]).append(HEX_DIGITS[byte.toInt() and 0xF])
        }
        i += Character.charCount(codePoint)
    }
    return out?.toString() ?: location
}

/** `scheme://` or `//` at the start of a Location: what follows, up to the next `/`, `?` or `#`, is the authority. */
private val LOCATION_AUTHORITY_PREFIX = Regex("^(?:[A-Za-z][A-Za-z0-9+.-]*:)?//")
private const val ILLEGAL_URI_CHARACTERS = "\"<>\\^`{|}"
private const val HEX_DIGITS = "0123456789ABCDEF"

/** Releases [connection]: disconnects an HTTP connection, or closes the input stream of any other connection. */
private fun release(connection: java.net.URLConnection) {
    val http = connection as? java.net.HttpURLConnection
    if (http != null) {
        runCatching { http.errorStream?.close() }
        runCatching { http.disconnect() }
    } else {
        runCatching { connection.getInputStream().close() }
    }
}

/**
 * Runs [action] on a helper thread and waits for it only until the deadline ([totalMillis] after [startedNanos]).
 * If the deadline passes (or the caller is interrupted) first, [onAbandon] is scheduled to run once [action] returns
 * and the result is a failed [RdfLoadTimeoutException] (or [java.io.InterruptedIOException]). Without a deadline, or
 * when every helper thread is busy, [action] runs on the calling thread. Failures of [action] itself are rethrown.
 */
private fun <T> withinDeadline(
    startedNanos: Long,
    totalMillis: Long,
    onAbandon: () -> Unit,
    action: () -> T,
): Result<T> {
    if (totalMillis <= 0) return Result.success(action())
    val call = HelperCall(action)
    if (!call.start()) return Result.success(action())
    return try {
        Result.success(call.await(TimeUnit.MILLISECONDS.toNanos(totalMillis) - (System.nanoTime() - startedNanos)))
    } catch (_: java.util.concurrent.TimeoutException) {
        call.abandon(onAbandon)
        Result.failure(RdfLoadTimeoutException(totalMillis))
    } catch (e: InterruptedException) {
        call.abandon(onAbandon)
        Thread.currentThread().interrupt()
        Result.failure(java.io.InterruptedIOException("Interrupted while loading RDF from a URL").apply { initCause(e) })
    }
}

/**
 * Bounded pool of daemon threads for URL-loading work that may block past the overall deadline. A load uses at most
 * one helper at a time; a helper whose work was abandoned stays busy until that work returns, which the capped socket
 * timeouts bound (a host name lookup is bounded only by the system resolver).
 */
internal object UrlLoadHelpers {
    const val MAX_THREADS = 32
    const val THREAD_NAME_PREFIX = "kastor-url-helper-"
    private val count = java.util.concurrent.atomic.AtomicInteger()

    private val pool: java.util.concurrent.ThreadPoolExecutor by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            0, MAX_THREADS, 10, TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
            { runnable -> Thread(runnable, THREAD_NAME_PREFIX + count.incrementAndGet()).apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        )
    }

    /** Starts [task] on a helper thread; false (without running it) if all [MAX_THREADS] helpers are busy. */
    fun tryExecute(task: Runnable): Boolean = try {
        pool.execute(task)
        true
    } catch (_: java.util.concurrent.RejectedExecutionException) {
        false
    }
}

/**
 * One blocking call run on a [UrlLoadHelpers] thread. If the caller stops waiting, [abandon] hands clean-up to the
 * helper, which runs it once the call returns: the JDK's HTTP connections and streams cannot be closed from another
 * thread while such a call blocks (closing waits for it), so this releases them without occupying a second thread.
 */
internal class HelperCall<T>(action: () -> T) {
    private val lock = Any()
    private var returned = false
    private var cleanup: (() -> Unit)? = null
    private val task = java.util.concurrent.FutureTask {
        try {
            action()
        } finally {
            val pending = synchronized(lock) { returned = true; cleanup }
            pending?.let { runCatching(it) }
        }
    }

    /** Starts the call; false if no helper thread is free (the call then never runs). */
    fun start(): Boolean = UrlLoadHelpers.tryExecute(task)

    /** Waits up to [nanos] for the result, rethrowing the call's own failure. */
    fun await(nanos: Long): T = try {
        task.get(nanos.coerceAtLeast(1), TimeUnit.NANOSECONDS)
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    }

    /** Runs [release] once the call has returned: now if it already has, otherwise on the helper thread afterwards. */
    fun abandon(release: () -> Unit) {
        val now = synchronized(lock) {
            if (!returned) cleanup = release
            returned
        }
        if (now) runCatching(release)
    }
}

/**
 * Input stream that releases its connection when closed. The release runs first: closing a JDK HTTP stream detaches
 * it from its connection, so a later disconnect would leave the socket in the keep-alive cache.
 */
private class ReleasingInputStream(input: InputStream, private val release: () -> Unit) : FilterInputStream(input) {
    override fun close() {
        try {
            release()
        } finally {
            super.close()
        }
    }

    override fun markSupported(): Boolean = false
}

private fun elapsedMillis(startedNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

/** Milliseconds left of [totalMillis] (at least 1), or 0 when there is no overall deadline. */
private fun remainingMillis(startedNanos: Long, totalMillis: Long): Long =
    if (totalMillis <= 0) 0 else (totalMillis - elapsedMillis(startedNanos)).coerceAtLeast(1)

/** A single blocking call may not wait longer than the time remaining. `0` means "no limit" for both. */
private fun cappedTimeout(timeoutMillis: Int, remainingMillis: Long): Int = when {
    remainingMillis <= 0 -> timeoutMillis
    timeoutMillis == 0 -> remainingMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    else -> minOf(timeoutMillis.toLong(), remainingMillis).toInt()
}

/**
 * Input stream that throws [RdfLoadTimeoutException] once [timeoutMillis] have passed since [startedNanos].
 *
 * - Reaching the end of the stream is never a timeout, even if the final read returns after the deadline.
 * - [blockingReadMillis] is the longest one read of the underlying stream can block (`0` = unbounded), or null if
 *   reads do not block. When less time than that remains, the read runs on a helper thread and the caller waits only
 *   until the deadline, so no read can overrun it. A read abandoned this way is closed by its helper thread once it
 *   returns, because the JDK's HTTP streams only close once the blocked read returns. When every helper thread is
 *   busy, the read runs on the calling thread.
 * - An I/O failure that happens after the deadline is reported as a timeout.
 * - [onClose] runs when the stream is closed, before the underlying stream is closed (so an HTTP connection can still
 *   be disconnected: closing its stream first forgets the connection), and after an abandoned read returns.
 */
internal class DeadlineInputStream(
    input: InputStream,
    private val startedNanos: Long,
    private val timeoutMillis: Long,
    blockingReadMillis: Long? = null,
    private val onClose: () -> Unit = {},
) : FilterInputStream(input), LimitedStream {
    private val limitNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    private val blockingReadNanos: Long? = blockingReadMillis?.let {
        if (it <= 0) Long.MAX_VALUE else TimeUnit.MILLISECONDS.toNanos(it)
    }
    private var ended = false
    private var abandoned: HelperCall<*>? = null
    private var closed = false

    override var failure: java.io.IOException? = null
        private set

    private fun remainingNanos() = limitNanos - (System.nanoTime() - startedNanos)

    private fun timeout(): java.io.IOException = failure ?: RdfLoadTimeoutException(timeoutMillis).also { failure = it }

    fun checkDeadline() {
        if (remainingNanos() < 0) throw timeout()
    }

    /** Buffer for helper-thread reads, reused across reads (no read runs after one is abandoned). */
    private var helperBuffer: ByteArray? = null

    /** True if a read may block without outlasting the deadline, so it can run on the calling thread. */
    private fun readsDirectly(remaining: Long): Boolean = blockingReadNanos == null || remaining > blockingReadNanos

    /** Runs one read of the underlying stream without letting it outlast the deadline. */
    private fun <T> timed(read: () -> T): T = timed(remainingNanos()) { read() }

    /**
     * Runs one read of the underlying stream without letting it outlast the deadline; [read] is told whether it runs
     * on the calling thread (decided from [remaining], the time left when the read was scheduled).
     */
    private fun <T> timed(remaining: Long, read: (direct: Boolean) -> T): T {
        abandoned?.let { throw timeout() }
        try {
            if (readsDirectly(remaining)) return read(true)
            val call = HelperCall { read(false) }
            if (!call.start()) return read(false)
            try {
                return call.await(remaining)
            } catch (_: java.util.concurrent.TimeoutException) {
                abandoned = call
                throw timeout()
            } catch (e: InterruptedException) {
                abandoned = call
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("Interrupted while loading RDF from a URL").apply { initCause(e) }
            }
        } catch (e: java.io.IOException) {
            // A read that was allowed to block past the deadline has a socket timeout no shorter than the time that was
            // left, so its socket timeout is the deadline expiring (even if the clock says a moment is left).
            val socketTimeout = e is java.net.SocketTimeoutException
            val deadlinePassed = remainingNanos() < 0 || (socketTimeout && !readsDirectly(remaining))
            if (e is RdfInputTooLargeException || e is RdfLoadTimeoutException || !deadlinePassed ||
                (e is java.io.InterruptedIOException && !socketTimeout)) throw e
            throw timeout().also { if (it !== e) it.addSuppressed(e) }
        }
    }

    override fun read(): Int {
        if (ended) return -1
        checkDeadline()
        val b = timed { `in`.read() }
        if (b < 0) { ended = true; return -1 }
        checkDeadline()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (ended) return -1
        if (len == 0) return 0
        checkDeadline()
        // A read on the calling thread fills the caller's array directly. A helper read fills a private buffer (reused
        // across reads), so an abandoned read never writes into the caller's array.
        val remaining = remainingNanos()
        val buffer = if (readsDirectly(remaining)) b else {
            helperBuffer?.takeIf { it.size >= len } ?: ByteArray(len).also { helperBuffer = it }
        }
        val n = timed(remaining) { direct -> if (direct) `in`.read(b, off, len) else `in`.read(buffer, 0, len) }
        if (n < 0) { ended = true; return -1 }
        if (buffer !== b) System.arraycopy(buffer, 0, b, off, n)
        checkDeadline()
        return n
    }

    override fun skip(n: Long): Long {
        checkDeadline()
        return timed { `in`.skip(n) }
    }

    override fun close() {
        if (closed) return
        closed = true
        val pending = abandoned
        if (pending != null) {
            pending.abandon {
                runCatching { onClose() }
                runCatching { `in`.close() }
            }
        } else {
            try {
                onClose()
            } finally {
                super.close()
            }
        }
    }

    override fun markSupported(): Boolean = false
}

/** Input stream that throws [RdfInputTooLargeException] once more than [limit] bytes have been read. */
internal class BoundedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input), LimitedStream {
    private var count = 0L

    override var failure: java.io.IOException? = null
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) advance(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) advance(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) advance(skipped)
        return skipped
    }

    override fun markSupported(): Boolean = false

    private fun advance(n: Long) {
        count += n
        if (count > limit) throw (failure ?: RdfInputTooLargeException(limit).also { failure = it })
    }
}

/** Counts bytes read, so a parse can tell whether a provider consumed input before declining it. */
internal class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count += n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) count += skipped
        return skipped
    }

    override fun markSupported(): Boolean = false
}

/** Finds a URL-loading limit failure (body size or overall deadline) wrapped by a provider's own parser exceptions. */
internal fun Throwable.inputLimitCause(): java.io.IOException? =
    generateSequence(this) { it.cause }.take(16)
        .firstOrNull { it is RdfInputTooLargeException || it is RdfLoadTimeoutException } as java.io.IOException?
