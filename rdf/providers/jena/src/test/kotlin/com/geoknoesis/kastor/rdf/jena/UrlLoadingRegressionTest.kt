package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.Rdf
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.jupiter.api.Test
import kotlin.test.*

class UrlLoadingRegressionTest {
    /**
     * The default async URL executor has 4 workers and a queue of 64. A load beyond that runs on (and blocks) the
     * calling thread instead of failing with RejectedExecutionException, so concurrency stays bounded and every
     * load of a burst completes.
     */
    @Test fun `default loader bounds workers and throttles overload on the caller instead of failing`() {
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
                val data = "<urn:s> <urn:p> <urn:o> .".toByteArray()
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
            } finally {
                active.decrementAndGet()
                exchange.close()
            }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/"
        val futures = mutableListOf<CompletableFuture<MutableRdfGraph>>()
        val overflow = AtomicReference<CompletableFuture<MutableRdfGraph>>()
        val overflowReturned = CountDownLatch(1)
        try {
            // 4 running + 64 queued: every call returns immediately.
            repeat(68) { futures.add(Rdf.parseFromUrlAsync(url)) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (active.get() < 4 && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals(4, active.get(), "the default loader runs 4 loads at a time")
            assertTrue(futures.none { it.isDone }, "no load of the burst is rejected")

            val caller = thread(isDaemon = true) {
                overflow.set(Rdf.parseFromUrlAsync(url))
                overflowReturned.countDown()
            }
            assertFalse(overflowReturned.await(500, TimeUnit.MILLISECONDS), "a load beyond the queue runs on the calling thread")

            release.countDown()
            assertTrue(overflowReturned.await(30, TimeUnit.SECONDS))
            caller.join(5_000)
            for (future in futures + overflow.get()) {
                assertEquals(1, future.get(30, TimeUnit.SECONDS).getTriples().size)
            }
            assertTrue(maxActive.get() <= 5, "at most 4 workers plus the throttled caller, saw ${maxActive.get()}")
        } finally {
            release.countDown()
            futures.forEach { it.cancel(true) }
            server.stop(0)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
