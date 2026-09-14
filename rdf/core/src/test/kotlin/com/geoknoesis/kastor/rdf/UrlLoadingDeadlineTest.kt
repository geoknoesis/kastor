package com.geoknoesis.kastor.rdf

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** URL loading: the overall deadline is hard, EOF is not a timeout, and async saturation never blocks the caller. */
class UrlLoadingDeadlineTest {
    private var server: HttpServer? = null
    private var pool: ExecutorService? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
        pool?.shutdownNow()
    }

    private fun serve(threads: Int = 16, handler: (com.sun.net.httpserver.HttpExchange) -> Unit): String {
        val executor = Executors.newFixedThreadPool(threads)
        val created = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                try { handler(exchange) } catch (_: IOException) { } catch (_: InterruptedException) { } finally { exchange.close() }
            }
            this.executor = executor
            start()
        }
        server = created
        pool = executor
        return "http://${created.address.hostString}:${created.address.port}"
    }

    @Test
    fun `a read that starts before the deadline cannot block past it`() {
        // Headers arrive late (1.5 s of a 2 s budget); the body then stalls. With a per-read timeout equal to the
        // whole budget, the load used to end only after 3.5 s, with a SocketTimeoutException.
        val root = serve { exchange ->
            Thread.sleep(1_500)
            exchange.responseHeaders.add("Content-Type", "application/n-triples")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("# ".toByteArray())
            exchange.responseBody.flush()
            Thread.sleep(10_000)
        }
        val options = UrlLoadOptions(connectTimeoutMillis = 10_000, readTimeoutMillis = 10_000, totalTimeoutMillis = 2_000)

        assertTimeoutPreemptively(Duration.ofSeconds(15)) {
            val started = System.nanoTime()
            val error = assertThrows(RdfLoadTimeoutException::class.java) { Rdf.parseFromUrl("$root/stall.nt", RdfFormat.N_TRIPLES, options) }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals(2_000, error.timeoutMillis)
            assertTrue(elapsed < 3_000, "load overran the 2 s deadline: $elapsed ms")
        }
    }

    @Test
    fun `waiting for response headers is bounded by the overall deadline`() {
        val root = serve { Thread.sleep(10_000) }
        val options = UrlLoadOptions(connectTimeoutMillis = 10_000, readTimeoutMillis = 10_000, totalTimeoutMillis = 800)
        assertTimeoutPreemptively(Duration.ofSeconds(15)) {
            val started = System.nanoTime()
            assertThrows(RdfLoadTimeoutException::class.java) { Rdf.parseFromUrl("$root/slow.nt", RdfFormat.N_TRIPLES, options) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_500)
        }
    }

    @Test
    fun `end of stream reached after the deadline is not a timeout`() {
        val slowEof = object : InputStream() {
            override fun read(): Int { Thread.sleep(60); return -1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { Thread.sleep(60); return -1 }
        }
        val stream = DeadlineInputStream(slowEof, System.nanoTime(), 20)
        assertEquals(-1, stream.read(ByteArray(8), 0, 8))
        assertEquals(-1, stream.read())
        assertEquals(null, stream.failure)
    }

    @Test
    fun `async loads beyond the default executor capacity fail immediately instead of running on the caller`() {
        val release = CountDownLatch(1)
        val root = serve(threads = 8) { exchange ->
            release.await(60, TimeUnit.SECONDS)
            val body = "<urn:s> <urn:p> <urn:o> .".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
        }
        val capacity = Rdf.URL_IO_THREADS + Rdf.URL_IO_QUEUE_CAPACITY
        try {
            val futures: List<java.util.concurrent.CompletableFuture<MutableRdfGraph>> =
                assertTimeoutPreemptively(Duration.ofSeconds(20), org.junit.jupiter.api.function.ThrowingSupplier {
                    (1..capacity + 20).map { Rdf.parseFromUrlAsync("$root/item$it.nt", RdfFormat.N_TRIPLES) }
                })
            val rejected = futures.filter { it.isCompletedExceptionally }
            assertTrue(rejected.size >= 20, "expected at least 20 rejected loads, got ${rejected.size}")
            rejected.forEach { future ->
                val cause = assertThrows(ExecutionException::class.java) { future.get(1, TimeUnit.SECONDS) }.cause
                assertTrue(cause is RejectedExecutionException, cause.toString())
                assertTrue(cause!!.message!!.contains("saturated"), cause.message)
            }
            release.countDown()
            futures.filterNot { it in rejected }.forEach { assertEquals(1, it.get(60, TimeUnit.SECONDS).size()) }
        } finally {
            release.countDown()
        }
    }
}
