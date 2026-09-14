package com.geoknoesis.kastor.rdf

import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale

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
 *   stopped with [RdfLoadTimeoutException]. `0` disables the overall deadline. The deadline is checked
 *   around every read, and connect/read timeouts are lowered to it, so one blocking call can overrun it by at
 *   most that call's own timeout. Default: 5 minutes.
 *
 * HTTP(S) requests send an `Accept` header for the requested format, and non-2xx responses (including
 * redirects the JDK does not follow, such as http to https) fail with [RdfHttpStatusException] before
 * anything is parsed.
 */
data class UrlLoadOptions(
    val allowedSchemes: Set<String> = setOf("http", "https"),
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val connectTimeoutMillis: Int = 30_000,
    val readTimeoutMillis: Int = 30_000,
    val totalTimeoutMillis: Long = DEFAULT_TOTAL_TIMEOUT_MILLIS,
) {
    /** Pre-[totalTimeoutMillis] constructor, kept for binary compatibility; uses the default overall deadline. */
    constructor(allowedSchemes: Set<String>, maxBytes: Long, connectTimeoutMillis: Int, readTimeoutMillis: Int) :
        this(allowedSchemes, maxBytes, connectTimeoutMillis, readTimeoutMillis, DEFAULT_TOTAL_TIMEOUT_MILLIS)

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

/** An opened URL response body and the connection it came from. */
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

/**
 * Validates [url] against [options], connects, and returns the response body.
 *
 * - The scheme must be allowed by [UrlLoadOptions.allowedSchemes].
 * - HTTP(S): sends `Accept` for [format] (any type when null) and fails with [RdfHttpStatusException] on a
 *   non-2xx status.
 * - A declared `Content-Length` above [UrlLoadOptions.maxBytes] fails fast; the body is bounded to it.
 * - [UrlLoadOptions.totalTimeoutMillis] is enforced from the moment the connection is opened.
 *
 * [onConnection] receives the connection before it connects, so a caller can disconnect it to cancel.
 * On failure the connection is released before the exception propagates.
 */
internal fun openRdfUrlStream(
    url: String,
    format: RdfFormat?,
    options: UrlLoadOptions,
    onConnection: (java.net.URLConnection) -> Unit = {},
): RdfUrlBody {
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
    val started = System.nanoTime()
    val connection = uri.toURL().openConnection().apply {
        connectTimeout = cappedTimeout(options.connectTimeoutMillis, options.totalTimeoutMillis)
        readTimeout = cappedTimeout(options.readTimeoutMillis, options.totalTimeoutMillis)
        setRequestProperty("Accept", format?.let { "${it.mediaType()}, */*;q=0.1" } ?: "*/*")
    }
    onConnection(connection)
    try {
        (connection as? java.net.HttpURLConnection)?.let { http ->
            val status = http.responseCode
            if (status !in 200..299) {
                val reason = http.responseMessage?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
                throw RdfHttpStatusException(url, status, "HTTP $status$reason while loading RDF from $url")
            }
        }
        if (connection.contentLengthLong > options.maxBytes) throw RdfInputTooLargeException(options.maxBytes)
        val bounded = BoundedInputStream(connection.getInputStream(), options.maxBytes)
        if (options.totalTimeoutMillis > 0) {
            val deadline = DeadlineInputStream(bounded, started, options.totalTimeoutMillis).also { it.checkDeadline() }
            return RdfUrlBody(connection, deadline, listOf(deadline, bounded))
        }
        return RdfUrlBody(connection, bounded, listOf(bounded))
    } catch (e: Throwable) {
        val http = connection as? java.net.HttpURLConnection
        if (http != null) {
            runCatching { http.errorStream?.close() }
            http.disconnect()
        } else {
            runCatching { connection.getInputStream().close() }
        }
        throw e
    }
}

/** A single blocking call may not wait longer than the overall deadline. `0` means "no limit" for both. */
private fun cappedTimeout(timeoutMillis: Int, totalTimeoutMillis: Long): Int = when {
    totalTimeoutMillis <= 0 -> timeoutMillis
    timeoutMillis == 0 -> totalTimeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    else -> minOf(timeoutMillis.toLong(), totalTimeoutMillis).toInt()
}

/** Input stream that throws [RdfLoadTimeoutException] once [timeoutMillis] have passed since [startedNanos]. */
internal class DeadlineInputStream(
    input: InputStream,
    private val startedNanos: Long,
    private val timeoutMillis: Long,
) : FilterInputStream(input), LimitedStream {
    private val limitNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

    override var failure: java.io.IOException? = null
        private set

    fun checkDeadline() {
        if (System.nanoTime() - startedNanos > limitNanos) {
            throw (failure ?: RdfLoadTimeoutException(timeoutMillis).also { failure = it })
        }
    }

    override fun read(): Int {
        checkDeadline()
        return super.read().also { checkDeadline() }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkDeadline()
        return super.read(b, off, len).also { checkDeadline() }
    }

    override fun skip(n: Long): Long {
        checkDeadline()
        return super.skip(n)
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
