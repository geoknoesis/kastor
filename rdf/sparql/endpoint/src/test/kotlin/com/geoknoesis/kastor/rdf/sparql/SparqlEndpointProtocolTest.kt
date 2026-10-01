package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLSession

/**
 * Header wait, redirects and Content-Type handling of the SPARQL HTTP adapter (fifth audit). Timing
 * assertions follow the rules in `EndpointTestSupport.kt`: the exception says which limit fired, a
 * lower bound shows it did not fire early, and upper bounds are generous.
 */
class SparqlEndpointProtocolTest {

    private val selectAll = SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")
    private val askAny = SparqlAskQuery("ASK { ?s ?p ?o }")

    /** A canned response for [HttpTransport] fakes. */
    private class CannedResponse(
        private val request: HttpRequest,
        private val status: Int,
        headers: Map<String, String>,
        body: String = "",
    ) : HttpResponse<InputStream> {
        private val headers = HttpHeaders.of(headers.mapValues { listOf(it.value) }) { _, _ -> true }
        private val body = body.byteInputStream()
        override fun statusCode(): Int = status
        override fun request(): HttpRequest = request
        override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()
        override fun headers(): HttpHeaders = headers
        override fun body(): InputStream = body
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri(): URI = request.uri()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

    // ------------------------------------------------------------------ header wait

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `no timer is set on the HTTP request so a header limit never covers the response body`() {
        // The body takes 6 x 400 ms; every limit below is 2 s.
        val limit = Duration.ofSeconds(2)
        LocalEndpoint { exchange, _ -> exchange.trickleRows(rows = 6, gapMillis = 400) }.use { endpoint ->
            val sent = CopyOnWriteArrayList<HttpRequest>()
            val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            val transport = HttpTransport { request ->
                sent += request
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            }
            try {
                // Streamed rows: requestTimeout only limits the wait for the response headers.
                SparqlRepository(SparqlEndpointConfig(endpoint.url, requestTimeout = limit), transport).use { repo ->
                    val start = System.nanoTime()
                    assertEquals(6, repo.withSelectRows(selectAll) { it.count() })
                    assertTrue(elapsedMillisSince(start) >= limit.toMillis(), "the body was expected to outlast the header limit")
                }
                // Without a request deadline the read timeout bounds the header wait and each read, not the whole body.
                SparqlRepository(SparqlEndpointConfig(endpoint.url, requestTimeout = null, readTimeout = limit), transport).use { repo ->
                    val start = System.nanoTime()
                    assertEquals(6, repo.withSelectRows(selectAll) { it.count() })
                    assertEquals(6, repo.select(selectAll).count())
                    assertTrue(elapsedMillisSince(start) >= 2 * limit.toMillis(), "the bodies were expected to outlast the read timeout")
                }
                assertEquals(3, sent.size)
                // From JDK 26 a request timeout also covers reading the body (JDK-8208693), so none may be set.
                sent.forEach { request -> assertTrue(request.timeout().isEmpty, "request timer set: ${request.timeout()}") }
            } finally {
                client.shutdownNow()
            }
        }
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a stalled wait for response headers times out and abandons the exchange`() {
        val limit = Duration.ofMillis(500)
        val long = Duration.ofSeconds(60)
        SilentServer().use { server ->
            val byDeadline = SparqlEndpointConfig(server.url, readTimeout = long, requestTimeout = limit)
            val byReadTimeout = SparqlEndpointConfig(server.url, readTimeout = limit, requestTimeout = null)
            var calls = 0
            for ((config, expected) in listOf(
                byDeadline to "exceeded its 500 ms deadline waiting for the response",
                byReadTimeout to "timed out after 500 ms waiting for the response",
            )) {
                SparqlRepository(config).use { repo ->
                    listOf<Pair<String, () -> Any>>(
                        "select" to { repo.select(selectAll) },
                        "stream" to { repo.withSelectRows(selectAll) { it.count() } },
                        "ask" to { repo.ask(askAny) },
                        "update" to { repo.update(UpdateQuery("CLEAR DEFAULT")) },
                    ).forEach { (name, call) ->
                        val start = System.nanoTime()
                        val e = assertThrows(RdfQueryException::class.java, { call() }, name)
                        assertTrue(e.message!!.contains(expected), "$name: ${e.message}")
                        assertEndedAtLimit(limit, start, name)
                        calls++
                    }
                }
            }
            // Every timed-out exchange was cancelled: the server sees its connection closed, not left waiting.
            assertEquals(calls, server.accepted.size)
            for (socket in server.accepted) {
                socket.soTimeout = SLOW_HOST_SLACK_MILLIS.toInt()
                try {
                    val input = socket.getInputStream()
                    while (input.read() >= 0) {
                        // the request, then end of stream
                    }
                } catch (e: SocketTimeoutException) {
                    fail<Unit>("the client left a timed-out connection open")
                } catch (_: java.io.IOException) {
                    // a reset is a closed connection too
                }
            }
        }
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `the header wait is one budget for all redirect hops`() {
        // Three hops that each answer after 700 ms, then the result: 2.1 s of header waits in total.
        LocalEndpoint { exchange, request ->
            when (request.path) {
                "/hop0" -> { Thread.sleep(700); exchange.redirect(307, "/hop1") }
                "/hop1" -> { Thread.sleep(700); exchange.redirect(307, "/hop2") }
                "/hop2" -> { Thread.sleep(700); exchange.redirect(307, "/result") }
                else -> exchange.respond(200, rowsJson(1))
            }
        }.use { endpoint ->
            val start = "${endpoint.base}/hop0"
            val limit = Duration.ofMillis(1_500)
            // Each hop is faster than the read timeout, all of them together are not.
            SparqlRepository(SparqlEndpointConfig(start, readTimeout = limit, requestTimeout = null)).use { repo ->
                listOf<Pair<String, () -> Any>>(
                    "select" to { repo.select(selectAll) },
                    "stream" to { repo.withSelectRows(selectAll) { it.count() } },
                ).forEach { (name, call) ->
                    val began = System.nanoTime()
                    val e = assertThrows(RdfQueryException::class.java, { call() }, name)
                    assertTrue(e.message!!.contains("timed out after 1500 ms waiting for the response"), "$name: ${e.message}")
                    assertEndedAtLimit(limit, began, name)
                }
            }
            // The same budget rule as with a request deadline.
            SparqlRepository(SparqlEndpointConfig(start, readTimeout = Duration.ofSeconds(60), requestTimeout = limit)).use { repo ->
                val began = System.nanoTime()
                val e = assertThrows(RdfQueryException::class.java) { repo.withSelectRows(selectAll) { it.count() } }
                assertTrue(e.message!!.contains("1500 ms deadline"), e.message)
                assertEndedAtLimit(limit, began, "deadline")
            }
            assertTrue(endpoint.requests.none { it.path == "/result" }, "a request ran past its header budget")
            // With enough budget the chain is followed to the end.
            SparqlRepository(SparqlEndpointConfig(start, readTimeout = Duration.ofSeconds(60), requestTimeout = null)).use { repo ->
                assertEquals(1, repo.select(selectAll).count())
            }
            assertEquals("/result", endpoint.requests.last().path)
        }
    }

    // ------------------------------------------------------------------ redirects

    @Test
    fun `301 and 303 are followed for GET queries and refused for POST`() {
        LocalEndpoint { exchange, request ->
            when (request.path) {
                "/moved-permanently" -> exchange.redirect(301, "/result")
                "/see-other" -> exchange.redirect(303, "result")
                else -> exchange.respond(200, rowsJson(2))
            }
        }.use { endpoint ->
            for (path in listOf("/moved-permanently", "/see-other")) {
                endpoint.requests.clear()
                SparqlRepository(SparqlEndpointConfig(endpoint.base + path, queryMethod = SparqlQueryMethod.GET)).use { repo ->
                    assertEquals(2, repo.select(selectAll).count(), path)
                }
                assertEquals(listOf(path, "/result"), endpoint.requests.map { it.path })
                assertEquals("GET", endpoint.requests.last().method)

                endpoint.requests.clear()
                SparqlRepository(endpoint.base + path).use { repo ->
                    for (call in listOf<() -> Any>({ repo.select(selectAll) }, { repo.update(UpdateQuery("CLEAR DEFAULT")) })) {
                        val e = assertThrows(RdfQueryException::class.java) { call() }
                        assertTrue(e.message!!.contains("answered a POST with HTTP"), e.message)
                    }
                }
                assertEquals(listOf(path, path), endpoint.requests.map { it.path }, "a redirected POST must not be re-sent")
            }
        }
    }

    @Test
    fun `a redirect Location with credentials or without a usable target is refused`() {
        LocalEndpoint { exchange, request ->
            val self = "127.0.0.1:${exchange.localAddress.port}"
            when (request.path) {
                "/userinfo" -> exchange.redirect(307, "http://alice:s3cret@$self/result")
                "/no-location" -> exchange.redirect(307, null)
                "/blank-location" -> exchange.redirect(307, " ")
                "/ftp" -> exchange.redirect(307, "ftp://$self/result")
                "/invalid" -> exchange.redirect(307, "http://$self/a b")
                else -> exchange.respond(200, rowsJson(1))
            }
        }.use { endpoint ->
            fun failure(path: String): String {
                endpoint.requests.clear()
                val message = SparqlRepository(SparqlEndpointConfig(endpoint.base + path, followCrossOriginRedirects = true)).use { repo ->
                    assertThrows(RdfQueryException::class.java, { repo.select(selectAll) }, path).message!!
                }
                assertEquals(listOf(path), endpoint.requests.map { it.path }, "the redirect must not be followed")
                return message
            }
            val userInfo = failure("/userinfo")
            assertTrue(userInfo.contains("unsupported URL"), userInfo)
            assertFalse(userInfo.contains("s3cret") || userInfo.contains("alice"), userInfo)
            assertTrue(failure("/no-location").contains("without a Location header"))
            assertTrue(failure("/blank-location").contains("without a Location header"))
            assertTrue(failure("/ftp").contains("unsupported URL"))
            assertTrue(failure("/invalid").contains("invalid redirect Location"))
        }
    }

    @Test
    fun `the redirect loop refuses to go from https to plain http`() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        // Plays an https origin without TLS: the adapter only sees requests and responses.
        val transport = HttpTransport { request ->
            sent += request
            val uri = request.uri()
            val response = when {
                uri.scheme == "https" && uri.host == "secure.example" && uri.path == "/downgrade" ->
                    CannedResponse(request, 307, mapOf("Location" to "http://secure.example/sparql"))
                uri.scheme == "https" && uri.host == "secure.example" && uri.path == "/elsewhere" ->
                    CannedResponse(request, 308, mapOf("Location" to "https://other.example/sparql"))
                uri.scheme == "https" ->
                    CannedResponse(request, 200, mapOf("Content-Type" to "application/sparql-results+json"), rowsJson(1))
                else -> CannedResponse(request, 500, emptyMap(), "plain http must never be requested")
            }
            CompletableFuture.completedFuture(response)
        }
        val downgrade = SparqlEndpointConfig("https://secure.example/downgrade", headers = mapOf("X-Api-Key" to "k"))
        for (config in listOf(downgrade, downgrade.copy(followCrossOriginRedirects = true))) {
            sent.clear()
            SparqlRepository(config, transport).use { repo ->
                for (call in listOf<() -> Any>(
                    { repo.select(selectAll) },
                    { repo.withSelectRows(selectAll) { it.count() } },
                    { repo.ask(askAny) },
                    { repo.update(UpdateQuery("CLEAR DEFAULT")) },
                )) {
                    val e = assertThrows(RdfQueryException::class.java) { call() }
                    assertTrue(e.message!!.contains("from https to plain http"), e.message)
                }
            }
            assertEquals(4, sent.size)
            assertTrue(sent.all { it.uri().scheme == "https" }, "a request was sent over plain http: ${sent.map { it.uri() }}")
        }
        // An https redirect to another origin is followed when allowed, without the custom header.
        sent.clear()
        SparqlRepository(downgrade.copy(endpoint = "https://secure.example/elsewhere", followCrossOriginRedirects = true), transport).use { repo ->
            assertEquals(1, repo.select(selectAll).count())
        }
        assertEquals(listOf("https://secure.example/elsewhere", "https://other.example/sparql"), sent.map { it.uri().toString() })
        assertEquals("k", sent[0].headers().firstValue("X-Api-Key").orElse(null))
        assertFalse(sent[1].headers().firstValue("X-Api-Key").isPresent)
    }

    // ------------------------------------------------------------------ Content-Type

    @Test
    fun `a response without a Content-Type is parsed as SPARQL JSON results`() {
        LocalEndpoint { exchange, request ->
            when (request.path) {
                "/ask" -> exchange.respond(200, "{\"head\":{},\"boolean\":true}", type = null)
                "/html" -> exchange.respond(200, "<html><body>login</body></html>", type = null)
                else -> exchange.respond(200, rowsJson(3), type = null)
            }
        }.use { endpoint ->
            SparqlRepository(endpoint.url).use { repo ->
                assertEquals(3, repo.select(selectAll).count())
                assertEquals(3, repo.withSelectRows(selectAll) { it.count() })
            }
            SparqlRepository("${endpoint.base}/ask").use { repo -> assertTrue(repo.ask(askAny)) }
            // Not JSON after all: a result-format error, never an empty result.
            SparqlRepository("${endpoint.base}/html").use { repo ->
                assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
                assertThrows(RdfQueryException::class.java) { repo.ask(askAny) }
            }
        }
    }

    @Test
    fun `the Content-Type check can be switched off for legacy servers`() {
        LocalEndpoint { exchange, request ->
            when (request.path) {
                "/text-json" -> exchange.respond(200, rowsJson(2), "text/json; charset=utf-8")
                "/javascript" -> exchange.respond(200, rowsJson(2), "application/javascript")
                "/ask" -> exchange.respond(200, "{\"head\":{},\"boolean\":true}", "text/json")
                else -> exchange.respond(200, "<html><body>login</body></html>", "text/html")
            }
        }.use { endpoint ->
            for (path in listOf("/text-json", "/javascript")) {
                val strict = SparqlEndpointConfig(endpoint.base + path)
                assertTrue(strict.strictContentType)
                SparqlRepository(strict).use { repo ->
                    val e = assertThrows(RdfQueryException::class.java, { repo.select(selectAll) }, path)
                    assertTrue(e.message!!.contains("Content-Type '"), e.message)
                    assertTrue(e.message!!.contains("strictContentType"), e.message)
                }
                SparqlRepository(strict.copy(strictContentType = false)).use { repo ->
                    assertEquals(2, repo.select(selectAll).count(), path)
                    assertEquals(2, repo.withSelectRows(selectAll) { it.count() }, path)
                }
            }
            val ask = SparqlEndpointConfig("${endpoint.base}/ask")
            SparqlRepository(ask).use { repo -> assertThrows(RdfQueryException::class.java) { repo.ask(askAny) } }
            SparqlRepository(ask.copy(strictContentType = false)).use { repo -> assertTrue(repo.ask(askAny)) }
            // Switching the check off does not turn a non-JSON body into an empty result.
            SparqlRepository(SparqlEndpointConfig("${endpoint.base}/html", strictContentType = false)).use { repo ->
                val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
                assertFalse(e.message!!.contains("Content-Type"), e.message)
                assertThrows(RdfQueryException::class.java) { repo.withSelectRows(selectAll) { it.count() } }
                assertThrows(RdfQueryException::class.java) { repo.ask(askAny) }
            }

            val options = mapOf("location" to endpoint.url)
            assertTrue(SparqlEndpointConfig.fromOptions(options).strictContentType)
            assertFalse(SparqlEndpointConfig.fromOptions(options + ("strictContentType" to "false")).strictContentType)
            assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig.fromOptions(options + ("strictContentType" to "no")) }
            assertTrue(SparqlEndpointConfig(endpoint.url, strictContentType = false).toString().contains("strictContentType=false"))
        }
    }

    // ------------------------------------------------------------------ initial bindings

    @Test
    fun `a literal bound to a predicate or graph variable is rejected by the endpoint adapter and the Jena provider alike`() {
        val queries = listOf(
            "SELECT ?s ?o WHERE { ?s ?v ?o }",
            "SELECT ?s ?o WHERE { ?s <urn:p> ?o ; ?v ?z }",
            "SELECT ?s ?o WHERE { GRAPH ?v { ?s <urn:p> ?o } }",
        )
        val timeout = Duration.ofSeconds(30)
        LocalEndpoint { exchange, _ -> exchange.respond(200, rowsJson(0)) }.use { endpoint ->
            SparqlRepository(endpoint.url).use { http ->
                JenaRepository.MemoryRepository().use { jena ->
                    jena.editDefaultGraph().addTriple(RdfTriple(Iri("urn:a"), Iri("urn:p"), Literal("x")))
                    for (repo in listOf<RdfRepository>(http, jena)) {
                        val name = repo.javaClass.simpleName
                        for (query in queries) {
                            for (term in listOf<RdfTerm>(Literal("x"), LangString("x", "en"), Literal("1", Iri("http://www.w3.org/2001/XMLSchema#integer")))) {
                                val e = assertThrows(IllegalArgumentException::class.java, {
                                    repo.withSelectRows(SparqlSelectQuery(query), mapOf("v" to term), timeout) { it.count() }
                                }, "$name: $query with $term")
                                assertTrue(e.message!!.contains("only an IRI"), e.message)
                            }
                        }
                        // The same literal is fine as a subject or object.
                        repo.withSelectRows(SparqlSelectQuery("SELECT ?s WHERE { ?s <urn:p> ?v }"), mapOf("v" to Literal("x")), timeout) { it.count() }
                    }
                    assertEquals(
                        1,
                        jena.withSelectRows(SparqlSelectQuery(queries[0]), mapOf("v" to Iri("urn:p")), timeout) { it.count() },
                        "an IRI is still accepted as a predicate",
                    )
                }
                assertEquals(1, endpoint.requests.size, "rejected queries must not be sent")
                http.withSelectRows(SparqlSelectQuery(queries[0]), mapOf("v" to Iri("urn:p")), timeout) { it.count() }
                assertTrue(endpoint.requests.last().body.contains("?s <urn:p> ?o"), endpoint.requests.last().body)
            }
        }
    }
}
