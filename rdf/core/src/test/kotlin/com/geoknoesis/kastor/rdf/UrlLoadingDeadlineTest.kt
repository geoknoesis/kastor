package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * URL loading: the overall deadline is hard, EOF is not a timeout, and the async executor bounds its workers, queues
 * what fits and rejects the rest without ever blocking the caller.
 *
 * A stalled server stalls on a latch that the test opens when it is done: that the load failed *while the server was
 * still stalled* is what shows the deadline was kept, not a stopwatch.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UrlLoadingDeadlineTest {
    private val http = LoopbackHttp()

    @AfterAll
    fun stopServer() {
        http.close()
    }

    @Test
    fun `a read that starts before the deadline cannot block past it`() {
        // The headers and the start of the body arrive, then the body stalls. The per-read timeout is longer than
        // the whole budget, so only the deadline can end the read.
        val release = CountDownLatch(1)
        val stalled = CountDownLatch(1)
        val root = http.serve { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/n-triples")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("# ".toByteArray())
            exchange.responseBody.flush()
            try {
                release.await(120, TimeUnit.SECONDS)
            } finally {
                stalled.countDown()
            }
        }
        val options = UrlLoadOptions(connectTimeoutMillis = 60_000, readTimeoutMillis = 60_000, totalTimeoutMillis = 1_000)
        try {
            val error = assertThrows(RdfLoadTimeoutException::class.java) { Rdf.parseFromUrl("$root/stall.nt", RdfFormat.N_TRIPLES, options) }
            assertEquals(1_000, error.timeoutMillis)
            assertEquals(1, stalled.count, "the load must end at its deadline, while the body is still stalled")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `waiting for response headers is bounded by the overall deadline`() {
        val release = CountDownLatch(1)
        val stalled = CountDownLatch(1)
        val root = http.serve {
            try {
                release.await(120, TimeUnit.SECONDS)
            } finally {
                stalled.countDown()
            }
        }
        val options = UrlLoadOptions(connectTimeoutMillis = 60_000, readTimeoutMillis = 60_000, totalTimeoutMillis = 800)
        try {
            assertThrows(RdfLoadTimeoutException::class.java) { Rdf.parseFromUrl("$root/slow.nt", RdfFormat.N_TRIPLES, options) }
            assertEquals(1, stalled.count, "the load must end at its deadline, while the server is still silent")
            assertEquals(listOf("/slow.nt"), http.requests.toList())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `end of stream reached after the deadline is not a timeout`() {
        // The clock of the stream is the test's: the read that finds the end of the stream takes a second of it,
        // against a budget of half a second.
        val clock = AtomicLong(0)
        val slowEof = object : InputStream() {
            override fun read(): Int { clock.addAndGet(TimeUnit.SECONDS.toNanos(1)); return -1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { clock.addAndGet(TimeUnit.SECONDS.toNanos(1)); return -1 }
        }
        val stream = DeadlineInputStream(slowEof, 0, 500, nanoTime = clock::get)
        assertEquals(-1, stream.read(ByteArray(8), 0, 8))
        assertEquals(-1, stream.read())
        assertEquals(null, stream.failure)
        assertTrue(clock.get() >= TimeUnit.SECONDS.toNanos(1), "the deadline had passed when the read returned")

        // A read that returns data after the deadline is a timeout.
        clock.set(0)
        val slowData = object : InputStream() {
            override fun read(): Int { clock.addAndGet(TimeUnit.SECONDS.toNanos(1)); return 'a'.code }
        }
        val late = DeadlineInputStream(slowData, 0, 500, nanoTime = clock::get)
        assertThrows(RdfLoadTimeoutException::class.java) { late.read() }
    }

    @Test
    fun `the async executor bounds its workers, queues what fits and rejects the rest without blocking the caller`() {
        // An executor of the default kind, small enough to saturate with a handful of loads.
        val workers = 2
        val queued = 3
        val overload = 4
        val executor = Rdf.newUrlIoExecutor(threads = workers, queueCapacity = queued)
        val release = CountDownLatch(1)
        val arrived = CountDownLatch(workers)
        val loading = AtomicInteger()
        val mostLoading = AtomicInteger()
        val root = http.serve { exchange ->
            mostLoading.accumulateAndGet(loading.incrementAndGet(), ::maxOf)
            arrived.countDown()
            try {
                release.await(120, TimeUnit.SECONDS)
            } finally {
                loading.decrementAndGet()
            }
            exchange.reply(200, "<urn:s> <urn:p> <urn:o> .", "application/n-triples")
        }
        try {
            // Every call returns at once, although no load can finish: the server answers nothing yet.
            val futures = (1..workers + queued + overload).map { Rdf.parseFromUrlAsync("$root/item$it.nt", RdfFormat.N_TRIPLES, executor) }
            val rejected = futures.filter { it.isCompletedExceptionally }
            assertEquals(overload, rejected.size, "loads beyond the workers and the queue are rejected")
            assertEquals(futures.takeLast(overload), rejected, "the last ones, in the order they were submitted")
            rejected.forEach { future ->
                val cause = assertThrows(ExecutionException::class.java) { future.get(30, TimeUnit.SECONDS) }.cause
                assertTrue(cause is RejectedExecutionException, cause.toString())
                assertTrue(cause!!.message!!.contains("saturated"), cause.message)
                assertTrue(cause.message!!.contains("$workers loads running") && cause.message!!.contains("$queued queued"), cause.message)
            }
            assertEquals(queued, executor.queue.size, "the queue holds what the workers cannot take")
            assertTrue(arrived.await(60, TimeUnit.SECONDS), "every worker is loading")
            assertEquals(workers, http.requests.size, "the queued loads wait for a worker")
            release.countDown()
            futures.filterNot { it in rejected }.forEach { assertEquals(1, it.get(120, TimeUnit.SECONDS).size()) }
            assertEquals(workers, mostLoading.get(), "never more loads at once than workers")
            assertEquals(workers + queued, http.requests.size)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `the default async executor has four workers and a queue of 256`() {
        assertEquals(4, Rdf.URL_IO_THREADS)
        assertEquals(256, Rdf.URL_IO_QUEUE_CAPACITY)
        val executor = Rdf.newUrlIoExecutor()
        try {
            assertEquals(4, executor.maximumPoolSize)
            assertEquals(256, executor.queue.remainingCapacity())
        } finally {
            executor.shutdownNow()
        }
    }
}
