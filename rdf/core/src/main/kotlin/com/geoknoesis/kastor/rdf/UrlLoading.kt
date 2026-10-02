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
 *   when the load starts; a server that trickles bytes just faster than [readTimeoutMillis] is
 *   stopped with [RdfLoadTimeoutException]. `0` disables the overall deadline. The deadline is hard and covers the
 *   whole load, including every redirect hop and every call of [addressPolicy] and [redirectPolicy]: connect and read
 *   timeouts are lowered to the time remaining when each hop starts, each HTTP request (host name lookup, connect,
 *   TLS handshake and response headers) and each policy call runs on a helper thread that the caller waits for only
 *   until the deadline, and a body read that could block past the deadline is abandoned when it passes. An abandoned
 *   request or read keeps its helper thread until its capped socket timeout (or the system's host name lookup)
 *   returns, and then releases its connection. The same holds for every scheme that was opted into
 *   ([allowedSchemes]): connecting, opening and reading the body of an `ftp:` or `file:` URL are bounded by the
 *   deadline like an HTTP request. Helper threads are daemon threads named `kastor-url-helper-N` in a pool shared by
 *   all loads of the JVM. The pool is bounded: by default to 32 threads or four per available processor, whichever
 *   is more; the system property `kastor.url.helperThreads` sets another bound. When all are busy, a load waits for
 *   one, for as long as its deadline allows, and then fails with [RdfLoadTimeoutException]; nothing that could
 *   outlast the deadline runs on the calling thread. Work that was abandoned at the deadline is interrupted (a policy
 *   that waits interruptibly returns at once; socket operations of the JDK do not react and end with their capped
 *   timeout). A helper whose abandoned work has still not returned after a grace period (60 seconds; system property
 *   `kastor.url.helperAbandonGraceMillis`) stops counting against the bound, so a policy that hangs for good costs
 *   its own thread but can never stop URL loading for the whole JVM. A body read that cannot block past the
 *   deadline, or whose data has already arrived, runs on the calling thread; the others of one response share one
 *   helper, which goes back to the pool when the body is closed or has not been read for a while. A read that
 *   reaches the end of the body is never reported as a timeout, and a load whose thread is interrupted fails with
 *   [java.io.InterruptedIOException], not with a timeout. Default: 5 minutes.
 *
 * HTTP(S) requests send an `Accept` header for the requested format and `Connection: close`, and the connection is
 * closed when the load ends. Redirects (301, 302, 303, 307, 308) are followed by Kastor, up to 10 hops, only to a URL
 * with an allowed scheme that keeps the scheme or upgrades `http` to `https`, only while
 * [java.net.HttpURLConnection.getFollowRedirects] is true, and only if [redirectPolicy] allows the target. A relative
 * `Location` is resolved against the redirecting URL; characters a URL may not contain unencoded (spaces, `|`,
 * non-ASCII) are percent-encoded in its path, query and fragment, as lenient HTTP clients do. Non-2xx responses,
 * including redirects that are not followed (the `Location` is missing or not a usable URL, the scheme or the
 * redirect policy refuses it, or the hop limit is reached), fail with [RdfHttpStatusException] before anything is
 * parsed.
 *
 * **URLs from untrusted input.** A URL, or a redirect from it, may name a host the caller must not reach from this
 * process: loopback, a cloud metadata service, an address of the internal network. Pass an [addressPolicy] - for
 * example [UrlAddressPolicy.PUBLIC_ADDRESSES] - to have every URL of the load checked before a request is sent to
 * it: the URL the load starts with and the target of every redirect. Read the limits of that policy on
 * [UrlAddressPolicy.PUBLIC_ADDRESSES] before relying on it: it narrows what a URL can reach, it is not a guarantee.
 *
 * @property redirectPolicy Decides, for each redirect that passed the scheme rules, whether it is followed.
 *   Default: [UrlRedirectPolicy.ALLOW_ALL]. It is not consulted for the URL the load starts with; a rule about where
 *   requests may go belongs in [addressPolicy].
 * @property addressPolicy Decides, for the URL the load starts with and for the target of every redirect that
 *   [redirectPolicy] allowed, whether a request may be sent to it. A refused URL fails the load with
 *   [RdfAddressRefusedException] before anything is sent to it. Default: [UrlAddressPolicy.ALLOW_ALL].
 */
data class UrlLoadOptions(
    val allowedSchemes: Set<String> = setOf("http", "https"),
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val connectTimeoutMillis: Int = 30_000,
    val readTimeoutMillis: Int = 30_000,
    val totalTimeoutMillis: Long = DEFAULT_TOTAL_TIMEOUT_MILLIS,
    val redirectPolicy: UrlRedirectPolicy = UrlRedirectPolicy.ALLOW_ALL,
    val addressPolicy: UrlAddressPolicy = UrlAddressPolicy.ALLOW_ALL,
) {
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
 * Decides whether URL loading may send a request to a URL ([UrlLoadOptions.addressPolicy]). It is asked once for
 * the URL a load starts with and once for the target of every redirect, each time before anything is sent there,
 * with an absolute URL whose scheme [UrlLoadOptions.allowedSchemes] allows. A refused URL fails the load with
 * [RdfAddressRefusedException]; an exception the policy throws fails the load with that exception.
 *
 * **Threading.** When the load has an overall deadline ([UrlLoadOptions.totalTimeoutMillis], on by default) the
 * policy runs on a `kastor-url-helper-N` daemon thread, not on the thread that called `Rdf.parseFromUrl`, so that
 * a slow host name lookup cannot outlast the deadline: thread-locals of the caller (a security context, an MDC, a
 * transaction) are not visible to it, and it must be safe to call from several threads. The call counts against
 * the deadline; when it is cut off there, its helper thread is interrupted, and the policy keeps running on that
 * thread until it returns. A policy should therefore wait interruptibly; one that never returns keeps its thread.
 */
fun interface UrlAddressPolicy {
    /** True if a request may be sent to [url]. */
    fun allows(url: java.net.URI): Boolean

    companion object {
        /** Allows every URL (the default). */
        @JvmField
        val ALLOW_ALL: UrlAddressPolicy = Named("ALLOW_ALL") { true }

        /**
         * Allows a URL only if its host is, or its host name resolves only to, public unicast addresses
         * ([isPublicUnicast]). A URL without a host (`file:`, `jar:`) and a host name that cannot be resolved are
         * refused.
         *
         * This closes the common ways a URL from untrusted input reaches loopback, link-local (cloud metadata)
         * and private addresses, including literal forms such as `http://2130706433/` and names that resolve to
         * such addresses. It does **not** make URL loading safe against server-side request forgery on its own:
         *
         * - **The name is resolved twice.** The policy looks the host name up, and the JDK's HTTP client looks it
         *   up again when it connects; `java.net.HttpURLConnection` offers no way to connect to the address that
         *   was checked while keeping the host name for the `Host` header and the TLS handshake. The JVM caches a
         *   successful lookup (`networkaddress.cache.ttl`, 30 seconds unless configured otherwise), so the second
         *   lookup normally returns the checked addresses; with caching disabled, or across the cache expiring,
         *   a DNS server under an attacker's control can answer the two lookups differently (DNS rebinding).
         * - **A proxy resolves the name itself.** When the request goes through an HTTP proxy (`http.proxyHost`,
         *   a [java.net.ProxySelector]), this process never connects to the addresses it checked: the proxy looks
         *   the name up from where it stands, and the policy says nothing about what the proxy can reach.
         * - **Public is not the same as allowed.** Addresses that are public to the Internet may still be internal
         *   to an organisation, and a service bound to a public address of the same machine is reachable.
         *
         * Where requests to internal services must be impossible, enforce that in the network (an egress proxy or
         * firewall rules for the process), and use this policy as a second line.
         */
        @JvmField
        val PUBLIC_ADDRESSES: UrlAddressPolicy = Named("PUBLIC_ADDRESSES", publicAddresses(java.net.InetAddress::getAllByName))

        /**
         * The policy behind [PUBLIC_ADDRESSES] with the host name lookup [resolve] (which throws
         * [java.net.UnknownHostException] for a name it cannot resolve), so that tests need no resolver.
         */
        internal fun publicAddresses(resolve: (String) -> Array<java.net.InetAddress>): UrlAddressPolicy =
            UrlAddressPolicy { url -> resolvesToPublicAddresses(url, resolve) }

        /**
         * True if [address] is a public unicast address: one that is globally routable and is not reserved for a
         * special purpose. False for
         *
         * - IPv4: `0.0.0.0/8` (this network), `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16` (private),
         *   `100.64.0.0/10` (shared address space, carrier-grade NAT), `127.0.0.0/8` (loopback), `169.254.0.0/16`
         *   (link-local), `192.0.0.0/24` (protocol assignments), `192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24`
         *   (documentation), `192.88.99.0/24` (6to4 relays), `198.18.0.0/15` (benchmarking), `224.0.0.0/4`
         *   (multicast) and `240.0.0.0/4` (reserved, with the broadcast address);
         * - IPv6: everything outside global unicast `2000::/3` (so the unspecified and loopback addresses,
         *   IPv4-compatible `::a.b.c.d`, the discard prefix `100::/64`, local-use NAT64 `64:ff9b:1::/48`,
         *   unique-local `fc00::/7`, link-local `fe80::/10`, site-local `fec0::/10`, multicast `ff00::/8`), and
         *   inside it `2001::/23` (protocol assignments, with Teredo `2001::/32`) and the documentation prefixes
         *   `2001:db8::/32` and `3fff::/20`;
         * - an IPv6 address that carries an IPv4 address - IPv4-mapped `::ffff:a.b.c.d`, NAT64 `64:ff9b::/96` and
         *   6to4 `2002::/16` - when that IPv4 address is not public.
         */
        fun isPublicUnicast(address: java.net.InetAddress): Boolean = isPublicUnicastAddress(address.address)

        private fun resolvesToPublicAddresses(url: java.net.URI, resolve: (String) -> Array<java.net.InetAddress>): Boolean {
            val host = url.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() } ?: return false
            // The connection is opened through java.net.URL: it must name the host that is checked here.
            val connectedHost = runCatching { url.toURL().host }.getOrNull()
            if (connectedHost != null && !connectedHost.equals(url.host, ignoreCase = true)) return false
            val addresses = try {
                resolve(host)
            } catch (_: java.net.UnknownHostException) {
                return false
            }
            return addresses.isNotEmpty() && addresses.all(::isPublicUnicast)
        }
    }

    private class Named(private val name: String, private val policy: UrlAddressPolicy) : UrlAddressPolicy by policy {
        override fun toString(): String = "UrlAddressPolicy.$name"
    }
}

/** True if the 4 bytes of an IPv4 address or the 16 bytes of an IPv6 address are a public unicast address. */
internal fun isPublicUnicastAddress(bytes: ByteArray): Boolean {
    fun at(index: Int) = bytes[index].toInt() and 0xFF
    fun ipv4(offset: Int): Boolean {
        val a = at(offset)
        val b = at(offset + 1)
        val c = at(offset + 2)
        return when {
            a == 0 || a == 10 || a == 127 -> false // this network, private, loopback
            a == 100 && b in 64..127 -> false // 100.64.0.0/10: shared address space (carrier-grade NAT)
            a == 169 && b == 254 -> false // link-local, with the cloud metadata address
            a == 172 && b in 16..31 -> false // private
            a == 192 && b == 0 && (c == 0 || c == 2) -> false // protocol assignments, documentation
            a == 192 && b == 88 && c == 99 -> false // 6to4 relay anycast
            a == 192 && b == 168 -> false // private
            a == 198 && (b == 18 || b == 19) -> false // benchmarking
            a == 198 && b == 51 && c == 100 -> false // documentation
            a == 203 && b == 0 && c == 113 -> false // documentation
            a >= 224 -> false // multicast, reserved, broadcast
            else -> true
        }
    }
    if (bytes.size == 4) return ipv4(0)
    if (bytes.size != 16) return false
    fun zero(from: Int, until: Int) = (from until until).all { bytes[it].toInt() == 0 }
    // IPv4-mapped ::ffff:a.b.c.d and NAT64 64:ff9b::a.b.c.d reach the IPv4 address they carry.
    if (zero(0, 10) && at(10) == 0xFF && at(11) == 0xFF) return ipv4(12)
    if (at(0) == 0x00 && at(1) == 0x64 && at(2) == 0xFF && at(3) == 0x9B && zero(4, 12)) return ipv4(12)
    // Global unicast is 2000::/3; everything else is unspecified, loopback, local, multicast or reserved.
    if ((at(0) and 0xE0) != 0x20) return false
    if (at(0) == 0x20 && at(1) == 0x01) {
        if (at(2) <= 0x01) return false // 2001::/23: protocol assignments, with Teredo 2001::/32
        if (at(2) == 0x0D && at(3) == 0xB8) return false // 2001:db8::/32: documentation
    }
    if (at(0) == 0x20 && at(1) == 0x02) return ipv4(2) // 6to4: a tunnel to the IPv4 address it carries
    if (at(0) == 0x3F && at(1) == 0xFF && (at(2) and 0xF0) == 0) return false // 3fff::/20: documentation
    return true
}

/**
 * Decides whether URL loading ([UrlLoadOptions.redirectPolicy]) follows an HTTP redirect. It is asked once per
 * redirect, after the scheme rules of [UrlLoadOptions] accepted the target, with absolute `http`/`https` URLs; a
 * refused redirect fails the load with [RdfHttpStatusException] carrying the redirect status. The call counts
 * against [UrlLoadOptions.totalTimeoutMillis] and, like [UrlAddressPolicy], runs on a `kastor-url-helper-N` thread
 * when the load has an overall deadline (no thread-locals of the caller); an exception it throws fails the load with
 * that exception.
 *
 * A redirect policy is about the relation between the two URLs of a redirect (same host, same site). It is not
 * asked about the URL a load starts with; to restrict where requests may go at all, use
 * [UrlLoadOptions.addressPolicy].
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
         * Follows a redirect only to a target that [UrlAddressPolicy.PUBLIC_ADDRESSES] allows, and has the limits
         * described there (the name is resolved again for the connection; a proxy resolves it itself). As a
         * redirect policy it never sees the URL the load starts with: prefer
         * `UrlLoadOptions(addressPolicy = UrlAddressPolicy.PUBLIC_ADDRESSES)`, which checks that URL as well.
         */
        @JvmField
        val PUBLIC_ADDRESSES: UrlRedirectPolicy =
            Named("PUBLIC_ADDRESSES") { _, to -> UrlAddressPolicy.PUBLIC_ADDRESSES.allows(to) }

        private fun effectivePort(uri: java.net.URI): Int = when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
    }

    private class Named(private val name: String, private val policy: UrlRedirectPolicy) : UrlRedirectPolicy by policy {
        override fun toString(): String = "UrlRedirectPolicy.$name"
    }
}

