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

/** Regression tests for the second audit of the SPARQL HTTP endpoint adapter. */
class SparqlEndpointReauditTest {

    private class Recorded(
        val method: String,
        val path: String,
        val contentType: String?,
        val headers: Map<String, String>,
        val rawQuery: String?,
        val body: String,
    )

    private class TestEndpoint(private val handler: (HttpExchange, Recorded) -> Unit) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Recorded>()
        private val pool = Executors.newFixedThreadPool(4)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/") { exchange ->
                try {
                    val recorded = Recorded(
                        method = exchange.requestMethod,
                        path = exchange.requestURI.rawPath,
                        contentType = exchange.requestHeaders.getFirst("Content-Type"),
                        headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                        rawQuery = exchange.requestURI.rawQuery,
                        body = exchange.requestBody.readBytes().toString(Charsets.UTF_8),
                    )
                    requests += recorded
                    handler(exchange, recorded)
                } catch (_: Exception) {
                    // Client disconnects surface here.
                } finally {
                    exchange.close()
                }
            }
            start()
        }
        val base: String get() = "http://127.0.0.1:${server.address.port}"
        val url: String get() = "$base/sparql"
        override fun close() {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    private fun HttpExchange.respond(status: Int, body: String, type: String = "application/sparql-results+json") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", type)
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.redirect(status: Int, location: String) {
        responseHeaders.add("Location", location)
        sendResponseHeaders(status, -1)
    }

    private fun jena(dataset: Dataset): (HttpExchange, Recorded) -> Unit = { exchange, request ->
        try {
            val form = if (request.contentType == "application/x-www-form-urlencoded") request.body else request.rawQuery.orEmpty()
            val params = form.split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            val update = if (request.contentType == "application/sparql-update") request.body else params["update"]
            if (update != null) {
                UpdateAction.parseExecute(update, dataset)
                exchange.respond(200, "")
            } else {
                val query = if (request.contentType == "application/sparql-query") request.body else params.getValue("query")
                QueryExecutionFactory.create(query, dataset).use { execution ->
                    val out = ByteArrayOutputStream()
                    if (execution.query.isAskType) ResultSetFormatter.outputAsJSON(out, execution.execAsk())
                    else ResultSetFormatter.outputAsJSON(out, execution.execSelect())
                    exchange.respond(200, out.toString(Charsets.UTF_8))
                }
            }
        } catch (e: Exception) {
            exchange.respond(400, e.toString(), "text/plain")
        }
    }

    private fun rowsJson(n: Int, value: (Int) -> String = { "row-$it" }) = buildString {
        append("{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[")
        repeat(n) {
            if (it > 0) append(',')
            append("{\"x\":{\"type\":\"literal\",\"value\":\"${value(it)}\"}}")
        }
        append("]}}")
    }

    private val selectAll = SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")

    private fun elapsedMillis(start: Long) = (System.nanoTime() - start) / 1_000_000

    // ------------------------------------------------------------------ initial bindings

    private fun key(term: RdfTerm?): String = when (term) {
        null -> "UNDEF"
        is Iri -> term.value
        is LangString -> "${term.lexical}@${term.lang.lowercase()}"
        is Literal -> if (term.datatype == XSD.string) term.lexical else "${term.lexical}^^${term.datatype.value}"
        else -> term.toString()
    }

    private fun key(node: RDFNode): String = when {
        node.isURIResource -> node.asResource().uri
        node.isLiteral -> node.asLiteral().let { l ->
            when {
                l.language.isNotEmpty() -> "${l.lexicalForm}@${l.language.lowercase()}"
                l.datatypeURI == XSD.string.value -> l.lexicalForm
                else -> "${l.lexicalForm}^^${l.datatypeURI}"
            }
        }
        else -> node.toString()
    }

    private fun jenaSubstitution(dataset: Dataset, query: String, bindings: Map<String, RDFNode>): List<Map<String, String>> {
        val initial = QuerySolutionMap()
        bindings.forEach { (name, node) -> initial.add(name, node) }
        return QueryExecution.dataset(dataset).query(query).substitution(initial).build().use { exec ->
            exec.execSelect().asSequence().map { row -> row.varNames().asSequence().associateWith { key(row.get(it)) } }.toList()
        }.sortedBy { it.toString() }
    }

    @Test
    fun `initial bindings restrict the query exactly like Jena substitution`() {
        val dataset = DatasetFactory.create()
        RDFParser.fromString(
            "<urn:a> <urn:p> 1 . <urn:a> <urn:p> 2 . <urn:b> <urn:p> 3 . <urn:a> <urn:l> \"x\"@en . <urn:a> <urn:l> \"y\"@fr . " +
                "<urn:b> <urn:l> \"z\"@en . <urn:b> <urn:q> 9 .",
            Lang.TURTLE,
        ).parse(dataset.asDatasetGraph())
        val a = Iri("urn:a") to ResourceFactory.createResource("urn:a")
        val b = Iri("urn:b") to ResourceFactory.createResource("urn:b")
        val en = Literal("en") to ResourceFactory.createPlainLiteral("en")
        val cases = listOf(
            "aggregate COUNT" to ("SELECT (COUNT(*) AS ?c) WHERE { ?s <urn:p> ?o }" to mapOf("s" to a)),
            "GROUP BY bound variable" to ("SELECT ?s (COUNT(*) AS ?c) WHERE { ?s <urn:p> ?o } GROUP BY ?s" to mapOf("s" to a)),
            "FILTER with bound variable" to ("SELECT ?s ?l WHERE { ?s <urn:l> ?l FILTER(lang(?l) = ?lang) }" to mapOf("lang" to en)),
            "sub-select with LIMIT" to ("SELECT ?s ?o WHERE { { SELECT ?s WHERE { ?s <urn:p> ?x } LIMIT 1 } ?s <urn:p> ?o }" to mapOf("s" to b)),
            "OPTIONAL" to ("SELECT ?s ?z WHERE { ?s <urn:p> ?o OPTIONAL { ?s <urn:q> ?z } }" to mapOf("s" to b)),
            "MINUS" to ("SELECT ?s ?o WHERE { ?s <urn:p> ?o MINUS { ?s <urn:q> ?z } }" to mapOf("s" to b)),
            "NOT EXISTS" to ("SELECT ?s ?o WHERE { ?s <urn:p> ?o FILTER NOT EXISTS { ?s <urn:q> ?z } }" to mapOf("s" to b)),
            "projection with prefixes comments and strings" to (
                "PREFIX ex: <urn:>\n# ?s in a comment\nSELECT DISTINCT ?s ?o (\"?s\" AS ?txt) WHERE { ?s ex:p ?o . FILTER(?o != \"?s\") } ORDER BY ?s ?o" to
                    mapOf("s" to a)),
            "SELECT star" to ("SELECT * WHERE { ?s <urn:p> ?o }" to mapOf("s" to a)),
            "dollar variables" to ("SELECT \$s ?o WHERE { \$s <urn:p> ?o }" to mapOf("s" to b)),
            "unused binding" to ("SELECT ?o WHERE { ?x <urn:p> ?o }" to mapOf("s" to b)),
        )
        TestEndpoint(jena(dataset)).use { endpoint ->
            SparqlRepository(endpoint.url).use { repo ->
                for ((name, case) in cases) {
                    val (query, bindings) = case
                    val expected = jenaSubstitution(dataset, query, bindings.mapValues { it.value.second })
                    val actual = repo.withSelectRows(SparqlSelectQuery(query), bindings.mapValues { it.value.first }, Duration.ofSeconds(10)) { rows ->
                        rows.map { row -> row.getVariableNames().associateWith { key(row.get(it)) } }.toList()
                    }.sortedBy { it.toString() }
                    assertEquals(expected, actual, "$name:\n${endpoint.requests.last().body}")
                }
            }
        }
        dataset.close()
    }

    @Test
    fun `initial bindings that cannot be substituted are rejected before sending`() = TestEndpoint { exchange, _ ->
        exchange.respond(200, rowsJson(0))
    }.use { endpoint ->
        SparqlRepository(endpoint.url).use { repo ->
            val bound = mapOf<String, RdfTerm>("s" to Iri("urn:a"))
            listOf(
                "SELECT ?s WHERE { BIND(<urn:a> AS ?s) }",
                "SELECT (?o AS ?s) WHERE { ?x <urn:p> ?o }",
                "SELECT ?s WHERE { VALUES ?s { <urn:a> } }",
                "SELECT ?o WHERE { ?x <urn:p> ?o } VALUES (?o ?s) { (1 <urn:a>) }",
                "SELECT ?o ?c WHERE { ?s <urn:p> ?o { SELECT (COUNT(?s) AS ?c) WHERE { ?s <urn:p> ?x } } }",
            ).forEach { query ->
                assertThrows(IllegalArgumentException::class.java, {
                    repo.withSelectRows(SparqlSelectQuery(query), bound, Duration.ofSeconds(1)) { it.count() }
                }, query)
            }
            assertThrows(IllegalArgumentException::class.java) {
                repo.withSelectRows(selectAll, mapOf("s" to BlankNode("b")), Duration.ofSeconds(1)) { it.count() }
            }
            assertThrows(IllegalArgumentException::class.java) {
                repo.withSelectRows(SparqlAskQuery("ASK { ?s ?p ?o }").let { SparqlSelectQuery(it.sparql) }, bound, Duration.ofSeconds(1)) { it.count() }
            }
        }
        assertTrue(endpoint.requests.isEmpty())
    }

    // ------------------------------------------------------------------ redirects

    @Test
    fun `cross-origin redirects are refused by default and never receive headers or credentials`() {
        TestEndpoint { exchange, _ -> exchange.respond(200, rowsJson(1)) }.use { other ->
            TestEndpoint { exchange, _ -> exchange.redirect(307, other.url) }.use { origin ->
                val config = SparqlEndpointConfig(origin.url, headers = mapOf("X-Api-Key" to "secret-key"), username = "u", password = "pw")
                SparqlRepository(config).use { repo ->
                    val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
                    assertTrue(e.message!!.contains("another origin"), e.message)
                }
                assertTrue(other.requests.isEmpty())

                SparqlRepository(config.copy(followCrossOriginRedirects = true)).use { repo ->
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
    fun `same-origin redirects keep method body and headers and POST is never downgraded`() = TestEndpoint { exchange, request ->
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

    // ------------------------------------------------------------------ timeouts

    @Test
    fun `request deadline aborts a stalled fixed-length body`() = TestEndpoint { exchange, _ ->
        exchange.sendResponseHeaders(200, 1_000)
        exchange.responseBody.write("{\"head\":{".toByteArray())
        exchange.responseBody.flush()
        Thread.sleep(6_000)
    }.use { endpoint ->
        SparqlRepository(SparqlEndpointConfig(endpoint.url, readTimeout = Duration.ofSeconds(30), requestTimeout = Duration.ofMillis(700))).use { repo ->
            val start = System.nanoTime()
            val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            assertTrue(elapsedMillis(start) < 3_000, "deadline not enforced, took ${elapsedMillis(start)} ms")
            assertTrue(e.message!!.contains("deadline"), e.message)
        }
        SparqlRepository(SparqlEndpointConfig(endpoint.url, readTimeout = Duration.ofMillis(500), requestTimeout = null)).use { repo ->
            val start = System.nanoTime()
            val e = assertThrows(RdfQueryException::class.java) { repo.ask(SparqlAskQuery("ASK {}")) }
            assertTrue(elapsedMillis(start) < 3_000, "read timeout not enforced, took ${elapsedMillis(start)} ms")
            assertTrue(e.message!!.contains("timed out"), e.message)
        }
    }

    @Test
    fun `request timeout covers stream headers but not slow consumers while a per-call timeout covers the whole stream`() = TestEndpoint { exchange, request ->
        if (request.path == "/slow-headers") {
            Thread.sleep(2_000)
            exchange.respond(200, rowsJson(1))
        } else if (request.path == "/burst") {
            exchange.respond(200, rowsJson(5))
        } else {
            exchange.responseHeaders.add("Content-Type", "application/sparql-results+json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                out.write("{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[".toByteArray())
                repeat(5) {
                    if (it > 0) out.write(",".toByteArray())
                    out.write("{\"x\":{\"type\":\"literal\",\"value\":\"$it\"}}".toByteArray())
                    out.flush()
                    Thread.sleep(200)
                }
                out.write("]}}".toByteArray())
            }
        }
    }.use { endpoint ->
        val config = SparqlEndpointConfig(endpoint.url, requestTimeout = Duration.ofMillis(400))
        SparqlRepository(config).use { repo ->
            val rows = repo.withSelectRows(selectAll) { seq -> seq.onEach { Thread.sleep(150) }.count() }
            assertEquals(5, rows)
            // An explicit per-call timeout bounds the whole call, like the Jena and RDF4J providers:
            // rows trickling in after the deadline fail the call.
            val start = System.nanoTime()
            val e = assertThrows(RdfQueryException::class.java) {
                repo.withSelectRows(selectAll, emptyMap(), Duration.ofMillis(400)) { it.count() }
            }
            assertTrue(elapsedMillis(start) < 900, "per-call timeout not enforced on the stream, took ${elapsedMillis(start)} ms")
            assertTrue(e.message!!.contains("deadline"), e.message)
            assertEquals(5, repo.withSelectRows(selectAll, emptyMap(), Duration.ofSeconds(10)) { it.count() })
            // ...and time the consumer spends on rows that are already buffered.
            SparqlRepository(config.copy(endpoint = "${endpoint.base}/burst")).use { burst ->
                val slow = assertThrows(RdfQueryException::class.java) {
                    burst.withSelectRows(selectAll, emptyMap(), Duration.ofMillis(400)) { seq -> seq.onEach { Thread.sleep(150) }.count() }
                }
                assertTrue(slow.message!!.contains("deadline"), slow.message)
            }
            // Buffered select keeps the whole-request deadline.
            assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
        }
        SparqlRepository(config.copy(streamingRequestTimeout = Duration.ofMillis(400))).use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.withSelectRows(selectAll) { it.count() } }
            assertTrue(e.message!!.contains("deadline"), e.message)
        }
        SparqlRepository(SparqlEndpointConfig("${endpoint.base}/slow-headers", requestTimeout = Duration.ofMillis(300))).use { repo ->
            val start = System.nanoTime()
            val e = assertThrows(RdfQueryException::class.java) { repo.withSelectRows(selectAll) { it.count() } }
            assertTrue(elapsedMillis(start) < 1_500, "header deadline not enforced, took ${elapsedMillis(start)} ms")
            assertTrue(e.message!!.contains("deadline"), e.message)
        }
    }

    @Test
    fun `a failed update is never re-sent automatically`() = TestEndpoint { exchange, request ->
        if (request.contentType == "application/sparql-update") {
            // Drop the connection without answering, as a crashing proxy would.
        } else {
            exchange.respond(200, """{"head":{},"boolean":true}""")
        }
    }.use { endpoint ->
        SparqlRepository(endpoint.url).use { repo ->
            assertTrue(repo.ask(SparqlAskQuery("ASK {}")))
            assertThrows(RdfQueryException::class.java) { repo.update(UpdateQuery("INSERT DATA { <urn:a> <urn:b> <urn:c> }")) }
        }
        Thread.sleep(300)
        assertEquals(1, endpoint.requests.count { it.contentType == "application/sparql-update" })
    }

    // ------------------------------------------------------------------ misc endpoint behaviour

    @Test
    fun `long GET queries fall back to a form POST`() = TestEndpoint { exchange, _ ->
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
    fun `every operation on a closed repository fails the same way`() = TestEndpoint { exchange, _ ->
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
    fun `blank node components are batched without ever being split`() {
        val dataset = DatasetFactory.create()
        TestEndpoint(jena(dataset)).use { endpoint ->
            SparqlRepository(SparqlEndpointConfig(endpoint.url, insertBatchSize = 3)).use { repo ->
                val graph = repo.editDefaultGraph()
                val p = Iri("urn:p")
                val a = BlankNode("a")
                val b = BlankNode("b")
                val c = BlankNode("c")
                val triples = listOf(
                    RdfTriple(Iri("urn:g1"), p, Literal("1")),
                    RdfTriple(a, p, Literal("a1")),
                    RdfTriple(Iri("urn:g2"), p, Literal("2")),
                    RdfTriple(a, p, b),
                    RdfTriple(c, p, Literal("c1")),
                    RdfTriple(b, p, Literal("b1")),
                    RdfTriple(Iri("urn:g3"), p, Literal("3")),
                    RdfTriple(c, p, Literal("c2")),
                    RdfTriple(Iri("urn:g4"), p, Literal("4")),
                )
                graph.addTriples(triples)
                val updates = endpoint.requests.filter { it.contentType == "application/sparql-update" }
                assertTrue(updates.size > 1, "expected several batches, got ${updates.size}")
                assertEquals(9, graph.size())
                // The a-b chain and the c pair are still connected on the server.
                assertTrue(repo.ask(SparqlAskQuery("ASK { ?a <urn:p> \"a1\" ; <urn:p> ?b . ?b <urn:p> \"b1\" }")))
                assertTrue(repo.ask(SparqlAskQuery("ASK { ?c <urn:p> \"c1\" , \"c2\" }")))
                assertEquals(3, repo.select(SparqlSelectQuery("SELECT DISTINCT ?x WHERE { ?x <urn:p> ?v FILTER(isBlank(?x)) }")).count())

                endpoint.requests.clear()
                SparqlRepository(SparqlEndpointConfig(endpoint.url, insertBatchSize = 3, maxBlankNodeComponentTriples = 2)).use { small ->
                    val e = assertThrows(IllegalArgumentException::class.java) {
                        small.editDefaultGraph().addTriples(listOf(RdfTriple(Iri("urn:x"), p, Literal("x"))) + triples)
                    }
                    assertTrue(e.message!!.contains("maxBlankNodeComponentTriples"), e.message)
                }
                assertTrue(endpoint.requests.isEmpty(), "nothing may be sent when a component is too large")
            }
        }
        dataset.close()
    }

    @Test
    fun `literal text with backslash-u sequences round-trips through the endpoint`() {
        val dataset = DatasetFactory.create()
        TestEndpoint(jena(dataset)).use { endpoint ->
            SparqlRepository(endpoint.url).use { repo ->
                val graph = repo.editDefaultGraph()
                val value = Literal("\\u0022 quoted \\U0001F600 path C:\\users")
                graph.addTriple(RdfTriple(Iri("urn:s"), Iri("urn:p"), value))
                assertEquals(value, graph.find(Iri("urn:s"), Iri("urn:p"), null).single().obj)
                assertTrue(graph.hasTriple(RdfTriple(Iri("urn:s"), Iri("urn:p"), value)))
            }
        }
        dataset.close()
    }

    // ------------------------------------------------------------------ third audit

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `without a request timeout the wait for response headers is bounded by the read timeout`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val accepted = CopyOnWriteArrayList<Socket>()
            Thread {
                try {
                    while (true) accepted += server.accept()
                } catch (_: IOException) {
                    // server closed
                }
            }.apply { isDaemon = true }.start()
            try {
                val config = SparqlEndpointConfig(
                    "http://127.0.0.1:${server.localPort}/sparql",
                    readTimeout = Duration.ofMillis(500),
                    requestTimeout = null,
                )
                SparqlRepository(config).use { repo ->
                    listOf<Pair<String, () -> Any>>(
                        "select" to { repo.select(selectAll) },
                        "stream" to { repo.withSelectRows(selectAll) { it.count() } },
                        "ask" to { repo.ask(SparqlAskQuery("ASK {}")) },
                        "update" to { repo.update(UpdateQuery("CLEAR DEFAULT")) },
                    ).forEach { (name, call) ->
                        val start = System.nanoTime()
                        val e = assertThrows(RdfQueryException::class.java, { call() }, name)
                        assertTrue(elapsedMillis(start) < 3_000, "$name: header wait not bounded, took ${elapsedMillis(start)} ms")
                        assertTrue(e.message!!.contains("timed out"), "$name: ${e.message}")
                    }
                }
            } finally {
                accepted.forEach { it.close() }
            }
        }
    }

    @Test
    fun `graph writes validate every batch before anything is sent`() = TestEndpoint { exchange, request ->
        if (request.contentType == "application/sparql-update") exchange.respond(200, "") else exchange.respond(200, "{\"boolean\":true}")
    }.use { endpoint ->
        SparqlRepository(SparqlEndpointConfig(endpoint.url, insertBatchSize = 1)).use { repo ->
            val graph = repo.editDefaultGraph()
            val p = Iri("urn:p")
            val valid = RdfTriple(Iri("urn:s"), p, Literal("ok"))
            val directional = RdfTriple(Iri("urn:s"), p, LangString("x", "ar", Direction.RTL))
            assertThrows(IllegalArgumentException::class.java) {
                graph.addTriples(listOf(valid, RdfTriple(Iri("urn:s2"), p, Literal("ok")), directional))
            }
            assertThrows(IllegalArgumentException::class.java) { graph.removeTriples(listOf(valid, directional)) }
            assertThrows(IllegalArgumentException::class.java) {
                graph.removeTriples(listOf(valid, RdfTriple(BlankNode("b"), p, Literal("x"))))
            }
            assertTrue(endpoint.requests.isEmpty(), "nothing may be sent: ${endpoint.requests.map { it.body }}")
        }
    }

    @Test
    fun `a failed insert batch reports how many triples earlier batches inserted`() {
        val updates = AtomicInteger()
        TestEndpoint { exchange, _ ->
            if (updates.incrementAndGet() == 2) exchange.respond(500, "boom", "text/plain") else exchange.respond(200, "")
        }.use { endpoint ->
            SparqlRepository(SparqlEndpointConfig(endpoint.url, insertBatchSize = 2)).use { repo ->
                val triples = (1..6).map { RdfTriple(Iri("urn:s$it"), Iri("urn:p"), Literal("$it")) }
                val e = assertThrows(RdfQueryException::class.java) { repo.editDefaultGraph().addTriples(triples) }
                assertTrue(e.message!!.contains("2 of 6"), e.message)
                assertTrue(e.message!!.contains("boom"), e.message)
                assertEquals(2, updates.get(), "no batch may be sent after a failure")
            }
        }
    }

    @Test
    fun `repositories with the same connect timeout share one HTTP client until the last one is closed`() = TestEndpoint { exchange, _ ->
        exchange.respond(200, rowsJson(1))
    }.use { endpoint ->
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

    @Test
    fun `parallel streams each enforce their own read timeout`() = TestEndpoint { exchange, request ->
        if (request.path == "/stall") {
            exchange.responseHeaders.add("Content-Type", "application/sparql-results+json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("{\"head\":".toByteArray())
            exchange.responseBody.flush()
            Thread.sleep(3_000)
        } else {
            exchange.respond(200, rowsJson(2_000))
        }
    }.use { endpoint ->
        val config = SparqlEndpointConfig(endpoint.url, readTimeout = Duration.ofMillis(500), requestTimeout = null)
        val pool = Executors.newFixedThreadPool(4)
        try {
            SparqlRepository(config).use { repo ->
                SparqlRepository(config.copy(endpoint = "${endpoint.base}/stall")).use { stalling ->
                    val fast = (1..3).map { pool.submit<Int> { (1..5).sumOf { repo.withSelectRows(selectAll) { rows -> rows.count() } } } }
                    val stalled = pool.submit<Long> {
                        val start = System.nanoTime()
                        val e = assertThrows(RdfQueryException::class.java) { stalling.withSelectRows(selectAll) { it.count() } }
                        assertTrue(e.message!!.contains("timed out"), e.message)
                        elapsedMillis(start)
                    }
                    fast.forEach { assertEquals(10_000, it.get(60, TimeUnit.SECONDS)) }
                    assertTrue(stalled.get(60, TimeUnit.SECONDS) < 2_500, "stalled stream not cut off in time")
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `streamed rows are parsed across buffer boundaries`() = TestEndpoint { exchange, _ ->
        exchange.respond(200, rowsJson(5_000) { "v\\\"$it\\\\ é€😀" })
    }.use { endpoint ->
        SparqlRepository(endpoint.url).use { repo ->
            val values = repo.withSelectRows(selectAll) { rows -> rows.map { (it.get("x") as Literal).lexical }.toList() }
            assertEquals(5_000, values.size)
            values.forEachIndexed { i, v -> assertEquals("v\"$i\\ é€😀", v) }
        }
    }
}
