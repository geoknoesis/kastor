package com.geoknoesis.kastor.ontoquality.embed

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class ModelDownloadWaiterRetryTest {
    @TempDir lateinit var root: Path

    @Test
    fun `waiter with a long budget retries after the in-flight caller times out`() {
        val body = "model-bytes".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val requests = AtomicInteger()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/warmup") { exchange ->
            try {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
            } finally { exchange.close() }
        }
        server.createContext("/asset") { exchange ->
            try {
                if (requests.incrementAndGet() == 1) {
                    // Headers plus one byte, then stall past the first caller's deadline.
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.write(body, 0, 1)
                    exchange.responseBody.flush()
                    firstEntered.countDown()
                    releaseFirst.await(30, TimeUnit.SECONDS)
                } else {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.write(body)
                }
            } catch (_: Exception) {
            } finally { exchange.close() }
        }
        server.start()
        val workers = Executors.newFixedThreadPool(2)
        try {
            // Warm the shared HTTP client outside the short deadline (separate context: no request counted).
            val warmup = root.resolve("warmup")
            ModelDownloader.ensureFile(
                warmup, URI("http://127.0.0.1:${server.address.port}/warmup"), hash, "warmup",
                DownloadBudget(Duration.ofSeconds(30)),
            )
            Files.delete(warmup)
            val assets = listOf(ModelDownloader.ModelAsset("model.onnx", URI("http://127.0.0.1:${server.address.port}/asset"), hash))
            val dir = Files.createDirectories(root.resolve("cache"))

            val shortCaller = workers.submit<List<Path>> { ModelDownloader.ensureAssets(dir, assets, Duration.ofMillis(1500)) }
            assertTrue(firstEntered.await(20, TimeUnit.SECONDS), "first download must reach the stalled body")
            val longCaller = workers.submit<List<Path>> { ModelDownloader.ensureAssets(dir, assets, Duration.ofSeconds(60)) }

            try {
                shortCaller.get(20, TimeUnit.SECONDS)
                fail("short-budget caller should time out")
            } catch (e: ExecutionException) {
                assertIs<SocketTimeoutException>(e.cause)
            }
            releaseFirst.countDown()

            val paths = longCaller.get(60, TimeUnit.SECONDS)
            assertContentEquals(body, Files.readAllBytes(paths.single()))
            assertTrue(requests.get() >= 2)
        } finally {
            releaseFirst.countDown()
            workers.shutdownNow()
            server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }
}
