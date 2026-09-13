package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Rdf
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import kotlin.test.*

class UrlLoadingRegressionTest {
    @Test fun `default loader bounds workers and rejects overload without blocking callers`() {
        val reached = CountDownLatch(4)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                reached.countDown()
                release.await(15, TimeUnit.SECONDS)
                val data = "<urn:s> <urn:p> <urn:o> .".toByteArray()
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
            } finally { exchange.close() }
        }
        server.start()
        val futures = mutableListOf<java.util.concurrent.CompletableFuture<*>>()
        try {
            repeat(72) { futures.add(Rdf.parseFromUrlAsync("http://127.0.0.1:${server.address.port}/")) }
            assertTrue(reached.await(10, TimeUnit.SECONDS))
            assertTrue(futures.count { it.isCompletedExceptionally } >= 4)
            futures.filterNot { it.isDone }.forEach { assertTrue(it.cancel(true)) }
        } finally {
            futures.forEach { it.cancel(true) }
            release.countDown(); server.stop(0); executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
