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
 *   stopped with [RdfLoadTimeoutException]. `0` disables the overall deadline. The deadline is hard: connect and
 *   read timeouts are lowered to the time remaining when each phase starts, and a body read that could block past
 *   the deadline is abandoned when it passes (its connection is then closed in the background). A read that reaches
 *   the end of the body is never reported as a timeout. Default: 5 minutes.
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
    val total = options.totalTimeoutMillis
    val connection = uri.toURL().openConnection().apply {
        connectTimeout = cappedTimeout(options.connectTimeoutMillis, remainingMillis(started, total))
        readTimeout = cappedTimeout(options.readTimeoutMillis, remainingMillis(started, total))
        setRequestProperty("Accept", format?.let { "${it.mediaType()}, */*;q=0.1" } ?: "*/*")
    }
    onConnection(connection)
    val http = connection as? java.net.HttpURLConnection
    try {
        if (http != null) {
            http.connect()
            if (total > 0) {
                if (elapsedMillis(started) >= total) throw RdfLoadTimeoutException(total)
                // Waiting for the response headers may only use what the connect left of the overall budget.
                http.readTimeout = cappedTimeout(options.readTimeoutMillis, remainingMillis(started, total))
            }
            val status = http.responseCode
            if (status !in 200..299) {
                val reason = http.responseMessage?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
                throw RdfHttpStatusException(url, status, "HTTP $status$reason while loading RDF from $url")
            }
        }
        if (connection.contentLengthLong > options.maxBytes) throw RdfInputTooLargeException(options.maxBytes)
        val bounded = BoundedInputStream(connection.getInputStream(), options.maxBytes)
        if (total > 0) {
            // The socket keeps the read timeout it had when the request was sent, so a body read may block that
            // long; the stream waits for such reads only as long as the deadline allows.
            val socketReadMillis = http?.readTimeout?.toLong()
            val deadline = DeadlineInputStream(bounded, started, total, socketReadMillis).also { it.checkDeadline() }
            return RdfUrlBody(connection, deadline, listOf(deadline, bounded))
        }
        return RdfUrlBody(connection, bounded, listOf(bounded))
    } catch (e: Throwable) {
        if (http != null) {
            runCatching { http.errorStream?.close() }
            http.disconnect()
        } else {
            runCatching { connection.getInputStream().close() }
        }
        val timedOut = total > 0 && e is java.io.IOException && e !is RdfLoadTimeoutException &&
            e !is RdfHttpStatusException && e !is RdfInputTooLargeException && elapsedMillis(started) >= total
        if (timedOut) throw RdfLoadTimeoutException(total).also { it.addSuppressed(e) }
        throw e
    }
}

private fun elapsedMillis(startedNanos: Long): Long =
    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

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
 *   until the deadline, so no read can overrun it. A read abandoned this way is closed asynchronously, because the
 *   JDK's HTTP streams only close once the blocked read returns.
 * - An I/O failure that happens after the deadline is reported as a timeout.
 */
internal class DeadlineInputStream(
    input: InputStream,
    private val startedNanos: Long,
    private val timeoutMillis: Long,
    blockingReadMillis: Long? = null,
) : FilterInputStream(input), LimitedStream {
    private val limitNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    private val blockingReadNanos: Long? = blockingReadMillis?.let {
        if (it <= 0) Long.MAX_VALUE else java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(it)
    }
    private var ended = false
    private var abandoned: java.util.concurrent.Future<*>? = null

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
            val task = java.util.concurrent.FutureTask { read(false) }
            readHelpers.execute(task)
            try {
                return task.get(remaining.coerceAtLeast(1), java.util.concurrent.TimeUnit.NANOSECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                abandoned = task
                throw timeout()
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            } catch (e: InterruptedException) {
                abandoned = task
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("Interrupted while loading RDF from a URL").apply { initCause(e) }
            }
        } catch (e: java.io.IOException) {
            if (e is RdfInputTooLargeException || e is RdfLoadTimeoutException || e is java.io.InterruptedIOException ||
                remainingNanos() >= 0) throw e
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
        val pending = abandoned
        if (pending != null && !pending.isDone) {
            readHelpers.execute { runCatching { `in`.close() } }
        } else {
            super.close()
        }
    }

    override fun markSupported(): Boolean = false

    private companion object {
        /** Threads for reads that might outlast the deadline; each load uses at most one at a time. */
        val readHelpers: java.util.concurrent.ExecutorService by lazy {
            java.util.concurrent.Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "kastor-url-read").apply { isDaemon = true }
            }
        }
    }
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
