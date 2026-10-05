package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/** Ninth audit of the SPARQL HTTP adapter: host names, message hygiene, streaming cap, Accept, Basic user names. */
class SparqlEndpointRound9Test {

    private val selectAll = SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")
    private val url = "http://sparql.example/sparql"

    private fun responding(status: Int, headers: Map<String, String>, body: String = ""): HttpTransport = HttpTransport { request ->
        CompletableFuture.completedFuture(FakeResponse(request, status, ByteArrayInputStream(body.toByteArray()), headers))
    }

    private fun failure(config: SparqlEndpointConfig, transport: HttpTransport): String =
        SparqlRepository(config, transport).use { repo ->
            assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }.message!!
        }

    // ------------------------------------------------------------------ host names with '_'

    @Test
    fun `an endpoint host name with an underscore is refused with a message that says why`() {
        // java.net.URI reads no host from it, and the JDK HTTP client rejects the URI.
        val e = assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig("http://my_fuseki:3030/ds/sparql") }
        assertTrue(e.message!!.contains("my_fuseki") && e.message!!.contains("'_'"), e.message)
        assertFalse(e.message!!.contains("has no host"), e.message)
        assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig(url, updateEndpoint = "https://a_b.example/update") }
        // Still the plain message when there really is no host.
        assertTrue(assertThrows(IllegalArgumentException::class.java) { HttpTarget.parse("http:///sparql") }.message!!.contains("has no host"))
        // Hyphens are fine.
        HttpTarget.parse("http://my-fuseki:3030/ds")
    }

    @Test
    fun `credentials never appear in the message about an underscore host`() {
        val e = assertThrows(IllegalArgumentException::class.java) { HttpTarget.parse("http://alice:s3cret@my_fuseki:3030/ds") }
        assertFalse(e.message!!.contains("s3cret") || e.message!!.contains("alice"), e.message)
        assertEquals("http://***@my_fuseki:3030/ds", HttpTarget.redact("http://alice:s3cret@my_fuseki:3030/ds"))
    }

    @Test
    fun `a redirect to an underscore host is refused with the same explanation`() {
        val transport = responding(307, mapOf("Location" to "http://my_fuseki:3030/ds?token=SIGNED-TOKEN"))
        val message = failure(SparqlEndpointConfig(url, followCrossOriginRedirects = true), transport)
        assertTrue(message.contains("my_fuseki") && message.contains("'_'"), message)
        assertFalse(message.contains("SIGNED-TOKEN"), message)
    }

    // ------------------------------------------------------------------ untrusted text in messages

    @Test
    fun `a redirect Location is cut down before it reaches a message`() {
        val long = "http://bob:pw123@sparql.example/" + "x".repeat(10_000) + "?sig=SIGNED-TOKEN#frag-secret"
        // A POST answered with 302 names the Location it will not follow.
        val message = failure(SparqlEndpointConfig(url), responding(302, mapOf("Location" to long)))
        assertTrue(message.contains("Configure the redirect target"), message)
        assertFalse(message.contains("SIGNED-TOKEN") || message.contains("pw123") || message.contains("frag-secret"), message)
        assertTrue(message.length < 1_500, "message has ${message.length} characters")
        // So does an unusable one.
        val invalid = failure(SparqlEndpointConfig(url), responding(307, mapOf("Location" to "http://sparql.example/a b?sig=SIGNED-TOKEN")))
        assertTrue(invalid.contains("invalid redirect Location"), invalid)
        assertFalse(invalid.contains("SIGNED-TOKEN"), invalid)
        val ftp = failure(SparqlEndpointConfig(url), responding(307, mapOf("Location" to "ftp://carol:pw456@sparql.example/r?sig=SIGNED-TOKEN")))
        assertFalse(ftp.contains("SIGNED-TOKEN") || ftp.contains("pw456"), ftp)
    }

    @Test
    fun `urlDetail drops user information, query and fragment and escapes control characters`() {
        assertEquals("https://***@h/p?...", urlDetail("https://u:p@h/p?sig=1#f"))
        assertEquals("https://h/p", urlDetail("https://h/p"))
        assertEquals("https://h/a\\u0007b", urlDetail("https://h/a\u0007b"))
        assertTrue(urlDetail("http://h/" + "a".repeat(5_000)).length <= 520)
    }

    @Test
    fun `a Content-Type is sanitised and bounded in the message`() {
        val hostile = "text/html; note=" + "a".repeat(5_000) + "\u0007\u202e\n[FORGED LOG LINE]"
        val message = failure(SparqlEndpointConfig(url), responding(200, mapOf("Content-Type" to hostile), "<html/>"))
        assertTrue(message.contains("instead of SPARQL JSON results"), message)
        assertTrue(message.length < 1_500, "message has ${message.length} characters")
        assertTrue(message.none { it == '\n' || it == '\u0007' || it == '\u202e' }, message)
    }

    // ------------------------------------------------------------------ streaming cap

    @Test
    fun `streams have an overall cap by default that can be removed`() {
        assertEquals(Duration.ofMinutes(60), SparqlEndpointConfig(url).streamingRequestTimeout)
        val options = mapOf("location" to url)
        assertEquals(Duration.ofMinutes(60), SparqlEndpointConfig.fromOptions(options).streamingRequestTimeout)
        assertEquals(Duration.ofSeconds(90), SparqlEndpointConfig.fromOptions(options + ("streamingRequestTimeoutMillis" to "90000")).streamingRequestTimeout)
        assertNull(SparqlEndpointConfig.fromOptions(options + ("streamingRequestTimeoutMillis" to "none")).streamingRequestTimeout)
        assertNull(SparqlEndpointConfig(url, streamingRequestTimeout = null).streamingRequestTimeout)
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a stream that drips rows inside the read timeout still ends at the overall cap`() {
        val limit = Duration.ofSeconds(2)
        LocalEndpoint { exchange, _ ->
            exchange.trickleRows(rows = 100_000, gapMillis = 20)
        }.use { endpoint ->
            // The read timeout (one hour) is never reached: every read returns within milliseconds.
            val config = SparqlEndpointConfig(endpoint.url, readTimeout = Duration.ofHours(1), streamingRequestTimeout = limit)
            SparqlRepository(config).use { repo ->
                val start = System.nanoTime()
                val e = assertThrows(RdfQueryException::class.java) { repo.withSelectRows(selectAll) { rows -> rows.count() } }
                assertTrue(e.message!!.contains("deadline"), e.message)
                assertEndedAtLimit(limit, start, "drip stream")
            }
        }
    }

    // ------------------------------------------------------------------ Accept

    @Test
    fun `queries accept plain JSON at a lower priority`() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val transport = HttpTransport { request ->
            sent += request
            CompletableFuture.completedFuture<HttpResponse<InputStream>>(
                FakeResponse(request, 200, ByteArrayInputStream(rowsJson(1).toByteArray()), mapOf("Content-Type" to "application/json")),
            )
        }
        SparqlRepository(SparqlEndpointConfig(url), transport).use { repo ->
            assertEquals(1, repo.select(selectAll).count())
        }
        assertEquals(listOf("application/sparql-results+json, application/json;q=0.8"), sent.single().headers().allValues("Accept"))
    }

    // ------------------------------------------------------------------ Basic authentication

    @Test
    fun `a Basic user name with a colon is refused`() {
        assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig(url, username = "a:b", password = "x") }
        assertThrows(IllegalArgumentException::class.java) { HttpTarget.basicAuthorization("a:b", "x") }
        // An encoded colon in a URL's user name is not the separator, and must not split the credentials differently.
        assertThrows(IllegalArgumentException::class.java) { HttpTarget.parse("http://a%3Ab:pw@sparql.example/sparql") }
        assertNotNull(HttpTarget.parse("http://alice:p%3Aw@sparql.example/sparql").userInfoAuthorization)
        // A colon in the password is fine.
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("a:b:c".toByteArray()), HttpTarget.basicAuthorization("a", "b:c"))
    }

    // ------------------------------------------------------------------ read expiry

    @Test
    fun `a read that the watchdog expires as it returns fails the next read with the message of that read`() {
        val watchdog = ReadWatchdog()
        lateinit var guarded: SparqlRepository.GuardedInputStream
        val raw = object : InputStream() {
            @Volatile var closed = false
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                // The watchdog finds the read overdue while it is still in progress, then the read returns data.
                assertEquals(Long.MAX_VALUE, guarded.expireIfDue(Long.MAX_VALUE))
                b[off] = 'x'.code.toByte()
                return 1
            }
            override fun close() { closed = true }
        }
        guarded = SparqlRepository.GuardedInputStream(raw, watchdog, Duration.ofHours(1), null, null)
        assertEquals(1, guarded.read(ByteArray(4), 0, 4))
        assertTrue(raw.closed)
        val e = assertThrows(IOException::class.java) { guarded.read(ByteArray(4), 0, 4) }
        assertEquals("SPARQL response read timed out after 3600000 ms", e.message)
        guarded.close()
    }
}
