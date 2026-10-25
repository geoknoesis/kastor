package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class UrlLoadingAsyncCancelTest {
    @Test
    fun `a load cancelled before its connection exists never connects`() {
        LoopbackHttp(threads = 2).use { http ->
            val root = http.serve { exchange -> exchange.turtle() }
            val inPolicy = CountDownLatch(1)
            val release = CountDownLatch(1)
            // The address policy runs before the connection is created, so the load is "in flight" with no connection.
            val options = UrlLoadOptions(addressPolicy = UrlAddressPolicy { inPolicy.countDown(); release.await(60, TimeUnit.SECONDS); true })
            val loader = Executors.newSingleThreadExecutor()
            try {
                val future = Rdf.parseFromUrlAsync("$root/doc.ttl", RdfFormat.TURTLE, loader, options)
                assertTrue(inPolicy.await(60, TimeUnit.SECONDS))
                assertTrue(future.cancel(true))
                release.countDown()
                loader.shutdown()
                assertTrue(loader.awaitTermination(120, TimeUnit.SECONDS), "the cancelled load must end")
                assertEquals(emptyList<String>(), http.requests.toList(), "a cancelled load must not send its request")
            } finally {
                release.countDown()
                loader.shutdownNow()
            }
        }
    }

    /** A connection that never touches the network; each step runs the hook the test gives it. */
    private class FakeHttp(
        private val onConnect: () -> Unit = {},
        private val onDisconnect: () -> Unit = {},
        private val onRequestProperty: (String) -> Unit = {},
    ) : java.net.HttpURLConnection(java.net.URI("http://example.org/doc.ttl").toURL()) {
        val connects = AtomicInteger()
        override fun connect() { connects.incrementAndGet(); onConnect(); connected = true }
        override fun disconnect() = onDisconnect()
        override fun usingProxy() = false
        override fun getResponseCode(): Int = 200
        override fun getInputStream(): java.io.InputStream = "<urn:s> <urn:p> <urn:o> .".byteInputStream()
        override fun setRequestProperty(key: String, value: String?) {
            super.setRequestProperty(key, value)
            onRequestProperty(key)
        }
    }

    @Test
    fun `cancel returns at once while releasing the connection blocks`() {
        val connecting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        // Like the JDK's connection: disconnect waits for the connect in progress.
        val connection = FakeHttp(
            onConnect = { connecting.countDown(); release.await(60, TimeUnit.SECONDS) },
            onDisconnect = { release.await(60, TimeUnit.SECONDS); disconnected.countDown() },
        )
        UrlLoadHelperPool(4).use { pool ->
            val loader = Executors.newSingleThreadExecutor()
            val canceller = Executors.newSingleThreadExecutor()
            try {
                val runtime = UrlLoadRuntime(helpers = pool, open = { connection })
                val future = Rdf.parseFromUrlAsync("http://example.org/doc.ttl", "TURTLE", loader, UrlLoadOptions(), runtime)
                assertTrue(connecting.await(60, TimeUnit.SECONDS))
                val cancel = canceller.submit<Boolean> { future.cancel(true) }
                assertTrue(cancel.get(30, TimeUnit.SECONDS), "cancel() must not wait for the connection to be released")
                assertEquals(1L, disconnected.count, "the release is still waiting, on another thread")
                release.countDown()
                assertTrue(disconnected.await(60, TimeUnit.SECONDS), "the connection is released once it can be")
            } finally {
                release.countDown()
                loader.shutdownNow()
                canceller.shutdownNow()
            }
        }
    }

    @Test
    fun `a burst of cancels while every helper is busy starts no more threads than the overflow releaser has`() {
        val hold = CountDownLatch(1)
        val released = CountDownLatch(20)
        val releasers = java.util.concurrent.ConcurrentHashMap.newKeySet<Thread>()
        UrlLoadHelperPool(1).use { pool ->
            val overflow = UrlLoadCancellation.overflowExecutor(2)
            try {
                // The only helper is busy, so every release goes to the overflow releaser.
                assertTrue(pool.tryExecute { hold.await(60, TimeUnit.SECONDS) } != null)
                repeat(20) {
                    val connection = FakeHttp(onDisconnect = {
                        releasers.add(Thread.currentThread())
                        hold.await(60, TimeUnit.SECONDS)
                        released.countDown()
                    })
                    UrlLoadCancellation(pool, overflow).apply { connecting(connection) }.cancel()
                }
                assertEquals(20L, released.count, "cancel() hands the release on and returns at once")
                hold.countDown()
                assertTrue(released.await(60, TimeUnit.SECONDS), "every queued release runs")
                assertTrue(releasers.size <= 2, "releases ran on ${releasers.size} threads")
            } finally {
                hold.countDown()
                overflow.shutdownNow()
            }
        }
    }

    @Test
    fun `a cancel that lands after the last check before connecting still stops the request`() {
        val armed = AtomicBoolean()
        val futureRef = AtomicReference<java.util.concurrent.Future<*>>()
        val connection = FakeHttp(onRequestProperty = { if (it == "Connection") armed.set(true) })
        // The first clock reading after the request headers are set is taken between the old cancellation check
        // and connect(): cancel there.
        val clock = { if (armed.compareAndSet(true, false)) futureRef.get().cancel(true); System.nanoTime() }
        val start = CountDownLatch(1)
        val done = CountDownLatch(1)
        UrlLoadHelperPool(4).use { pool ->
            val executor = java.util.concurrent.Executor { task ->
                Thread({ start.await(60, TimeUnit.SECONDS); try { task.run() } finally { done.countDown() } }, "test-loader").start()
            }
            val runtime = UrlLoadRuntime(helpers = pool, open = { connection }, nanoTime = clock)
            val future = Rdf.parseFromUrlAsync("http://example.org/doc.ttl", "TURTLE", executor, UrlLoadOptions(), runtime)
            futureRef.set(future)
            start.countDown()
            assertTrue(done.await(60, TimeUnit.SECONDS))
            assertTrue(future.isCancelled)
            assertEquals(0, connection.connects.get(), "a cancelled load must not send its request")
        }
    }
}
