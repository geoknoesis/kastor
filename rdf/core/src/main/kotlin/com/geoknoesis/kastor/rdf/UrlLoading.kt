package com.geoknoesis.kastor.rdf

import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

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
 * What URL loading takes from its environment. Production code uses [DEFAULT]; tests replace single parts to make a
 * load deterministic and to observe the one load they started: a helper pool of their own, connections that never
 * touch the network, a clock.
 */
internal class UrlLoadRuntime(
    /** The helper threads of the loads that use this runtime. */
    val helpers: UrlLoadHelperPool = UrlLoadHelpers.shared,
    /** Creates the connection for a URL; the load configures and connects it. */
    val open: (java.net.URI) -> java.net.URLConnection = { it.toURL().openConnection() },
    /** Told once about every connection of a load when it is released (disconnected, or its stream closed). */
    val onRelease: (java.net.URLConnection) -> Unit = {},
    /** The clock the overall deadline is measured with. */
    val nanoTime: () -> Long = System::nanoTime,
) {
    companion object {
        val DEFAULT: UrlLoadRuntime by lazy { UrlLoadRuntime() }
    }
}

/**
 * Validates [url] against [options], connects, and returns the response body.
 *
 * - The scheme must be allowed by [UrlLoadOptions.allowedSchemes].
 * - HTTP(S): sends `Accept` for [format] (any type when null), follows redirects as described on
 *   [UrlLoadOptions], and fails with [RdfHttpStatusException] on a non-2xx status.
 * - A declared `Content-Length` above [UrlLoadOptions.maxBytes] fails fast; the body is bounded to it.
 * - [UrlLoadOptions.addressPolicy] is asked about [url] and about every redirect target before a connection to it
 *   is opened; a refusal fails with [RdfAddressRefusedException].
 * - [UrlLoadOptions.totalTimeoutMillis] is enforced from the moment the load starts, across policies and hops, for
 *   every scheme: connecting (and, for a scheme other than HTTP, opening the body, which is where FTP sends its
 *   commands) runs on a helper thread, and so does every body read that could block past the deadline.
 *
 * [onConnection] receives each connection (one per redirect hop) before it connects, so a caller can disconnect it
 * to cancel. On failure the connection is released before the exception propagates (or, if a request was abandoned
 * at the deadline, once that request returns).
 */
