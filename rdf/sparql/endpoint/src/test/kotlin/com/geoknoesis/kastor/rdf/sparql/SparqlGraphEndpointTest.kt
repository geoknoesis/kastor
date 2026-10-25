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

/** [SparqlGraph] over an endpoint: counts, batched writes, blank-node components and literal escaping. */
class SparqlGraphEndpointTest {

    @Test
    fun `graph size rejects missing and non-literal counts instead of reporting an empty graph`() {
        for (bindings in listOf(
            "[]",
            "[{}]",
            """[{"count":{"type":"uri","value":"urn:count"}}]""",
            """[{"count":{"type":"bnode","value":"count"}}]""",
        )) {
            LocalEndpoint { exchange, _ ->
                exchange.respond(200, """{"head":{"vars":["count"]},"results":{"bindings":$bindings}}""")
            }.use { endpoint ->
                SparqlRepository(endpoint.url).use { repo ->
                    for (graph in listOf(repo.defaultGraph, repo.getGraph(Iri("urn:graph")))) {
                        val error = assertThrows(RdfQueryException::class.java) { graph.size() }
                        assertTrue(error.message.orEmpty().contains("triple count"))
                    }
                }
            }
        }
    }

    @Test
    fun `graph size accepts an explicit zero count`() {
        LocalEndpoint { exchange, _ ->
            exchange.respond(200, """{"head":{"vars":["count"]},"results":{"bindings":[{"count":{"type":"literal","datatype":"http://www.w3.org/2001/XMLSchema#integer","value":"0"}}]}}""")
        }.use { endpoint ->
            SparqlRepository(endpoint.url).use { repo -> assertEquals(0, repo.defaultGraph.size()) }
        }
    }

    @Test
    fun `blank node components are batched without ever being split`() {
        val dataset = DatasetFactory.create()
        LocalEndpoint(jenaProtocolHandler(dataset)).use { endpoint ->
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
        LocalEndpoint(jenaProtocolHandler(dataset)).use { endpoint ->
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

    @Test
    fun `graph writes validate every batch before anything is sent`(): Unit = LocalEndpoint { exchange, request ->
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
        LocalEndpoint { exchange, _ ->
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
    fun `a graph size beyond Int range fails as a query error`(): Unit = LocalEndpoint { exchange, _ ->
        exchange.respond(
            200,
            "{\"head\":{\"vars\":[\"count\"]},\"results\":{\"bindings\":[{\"count\":{\"type\":\"literal\"," +
                "\"datatype\":\"http://www.w3.org/2001/XMLSchema#integer\",\"value\":\"3000000000\"}}]}}",
        )
    }.use { endpoint ->
        SparqlRepository(endpoint.url).use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.defaultGraph.size() }
            assertTrue(e.message!!.contains("3000000000"), e.message)
        }
    }
}
