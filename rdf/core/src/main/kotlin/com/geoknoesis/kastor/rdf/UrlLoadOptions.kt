package com.geoknoesis.kastor.rdf

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
