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

/** Request deadlines, header waits and per-stream read timeouts. */
class SparqlEndpointTimeoutTest {

    private val selectAll = SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")

    @Test
    fun `request deadline aborts a stalled fixed-length body`(): Unit = LocalEndpoint { exchange, _ ->
        exchange.sendResponseHeaders(200, 1_000)
        exchange.responseBody.write("{\"head\":{".toByteArray())
        exchange.responseBody.flush()
        Thread.sleep(STALL_MILLIS)
    }.use { endpoint ->
        // The server stalls for far longer than any slack, so only the configured limit can end the call;
        // the message says which limit that was.
        val deadline = Duration.ofMillis(700)
        SparqlRepository(SparqlEndpointConfig(endpoint.url, readTimeout = Duration.ofSeconds(60), requestTimeout = deadline)).use { repo ->
            val start = System.nanoTime()
            val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            assertTrue(e.message!!.contains("exceeded its 700 ms deadline"), e.message)
            assertEndedAtLimit(deadline, start, "request deadline")
        }
        val readTimeout = Duration.ofMillis(500)
        SparqlRepository(SparqlEndpointConfig(endpoint.url, readTimeout = readTimeout, requestTimeout = null)).use { repo ->
            val start = System.nanoTime()
            val e = assertThrows(RdfQueryException::class.java) { repo.ask(SparqlAskQuery("ASK {}")) }
            assertTrue(e.message!!.contains("timed out after 500 ms"), e.message)
            assertEndedAtLimit(readTimeout, start, "read timeout")
        }
    }

    /**
     * The test clock of [clockedRepository]: the adapter's deadlines and its read watchdog read it, and the fake
     * transport and the consumers advance it, so no outcome depends on how fast the host runs.
     */
    private class TestClock {
        private val nanos = java.util.concurrent.atomic.AtomicLong(0)
        fun now(): Long = nanos.get()
        fun advance(millis: Long) { nanos.addAndGet(millis * 1_000_000) }
    }