/**
 * Thrown when [UrlLoadOptions.addressPolicy] refuses a URL of a load: the URL it starts with, or the target of a
 * redirect. Nothing was sent to that URL.
 *
 * @property url The refused URL
 */
class RdfAddressRefusedException(val url: String, message: String) : java.io.IOException(message)

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

/** The helper pool shared by all URL loads of the JVM, and how it is configured. */
internal object UrlLoadHelpers {
    const val THREAD_NAME_PREFIX = "kastor-url-helper-"

    /** System property: the largest number of helper threads (a positive integer). */
    const val MAX_THREADS_PROPERTY = "kastor.url.helperThreads"

    /** System property: milliseconds after which a helper whose abandoned work has not returned is replaced. */
    const val ABANDON_GRACE_PROPERTY = "kastor.url.helperAbandonGraceMillis"

    /** The default bound is never lower than this. */
    const val MIN_DEFAULT_THREADS = 32

    /** Helper threads per available processor of the default bound: the threads wait for the network. */
    const val DEFAULT_THREADS_PER_PROCESSOR = 4

    const val DEFAULT_ABANDON_GRACE_MILLIS = 60_000L

    /** The bound for the value [configured] of [MAX_THREADS_PROPERTY] (null or unusable: the default). */
    fun maxThreads(configured: String?, processors: Int): Int =
        configured?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            ?: maxOf(MIN_DEFAULT_THREADS, DEFAULT_THREADS_PER_PROCESSOR * processors)

