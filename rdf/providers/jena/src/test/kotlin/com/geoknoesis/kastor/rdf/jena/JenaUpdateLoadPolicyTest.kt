package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Round seventeen, security review: a SPARQL `LOAD` opened whatever its source named (`file:` and `jar:` URLs, any
 * host, loopback and the internal network included). It now opens it as `Rdf.parseFromUrl` does, under the
 * repository's [JenaRepository.updateLoadOptions] (default: `http`/`https` to public addresses only, checked before
 * every connection and redirect), with messages that do not tell whether a file exists or a host answered. `SERVICE`
 * is refused in updates.
 */
class JenaUpdateLoadPolicyTest {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.add(exchange.requestURI.path)
            when (exchange.requestURI.path) {
                "/doc.ttl" -> {
                    val body = "<http://e/s> <http://e/p> \"v\" .\n".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/turtle")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                "/redirect" -> {
                    exchange.responseHeaders.add("Location", "$base/doc.ttl")
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                }
                else -> {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                }
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun refused(repo: JenaRepository, update: String): String =
        assertThrows(RdfQueryException::class.java) { repo.update(UpdateQuery(update)) }.message.orEmpty()

    @Test
    fun `by default LOAD refuses loopback, local names, files and jars without opening them`() {
        val file = kotlin.io.path.createTempFile("kastor-load", ".ttl").toFile().apply {
            writeText("<http://e/s> <http://e/p> \"v\" .\n")
            deleteOnExit()
        }
        JenaRepository.MemoryRepository().use { repo ->
            for (source in listOf("$base/doc.ttl", "http://localhost:${server.address.port}/doc.ttl", "$base/redirect")) {
                for (update in listOf("LOAD <$source>", "LOAD <$source> INTO GRAPH <http://e/g>")) {
                    val message = refused(repo, update)
                    assertTrue(message.contains("address policy"), message)
                }
            }
            // A file that exists and one that does not are refused alike, before anything is read.
            val existing = refused(repo, "LOAD <${file.toURI()}>").replace(file.toURI().toString(), "<url>")
            val missing = refused(repo, "LOAD <${file.toURI()}.missing>").replace("${file.toURI()}.missing", "<url>")
            assertEquals(existing, missing)
            assertTrue(existing.contains("scheme 'file' is not allowed"), existing)
            assertTrue(refused(repo, "LOAD <jar:${file.toURI()}!/a.ttl>").contains("scheme 'jar' is not allowed"))
            // SILENT ignores the refusal, as it ignores any LOAD failure; nothing is loaded.
            repo.update(UpdateQuery("LOAD SILENT <$base/doc.ttl>"))
            assertEquals(0, repo.defaultGraph.size())
            assertTrue(repo.listGraphs().isEmpty())
        }
        assertEquals(emptyList<String>(), requests.toList())
    }

    @Test
    fun `a redirect is checked against the policy before it is followed`() {
        JenaRepository.MemoryRepository().use { repo ->
            // A policy that lets only the first hop through: the redirect to the document is refused.
            repo.updateLoadOptions = UrlLoadOptions.DEFAULT.withAddressPolicy(UrlAddressPolicy { it.path == "/redirect" })
            val message = refused(repo, "LOAD <$base/redirect>")
            assertTrue(message.contains("address policy"), message)
            assertEquals(0, repo.defaultGraph.size())
        }
        assertEquals(listOf("/redirect"), requests.toList())
    }

    @Test
    fun `the opt-in loads as before, and failures do not tell why`() {
        JenaRepository.MemoryRepository().use { repo ->
            repo.updateLoadOptions = UrlLoadOptions.ALLOW_ALL_ADDRESSES
            repo.update(UpdateQuery("LOAD <$base/redirect> INTO GRAPH <http://e/g>"))
            assertEquals(1, repo.getGraph(Iri("http://e/g")).size())
            repo.updateLoadOptions = JenaUpdateBoundsProbe.TRUSTED_FILES
            val file = kotlin.io.path.createTempFile("kastor-load", ".ttl").toFile().apply {
                writeText("<http://e/s> <http://e/p> \"v\" .\n")
                deleteOnExit()
            }
            repo.update(UpdateQuery("LOAD <${file.toURI()}>"))
            assertEquals(1, repo.defaultGraph.size())
            // A missing file, a 404 and a port nobody listens on fail with the same message.
            val closedPort = java.net.ServerSocket(0).use { it.localPort }
            val messages = listOf("${file.toURI()}.missing", "$base/missing.ttl", "http://127.0.0.1:$closedPort/doc.ttl").map { source ->
                refused(repo, "LOAD <$source>").replace(Regex("LOAD <[^>]*>"), "LOAD <url>")
            }
            assertEquals(1, messages.toSet().size, "$messages")
            assertTrue(messages[0].contains("could not be loaded"), messages[0])
            assertFalse(messages[0].contains("404") || messages[0].contains("refused") || messages[0].contains("NoSuchFile"), messages[0])
        }
    }

    @Test
    fun `SERVICE is refused in an update, without a request`() {
        JenaRepository.MemoryRepository().use { repo ->
            repo.updateLoadOptions = UrlLoadOptions.ALLOW_ALL_ADDRESSES
            for (update in listOf(
                "INSERT { ?s ?p ?o } WHERE { SERVICE <$base/sparql> { ?s ?p ?o } }",
                "INSERT { ?s ?p ?o } WHERE { SERVICE SILENT <$base/sparql> { ?s ?p ?o } }",
                "DELETE { ?s ?p ?o } WHERE { ?s ?p ?o FILTER EXISTS { SERVICE <$base/sparql> { ?s ?p ?o } } }",
            )) {
                assertTrue(refused(repo, update).contains("SERVICE"), update)
            }
        }
        assertEquals(emptyList<String>(), requests.toList())
    }
}
