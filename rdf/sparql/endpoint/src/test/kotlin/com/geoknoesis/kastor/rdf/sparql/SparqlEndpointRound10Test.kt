package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** Tenth audit of the SPARQL HTTP adapter: credentials in exception chains, header deadlines, update bodies. */
class SparqlEndpointRound10Test {

    private val selectAll = SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")
    private val insert = UpdateQuery("INSERT DATA { <urn:a> <urn:b> <urn:c> }")

    /** Everything an exception would put into a log: messages, `toString`, causes, suppressed exceptions. */
    private fun everything(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    // ------------------------------------------------------------------ credentials in an unparsable endpoint

    @Test
    fun `no part of the password of an unparsable endpoint reaches the exception chain`() {
        // `%of` is not a valid escape, so java.net.URI refuses the URL, quoting it whole in its own exception.
        val endpoint = "http://u:50%off@host/ds"
        for (e in listOf(
            assertThrows(IllegalArgumentException::class.java) { HttpTarget.parse(endpoint) },
            assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig(endpoint) },
            assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig("http://h/sparql", updateEndpoint = endpoint) },
        )) {
            val text = everything(e)
            assertFalse(text.contains("50%") || text.contains("off@") || text.contains("%off"), text)
            assertTrue(e.message!!.contains("http://***@host/ds"), e.message)
            assertTrue(e.message!!.contains("index"), "the parser's position is kept: ${e.message}")
        }
    }

    @Test
    fun `user information with an unencoded at sign is cut out up to the last one`() {
        assertEquals("http://***@host/", HttpTarget.redact("http://u:p@ss@host/"))
        assertEquals("http://***@host/ds?x=a@b", HttpTarget.redact("http://u:p@ss@host/ds?x=a@b"))
        assertEquals("http://***@host?x=a@b", HttpTarget.redact("http://u:p@ss@host?x=a@b"))
        assertEquals("http://host/ds", HttpTarget.redact("http://host/ds"))
        assertEquals("http://***@host/a?...", urlDetail("http://u:p@ss@host/a?sig=a@b"))
        assertEquals("http://***@host/a/b@c", urlDetail("http://u:p@ss@host/a/b@c"))
        val e = assertThrows(IllegalArgumentException::class.java) { HttpTarget.parse("http://u:p@ss@host/") }
        val text = everything(e)
        assertFalse(text.contains("p@ss") || text.contains("ss@"), text)
    }

    // ------------------------------------------------------------------ redirect Location in the cause

    @Test
    fun `an invalid redirect Location is sanitised in the cause too`() {
        val transport = HttpTransport { request ->
            CompletableFuture.completedFuture(
                FakeResponse(request, 307, ByteArrayInputStream(ByteArray(0)), mapOf("Location" to "http://u:pw123@sparql.example/a b?sig=SIGNED-TOKEN#frag")),
            )
        }
        SparqlRepository(SparqlEndpointConfig("http://sparql.example/sparql"), transport).use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            assertTrue(e.message!!.contains("invalid redirect Location"), e.message)
            val text = everything(e)
            assertFalse(text.contains("SIGNED-TOKEN") || text.contains("pw123") || text.contains("frag"), text)
            assertTrue(e.cause is java.net.URISyntaxException, "the parser's reason is kept: ${e.cause}")
        }
    }

    // ------------------------------------------------------------------ header deadline of the timed stream

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `the timed stream waits for headers until the earlier of requestTimeout and its own timeout`() {
        val requestTimeout = Duration.ofSeconds(3)
        val long = Duration.ofSeconds(60)
        SilentServer().use { server ->
            SparqlRepository(SparqlEndpointConfig(server.url, readTimeout = long, requestTimeout = requestTimeout)).use { repo ->
                val start = System.nanoTime()
                val e = assertThrows(RdfQueryException::class.java) { repo.withSelectRows(selectAll, emptyMap(), long) { it.count() } }
                assertTrue(e.message!!.contains("exceeded its 3000 ms deadline waiting for the response"), e.message)
                assertEndedAtLimit(requestTimeout, start, "timed stream")
            }
            // The per-call timeout still wins when it is the shorter one.
            SparqlRepository(SparqlEndpointConfig(server.url, readTimeout = long, requestTimeout = long)).use { repo ->
                val start = System.nanoTime()
                val e = assertThrows(RdfQueryException::class.java) { repo.withSelectRows(selectAll, emptyMap(), requestTimeout) { it.count() } }
                assertTrue(e.message!!.contains("exceeded its 3000 ms deadline waiting for the response"), e.message)
                assertEndedAtLimit(requestTimeout, start, "timed stream")
            }
        }
    }

    // ------------------------------------------------------------------ the body of a successful update

    @Test
    fun `a successful update whose response body exceeds the cap still succeeds and is sent once`() {
        LocalEndpoint { exchange, _ -> exchange.respond(200, "x".repeat(10_000), "text/plain") }.use { endpoint ->
            SparqlRepository(SparqlEndpointConfig(endpoint.url, maxResponseBytes = 1_024)).use { repo ->
                repo.update(insert)
            }
            assertEquals(1, endpoint.requests.size)
        }
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a successful update whose response body stalls still succeeds at the read timeout`() {
        val readTimeout = Duration.ofSeconds(2)
        LocalEndpoint { exchange, _ ->
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("applied".toByteArray())
            exchange.responseBody.flush()
            Thread.sleep(STALL_MILLIS)
        }.use { endpoint ->
            SparqlRepository(SparqlEndpointConfig(endpoint.url, readTimeout = readTimeout)).use { repo ->
                val start = System.nanoTime()
                repo.update(insert)
                assertEndedAtLimit(readTimeout, start, "update")
            }
            assertEquals(1, endpoint.requests.size)
        }
        // A failure status is still a failure.
        LocalEndpoint { exchange, _ -> exchange.respond(500, "boom", "text/plain") }.use { endpoint ->
            SparqlRepository(SparqlEndpointConfig(endpoint.url)).use { repo ->
                assertThrows(RdfQueryException::class.java) { repo.update(insert) }
            }
        }
    }
}