    /** The grace for the value [configured] of [ABANDON_GRACE_PROPERTY] (null or unusable: the default). */
    fun abandonGraceMillis(configured: String?): Long =
        configured?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: DEFAULT_ABANDON_GRACE_MILLIS

    /** The pool of every load that does not bring its own [UrlLoadRuntime]. */
    val shared: UrlLoadHelperPool by lazy {
        UrlLoadHelperPool(
            maxThreads(System.getProperty(MAX_THREADS_PROPERTY), Runtime.getRuntime().availableProcessors()),
            abandonGraceMillis(System.getProperty(ABANDON_GRACE_PROPERTY)),
        )
    }
}

/**
 * Bounded pool of daemon threads for URL-loading work that may block past the overall deadline. A load uses at most
 * one helper at a time, and the pool never has more than [maxThreads] threads - except for the ones it gave up on:
 *
 * A helper whose work was abandoned ([Handle.abandon]) is interrupted and stays busy until that work returns, which
 * the capped socket timeouts bound for requests and reads. Work that does not return - a host name lookup of the
 * system resolver, a custom policy that hangs - would hold its helper forever: once [abandonGraceMillis] have passed
 * since it was abandoned, its helper stops counting as busy and the pool may start one thread more in its place. The
 * hung thread itself cannot be stopped; when its work does return, the pool goes back to its bound.
 *
 * [nanoTime] is the clock of the grace period (the waits of [execute] use the system clock).
 */
