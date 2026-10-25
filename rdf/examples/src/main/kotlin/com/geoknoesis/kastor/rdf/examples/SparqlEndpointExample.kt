package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.RdfQueryException
import com.geoknoesis.kastor.rdf.SparqlAskQuery
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.sparql.SparqlEndpointConfig
import com.geoknoesis.kastor.rdf.sparql.SparqlRepository
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration

/**
 * Querying a remote SPARQL 1.1 endpoint with `SparqlRepository`: a buffered `select`, a streamed `withSelectRows`, an
 * `ask`, and the limits that keep a large or hostile response from exhausting the heap.
 *
 * So that it runs anywhere, the example starts a tiny local "endpoint" that answers every query with canned SPARQL JSON
 * results; point `endpoint` at Fuseki, GraphDB or any other SPARQL 1.1 service to try it for real.
 */
fun main() {
    println("=== SPARQL Endpoint Example ===\n")
    val server = cannedEndpoint()
    try {
        val endpoint = "http://127.0.0.1:${server.address.port}/sparql"
        val config = SparqlEndpointConfig(
            endpoint = endpoint,
            requestTimeout = Duration.ofSeconds(30),
            // The bytes of a buffered response, and the estimated heap of its decoded rows.
            maxResponseBytes = 8L * 1024 * 1024,
            maxBufferedResultBytes = 64L * 1024 * 1024,
        )
        println("Configuration (credentials and query strings are never printed): $config\n")
        SparqlRepository(config).use { repo ->
            // A buffered SELECT: every row in memory, within both limits.
            val result = repo.select(SparqlSelectQuery("SELECT ?person ?name WHERE { ?person <http://xmlns.com/foaf/0.1/name> ?name }"))
            println("Buffered select: ${result.count()} rows")
            result.forEach { row -> println("  ${row.get("person")} -> ${row.getString("name")}") }

            // A streamed SELECT: rows are handed over as they are decoded and never all held at once.
            val names = repo.withSelectRows(SparqlSelectQuery("SELECT ?person ?name WHERE { ?person <http://xmlns.com/foaf/0.1/name> ?name }")) { rows ->
                rows.mapNotNull { it.getString("name") }.toList()
            }
            println("Streamed select: $names")

            println("Ask: ${repo.ask(SparqlAskQuery("ASK { ?s ?p ?o }"))}")
        }

        // The same result under a heap cap that is too small for it fails clearly instead of filling the heap.
        SparqlRepository(config.copy(maxBufferedResultBytes = 64)).use { repo ->
            try {
                repo.select(SparqlSelectQuery("SELECT ?person ?name WHERE { ?person ?p ?name }"))
                println("Unexpected: the tiny heap cap was not enforced")
                reportFailure("maxBufferedResultBytes", IllegalStateException("not enforced"))
            } catch (expected: RdfQueryException) {
                println("\nWith maxBufferedResultBytes = 64 the select is refused, as configured:")
                println("  ${expected.message?.substringBefore(";")}")
            }
        }
    } finally {
        server.stop(0)
    }
}

/** A local HTTP server that answers every request with one canned SPARQL JSON result (SELECT or ASK). */
private fun cannedEndpoint(): HttpServer =
    HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/sparql") { exchange ->
            val query = String(exchange.requestBody.readBytes(), Charsets.UTF_8)
            val body = if (query.trimStart().startsWith("ASK")) """{"head":{},"boolean":true}""" else SELECT_RESULT
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/sparql-results+json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

private const val SELECT_RESULT = """{
  "head": { "vars": ["person", "name"] },
  "results": { "bindings": [
    { "person": { "type": "uri", "value": "http://example.org/alice" }, "name": { "type": "literal", "value": "Alice", "xml:lang": "en" } },
    { "person": { "type": "uri", "value": "http://example.org/bob" }, "name": { "type": "literal", "value": "Bob" } }
  ] }
}"""
