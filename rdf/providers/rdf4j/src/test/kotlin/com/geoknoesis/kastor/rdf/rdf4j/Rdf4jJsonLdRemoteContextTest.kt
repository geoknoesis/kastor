package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import com.sun.net.httpserver.HttpServer
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * JSON-LD documents never make the RDF4J provider fetch a remote or local context, also not one that RDF4J's own
 * secure mode would allow (its built-in whitelist of well-known contexts): the same rule as the Jena provider's.
 */
@Timeout(60)
class Rdf4jJsonLdRemoteContextTest {
    private val requests = CopyOnWriteArrayList<String>()
    private val context = """{"@context": {"name": "http://xmlns.com/foaf/0.1/name"}}"""
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            try {
                requests.add(exchange.requestURI.path)
                val body = context.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/ld+json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally {
                exchange.close()
            }
        }
        start()
    }
    private val contextUrl = "http://127.0.0.1:${server.address.port}/context.jsonld"

    @AfterEach
    fun stop() = server.stop(0)

    private fun document(contextRef: String) = """{"@context": "$contextRef", "@id": "urn:s", "name": "x"}"""

    private fun assertRefused(parse: () -> Unit) {
        val error = assertThrows(RdfFormatException::class.java) { parse() }
        assertTrue(error.message!!.contains("context", ignoreCase = true), error.message)
    }

    @Test
    fun `a remote context is not fetched by any parse entry point`() {
        val provider = Rdf4jProvider()
        assertRefused { provider.parseGraph(document(contextUrl).byteInputStream(), "JSON-LD") }
        assertRefused { provider.parseGraph(document(contextUrl).byteInputStream(), "JSON-LD", "http://example.org/") }
        assertRefused { provider.openTripleStream(document(contextUrl).byteInputStream(), "JSON-LD").use { it.toList() } }
        val native = SailRepository(MemoryStore()).also { it.init() }
        Rdf4jRepository(native).use { repo ->
            assertRefused { provider.parseDataset(repo, document(contextUrl).byteInputStream(), "JSON-LD") }
        }
        val imported = """{"@context": {"@version": 1.1, "@import": "$contextUrl"}, "@id": "urn:s", "name": "x"}"""
        assertRefused { provider.parseGraph(imported.byteInputStream(), "JSON-LD") }
        assertEquals(emptyList<String>(), requests.toList())
    }

    @Test
    fun `a context on RDF4J's built-in whitelist is not fetched either`() {
        // On RDF4J's default whitelist: with RDF4J's defaults this document would be fetched from schema.org.
        assertRefused { Rdf4jProvider().parseGraph(document("https://schema.org/").byteInputStream(), "JSON-LD") }
    }

    @Test
    fun `a file context is not read`(@TempDir dir: Path) {
        val file = dir.resolve("context.jsonld").toFile().apply { writeText(context) }
        assertRefused { Rdf4jProvider().parseGraph(document(file.toURI().toString()).byteInputStream(), "JSON-LD") }
    }

    @Test
    fun `inline contexts still work`() {
        val graph = Rdf4jProvider().parseGraph(
            """{"@context": {"name": "http://xmlns.com/foaf/0.1/name"}, "@id": "urn:s", "name": "x"}""".byteInputStream(),
            "JSON-LD",
        )
        assertEquals(listOf(RdfTriple(Iri("urn:s"), Iri("http://xmlns.com/foaf/0.1/name"), string("x"))), graph.getTriples())
    }
}
