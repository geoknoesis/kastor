package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** URL loading against a local HTTP server: status handling, content negotiation, base IRI, the overall deadline. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UrlLoadingHttpTest {
    private val http = LoopbackHttp()

    @AfterAll
    fun stopServer() {
        http.close()
    }

    @Test
    fun `non-2xx responses fail with the HTTP status instead of being parsed`() {
        val root = http.serve { exchange ->
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

        val async = assertThrows(ExecutionException::class.java) { Rdf.parseFromUrlAsync("$root/missing", RdfFormat.TURTLE).get(60, TimeUnit.SECONDS) }
        assertEquals(404, (async.cause as RdfHttpStatusException).statusCode)
    }

    @Test
    fun `requests send an Accept header for the format and resolve relative IRIs against the URL`() {
        val accepts = ConcurrentLinkedQueue<String>()
        val root = http.serve { exchange ->
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

        val asyncGraph = Rdf.parseFromUrlAsync(url, RdfFormat.TURTLE).get(60, TimeUnit.SECONDS)
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
        // A body that never stalls for as long as the per-read timeout, but never ends either: one byte every
        // 100 ms of the load's clock, which is the test's. No read waits for anything real.
        val clock = AtomicLong(0)
        val reads = AtomicInteger()
        val trickle = object : InputStream() {
            override fun available(): Int = 1
            override fun read(): Int {
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100))
                return if (reads.getAndIncrement() == 0) '#'.code else 'a'.code
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b[off] = read().toByte()
                return 1
            }
        }
        val pool = UrlLoadHelperPool(2)
        try {
            val runtime = UrlLoadRuntime(helpers = pool, open = { uri -> FakeHttpConnection(uri.toURL(), body = { trickle }) }, nanoTime = clock::get)
            val options = UrlLoadOptions(readTimeoutMillis = 5_000, totalTimeoutMillis = 700)
            val body = openRdfUrlStream("http://trickle.example/slow.nt", RdfFormat.N_TRIPLES, options, runtime)
            val error = assertThrows(RdfLoadTimeoutException::class.java) { body.read { it.readBytes() } }
            assertEquals(700, error.timeoutMillis)
            // Seven bytes fit into 700 ms; the eighth read returns after the deadline and is the timeout.
            assertEquals(8, reads.get())
        } finally {
            pool.close()
        }
        assertThrows(IllegalArgumentException::class.java) { UrlLoadOptions(totalTimeoutMillis = -1) }
    }
}