    /** A body handed out [chunk] bytes per read, each read taking [readMillis] on [clock]: a server trickling it. */
    private class ClockedBody(text: String, private val clock: TestClock, private val readMillis: Long, private val chunk: Int) : java.io.InputStream() {
        private val bytes = text.toByteArray(Charsets.UTF_8)
        private var position = 0

        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xFF }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position == bytes.size) return -1
            clock.advance(readMillis)
            val n = minOf(len, chunk, bytes.size - position)
            System.arraycopy(bytes, position, b, off, n)
            position += n
            return n
        }
    }

    /**
     * A fake endpoint on [clock]: `/slow-headers` sends no headers within any wait (the wait asked for passes on the
     * clock, then it times out), `/burst` sends five rows at once, anything else trickles five rows over 1.5 s or more.
     */
    private fun clockedTransport(clock: TestClock, headerWaits: MutableList<Long>) = HttpTransport { request ->
        val path = request.uri().path
        when {
            path.endsWith("/slow-headers") -> object : java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<java.io.InputStream>>() {
                override fun get(timeout: Long, unit: TimeUnit): java.net.http.HttpResponse<java.io.InputStream> {
                    headerWaits += unit.toNanos(timeout)
                    clock.advance(unit.toMillis(timeout))
                    throw java.util.concurrent.TimeoutException()
                }
            }
            path.endsWith("/burst") -> java.util.concurrent.CompletableFuture.completedFuture(FakeResponse(request, 200, rowsJson(5).byteInputStream()))
            else -> {
                val text = rowsJson(5)
                java.util.concurrent.CompletableFuture.completedFuture(
                    FakeResponse(request, 200, ClockedBody(text, clock, readMillis = 300, chunk = text.length / 5 + 1)),
                )
            }
        }
    }

    private fun clockedRepository(config: SparqlEndpointConfig, clock: TestClock, headerWaits: MutableList<Long> = mutableListOf()) =
        SparqlRepository(config, clockedTransport(clock, headerWaits), ReadWatchdog(nanoTime = clock::now), nanoTime = clock::now)

    @Test
    fun `request timeout covers stream headers but not slow consumers while a per-call timeout covers the whole stream`() {
        val clock = TestClock()
        val url = "http://sparql.example/sparql"
        val limit = Duration.ofSeconds(1)
        val config = SparqlEndpointConfig(url, requestTimeout = limit)
        clockedRepository(config, clock).use { repo ->
            // The headers come at once; the body trickles for 1.5 s and the consumer takes 300 ms a row: the request
            // timeout bounds the wait for headers only, so every row arrives.
            val rows = repo.withSelectRows(selectAll) { seq -> seq.onEach { clock.advance(300) }.count() }
            assertEquals(5, rows)
            // An explicit per-call timeout bounds the whole call, like the Jena and RDF4J providers:
            // rows trickling in after the deadline fail the call.
            val e = assertThrows(RdfQueryException::class.java) { repo.withSelectRows(selectAll, emptyMap(), limit) { it.count() } }
            assertTrue(e.message!!.contains("exceeded its 1000 ms deadline"), e.message)
            assertEquals(5, repo.withSelectRows(selectAll, emptyMap(), Duration.ofSeconds(60)) { it.count() })
            // Buffered select keeps the whole-request deadline.
            val buffered = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            assertTrue(buffered.message!!.contains("exceeded its 1000 ms deadline"), buffered.message)
        }
        // ...and the per-call timeout covers the time the consumer spends on rows that are already buffered.
        clockedRepository(config.copy(endpoint = "$url/burst"), clock).use { burst ->
            assertEquals(5, burst.withSelectRows(selectAll, emptyMap(), limit) { it.count() })
            val slow = assertThrows(RdfQueryException::class.java) {
                burst.withSelectRows(selectAll, emptyMap(), limit) { seq -> seq.onEach { clock.advance(300) }.count() }
            }
            assertTrue(slow.message!!.contains("exceeded its 1000 ms deadline"), slow.message)
        }
        clockedRepository(config.copy(requestTimeout = Duration.ofSeconds(60), streamingRequestTimeout = limit), clock).use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.withSelectRows(selectAll) { it.count() } }
            assertTrue(e.message!!.contains("exceeded its 1000 ms deadline"), e.message)
        }
        val headerWaits = mutableListOf<Long>()
        val headerLimit = Duration.ofMillis(300)
        clockedRepository(SparqlEndpointConfig("$url/slow-headers", requestTimeout = headerLimit), clock, headerWaits).use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.withSelectRows(selectAll) { it.count() } }
            assertTrue(e.message!!.contains("exceeded its 300 ms deadline waiting for the response"), e.message)
        }
        // The adapter waited for the headers exactly as long as the request timeout allows.
        assertEquals(listOf(headerLimit.toNanos()), headerWaits)
    }

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
                val readTimeout = Duration.ofMillis(500)
                val config = SparqlEndpointConfig(
                    "http://127.0.0.1:${server.localPort}/sparql",
                    readTimeout = readTimeout,
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
                        assertTrue(e.message!!.contains("timed out after 500 ms waiting for the response"), "$name: ${e.message}")
                        assertEndedAtLimit(readTimeout, start, name)
                    }
                }
            } finally {
                accepted.forEach { it.close() }
            }
        }
    }

    @Test
    fun `parallel streams each enforce their own read timeout`(): Unit = LocalEndpoint { exchange, request ->
        if (request.path == "/stall") {
            exchange.responseHeaders.add("Content-Type", "application/sparql-results+json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("{\"head\":".toByteArray())
            exchange.responseBody.flush()
            Thread.sleep(STALL_MILLIS)
        } else {
            exchange.respond(200, rowsJson(2_000))
        }
    }.use { endpoint ->
        val readTimeout = Duration.ofSeconds(1)
        val config = SparqlEndpointConfig(endpoint.url, readTimeout = readTimeout, requestTimeout = null)
        val pool = Executors.newFixedThreadPool(4)
        try {
            SparqlRepository(config).use { repo ->
                SparqlRepository(config.copy(endpoint = "${endpoint.base}/stall")).use { stalling ->
                    val fast = (1..3).map { pool.submit<Int> { (1..5).sumOf { repo.withSelectRows(selectAll) { rows -> rows.count() } } } }
                    val stalled = pool.submit<Unit> {
                        val start = System.nanoTime()
                        val e = assertThrows(RdfQueryException::class.java) { stalling.withSelectRows(selectAll) { it.count() } }
                        assertTrue(e.message!!.contains("timed out after 1000 ms"), e.message)
                        assertEndedAtLimit(readTimeout, start, "stalled stream")
                    }
                    fast.forEach { assertEquals(10_000, it.get(90, TimeUnit.SECONDS)) }
                    // Rethrows an assertion that failed in the task.
                    stalled.get(90, TimeUnit.SECONDS)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** A server that accepts connections but never sends response headers. */
    private fun withSilentServer(run: (String) -> Unit) {
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
                run("http://127.0.0.1:${server.localPort}/sparql")
            } finally {
                accepted.forEach { it.close() }
            }
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a short streaming request timeout also bounds the wait for response headers`(): Unit = withSilentServer { url ->
        val limit = Duration.ofMillis(500)
        val config = SparqlEndpointConfig(
            url,
            readTimeout = Duration.ofSeconds(60),
            requestTimeout = Duration.ofSeconds(60),
            streamingRequestTimeout = limit,
        )
        SparqlRepository(config).use { repo ->
            listOf<Pair<String, () -> Any>>(
                "stream" to { repo.withSelectRows(selectAll) { it.count() } },
                "stream with per-call timeout" to { repo.withSelectRows(selectAll, emptyMap(), Duration.ofSeconds(60)) { it.count() } },
            ).forEach { (name, call) ->
                val start = System.nanoTime()
                val e = assertThrows(RdfQueryException::class.java, { call() }, name)
                assertTrue(e.message!!.contains("exceeded its 500 ms deadline waiting for the response"), "$name: ${e.message}")
                assertEndedAtLimit(limit, start, name)
            }
        }
        SparqlRepository(config.copy(requestTimeout = null)).use { repo ->
            val start = System.nanoTime()
            val e = assertThrows(RdfQueryException::class.java) { repo.withSelectRows(selectAll) { it.count() } }
            assertTrue(e.message!!.contains("exceeded its 500 ms deadline waiting for the response"), e.message)
            assertEndedAtLimit(limit, start, "stream without a request timeout")
        }
    }
}
