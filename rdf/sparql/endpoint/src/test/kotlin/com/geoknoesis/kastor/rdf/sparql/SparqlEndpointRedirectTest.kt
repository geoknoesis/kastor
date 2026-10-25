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

/** Which redirects are followed, and what a redirected request may carry. */
class SparqlEndpointRedirectTest {

    private val selectAll = SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")

    @Test
    fun `cross-origin redirects are refused by default and never receive headers or credentials`() {
        LocalEndpoint { exchange, _ -> exchange.respond(200, rowsJson(1)) }.use { other ->
            LocalEndpoint { exchange, _ -> exchange.redirect(307, other.url) }.use { origin ->
                val config = SparqlEndpointConfig(origin.url, headers = mapOf("X-Api-Key" to "secret-key"), username = "u", password = "pw")
                SparqlRepository(config).use { repo ->
                    val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
                    assertTrue(e.message!!.contains("another origin"), e.message)
                }
                assertTrue(other.requests.isEmpty())

                // Followed redirects are still held to the address policy: the other origin is loopback.
                SparqlRepository(config.copy(followCrossOriginRedirects = true)).use { repo ->
                    val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
                    assertTrue(e.message!!.contains("redirectAddressPolicy"), e.message)
                }
                assertTrue(other.requests.isEmpty())

                SparqlRepository(config.copy(followCrossOriginRedirects = true, redirectAddressPolicy = UrlAddressPolicy.ALLOW_ALL)).use { repo ->
                    assertEquals(1, repo.select(selectAll).count())
                }
                val forwarded = other.requests.single()
                assertEquals("POST", forwarded.method)
                assertEquals(selectAll.sparql, forwarded.body)
                assertNull(forwarded.headers["x-api-key"])
                assertNull(forwarded.headers["authorization"])
                assertEquals("secret-key", origin.requests.last().headers["x-api-key"])
            }
        }
    }

    @Test
    fun `same-origin redirects keep method body and headers and POST is never downgraded`(): Unit = LocalEndpoint { exchange, request ->
        when (request.path) {
            "/sparql" -> exchange.redirect(308, "/moved")
            "/found" -> exchange.redirect(302, "/moved")
            "/loop" -> exchange.redirect(307, "/loop")
            else -> exchange.respond(200, rowsJson(2))
        }
    }.use { endpoint ->
        SparqlRepository(SparqlEndpointConfig(endpoint.url, headers = mapOf("X-Api-Key" to "k"))).use { repo ->
            assertEquals(2, repo.select(selectAll).count())
        }
        val moved = endpoint.requests.last()
        assertEquals("/moved", moved.path)
        assertEquals("POST", moved.method)
        assertEquals(selectAll.sparql, moved.body)
        assertEquals("k", moved.headers["x-api-key"])

        endpoint.requests.clear()
        SparqlRepository("${endpoint.base}/found").use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            assertTrue(e.message!!.contains("POST"), e.message)
        }
        assertEquals(1, endpoint.requests.size, "a redirected POST must not be re-sent as GET")

        SparqlRepository(SparqlEndpointConfig("${endpoint.base}/found", queryMethod = SparqlQueryMethod.GET)).use { repo ->
            assertEquals(2, repo.select(selectAll).count())
        }
        assertEquals("GET", endpoint.requests.last().method)

        SparqlRepository(SparqlEndpointConfig("${endpoint.base}/loop", maxRedirects = 3)).use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            assertTrue(e.message!!.contains("more than 3"), e.message)
        }
    }

    @Test
    fun `https to http redirects are refused even when cross-origin redirects are followed`() {
        val https = java.net.URI("https://example.org/sparql")
        val http = java.net.URI("http://example.org/sparql")
        val refusal = Redirects.refusal(https, http, followCrossOriginRedirects = true)
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("plain http"), refusal)
        assertNotNull(Redirects.refusal(https, http, followCrossOriginRedirects = false))
        assertNull(Redirects.refusal(http, java.net.URI("https://example.org/sparql"), followCrossOriginRedirects = true))
        assertNull(Redirects.refusal(https, java.net.URI("https://other.org/sparql"), followCrossOriginRedirects = true))
        assertNotNull(Redirects.refusal(https, java.net.URI("https://other.org/sparql"), followCrossOriginRedirects = false))
        assertNull(Redirects.refusal(https, java.net.URI("https://example.org/moved"), followCrossOriginRedirects = false))
    }
}