internal fun openRdfUrlStream(
    url: String,
    format: RdfFormat?,
    options: UrlLoadOptions,
    runtime: UrlLoadRuntime = UrlLoadRuntime.DEFAULT,
    onConnection: (java.net.URLConnection) -> Unit = {},
): RdfUrlBody {
    var uri = checkedUri(url, options)
    val started = runtime.nanoTime()
    val total = options.totalTimeoutMillis
    fun elapsedMillis(): Long = TimeUnit.NANOSECONDS.toMillis(runtime.nanoTime() - started)
    // Milliseconds left of the deadline (at least 1), or 0 when there is no overall deadline.
    fun remainingMillis(): Long = if (total <= 0) 0 else (total - elapsedMillis()).coerceAtLeast(1)
    var redirects = 0
    while (true) {
        val addressPolicy = options.addressPolicy
        if (addressPolicy !== UrlAddressPolicy.ALLOW_ALL) {
            val target = uri
            // The policy may look host names up, so it runs within the deadline like the request itself.
            if (!withinDeadline(runtime, started, total, onAbandon = {}) { addressPolicy.allows(target) }.getOrThrow()) {
                throw RdfAddressRefusedException(
                    target.toString(),
                    (if (redirects == 0) "Loading RDF from $target" else "The redirect to $target (from $url)") +
                        " was refused by the address policy $addressPolicy",
                )
            }
        }
        // When both socket timeouts of this hop are the time left, a socket timeout is the deadline expiring.
        val hopRemaining = remainingMillis()
        val timeoutsAreDeadline = total > 0 &&
            listOf(options.connectTimeoutMillis, options.readTimeoutMillis).all { it == 0 || it >= hopRemaining }
        val connection = runtime.open(uri).apply {
            connectTimeout = cappedTimeout(options.connectTimeoutMillis, remainingMillis())
            readTimeout = cappedTimeout(options.readTimeoutMillis, remainingMillis())
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
        // The body of a connection that is not HTTP, once it is open: what releasing such a connection closes.
        val openedBody = java.util.concurrent.atomic.AtomicReference<InputStream?>()
        val released = java.util.concurrent.atomic.AtomicBoolean()
        val releaseConnection: () -> Unit = {
            if (released.compareAndSet(false, true)) {
                release(connection, openedBody.get())
                runCatching { runtime.onRelease(connection) }
            }
        }
        // Set when the connection is released elsewhere: by an abandoned request once it returns, or by the body.
        var releasedElsewhere = false
        try {
            // Whatever the scheme, connecting may block for longer than the deadline allows (a host name lookup, the
            // response headers of HTTP, the commands of FTP): it runs on a helper the caller waits for until then.
            val status = withinDeadline(runtime, started, total, onAbandon = releaseConnection) {
                connection.connect()
                if (http != null) http.responseCode else {
                    openedBody.set(connection.getInputStream())
                    -1
                }
            }.getOrElse { e ->
                releasedElsewhere = e is RdfLoadTimeoutException || e is java.io.InterruptedIOException
                throw e
            }
            if (total > 0 && elapsedMillis() >= total) throw RdfLoadTimeoutException(total)
            if (http != null) {
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
                        !withinDeadline(runtime, started, total, onAbandon = {}) { policy.allows(from, resolved) }.getOrThrow()
                    ) "refused by the redirect policy" else resolved
                    if (target is java.net.URI) {
                        releaseConnection()
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
            val bounded = BoundedInputStream(openedBody.get() ?: connection.getInputStream(), options.maxBytes)
            if (total > 0) {
                // The connection keeps the read timeout it had when it connected, so a body read may block that long
                // (0: without limit); the stream waits for such reads only as long as the deadline allows.
                val deadline = DeadlineInputStream(
                    bounded, started, total, connection.readTimeout.toLong(),
                    helpers = runtime.helpers, nanoTime = runtime.nanoTime, onClose = releaseConnection,
                )
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
            if (!releasedElsewhere) releaseConnection()
            // An interrupt is reported as what it is, also when it arrives after the deadline.
            val interrupted = e is java.io.InterruptedIOException && e !is java.net.SocketTimeoutException
            val timedOut = total > 0 && e is java.io.IOException && !interrupted &&
                e !is RdfHttpStatusException && e !is RdfInputTooLargeException &&
                (elapsedMillis() >= total || (e is java.net.SocketTimeoutException && timeoutsAreDeadline))
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

/**
 * Releases [connection]: disconnects an HTTP connection, or closes the body [opened] of any other connection (null
 * if it was never opened).
 */
private fun release(connection: java.net.URLConnection, opened: InputStream?) {
    val http = connection as? java.net.HttpURLConnection
    if (http != null) {
        runCatching { http.errorStream?.close() }
        runCatching { http.disconnect() }
    } else {
        runCatching { opened?.close() }
    }
}

/**
 * Runs [action] on a helper thread and waits for it only until the deadline ([totalMillis] after [startedNanos]).
 * If the deadline passes (or the caller is interrupted) first, the helper thread is interrupted, [onAbandon] is
 * scheduled to run once [action] returns and the result is a failed [RdfLoadTimeoutException] (or
 * [java.io.InterruptedIOException]). When every helper thread is busy the caller waits for one, also only until the
 * deadline; if none becomes free, [action] never runs and [onAbandon] runs at once. Without a deadline [action] runs
 * on the calling thread. Failures of [action] itself are rethrown.
 */
private fun <T> withinDeadline(
    runtime: UrlLoadRuntime,
    startedNanos: Long,
    totalMillis: Long,
    onAbandon: () -> Unit,
    action: () -> T,
): Result<T> {
    if (totalMillis <= 0) return Result.success(action())
    fun remaining() = TimeUnit.MILLISECONDS.toNanos(totalMillis) - (runtime.nanoTime() - startedNanos)
    val call = HelperCall(runtime.helpers, action)
    return try {
        if (call.start(remaining())) Result.success(call.await(remaining())) else {
            runCatching(onAbandon)
            Result.failure(RdfLoadTimeoutException(totalMillis))
        }
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

/** A single blocking call may not wait longer than the time remaining. `0` means "no limit" for both. */
private fun cappedTimeout(timeoutMillis: Int, remainingMillis: Long): Int = when {
    remainingMillis <= 0 -> timeoutMillis
    timeoutMillis == 0 -> remainingMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    else -> minOf(timeoutMillis.toLong(), remainingMillis).toInt()
}
