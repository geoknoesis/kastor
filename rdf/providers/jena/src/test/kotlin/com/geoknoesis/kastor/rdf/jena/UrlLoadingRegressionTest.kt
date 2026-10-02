package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.Rdf
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.jupiter.api.Test
import kotlin.test.*

class UrlLoadingRegressionTest {
    /**
     * The default async URL executor has 4 workers and a queue of 256. A load beyond that never runs on (or blocks)
     * the calling thread: the call returns at once with a future that failed with RejectedExecutionException, while
     * concurrency stays bounded and every accepted load of the burst completes.
     */
    @Test fun `default loader bounds workers and rejects overload without blocking the caller`() {
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                release.await(30, TimeUnit.SECONDS)
            } finally {
                // Before the response is written: a client that has read it may send its next request before this
                // thread runs again, and that request must not be counted on top of this one.
                active.decrementAndGet()
            }
            try {
                val data = "<urn:s> <urn:p> <urn:o> .".toByteArray()
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/"
        val futures = mutableListOf<CompletableFuture<MutableRdfGraph>>()
        val overflow = AtomicReference<CompletableFuture<MutableRdfGraph>>()
        val overflowReturned = CountDownLatch(1)
        try {
            // 4 running + 256 queued: every call returns immediately and is accepted.
            repeat(260) { futures.add(Rdf.parseFromUrlAsync(url)) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (active.get() < 4 && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals(4, active.get(), "the default loader runs 4 loads at a time")
            assertTrue(futures.none { it.isDone }, "no load of the burst is rejected")

            thread(isDaemon = true) {
                overflow.set(Rdf.parseFromUrlAsync(url))
                overflowReturned.countDown()
            }
            // All workers are blocked on the server, so returning here proves the load did not run on the caller.
            assertTrue(overflowReturned.await(10, TimeUnit.SECONDS), "a load beyond the queue does not block the caller")
            val rejected = assertFailsWith<ExecutionException> { overflow.get().get(5, TimeUnit.SECONDS) }
            assertIs<RejectedExecutionException>(rejected.cause, "a saturated default executor rejects the load")

            release.countDown()
            for (future in futures) {
                assertEquals(1, future.get(60, TimeUnit.SECONDS).getTriples().size)
            }
            assertTrue(maxActive.get() <= 4, "at most the 4 default workers load at once, saw ${maxActive.get()}")
        } finally {
            release.countDown()
            futures.forEach { it.cancel(true) }
            server.stop(0)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