internal class UrlLoadHelperPool(
    val maxThreads: Int,
    abandonGraceMillis: Long = UrlLoadHelpers.DEFAULT_ABANDON_GRACE_MILLIS,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    init {
        require(maxThreads > 0) { "maxThreads must be positive, got $maxThreads" }
        require(abandonGraceMillis >= 0) { "abandonGraceMillis must not be negative, got $abandonGraceMillis" }
    }

    private val graceNanos = TimeUnit.MILLISECONDS.toNanos(abandonGraceMillis)
    private val threadCount = java.util.concurrent.atomic.AtomicInteger()

    /** No queue: a task is handed to a thread that is free, or to a new one while there is room, or not at all. */
    private val threads = java.util.concurrent.ThreadPoolExecutor(
        0, maxThreads, 10, TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
        { runnable ->
            Thread(runnable, UrlLoadHelpers.THREAD_NAME_PREFIX + threadCount.incrementAndGet()).apply { isDaemon = true }
        },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
    )

    /** Abandoned work that has not returned yet. */
    private val abandoned = java.util.concurrent.ConcurrentLinkedQueue<Handle>()

    /** Tasks that were handed to a thread and count as busy: not finished, and not given up on. */
    private val busyCount = java.util.concurrent.atomic.AtomicInteger()
    private val idleLock = Object()

    /** Helpers given up on whose work is still running; guarded by [resizeLock]. */
    private var hung = 0
    private val resizeLock = Any()

    /** Number of tasks handed to a helper thread so far. */
    val tasksStarted = java.util.concurrent.atomic.AtomicLong()

    /** Number of helpers that stopped counting as busy because their abandoned work outlived the grace period. */
    val helpersReplaced = java.util.concurrent.atomic.AtomicLong()

    /** Helpers that count as busy right now. */
    val busy: Int get() = busyCount.get()

    /** The largest number of threads this pool has had at one time. */
    val largestThreadCount: Int get() = threads.largestPoolSize

    /** The number of threads the pool may have right now: [maxThreads], and one for every helper it gave up on. */
    val threadLimit: Int get() = threads.maximumPoolSize

    /** A task running (or about to run) on a helper thread. */
    inner class Handle internal constructor(private val task: Runnable) : Runnable {
        // Guarded by this.
        private var thread: Thread? = null
        private var done = false
        private var interrupt = false
        private var replaced = false
        private val finished = java.util.concurrent.CountDownLatch(1)

        /** When the task was abandoned, by the clock of the pool. */
        @Volatile internal var abandonedAt = 0L

        internal val isDone: Boolean get() = synchronized(this) { done }

        override fun run() {
            synchronized(this) {
                thread = Thread.currentThread()
                if (interrupt) Thread.currentThread().interrupt()
            }
            try {
                task.run()
            } finally {
                val wasAbandoned: Boolean
                val wasReplaced: Boolean
                synchronized(this) {
                    done = true
                    thread = null
                    // An interrupt meant for this task must not reach the next task of the thread.
                    Thread.interrupted()
                    wasAbandoned = interrupt
                    wasReplaced = replaced
                }
                if (wasAbandoned) abandoned.remove(this)
                if (wasReplaced) resize(-1) else uncount()
                finished.countDown()
            }
        }

        /** Gives up on the task if it is still running: it stops counting as busy; true if this call did that. */
        internal fun replace(): Boolean {
            synchronized(this) {
                if (done || replaced) return false
                replaced = true
            }
            resize(+1)
            uncount()
            return true
        }

        /**
         * The caller no longer waits for the task: interrupts it if it has not returned (once), and lets the pool
         * replace its helper if it has still not returned when the grace period is over.
         */
        fun abandon() {
            synchronized(this) {
                if (done || interrupt) return
                interrupt = true
                abandonedAt = nanoTime()
                abandoned.add(this)
                thread?.interrupt()
            }
        }

        /** Waits up to [millis] until the task has returned and the pool has taken note; true if it has. */
        fun awaitDone(millis: Long): Boolean = finished.await(millis, TimeUnit.MILLISECONDS)
    }

    /** One thread more ([delta] = 1) while a helper that was given up on still runs, one less when it has returned. */
    private fun resize(delta: Int) {
        synchronized(resizeLock) {
            hung += delta
            threads.maximumPoolSize = maxThreads + hung
        }
    }

    private fun uncount() {
        if (busyCount.decrementAndGet() <= 0) synchronized(idleLock) { idleLock.notifyAll() }
    }

    /** Gives up on the helpers whose abandoned work has outlived the grace period; true if there was one. */
    private fun reclaim(): Boolean {
        if (abandoned.isEmpty()) return false
        val now = nanoTime()
        var freed = false
        val each = abandoned.iterator()
        while (each.hasNext()) {
            val handle = each.next()
            if (handle.isDone) {
                each.remove()
            } else if (now - handle.abandonedAt >= graceNanos) {
                each.remove()
                if (handle.replace()) {
                    helpersReplaced.incrementAndGet()
                    freed = true
                }
            }
        }
        return freed
    }

    /** Hands [handle] to a thread that is free, or to a new one if there is room; false if there is neither. */
    private fun start(handle: Handle): Boolean {
        busyCount.incrementAndGet()
        try {
            threads.execute(handle)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            uncount()
            return false
        }
        tasksStarted.incrementAndGet()
        return true
    }

    /** Starts [task] on a helper thread; null (without running it) if all [maxThreads] helpers are busy. */
    fun tryExecute(task: Runnable): Handle? {
        val handle = Handle(task)
        return if (start(handle) || (reclaim() && start(handle))) handle else null
    }

    /**
     * Starts [task] on a helper thread, waiting up to [waitNanos] for one to become free; null (without running it)
     * if none did.
     *
     * @throws InterruptedException if the calling thread is interrupted while it waits (the task then never runs)
     */
    fun execute(task: Runnable, waitNanos: Long): Handle? {
        val handle = Handle(task)
        val deadline = System.nanoTime() + waitNanos
        while (!threads.isShutdown) {
            if (start(handle) || (reclaim() && start(handle))) return handle
            val left = deadline - System.nanoTime()
            if (left <= 0) return null
            // Every thread is busy: hand the task to the first one that asks for work. The wait is sliced because
            // a pool whose threads all retired meanwhile has nobody asking, but room for a new thread - and so has
            // one whose hung helper outlives its grace period.
            busyCount.incrementAndGet()
            val taken = try {
                threads.queue.offer(handle, minOf(left, HAND_OFF_SLICE_NANOS), TimeUnit.NANOSECONDS)
            } catch (e: InterruptedException) {
                uncount()
                throw e
            }
            if (taken) {
                tasksStarted.incrementAndGet()
                return handle
            }
            uncount()
        }
        return null
    }

    /** Waits up to [millis] until no helper counts as busy; true if that happened. */
    fun awaitIdle(millis: Long): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        synchronized(idleLock) {
            while (busyCount.get() > 0) {
                val left = end - System.nanoTime()
                if (left <= 0) return false
                TimeUnit.NANOSECONDS.timedWait(idleLock, left)
            }
            return true
        }
    }

    /** Interrupts every helper and waits a moment for the threads to end; no task is accepted afterwards. */
    override fun close() {
        threads.shutdownNow()
        runCatching { threads.awaitTermination(10, TimeUnit.SECONDS) }
    }

    private companion object {
        /** Longest single wait for a thread to take a task before the pool is asked again. */
        val HAND_OFF_SLICE_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(250)
    }
}

