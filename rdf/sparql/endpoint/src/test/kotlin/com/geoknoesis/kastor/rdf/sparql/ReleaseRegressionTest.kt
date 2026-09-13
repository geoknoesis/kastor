package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.sun.net.httpserver.HttpServer
import org.apache.jena.query.*
import org.apache.jena.update.UpdateAction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class ReleaseRegressionTest {
    private fun <T> endpoint(response: ((String, String) -> String)? = null, run: (SparqlRepository) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newSingleThreadExecutor()
        val dataset = DatasetFactory.create()
        server.executor = pool
        server.createContext("/") { exchange ->
            try {
                val body = exchange.requestBody.reader().readText()
                val kind = exchange.requestHeaders.getFirst("Content-Type")
                val text = response?.invoke(kind, body) ?: if (kind == "application/sparql-update") {
                    UpdateAction.parseExecute(body, dataset); ""
                } else QueryExecutionFactory.create(body, dataset).use { query ->
                    val out = java.io.ByteArrayOutputStream()
                    if (body.trimStart().startsWith("ASK")) ResultSetFormatter.outputAsJSON(out, query.execAsk())
                    else ResultSetFormatter.outputAsJSON(out, query.execSelect())
                    out.toString(Charsets.UTF_8)
                }
                val bytes = text.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (e: Exception) {
                val bytes = e.toString().toByteArray()
                exchange.sendResponseHeaders(400, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        server.start()
        try { return SparqlRepository("http://127.0.0.1:${server.address.port}/").use(run) }
        finally { server.stop(0); pool.shutdownNow(); dataset.close() }
    }
    @Test fun `default and named mutations and every bound pattern work against parser`() = endpoint { repo ->
        val t = RdfTriple(Iri("urn:s"), Iri("urn:p"), Literal("value"))
        val graph = repo.editDefaultGraph()
        graph.addTriple(t)
        for (mask in 0..7) assertEquals(listOf(t), graph.find(
            if (mask and 1 != 0) t.subject else null, if (mask and 2 != 0) t.predicate else null, if (mask and 4 != 0) t.obj else null))
        repo.editGraph(Iri("urn:g")).addTriple(t)
        assertTrue(graph.removeTriple(t)); assertFalse(graph.removeTriple(t))
        assertEquals(1, repo.getGraph(Iri("urn:g")).size())
        graph.addTriples(listOf(t)); graph.clear()
        assertEquals(1, repo.getGraph(Iri("urn:g")).size())
        repo.clear(); assertTrue(repo.listGraphs().isEmpty())
    }
    @Test fun `blank nodes are inserted as one request and unsafe identity operations fail`() = endpoint { repo ->
        val t = RdfTriple(BlankNode("a"), Iri("urn:p"), BlankNode("b"))
        val graph = repo.editDefaultGraph()
        graph.addTriples(listOf(t, RdfTriple(BlankNode("b"), Iri("urn:p"), Literal("v"))))
        assertEquals(2, graph.size())
        // addTriple follows the same rule as a one-element addTriples: fresh blank nodes per call.
        graph.addTriple(t)
        assertEquals(3, graph.size())
        assertThrows(IllegalArgumentException::class.java) { graph.removeTriple(t) }
        // (Expression-bodied JUnit tests must return Unit, otherwise they are silently skipped.)
        assertEquals(3, graph.size())
    }
    @Test fun `malformed or unsupported results fail instead of becoming empty`() {
        for (json in listOf("{}", "{\"results\":{\"bindings\":[{\"x\":{\"type\":\"unknown\",\"value\":\"x\"}}]}}")) {
            endpoint({ _, _ -> json }) { repo ->
                assertThrows(RdfQueryException::class.java) { repo.select(SparqlSelectQuery("SELECT * WHERE {}")) }
            }
        }
    }
    @Test fun `scoped rows permit early termination`() = endpoint({ _, _ ->
        "{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[{\"x\":{\"type\":\"uri\",\"value\":\"urn:x\"}}]}}"
    }) { repo ->
        repo.withSelectRows(SparqlSelectQuery("SELECT * WHERE {}")) { assertEquals(Iri("urn:x"), it.first().get("x")) }
    }
}
