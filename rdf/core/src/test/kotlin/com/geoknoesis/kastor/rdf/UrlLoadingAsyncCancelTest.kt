package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
}