/**
 * One blocking call run on a thread of [pool]. If the caller stops waiting, [abandon] interrupts the call and hands
 * clean-up to the helper, which runs it once the call returns: the JDK's HTTP connections and streams cannot be
 * closed from another thread while such a call blocks (closing waits for it), so this releases them without
 * occupying a second thread.
 */
internal class HelperCall<T>(private val pool: UrlLoadHelperPool, action: () -> T) {
    private val lock = Any()
    private var started = false
    private var returned = false
    private var cleanup: (() -> Unit)? = null
    private var handle: UrlLoadHelperPool.Handle? = null
    private val task = java.util.concurrent.FutureTask {
        try {
            action()
        } finally {
            val pending = synchronized(lock) { returned = true; cleanup }
            pending?.let { runCatching(it) }
        }
    }

    /**
     * Starts the call, waiting up to [waitNanos] for a free helper thread; false if none became free (the call then
     * never runs).
     */
    fun start(waitNanos: Long = 0): Boolean {
        val accepted = (if (waitNanos <= 0) pool.tryExecute(task) else pool.execute(task, waitNanos)) ?: return false
        synchronized(lock) { started = true; handle = accepted }
        return true
    }

    /** Waits up to [nanos] for the result, rethrowing the call's own failure. */
    fun await(nanos: Long): T = try {
        task.get(nanos.coerceAtLeast(1), TimeUnit.NANOSECONDS)
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    }

