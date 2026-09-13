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
 * @property readTimeoutMillis Read timeout passed to the URL connection
 */
data class UrlLoadOptions(
    val allowedSchemes: Set<String> = setOf("http", "https"),
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val connectTimeoutMillis: Int = 30_000,
    val readTimeoutMillis: Int = 30_000,
) {
    init {
        require(allowedSchemes.isNotEmpty()) { "allowedSchemes must not be empty" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(connectTimeoutMillis >= 0 && readTimeoutMillis >= 0) { "timeouts must not be negative" }
    }

    companion object {
        /** Default body size limit: 64 MiB. */
        const val DEFAULT_MAX_BYTES: Long = 64L * 1024 * 1024

        @JvmField
        val DEFAULT = UrlLoadOptions()
    }
}

/** Thrown when RDF input read from a URL exceeds [UrlLoadOptions.maxBytes]. */
class RdfInputTooLargeException(val limitBytes: Long) :
    java.io.IOException("RDF input exceeds the configured limit of $limitBytes bytes")

/** Validates [url] against [options] and opens (but does not read) a connection. */
internal fun openRdfUrl(url: String, options: UrlLoadOptions): java.net.URLConnection {
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
    return uri.toURL().openConnection().apply {
        connectTimeout = options.connectTimeoutMillis
        readTimeout = options.readTimeoutMillis
    }
}

/** Returns the connection's body, failing fast on a declared length above the limit and bounding the rest. */
internal fun boundedUrlStream(connection: java.net.URLConnection, options: UrlLoadOptions): InputStream {
    if (connection.contentLengthLong > options.maxBytes) {
        runCatching { connection.getInputStream().close() }
        (connection as? java.net.HttpURLConnection)?.disconnect()
        throw RdfInputTooLargeException(options.maxBytes)
    }
    return BoundedInputStream(connection.getInputStream(), options.maxBytes)
}

/** Input stream that throws [RdfInputTooLargeException] once more than [limit] bytes have been read. */
internal class BoundedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

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
        if (count > limit) throw RdfInputTooLargeException(limit)
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

/** Finds an input-limit failure wrapped by a provider's own parser exceptions. */
internal fun Throwable.inputLimitCause(): RdfInputTooLargeException? =
    generateSequence(this) { it.cause }.take(16).filterIsInstance<RdfInputTooLargeException>().firstOrNull()
