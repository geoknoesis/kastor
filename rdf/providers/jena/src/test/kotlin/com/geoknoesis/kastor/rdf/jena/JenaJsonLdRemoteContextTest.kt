package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** JSON-LD documents never make the parser fetch a remote or local context (`@context` / `@import` by URL). */
@Timeout(60)
class JenaJsonLdRemoteContextTest {
    private val requests = CopyOnWriteArrayList<String>()
    private val context = """{"@context": {"name": "http://xmlns.com/foaf/0.1/name"}}"""
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            try {
                requests.add(exchange.requestURI.path)
                val body = when (exchange.requestURI.path) {
                    "/doc.jsonld" -> document("http://127.0.0.1:${address.port}/context.jsonld")
                    else -> context
                }.toByteArray()
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
        val provider = JenaProvider()
        assertRefused { provider.parseGraph(document(contextUrl).byteInputStream(), "JSON-LD") }
        assertRefused { Rdf.parse(document(contextUrl), RdfFormat.JSON_LD) }
        assertRefused { provider.openTripleStream(document(contextUrl).byteInputStream(), "JSON-LD").use { it.toList() } }
        Rdf.memory().use { repo -> assertRefused { provider.parseDataset(repo, document(contextUrl).byteInputStream(), "JSON-LD") } }
        val imported = """{"@context": {"@version": 1.1, "@import": "$contextUrl"}, "@id": "urn:s", "name": "x"}"""
        assertRefused { provider.parseGraph(imported.byteInputStream(), "JSON-LD") }
        assertEquals(emptyList<String>(), requests.toList())
    }

    @Test
    fun `a URL load fetches the document only, never a context it names`() {
        val asked = CopyOnWriteArrayList<String>()
        val options = UrlLoadOptions(addressPolicy = UrlAddressPolicy { asked.add(it.toString()); true })
        val doc = "http://127.0.0.1:${server.address.port}/doc.jsonld"
        assertRefused { Rdf.parseFromUrl(doc, RdfFormat.JSON_LD, options) }
        assertRefused { JenaBridge.fromUrl(doc, "JSON-LD") }
        assertEquals(listOf("/doc.jsonld", "/doc.jsonld"), requests.toList())
        assertEquals(listOf(doc), asked.toList())
    }

    @Test
    fun `a file context is not read`(@TempDir dir: Path) {
        val file = dir.resolve("context.jsonld").toFile().apply { writeText(context) }
        assertRefused { JenaProvider().parseGraph(document(file.toURI().toString()).byteInputStream(), "JSON-LD") }
    }

    @Test
    fun `inline contexts still work`() {
        val graph = JenaProvider().parseGraph(
            """{"@context": {"name": "http://xmlns.com/foaf/0.1/name"}, "@id": "urn:s", "name": "x"}""".byteInputStream(),
            "JSON-LD",
        )
        assertEquals(listOf(RdfTriple(Iri("urn:s"), Iri("http://xmlns.com/foaf/0.1/name"), string("x"))), graph.getTriples())
    }
}
