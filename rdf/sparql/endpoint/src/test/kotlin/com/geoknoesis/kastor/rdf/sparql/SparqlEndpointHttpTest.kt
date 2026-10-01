package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.apache.jena.query.Dataset
import org.apache.jena.query.DatasetFactory
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.ResultSetFormatter
import org.apache.jena.update.UpdateAction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class SparqlEndpointHttpTest {

    private class Recorded(
        val method: String,
        val contentType: String?,
        val headers: Map<String, String>,
        val rawQuery: String?,
        val body: String,
        val remotePort: Int,
    )

    /** Local HTTP endpoint recording every request. */
    private class TestEndpoint(private val handler: (HttpExchange, Recorded) -> Unit) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Recorded>()
        private val pool = Executors.newFixedThreadPool(4)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/") { exchange ->
                try {
                    val recorded = Recorded(
                        method = exchange.requestMethod,
                        contentType = exchange.requestHeaders.getFirst("Content-Type"),
                        headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                        rawQuery = exchange.requestURI.rawQuery,
                        body = exchange.requestBody.readBytes().toString(Charsets.UTF_8),
                        remotePort = exchange.remoteAddress.port,
                    )
                    requests += recorded
                    handler(exchange, recorded)
                } catch (_: Exception) {
                    // Client disconnects (deadline tests) surface here.
                } finally {
                    exchange.close()
                }
            }
            start()
        }
        val url: String get() = "http://127.0.0.1:${server.address.port}/sparql"
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

    /** SPARQL 1.1 Protocol handler backed by a Jena dataset (POST, POST form and GET). */
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

    private fun rowsJson(n: Int) = buildString {
        append("{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[")
        repeat(n) {
            if (it > 0) append(',')
            append("{\"x\":{\"type\":\"literal\",\"value\":\"row-$it\"}}")
        }
        append("]}}")
    }

    private fun basic(credentials: String) = "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray())

    private val selectAll = SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")

    @Test
    fun `HTTP error status and body are surfaced`(): Unit = TestEndpoint { exchange, _ ->
        exchange.respond(503, "backend exploded: details", "text/plain")
    }.use { endpoint ->
        SparqlRepository(endpoint.url).use { repo ->
            for (call in listOf<() -> Unit>(
                { repo.select(selectAll) },
                { repo.ask(SparqlAskQuery("ASK {}")) },
                { repo.update(UpdateQuery("CLEAR DEFAULT")) },
                { repo.withSelectRows(selectAll) { it.count() } },
            )) {
                val e = assertThrows(RdfQueryException::class.java) { call() }
                assertTrue(e.message!!.contains("503") && e.message!!.contains("backend exploded: details"), e.message)
            }
        }
    }

    @Test
    fun `buffered responses are capped while streamed rows use their own limit`(): Unit = TestEndpoint { exchange, _ ->
        exchange.respond(200, rowsJson(200))
    }.use { endpoint ->
        val config = SparqlEndpointConfig(endpoint.url, maxResponseBytes = 1_000)
        SparqlRepository(config).use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            assertTrue(e.message!!.contains("exceeds 1000 bytes"), e.message)
            assertEquals(200, repo.withSelectRows(selectAll) { it.count() })
        }
        SparqlRepository(config.copy(maxStreamedResponseBytes = 1_000)).use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.withSelectRows(selectAll) { it.count() } }
            assertTrue(e.message!!.contains("exceeds 1000 bytes"), e.message)
        }
    }

    @Test
    fun `overall deadline aborts a response that keeps trickling within the read timeout`(): Unit = TestEndpoint { exchange, _ ->
        exchange.responseHeaders.add("Content-Type", "application/sparql-results+json")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { out ->
            // Keeps trickling for far longer than any slack: only the deadline can end the call.
            repeat((STALL_MILLIS / 100).toInt()) {
                out.write(' '.code)
                out.flush()
                Thread.sleep(100)
            }
        }
    }.use { endpoint ->
        val deadline = Duration.ofMillis(700)
        val config = SparqlEndpointConfig(endpoint.url, readTimeout = Duration.ofSeconds(60), requestTimeout = deadline)
        SparqlRepository(config).use { repo ->
            val start = System.nanoTime()
            val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            assertTrue(e.message!!.contains("exceeded its 700 ms deadline"), e.message)
            assertEndedAtLimit(deadline, start, "request deadline")
        }
    }

    @Test
    fun `result terms keep language datatype and blank node identifiers`(): Unit = TestEndpoint { exchange, _ ->
        exchange.respond(
            200,
            """{"head":{"vars":["l","t","b","p","u"]},"results":{"bindings":[{""" +
                """"l":{"type":"literal","value":"chat","xml:lang":"fr"},""" +
                """"t":{"type":"literal","value":"42","datatype":"http://www.w3.org/2001/XMLSchema#integer"},""" +
                """"b":{"type":"bnode","value":"nodeID://b1"},""" +
                """"p":{"type":"literal","value":"plain"},""" +
                """"u":{"type":"uri","value":"urn:x"}}]}}"""
        )
    }.use { endpoint ->
        SparqlRepository(endpoint.url).use { repo ->
            val row = repo.select(selectAll).first()!!
            assertEquals(LangString("chat", "fr"), row.get("l"))
            assertEquals(Literal("42", XSD.integer), row.get("t"))
            assertEquals(BlankNode("nodeID://b1"), row.get("b"))
            assertEquals(Literal("plain"), row.get("p"))
            assertEquals(Iri("urn:x"), row.get("u"))
        }
    }

    @Test
    fun `exceptions thrown by the row consumer propagate unchanged`(): Unit = TestEndpoint { exchange, _ ->
        exchange.respond(200, rowsJson(3))
    }.use { endpoint ->
        SparqlRepository(endpoint.url).use { repo ->
            val e = assertThrows(IllegalStateException::class.java) {
                repo.withSelectRows(selectAll) { rows ->
                    rows.first()
                    error("consumer failed")
                }
            }
            assertEquals("consumer failed", e.message)
        }
    }

    @Test
    fun `non-http endpoint urls are rejected at construction`() {
        for (url in listOf("ftp://example.org/sparql", "file:///tmp/sparql", "mailto:someone@example.org", "not a url", "http:///no-host")) {
            assertThrows(IllegalArgumentException::class.java, { SparqlRepository(url) }, url)
        }
    }

    @Test
    fun `credentials are redacted from the configuration string`() {
        val text = SparqlEndpointConfig("https://alice:hunter2@example.org/sparql", username = "bob", password = "s3cret").toString()
        assertFalse(text.contains("hunter2") || text.contains("s3cret"), text)
    }

    @Test
    fun `custom headers credentials and request methods are applied`(): Unit = TestEndpoint { exchange, _ ->
        exchange.respond(200, rowsJson(1))
    }.use { endpoint ->
        val authority = endpoint.url.removePrefix("http://")
        val sparql = "SELECT * WHERE { ?s ?p \"a b&c=d\" }"
        SparqlRepository(
            SparqlEndpointConfig(
                "http://alice:s%3Acret@$authority",
                headers = mapOf("X-Api-Key" to "k1"),
                queryMethod = SparqlQueryMethod.GET,
            )
        ).use { it.select(SparqlSelectQuery(sparql)) }
        val get = endpoint.requests.single()
        assertEquals("GET", get.method)
        assertEquals("k1", get.headers["x-api-key"])
        assertEquals(basic("alice:s:cret"), get.headers["authorization"])
        assertEquals(sparql, URLDecoder.decode(get.rawQuery!!.removePrefix("query="), Charsets.UTF_8))

        SparqlRepository(
            SparqlEndpointConfig(endpoint.url, username = "bob", password = "pw", queryMethod = SparqlQueryMethod.POST_FORM)
        ).use { it.select(SparqlSelectQuery(sparql)) }
        val form = endpoint.requests.last()
        assertEquals("POST", form.method)
        assertEquals("application/x-www-form-urlencoded", form.contentType)
        assertEquals(sparql, URLDecoder.decode(form.body.removePrefix("query="), Charsets.UTF_8))
        assertEquals(basic("bob:pw"), form.headers["authorization"])
    }

    @Test
    fun `connections are kept alive across requests`(): Unit = TestEndpoint { exchange, _ ->
        exchange.respond(200, """{"head":{},"boolean":true}""")
    }.use { endpoint ->
        SparqlRepository(endpoint.url).use { repo -> repeat(3) { assertTrue(repo.ask(SparqlAskQuery("ASK {}"))) } }
        assertEquals(1, endpoint.requests.map { it.remotePort }.distinct().size, "requests used separate connections")
    }

    @Test
    fun `inserts are batched and removals avoid per-triple round trips`() {
        val dataset = DatasetFactory.create()
        TestEndpoint(jena(dataset)).use { endpoint ->
            SparqlRepository(SparqlEndpointConfig(endpoint.url, insertBatchSize = 5)).use { repo ->
                val graph = repo.editGraph(Iri("urn:g"))
                val triples = (0 until 12).map { RdfTriple(Iri("urn:s$it"), Iri("urn:p"), Literal("v\"$it\n")) }
                graph.addTriples(triples)
                assertEquals(3, endpoint.requests.count { it.contentType == "application/sparql-update" })
                assertEquals(12, graph.size())

                endpoint.requests.clear()
                val missing = RdfTriple(Iri("urn:missing"), Iri("urn:p"), Literal("x"))
                assertTrue(graph.removeTriples(triples.take(4) + missing))
                assertEquals(2, endpoint.requests.size, "expected one ASK and one DELETE DATA")
                assertEquals(8, graph.size())
                assertFalse(graph.removeTriples(triples.take(4)))
            }
        }
        dataset.close()
    }

    @Test
    fun `blank nodes follow one rule and endpoint-assigned labels can be copied back`() {
        val dataset = DatasetFactory.create()
        TestEndpoint(jena(dataset)).use { endpoint ->
            SparqlRepository(SparqlEndpointConfig(endpoint.url, insertBatchSize = 2)).use { repo ->
                val graph = repo.editDefaultGraph()
                graph.addTriple(RdfTriple(BlankNode("a"), Iri("urn:p"), Literal("single")))

                endpoint.requests.clear()
                val virtuoso = BlankNode("nodeID://b1")
                graph.addTriples(listOf(
                    RdfTriple(virtuoso, Iri("urn:p"), Literal("x")),
                    RdfTriple(virtuoso, Iri("urn:q"), Literal("y")),
                    RdfTriple(Iri("urn:s"), Iri("urn:r"), virtuoso),
                ))
                assertEquals(1, endpoint.requests.size, "blank-node data must not be split across requests")
                assertEquals(4, graph.size())
                assertTrue(repo.ask(SparqlAskQuery("ASK { ?b <urn:p> \"x\" ; <urn:q> \"y\" . <urn:s> <urn:r> ?b }")))

                val found = graph.find(null, Iri("urn:q"), null).single()
                val e = assertThrows(IllegalArgumentException::class.java) { graph.removeTriple(found) }
                assertTrue(e.message!!.contains("DELETE WHERE"), e.message)
            }
        }
        dataset.close()
    }

    @Test
    fun `bound and timed select rows are supported`() {
        val dataset = DatasetFactory.create()
        TestEndpoint(jena(dataset)).use { endpoint ->
            SparqlRepository(endpoint.url).use { repo ->
                repo.editDefaultGraph().addTriples((1..3).map { RdfTriple(Iri("urn:s$it"), Iri("urn:p"), Literal("v$it")) })
                val values = repo.withSelectRows(
                    SparqlSelectQuery("SELECT ?s ?o WHERE { ?s <urn:p> ?o }"),
                    mapOf("s" to Iri("urn:s2")),
                    Duration.ofSeconds(10),
                ) { rows -> rows.map { it.get("o") }.toList() }
                assertEquals(listOf<RdfTerm>(Literal("v2")), values)
                assertThrows(IllegalArgumentException::class.java) {
                    repo.withSelectRows(selectAll, mapOf("s } #" to Iri("urn:x")), Duration.ofSeconds(1)) { it.count() }
                }
            }
        }
        dataset.close()
    }

    @Test
    fun `provider passes options and reports the same truthful capabilities as the repository`(): Unit = TestEndpoint { exchange, _ ->
        exchange.respond(200, rowsJson(1))
    }.use { endpoint ->
        val provider = SparqlProvider()
        val options = mapOf(
            "location" to endpoint.url,
            "header.X-Trace" to "t1",
            "queryMethod" to "get",
            "requestTimeoutMillis" to "1234",
            "maxResponseBytes" to "4096",
            "maxStreamedResponseBytes" to "none",
            "insertBatchSize" to "7",
        )
        val repo = provider.createRepository("sparql", RdfConfig(options = options)) as SparqlRepository
        repo.use {
            assertEquals(Duration.ofMillis(1234), it.config.requestTimeout)
            assertEquals(4096L, it.config.maxResponseBytes)
            assertNull(it.config.maxStreamedResponseBytes)
            assertEquals(7, it.config.insertBatchSize)
            it.select(selectAll)
        }
        val request = endpoint.requests.single()
        assertEquals("GET", request.method)
        assertEquals("t1", request.headers["x-trace"])

        val capabilities = provider.getCapabilities("sparql")
        assertEquals(capabilities, repo.getCapabilities())
        assertFalse(capabilities.supportsRdfStar)
        assertFalse(capabilities.supportsTripleTerms)
        assertFalse(capabilities.supportsFederation)
        assertEquals("1.1", capabilities.sparqlVersion)

        assertThrows(IllegalArgumentException::class.java) {
            provider.createRepository("sparql", RdfConfig(options = mapOf("location" to endpoint.url, "connectTimeoutMillis" to "soon")))
        }
        assertEquals(capabilities, provider.getCapabilities(null))
    }

    @Test
    fun `registry discovers the sparql provider and its detailed capabilities are truthful`() {
        val provider = RdfProviderRegistry.getProvider("sparql")
        assertTrue(provider is SparqlProvider, "expected SparqlProvider via ServiceLoader, got $provider")
        val detailed = provider!!.getDetailedCapabilities(provider.defaultVariantId())
        assertEquals(ProviderCategory.SPARQL_ENDPOINT, detailed.providerCategory)
        assertEquals(provider.getCapabilities(provider.defaultVariantId()), detailed.basic)
        assertEquals(false, detailed.supportedSparqlFeatures["RDF-star"])
        assertEquals(false, detailed.supportedSparqlFeatures["Federation"])
        assertEquals(true, detailed.supportedSparqlFeatures["Property Paths"])
        assertFalse(detailed.basic.supportsVersionDeclaration)
        assertTrue(RdfProviderRegistry.supportsFeature("sparql", "Named Graphs"))
    }
}