    /**
     * Runs [release] once the call has returned: now if it already has (or never started); otherwise the call is
     * interrupted and [release] runs on the helper thread when it returns.
     */
    fun abandon(release: () -> Unit) {
        var running: UrlLoadHelperPool.Handle? = null
        val now = synchronized(lock) {
            val pending = started && !returned
            if (pending) {
                cleanup = release
                running = handle
            }
            !pending
        }
        if (now) runCatching(release) else running?.abandon()
    }
}

/**
 * The long-lived helper of one [DeadlineInputStream]: a loop on a [UrlLoadHelperPool] thread that performs the reads
 * of [input] the stream asks for, one at a time, so that the stream can stop waiting for a read at its deadline. One
 * helper serves all such reads of a stream; there is no task, future or thread hand-over per read.
 *
 * The helper reads into its own buffer and the bytes are copied to the caller once the read has returned, so a read
 * the caller gave up on never writes into the caller's array. It leaves its thread when the stream is closed, when a
 * read was given up (after that read returns, running the clean-up handed to [finish]), or when it has not been
 * asked for [idleNanos]; the stream then starts another helper for its next read.
 */
internal class StreamHelper(private val input: InputStream, private val idleNanos: Long) : Runnable {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val asked = lock.newCondition()
    private val answered = lock.newCondition()

    // All guarded by lock.
    private var buffer = ByteArray(0)
    private var request = NONE
    private var argument = 0L
    private var reading = false
    private var answer = 0L
    private var failure: Throwable? = null
    private var done = false
    private var finished = false
    private var gone = false
    private var cleanup: (() -> Unit)? = null

    /** The pool's handle of the thread this helper runs on, set once it was started. */
    @Volatile var handle: UrlLoadHelperPool.Handle? = null

    override fun run() {
        while (true) {
            val operation: Int
            val amount: Long
            lock.lock()
            try {
                var idle = idleNanos
                while (request == NONE && !finished && idle > 0) {
                    idle = try {
                        asked.awaitNanos(idle)
                    } catch (_: InterruptedException) {
                        0
                    }
                }
                if (request == NONE || finished) {
                    gone = true
                    return
                }
                operation = request
                amount = argument
                reading = true
                if (operation == READ && buffer.size < amount) buffer = ByteArray(amount.toInt())
            } finally {
                lock.unlock()
            }
            var result = 0L
            var error: Throwable? = null
            try {
                result = if (operation == READ) input.read(buffer, 0, amount.toInt()).toLong() else input.skip(amount)
            } catch (e: Throwable) {
                error = e
            }
            val pending: (() -> Unit)?
            lock.lock()
            try {
                reading = false
                request = NONE
                answer = result
                failure = error
                done = true
                answered.signalAll()
                pending = cleanup
                if (pending != null) gone = true
            } finally {
                lock.unlock()
            }
            if (pending != null) {
                runCatching(pending)
                return
            }
        }
    }

    /**
     * Asks the helper to read up to [length] bytes, waits up to [nanos] for it and copies the bytes read to
     * [target] at [offset]. Returns the number of bytes read, -1 at the end of the stream, or [GONE] if the helper
     * has left its thread (nothing was read; ask a new helper). Rethrows the failure of the read.
     *
     * @throws java.util.concurrent.TimeoutException if the read did not return in time: it is still running, no
     *   other read may be asked for, and [finish] must be called
     * @throws InterruptedException if the calling thread was interrupted while waiting, with the same consequences
     */
    fun read(target: ByteArray, offset: Int, length: Int, nanos: Long): Int {
        lock.lock()
        try {
            val read = perform(READ, length.toLong(), nanos)
            if (read > 0) System.arraycopy(buffer, 0, target, offset, read.toInt())
            return read.toInt()
        } finally {
            lock.unlock()
        }
    }

