package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.apache.jena.query.Dataset
import org.apache.jena.query.DatasetFactory
import org.apache.jena.query.QueryExecution
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.QuerySolutionMap
import org.apache.jena.query.ResultSetFormatter
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFParser
import org.apache.jena.update.UpdateAction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Requests the adapter sends and refuses to send: updates, GET fallback, closed repositories, credentials, configuration. */
class SparqlEndpointRequestTest {

    private val selectAll = SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")

    @Test
    fun `a failed update is never re-sent automatically`(): Unit = LocalEndpoint { exchange, request ->
        if (request.contentType == "application/sparql-update") {
            // Drop the connection without answering, as a crashing proxy would.
        } else {
            exchange.respond(200, """{"head":{},"boolean":true}""")
        }
    }.use { endpoint ->
        SparqlRepository(endpoint.url).use { repo ->
            assertTrue(repo.ask(SparqlAskQuery("ASK {}")))
            assertThrows(RdfQueryException::class.java) { repo.update(UpdateQuery("INSERT DATA { <urn:a> <urn:b> <urn:c> }")) }
            // Nothing to wait for: the endpoint records a request before it drops the connection, and the
            // HTTP client reports the failure only when it has given up, so a re-sent update would be
            // recorded by now. The endpoint still answers, on a new connection.
            assertEquals(1, endpoint.requests.count { it.contentType == "application/sparql-update" })
            assertTrue(repo.ask(SparqlAskQuery("ASK {}")))
        }
        assertEquals(1, endpoint.requests.count { it.contentType == "application/sparql-update" })
    }

    @Test
    fun `long GET queries fall back to a form POST`(): Unit = LocalEndpoint { exchange, _ ->
        exchange.respond(200, rowsJson(1))
    }.use { endpoint ->
        SparqlRepository(SparqlEndpointConfig(endpoint.url, queryMethod = SparqlQueryMethod.GET, maxGetUrlLength = 200)).use { repo ->
            repo.select(selectAll)
            val long = SparqlSelectQuery("SELECT * WHERE { ?s ?p \"${"x".repeat(300)}\" }")
            repo.select(long)
        }
        assertEquals("GET", endpoint.requests[0].method)
        val fallback = endpoint.requests[1]
        assertEquals("POST", fallback.method)
        assertEquals("application/x-www-form-urlencoded", fallback.contentType)
        assertTrue(URLDecoder.decode(fallback.body.removePrefix("query="), Charsets.UTF_8).contains("x".repeat(300)))
    }

    @Test
    fun `every operation on a closed repository fails the same way`(): Unit = LocalEndpoint { exchange, _ ->
        exchange.respond(200, rowsJson(1))
    }.use { endpoint ->
        val repo = SparqlRepository(endpoint.url)
        repo.close()
        listOf<() -> Any?>(
            { repo.select(selectAll) },
            { repo.withSelectRows(selectAll) { it.count() } },
            { repo.withSelectRows(selectAll, emptyMap(), Duration.ofSeconds(1)) { it.count() } },
            { repo.ask(SparqlAskQuery("ASK {}")) },
            { repo.update(UpdateQuery("CLEAR DEFAULT")) },
        ).forEach { call ->
            val e = assertThrows(IllegalStateException::class.java) { call() }
            assertTrue(e.message!!.contains("closed"), e.message)
        }
        assertTrue(endpoint.requests.isEmpty())
    }

    @Test
    fun `basic authentication over plain http is flagged and redacted`() {
        val plain = SparqlRepository.insecureAuthorizationWarning(SparqlEndpointConfig("http://example.org/sparql", username = "u", password = "hunter2"))
        assertNotNull(plain)
        assertFalse(plain!!.contains("hunter2"))
        val embedded = SparqlRepository.insecureAuthorizationWarning(SparqlEndpointConfig("http://alice:s3cret@example.org/sparql"))
        assertNotNull(embedded)
        assertFalse(embedded!!.contains("s3cret"), embedded)
        assertNull(SparqlRepository.insecureAuthorizationWarning(SparqlEndpointConfig("https://example.org/sparql", username = "u", password = "p")))
        assertNull(SparqlRepository.insecureAuthorizationWarning(SparqlEndpointConfig("http://example.org/sparql")))
        assertNotNull(
            SparqlRepository.insecureAuthorizationWarning(
                SparqlEndpointConfig("https://example.org/sparql", updateEndpoint = "http://example.org/update", username = "u", password = "p")
            )
        )
    }

    @Test
    fun `headers managed by the HTTP client are rejected in the configuration`() {
        for (name in listOf("Host", "content-length", "Connection", "Expect", "Upgrade")) {
            assertThrows(IllegalArgumentException::class.java, { SparqlEndpointConfig("https://example.org/sparql", headers = mapOf(name to "x")) }, name)
        }
    }

