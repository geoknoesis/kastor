package com.geoknoesis.kastor.rdf

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Redirect handling of URL loading against a local HTTP server: Location resolution, hop cap, host policy, cancel. */
class UrlLoadingRedirectTest {
    private var server: HttpServer? = null
    private var pool: ExecutorService? = null

    /** Raw request targets (path and query, as sent) in arrival order. */
    private val requests = CopyOnWriteArrayList<String>()

    @AfterEach
    fun stop() {
        server?.stop(0)
        pool?.shutdownNow()
    }

    /** Starts a server and returns its root URL without a trailing slash, e.g. `http://127.0.0.1:1234`. */
    private fun serve(handler: (HttpExchange) -> Unit): String {
        val threads = Executors.newFixedThreadPool(8)
        val created = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                requests.add(exchange.requestURI.toString())
                try { handler(exchange) } catch (_: IOException) { } catch (_: InterruptedException) { } finally { exchange.close() }
            }
            executor = threads
            start()
        }
        server = created
        pool = threads
        return "http://127.0.0.1:${created.address.port}"
    }

    private fun HttpExchange.redirect(location: String, status: Int = 302) {
        responseHeaders.add("Location", location)
        sendResponseHeaders(status, -1)
    }

    private fun HttpExchange.turtle(body: String = "<#s> <urn:p> <> .") {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "text/turtle")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.write(bytes)
    }

    @Test
    fun `a relative Location is resolved against a URL without a path`() {
        val root = serve { exchange ->
            when (exchange.requestURI.toString()) {
                "/" -> exchange.redirect("data.ttl")
                "/?start" -> exchange.redirect("?page=2")
                else -> exchange.turtle()
            }
        }
        // No trailing slash: the request URL has an empty path.
        val graph = Rdf.parseFromUrl(root)
        assertEquals(listOf(RdfTriple(Iri("$root/data.ttl#s"), Iri("urn:p"), Iri("$root/data.ttl"))), graph.getTriples())
        assertEquals(listOf("/", "/data.ttl"), requests.toList())

        requests.clear()
        val query = Rdf.parseFromUrl("$root?start")
        assertEquals(listOf(RdfTriple(Iri("$root/?page=2#s"), Iri("urn:p"), Iri("$root/?page=2"))), query.getTriples())
        assertEquals(listOf("/?start", "/?page=2"), requests.toList())
    }

    @Test
    fun `unencoded spaces and bars in a Location are encoded, as the JDK's own redirect handling tolerates them`() {
        val root = serve { exchange ->
            when (exchange.requestURI.rawPath) {
                "/a" -> exchange.redirect("/my docs/a|b.ttl?q=x y#frag ment")
                "/b" -> exchange.redirect("sub dir/caf" + 0xE9.toChar() + ".ttl")
                "/c" -> exchange.redirect("/100%/done%20already.ttl")
                else -> exchange.turtle("<urn:s> <urn:p> <urn:o> .")
            }
        }
        assertEquals(1, Rdf.parseFromUrl("$root/a").size())
        assertEquals("/my%20docs/a%7Cb.ttl?q=x%20y", requests.last())
        assertEquals(1, Rdf.parseFromUrl("$root/b").size())
        assertEquals("/sub%20dir/caf%C3%A9.ttl", requests.last())
        assertEquals(1, Rdf.parseFromUrl("$root/c").size())
        assertEquals("/100%25/done%20already.ttl", requests.last())
    }

    @Test
    fun `a Location that is not a usable URL fails with the redirect status`() {
        val locations = mapOf(
            "/space-in-host" to "http://exa mple.org/x.ttl",
            "/bad-bracket" to "http://[::1/x.ttl",
            "/bad-port" to "http://127.0.0.1:99999999/x.ttl",
            "/port-text" to "http://127.0.0.1:http/x.ttl",
            "/no-host" to "http:///x.ttl",
            "/underscore-host" to "http://bad_host/x.ttl",
            "/opaque" to "http:x.ttl",
            "/empty-authority" to "//",
        )
        val root = serve { exchange -> exchange.redirect(locations.getValue(exchange.requestURI.path)) }
        for ((path, location) in locations) {
            val error = assertThrows(RdfHttpStatusException::class.java, { Rdf.parseFromUrl("$root$path") }, location)
            assertEquals(302, error.statusCode, location)
            assertTrue(error.message!!.contains("not followed"), error.message)
            assertEquals("$root$path", error.url)
        }
    }

    @Test
    fun `the eleventh redirect is refused with the hop limit in the message`() {
        val root = serve { exchange ->
            val path = exchange.requestURI.path
            val hop = path.substringAfterLast('/').toInt()
            when {
                path.startsWith("/ten/") && hop == 10 -> exchange.turtle("<urn:s> <urn:p> <urn:o> .")
                else -> exchange.redirect("${path.substringBeforeLast('/')}/${hop + 1}")
            }
        }
        // Ten redirects are followed.
        assertEquals(1, Rdf.parseFromUrl("$root/ten/0").size())
        assertEquals((0..10).map { "/ten/$it" }, requests.toList())

        requests.clear()
        val error = assertThrows(RdfHttpStatusException::class.java) { Rdf.parseFromUrl("$root/loop/0") }
        assertEquals(302, error.statusCode)
        assertTrue(error.message!!.contains("more than $MAX_URL_REDIRECTS redirects"), error.message)
        assertTrue(error.message!!.contains("$root/loop/10"), error.message)
        assertEquals("$root/loop/10", error.url)
        assertEquals((0..10).map { "/loop/$it" }, requests.toList())
    }

    @Test
    fun `cancelling an async load during a redirect stops the hop in flight`() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val root = serve { exchange ->
            when (exchange.requestURI.path) {
                "/start" -> exchange.redirect("/stall")
                "/stall" -> {
                    arrived.countDown()
                    release.await(20, TimeUnit.SECONDS)
                    exchange.redirect("/after")
                }
                else -> exchange.turtle("<urn:s> <urn:p> <urn:o> .")
            }
        }
        assertTimeoutPreemptively(Duration.ofSeconds(30)) {
            val future = Rdf.parseFromUrlAsync("$root/start", RdfFormat.TURTLE)
            assertTrue(arrived.await(10, TimeUnit.SECONDS), "the second hop must be requested")
            assertTrue(future.cancel(true))
            assertTrue(future.isCancelled)
            // The connection of the hop in flight was disconnected: its late answer is never followed.
            release.countDown()
            Thread.sleep(700)
            assertEquals(listOf("/start", "/stall"), requests.toList())
        }
    }

    @Test
    fun `a redirect to another host is followed by default and can be refused by a redirect policy`() {
        val root = serve { exchange ->
            when (exchange.requestURI.path) {
                "/start" -> exchange.redirect("http://localhost:${exchange.localAddress.port}/doc.ttl")
                else -> exchange.turtle()
            }
        }
        val other = root.replace("127.0.0.1", "localhost")

        // Default: cross-host redirects are followed, and the final URL is the base IRI.
        assertEquals(UrlRedirectPolicy.ALLOW_ALL, UrlLoadOptions.DEFAULT.redirectPolicy)
        assertEquals(
            listOf(RdfTriple(Iri("$other/doc.ttl#s"), Iri("urn:p"), Iri("$other/doc.ttl"))),
            Rdf.parseFromUrl("$root/start").getTriples(),
        )

        for (policy in listOf(UrlRedirectPolicy.SAME_HOST, UrlRedirectPolicy.PUBLIC_ADDRESSES)) {
            requests.clear()
            val error = assertThrows(RdfHttpStatusException::class.java) {
                Rdf.parseFromUrl("$root/start", RdfFormat.TURTLE, UrlLoadOptions(redirectPolicy = policy))
            }
            assertEquals(302, error.statusCode)
            assertTrue(error.message!!.contains("refused by the redirect policy"), error.message)
            assertTrue(error.message!!.contains("$other/doc.ttl"), error.message)
            assertEquals(listOf("/start"), requests.toList(), "the refused target must not be requested ($policy)")
        }
        assertEquals("UrlRedirectPolicy.PUBLIC_ADDRESSES", UrlRedirectPolicy.PUBLIC_ADDRESSES.toString())
    }

    @Test
    fun `a redirect policy sees the redirecting URL and the resolved target of every hop`() {
        val root = serve { exchange ->
            when (exchange.requestURI.path) {
                "/a" -> exchange.redirect("b/")
                "/b/" -> exchange.redirect("../c.ttl?x=1")
                else -> exchange.turtle()
            }
        }
        val seen = CopyOnWriteArrayList<Pair<String, String>>()
        val options = UrlLoadOptions(redirectPolicy = { from, to -> seen.add(from.toString() to to.toString()); true })
        assertEquals(1, Rdf.parseFromUrl("$root/a", RdfFormat.TURTLE, options).size())
        assertEquals(listOf("$root/a" to "$root/b/", "$root/b/" to "$root/c.ttl?x=1"), seen.toList())

        // Same-host redirects pass SAME_HOST; a different port is a different host.
        assertEquals(1, Rdf.parseFromUrl("$root/a", RdfFormat.TURTLE, UrlLoadOptions(redirectPolicy = UrlRedirectPolicy.SAME_HOST)).size())
        val same = UrlRedirectPolicy.SAME_HOST
        assertTrue(same.allows(java.net.URI("http://Example.org/a"), java.net.URI("http://example.ORG:80/b")))
        assertTrue(same.allows(java.net.URI("https://example.org/a"), java.net.URI("https://example.org:443/b")))
        assertFalse(same.allows(java.net.URI("http://example.org/a"), java.net.URI("http://example.org:8080/b")))
        assertFalse(same.allows(java.net.URI("http://example.org/a"), java.net.URI("http://www.example.org/b")))

        // A failing policy fails the load with its own exception.
        val failing = UrlLoadOptions(redirectPolicy = { _, _ -> throw IllegalStateException("policy failed") })
        val error = assertThrows(IllegalStateException::class.java) { Rdf.parseFromUrl("$root/a", RdfFormat.TURTLE, failing) }
        assertEquals("policy failed", error.message)
    }

    @Test
    fun `the public-address policy refuses loopback, private, link-local and unresolvable targets`() {
        val policy = UrlRedirectPolicy.PUBLIC_ADDRESSES
        val from = java.net.URI("http://example.org/")
        val refused = listOf(
            "http://127.0.0.1/x", "http://127.8.9.10:8080/x", "http://localhost/x", "http://[::1]/x", "http://0.0.0.0/x",
            "http://0.1.2.3/x", "http://10.1.2.3/x", "http://172.16.0.1/x", "http://172.31.255.255/x", "http://192.168.1.1/x",
            "http://169.254.169.254/latest/meta-data", "http://[fe80::1]/x", "http://[fc00::1]/x", "http://[fd12:3456::1]/x",
            "http://[::ffff:127.0.0.1]/x", "http://[::ffff:10.0.0.1]/x", "http://224.0.0.1/x", "http://[ff02::1]/x",
            "http://no-such-host.invalid/x",
        )
        for (target in refused) assertFalse(policy.allows(from, java.net.URI(target)), target)
        // Literal public addresses need no name lookup.
        for (target in listOf("http://93.184.216.34/x", "http://172.32.0.1/x", "http://[2606:2800:220:1::1]/x")) {
            assertTrue(policy.allows(from, java.net.URI(target)), target)
        }
    }

    @Test
    fun `a slow redirect policy is cut off by the overall deadline`() {
        val root = serve { exchange ->
            if (exchange.requestURI.path == "/start") exchange.redirect("/doc.ttl") else exchange.turtle()
        }
        val release = CountDownLatch(1)
        val options = UrlLoadOptions(
            totalTimeoutMillis = 500,
            redirectPolicy = { _, _ -> release.await(20, TimeUnit.SECONDS); true },
        )
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                assertThrows(RdfLoadTimeoutException::class.java) { Rdf.parseFromUrl("$root/start", RdfFormat.TURTLE, options) }
            }
            assertEquals(listOf("/start"), requests.toList())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `scheme rules of a redirect - downgrade refused, upgrade allowed, other schemes refused`() {
        val options = UrlLoadOptions.DEFAULT
        fun target(from: String, location: String): Any = redirectTarget(java.net.URI(from), location, options)
        assertEquals("scheme change https to http", target("https://example.org/a", "http://example.org/b"))
        assertEquals(java.net.URI("https://example.org/b"), target("http://example.org/a", "https://example.org/b"))
        assertEquals(java.net.URI("https://other.example/b"), target("https://example.org/a", "//other.example/b"))
        assertEquals("scheme 'file' is not allowed", target("http://example.org/a", "file:///etc/passwd"))
        assertEquals("scheme 'ftp' is not allowed", target("http://example.org/a", "ftp://example.org/b"))
        // Resolution against a URL without a path, on every JDK.
        assertEquals(java.net.URI("http://example.com/data.ttl"), target("http://example.com", "data.ttl"))
        assertEquals(java.net.URI("http://example.com/?q=1"), target("http://example.com", "?q=1"))
        assertEquals(java.net.URI("http://example.com/a/c"), target("http://example.com/a/b?x#y", "c"))
        // Characters that would have to be encoded inside the host are never encoded: that would name another host.
        val backslash = 0x5C.toChar()
        assertEquals("invalid Location", target("http://example.com/a", "http://good.example$backslash@evil.example/"))
        assertEquals("invalid Location", target("http://example.com/a", "http://evil.example|.good.example/"))
        assertEquals("invalid Location", target("http://example.com/a", "http://evil.example .good.example/"))
    }

    @Test
    fun `options have defaults for their policies, and equality`() {
        val five = UrlLoadOptions(setOf("http"), 10L, 1, 2, 3L)
        assertEquals(UrlRedirectPolicy.ALLOW_ALL, five.redirectPolicy)
        assertEquals(UrlAddressPolicy.ALLOW_ALL, five.addressPolicy)
        assertEquals(five, UrlLoadOptions(setOf("http"), 10L, 1, 2, 3L, UrlRedirectPolicy.ALLOW_ALL, UrlAddressPolicy.ALLOW_ALL))
        // One constructor: no overloads kept for earlier, unreleased shapes of the class.
        // (Kotlin adds a no-argument constructor to a class whose parameters all have defaults.)
        assertEquals(
            listOf(0, 7),
            UrlLoadOptions::class.java.constructors.filterNot { it.isSynthetic }.map { it.parameterCount }.sorted(),
        )
        assertEquals(UrlLoadOptions(), UrlLoadOptions.DEFAULT)
        assertEquals(UrlRedirectPolicy.SAME_HOST, five.copy(redirectPolicy = UrlRedirectPolicy.SAME_HOST).redirectPolicy)
    }
}