    /** Like [read], for skipping up to [count] bytes; returns the number of bytes skipped or [GONE]. */
    fun skip(count: Long, nanos: Long): Long {
        lock.lock()
        try {
            return perform(SKIP, count, nanos)
        } finally {
            lock.unlock()
        }
    }

    /** Called with the lock held. */
    private fun perform(operation: Int, amount: Long, nanos: Long): Long {
        if (gone || finished) return GONE.toLong()
        request = operation
        argument = amount
        done = false
        asked.signal()
        var left = nanos
        while (!done) {
            if (left <= 0) throw java.util.concurrent.TimeoutException()
            left = answered.awaitNanos(left)
        }
        failure?.let { throw it }
        return answer
    }

    /**
     * Ends the helper. If a read is in flight, it is interrupted, [release] runs on the helper thread once that read
     * returns and the result is false; otherwise the helper leaves its thread, nothing else happens and the result
     * is true (the caller releases the stream itself).
     */
    fun finish(release: () -> Unit): Boolean {
        lock.lock()
        try {
            finished = true
            if (reading) {
                cleanup = release
                handle?.abandon()
                return false
            }
            // A request the helper has not picked up yet is withdrawn with it.
            request = NONE
            asked.signal()
            return true
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /** Result of [read] and [skip] when the helper has left its thread. */
        const val GONE = Int.MIN_VALUE
        private const val NONE = 0
        private const val READ = 1
        private const val SKIP = 2
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

/**
 * Input stream that throws [RdfLoadTimeoutException] once [timeoutMillis] have passed since [startedNanos].
 *
 * - Reaching the end of the stream is never a timeout, even if the final read returns after the deadline.
 * - [blockingReadMillis] is the longest one read of the underlying stream can block (`0` = unbounded), or null if
 *   reads do not block. A read runs on the calling thread when more time than that remains, or when the underlying
 *   stream reports data available (such a read does not block). Any other read is done by the stream's helper
 *   ([StreamHelper]) while the caller waits only until the deadline, so no read can overrun it. A read given up
 *   this way ends the stream: later reads fail with the same timeout, and the underlying stream is closed by the
 *   helper thread once the read returns, because the JDK's HTTP streams only close once a blocked read has
 *   returned. When every helper thread is busy the read waits for one until the deadline.
 * - A read given up because the reading thread was interrupted ends the stream as well, and is reported as what it
 *   is: that read and every later one fail with [java.io.InterruptedIOException], never with a timeout.
 * - An I/O failure that happens after the deadline is reported as a timeout (an interrupt is not).
 * - [helpers] is the pool the stream's helper runs in, and [nanoTime] the clock of the deadline.
 * - [onClose] runs when the stream is closed, before the underlying stream is closed (so an HTTP connection can still
 *   be disconnected: closing its stream first forgets the connection), and after a read in flight returns.
 * - [helperIdleMillis] is how long the helper waits for the next read before it gives its thread back.
 */
internal class DeadlineInputStream(
    input: InputStream,
    private val startedNanos: Long,
    private val timeoutMillis: Long,
    blockingReadMillis: Long? = null,
    private val helperIdleMillis: Long = HELPER_IDLE_MILLIS,
    private val helpers: UrlLoadHelperPool = UrlLoadHelpers.shared,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onClose: () -> Unit = {},
) : FilterInputStream(input), LimitedStream {
    private val limitNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    private val blockingReadNanos: Long? = blockingReadMillis?.let {
        if (it <= 0) Long.MAX_VALUE else TimeUnit.MILLISECONDS.toNanos(it)
    }
    private var ended = false

    /**
     * Set when a read was given up - at the deadline, or because the reading thread was interrupted: that read may
     * still be running, so no other read may start. Says which of the two it was.
     */
    @Volatile private var abandonment: Abandonment? = null
    @Volatile private var helper: StreamHelper? = null
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private var single: ByteArray? = null

    override var failure: java.io.IOException? = null
        private set

    private fun remainingNanos() = limitNanos - (nanoTime() - startedNanos)

    private fun interrupted(cause: Throwable?): java.io.InterruptedIOException =
        java.io.InterruptedIOException(
            if (cause == null) "Loading RDF from a URL was abandoned when the reading thread was interrupted"
            else "Interrupted while loading RDF from a URL",
        ).apply { if (cause != null) initCause(cause) }

    private fun timeout(): java.io.IOException = failure ?: RdfLoadTimeoutException(timeoutMillis).also { failure = it }

    fun checkDeadline() {
        if (remainingNanos() < 0) throw timeout()
    }

    /** True if a read may block without outlasting the deadline, so it can run on the calling thread. */
    private fun readsDirectly(remaining: Long): Boolean = blockingReadNanos == null || remaining > blockingReadNanos

    /** True if the underlying stream has data at hand, so a read of it returns without blocking. */
    private fun dataAvailable(): Boolean = try {
        `in`.available() > 0
    } catch (_: java.io.IOException) {
        false
    }

    /**
     * Runs one read of the underlying stream without letting it outlast the deadline: [direct] on the calling thread
     * if it cannot, otherwise [viaHelper], which is given the time left.
     */
    private inline fun timed(direct: () -> Long, viaHelper: (remaining: Long) -> Long): Long {
        when (abandonment) {
            Abandonment.DEADLINE -> throw timeout()
            Abandonment.INTERRUPT -> throw interrupted(null)
            null -> Unit
        }
        val remaining = remainingNanos()
        val mayOverrun = !readsDirectly(remaining)
        try {
            return if (!mayOverrun || dataAvailable()) direct() else viaHelper(remaining)
        } catch (e: java.io.IOException) {
            // A read that was allowed to block past the deadline has a socket timeout no shorter than the time that was
            // left, so its socket timeout is the deadline expiring (even if the clock says a moment is left).
            val socketTimeout = e is java.net.SocketTimeoutException
            val deadlinePassed = remainingNanos() < 0 || (socketTimeout && mayOverrun)
            if (e is RdfInputTooLargeException || e is RdfLoadTimeoutException || !deadlinePassed ||
                (e is java.io.InterruptedIOException && !socketTimeout)) throw e
            throw timeout().also { if (it !== e) it.addSuppressed(e) }
        }
    }

    /**
     * Has the helper do one read ([ask] returns [StreamHelper.GONE] when the helper it was given has left its
     * thread; another one is started then). Gives the stream up if the read does not return in time.
     */
    private inline fun helped(remaining: Long, ask: (StreamHelper) -> Long): Long {
        while (true) {
            if (closed.get()) throw java.io.IOException("Stream closed")
            try {
                val result = ask(helper ?: startHelper(remaining))
                if (result != StreamHelper.GONE.toLong()) return result
                helper = null
            } catch (_: java.util.concurrent.TimeoutException) {
                abandonment = Abandonment.DEADLINE
                throw timeout()
            } catch (e: InterruptedException) {
                abandonment = Abandonment.INTERRUPT
                Thread.currentThread().interrupt()
                throw interrupted(e)
            }
        }
    }

    /** Starts the helper of this stream, waiting for a free helper thread only as long as the deadline allows. */
    private fun startHelper(remaining: Long): StreamHelper {
        val created = StreamHelper(`in`, TimeUnit.MILLISECONDS.toNanos(helperIdleMillis))
        // Interrupted while waiting for a thread: no read is in flight, the stream stays usable.
        val accepted = try {
            helpers.execute(created, remaining)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted(e)
        }
        created.handle = accepted ?: throw timeout()
        helper = created
        // Closed meanwhile by another thread (a cancelled load): the helper must not outlive the stream.
        if (closed.get()) created.finish {}
        return created
    }

    override fun read(): Int {
        if (ended) return -1
        checkDeadline()
        val one = single ?: ByteArray(1).also { single = it }
        val n = timed(
            direct = { val b = `in`.read(); if (b < 0) -1L else { one[0] = b.toByte(); 1L } },
            viaHelper = { remaining -> helped(remaining) { it.read(one, 0, 1, remainingNanos()).toLong() } },
        )
        if (n < 0) { ended = true; return -1 }
        checkDeadline()
        return one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (ended) return -1
        if (len == 0) return 0
        checkDeadline()
        // A read on the calling thread fills the caller's array directly. A helper read fills the helper's buffer,
        // which is copied once the read has returned, so a read that was given up never writes into the caller's array.
        val n = timed(
            direct = { `in`.read(b, off, len).toLong() },
            viaHelper = { remaining -> helped(remaining) { it.read(b, off, len, remainingNanos()).toLong() } },
        )
        if (n < 0) { ended = true; return -1 }
        checkDeadline()
        return n.toInt()
    }

    override fun skip(n: Long): Long {
        checkDeadline()
        return timed(
            direct = { `in`.skip(n) },
            viaHelper = { remaining -> helped(remaining) { it.skip(n, remainingNanos()) } },
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val release = {
            runCatching { onClose() }
            runCatching { `in`.close() }
            Unit
        }
        // With a read in flight (given up at the deadline, or still running when another thread closes the stream)
        // the helper releases the stream when that read returns.
        if (helper?.finish(release) == false) return
        try {
            onClose()
        } finally {
            super.close()
        }
    }

    override fun markSupported(): Boolean = false

    /** Why a read was given up. */
    private enum class Abandonment { DEADLINE, INTERRUPT }

    companion object {
        /** How long a stream's helper waits for the next read before it gives its thread back to the pool. */
        const val HELPER_IDLE_MILLIS = 2_000L
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

/**
 * Counts bytes read, so a parse can tell whether a provider consumed input before declining it.
 *
 * [close] does **not** close the underlying stream: this is the stream the `parseFromInputStream`, `parseStreaming`
 * and `parseDataset` entry points of [Rdf] hand to a provider, and the stream they were given stays the caller's to
 * close, whatever the provider's parser does when it is done (Jena's parsers close their input).
 */
internal class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun close() = Unit

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
