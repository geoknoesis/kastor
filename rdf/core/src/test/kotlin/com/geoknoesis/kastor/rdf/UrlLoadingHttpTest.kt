package com.geoknoesis.kastor.rdf

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** URL loading against a local HTTP server: status handling, content negotiation, base IRI, deadlines, bursts. */
class UrlLoadingHttpTest {
    private var server: HttpServer? = null
    private var pool: ExecutorService? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
        pool?.shutdownNow()
    }

    private fun serve(handler: (HttpExchange) -> Unit): String {
        val threads = Executors.newFixedThreadPool(16)
        val created = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                try { handler(exchange) } catch (_: IOException) { } finally { exchange.close() }
            }
            executor = threads
            start()
        }
        server = created
        pool = threads
        return "http://${created.address.hostString}:${created.address.port}"
    }

    private fun HttpExchange.reply(status: Int, body: String, contentType: String = "text/turtle") {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.write(bytes)
    }

    @Test
    fun `non-2xx responses fail with the HTTP status instead of being parsed`() {
        val root = serve { exchange ->
            when (exchange.requestURI.path) {
                "/missing" -> exchange.reply(404, "<html><body>Not found</body></html>", "text/html")
                "/moved" -> {
                    exchange.responseHeaders.add("Location", "ftp://127.0.0.1:1/elsewhere")
                    exchange.reply(302, "")
                }
                else -> exchange.reply(500, "oops", "text/plain")
            }
        }

        val missing = assertThrows(RdfHttpStatusException::class.java) { Rdf.parseFromUrl("$root/missing", RdfFormat.TURTLE) }
        assertEquals(404, missing.statusCode)
        assertTrue(missing.message!!.contains("404"))
        assertEquals(302, assertThrows(RdfHttpStatusException::class.java) { Rdf.parseFromUrl("$root/moved") }.statusCode)
        assertEquals(500, assertThrows(RdfHttpStatusException::class.java) { Rdf.parseDatasetFromUrl("$root/err", RdfFormat.TRIG) }.statusCode)

        val async = assertThrows(ExecutionException::class.java) { Rdf.parseFromUrlAsync("$root/missing", RdfFormat.TURTLE).get(30, TimeUnit.SECONDS) }
        assertEquals(404, (async.cause as RdfHttpStatusException).statusCode)
    }

    @Test
    fun `requests send an Accept header for the format and resolve relative IRIs against the URL`() {
        val accepts = ConcurrentLinkedQueue<String>()
        val root = serve { exchange ->
            accepts.add(exchange.requestHeaders.getFirst("Accept") ?: "")
            if (exchange.requestURI.path.endsWith(".trig")) {
                exchange.reply(200, "GRAPH <#g> { <#s> <urn:p> <> . }", "application/trig")
            } else {
                exchange.reply(200, "<#s> <urn:p> <> .")
            }
        }

        val url = "$root/doc.ttl"
        val graph = Rdf.parseFromUrl(url, RdfFormat.TURTLE)
        assertEquals(listOf(RdfTriple(Iri("$url#s"), Iri("urn:p"), Iri(url))), graph.getTriples())
        assertTrue(accepts.poll().contains("text/turtle"))

        val asyncGraph = Rdf.parseFromUrlAsync(url, RdfFormat.TURTLE).get(30, TimeUnit.SECONDS)
        assertEquals(graph.getTriples(), asyncGraph.getTriples())
        assertTrue(accepts.poll().contains("text/turtle"))

        val trigUrl = "$root/data.trig"
        val dataset = Rdf.parseDatasetFromUrl(trigUrl, RdfFormat.TRIG) as RdfRepository
        try {
            assertEquals(listOf(RdfTriple(Iri("$trigUrl#s"), Iri("urn:p"), Iri(trigUrl))), dataset.getGraph(Iri("$trigUrl#g")).getTriples())
        } finally {
            dataset.close()
        }
        assertTrue(accepts.poll().contains("application/trig"))
    }

    @Test
    fun `a slowly trickling body is stopped by the overall deadline`() {
        val root = serve { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/n-triples")
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody
            out.write("# ".toByteArray())
            repeat(200) {
                out.write('a'.code)
                out.flush()
                Thread.sleep(100)
            }
        }
        val options = UrlLoadOptions(readTimeoutMillis = 5_000, totalTimeoutMillis = 700)

        assertTimeoutPreemptively(Duration.ofSeconds(15)) {
            val error = assertThrows(RdfLoadTimeoutException::class.java) { Rdf.parseFromUrl("$root/slow.nt", RdfFormat.N_TRIPLES, options) }
            assertEquals(700, error.timeoutMillis)
        }
        assertThrows(IllegalArgumentException::class.java) { UrlLoadOptions(totalTimeoutMillis = -1) }
    }

    @Test
    fun `bursts of async loads beyond the default queue capacity all complete`() {
        val root = serve { exchange ->
            Thread.sleep(20)
            exchange.reply(200, "<urn:s> <urn:p> <urn:o> .", "application/n-triples")
        }

        val futures = (1..150).map { Rdf.parseFromUrlAsync("$root/item$it.nt", RdfFormat.N_TRIPLES) }
        CompletableFuture.allOf(*futures.toTypedArray()).get(120, TimeUnit.SECONDS)
        assertTrue(futures.all { it.get().size() == 1 })
    }
}