    @Test
    fun `repositories with the same connect timeout share one HTTP client until the last one is closed`() {
        LocalEndpoint { exchange, _ -> exchange.respond(200, rowsJson(1)) }.use { endpoint ->
            val connectTimeout = Duration.ofMillis(12_345)
            val before = SparqlRepository.sharedHttpClientCount()
            val first = SparqlRepository(SparqlEndpointConfig(endpoint.url, connectTimeout = connectTimeout))
            val second = SparqlRepository(SparqlEndpointConfig(endpoint.url, connectTimeout = connectTimeout, readTimeout = Duration.ofSeconds(5)))
            assertEquals(before + 1, SparqlRepository.sharedHttpClientCount())
            first.close()
            first.close()
            assertEquals(1, second.select(selectAll).count())
            second.close()
            assertEquals(before, SparqlRepository.sharedHttpClientCount())
            assertThrows(IllegalStateException::class.java) { second.select(selectAll) }
        }
    }

    @Test
    fun `streamed rows are parsed across buffer boundaries`(): Unit = LocalEndpoint { exchange, _ ->
        exchange.respond(200, rowsJson(5_000) { "v\\\"$it\\\\ é€😀" })
    }.use { endpoint ->
        SparqlRepository(endpoint.url).use { repo ->
            val values = repo.withSelectRows(selectAll) { rows -> rows.map { (it.get("x") as Literal).lexical }.toList() }
            assertEquals(5_000, values.size)
            values.forEachIndexed { i, v -> assertEquals("v\"$i\\ é€😀", v) }
        }
    }

    @Test
    fun `an oversized result row fails even when streamed responses are unbounded`(): Unit = LocalEndpoint { exchange, request ->
        when (request.path) {
            "/head" -> exchange.respond(200, "{\"head\":{\"vars\":[\"${"x".repeat(5_000)}\"]},\"results\":{\"bindings\":[]}}")
            else -> exchange.respond(200, rowsJson(3) { if (it == 1) "y".repeat(5_000) else "ok" })
        }
    }.use { endpoint ->
        val config = SparqlEndpointConfig(endpoint.url, maxStreamedResponseBytes = null, maxResultRowChars = 1_000)
        SparqlRepository(config).use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.withSelectRows(selectAll) { it.count() } }
            assertTrue(e.message!!.contains("maxResultRowChars"), e.message)
            assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
        }
        SparqlRepository(config.copy(endpoint = "${endpoint.base}/head")).use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.withSelectRows(selectAll) { it.count() } }
            assertTrue(e.message!!.contains("maxResultRowChars"), e.message)
        }
        SparqlRepository(endpoint.url).use { repo -> assertEquals(3, repo.withSelectRows(selectAll) { it.count() }) }
        assertEquals(SparqlEndpointConfig.DEFAULT_MAX_RESULT_ROW_CHARS, SparqlEndpointConfig(endpoint.url).maxResultRowChars)
        assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig(endpoint.url, maxResultRowChars = 0) }
        // (Expression-bodied JUnit tests must return Unit, otherwise they are silently skipped.)
        assertEquals(77, SparqlEndpointConfig.fromOptions(mapOf("location" to endpoint.url, "maxResultRowChars" to "77")).maxResultRowChars)
    }

    @Test
    fun `a success response that is not SPARQL JSON fails with its content type`(): Unit = LocalEndpoint { exchange, request ->
        when (request.path) {
            "/html" -> exchange.respond(200, "<html><body>login</body></html>", "text/html; charset=utf-8")
            "/xml" -> exchange.respond(200, "<sparql/>", "application/sparql-results+xml")
            "/plain" -> exchange.respond(200, "true", "text/plain")
            else -> exchange.respond(200, rowsJson(1), "application/json; charset=UTF-8")
        }
    }.use { endpoint ->
        SparqlRepository("${endpoint.base}/html").use { repo ->
            val select = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            assertTrue(select.message!!.contains("text/html"), select.message)
            val stream = assertThrows(RdfQueryException::class.java) { repo.withSelectRows(selectAll) { it.count() } }
            assertTrue(stream.message!!.contains("text/html"), stream.message)
            val ask = assertThrows(RdfQueryException::class.java) { repo.ask(SparqlAskQuery("ASK {}")) }
            assertTrue(ask.message!!.contains("text/html"), ask.message)
        }
        SparqlRepository("${endpoint.base}/xml").use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            assertTrue(e.message!!.contains("application/sparql-results+xml"), e.message)
        }
        SparqlRepository("${endpoint.base}/plain").use { repo -> assertTrue(repo.ask(SparqlAskQuery("ASK {}"))) }
        SparqlRepository(endpoint.url).use { repo -> assertEquals(1, repo.select(selectAll).count()) }
    }

    @Test
    fun `endpoint URLs with a fragment are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig("http://example.org/sparql#frag") }
        assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig("http://example.org/sparql#") }
        assertThrows(IllegalArgumentException::class.java) {
            SparqlEndpointConfig("http://example.org/sparql", updateEndpoint = "http://example.org/update#x")
        }
        SparqlEndpointConfig("http://example.org/sparql?default-graph-uri=urn%3Ag")
    }
}
