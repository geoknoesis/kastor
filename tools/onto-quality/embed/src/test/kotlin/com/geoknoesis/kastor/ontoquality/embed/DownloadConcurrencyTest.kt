package com.geoknoesis.kastor.ontoquality.embed

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DownloadConcurrencyTest {
    @TempDir lateinit var root: Path

    @Test fun `different caches progress independently and same cache wait is bounded`() {
        val first = Files.createDirectory(root.resolve("one"))
        val second = Files.createDirectory(root.resolve("two"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        val owner = workers.submit {
            withModelCacheLock(first, DownloadBudget(Duration.ofSeconds(10))) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals("independent", withModelCacheLock(second, DownloadBudget(Duration.ofSeconds(1))) { "independent" })
            assertFailsWith<SocketTimeoutException> {
                withModelCacheLock(first, DownloadBudget(Duration.ofMillis(40))) { fail("lock was not released") }
            }
        } finally { release.countDown(); owner.get(5, TimeUnit.SECONDS); workers.shutdownNow() }
        assertEquals("released", withModelCacheLock(first, DownloadBudget(Duration.ofSeconds(1))) { "released" })
    }

    @Test fun `body stalled after headers is closed at deadline and temporary file removed`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val warmupBody = byteArrayOf(1)
        server.createContext("/warmup") { exchange ->
            try {
                exchange.sendResponseHeaders(200, warmupBody.size.toLong())
                exchange.responseBody.write(warmupBody)
            } finally { exchange.close() }
        }
        server.createContext("/stall") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 100)
                exchange.responseBody.write(byteArrayOf(1))
                exchange.responseBody.flush()
                entered.countDown()
                release.await(30, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        val worker = Executors.newSingleThreadExecutor()
        try {
            // Initialize the HTTP client and establish a connection outside the short
            // deadline. Cold JVM startup must not prevent reaching the stalled body.
            val warmup = root.resolve("warmup")
            val hash = MessageDigest.getInstance("SHA-256").digest(warmupBody)
                .joinToString("") { "%02x".format(it) }
            ModelDownloader.ensureFile(warmup, URI("http://127.0.0.1:${server.address.port}/warmup"),
                hash, "warmup", DownloadBudget(Duration.ofSeconds(30)))
            Files.delete(warmup)
            val job = worker.submit {
                assertFailsWith<SocketTimeoutException> {
                    ModelDownloader.ensureFile(root.resolve("asset"), URI("http://127.0.0.1:${server.address.port}/stall"),
                        "0".repeat(64), "stall", DownloadBudget(Duration.ofSeconds(5)))
                }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "The request must reach the stalled response body")
            job.get(10, TimeUnit.SECONDS)
            assertFalse(Files.exists(root.resolve("asset")))
            Files.list(root).use { assertEquals(0, it.count()) }
            assertEquals(0, modelDownloadWatchdog.queue.size)
        } finally { release.countDown(); server.stop(0); worker.shutdownNow() }
    }
}
