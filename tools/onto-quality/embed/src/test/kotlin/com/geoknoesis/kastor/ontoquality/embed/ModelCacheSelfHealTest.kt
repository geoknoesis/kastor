package com.geoknoesis.kastor.ontoquality.embed

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ModelCacheSelfHealTest {
    @TempDir lateinit var directory: Path

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `corrupted cached asset is deleted and downloaded again once`() {
        val good = "model-bytes".toByteArray()
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/good") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, good.size.toLong())
            exchange.responseBody.use { it.write(good) }
        }
        server.createContext("/bad") { exchange ->
            requests.incrementAndGet()
            val bad = "tampered".toByteArray()
            exchange.sendResponseHeaders(200, bad.size.toLong())
            exchange.responseBody.use { it.write(bad) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val target = directory.resolve("model.onnx")
            Files.write(target, "truncated".toByteArray())

            ModelDownloader.ensureFile(target, URI("$base/good"), sha256(good), "fixture")
            assertContentEquals(good, Files.readAllBytes(target))
            assertEquals(1, requests.get())

            // An intact cache is used as-is.
            ModelDownloader.ensureFile(target, URI("$base/good"), sha256(good), "fixture")
            assertEquals(1, requests.get())

            // A corrupted cache whose re-download is also wrong fails once and leaves no cache entry behind.
            Files.write(target, "truncated".toByteArray())
            assertFailsWith<IllegalArgumentException> { ModelDownloader.ensureFile(target, URI("$base/bad"), sha256(good), "fixture") }
            assertEquals(2, requests.get())
            assertFalse(Files.exists(target))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `CLI option validation is separate from model loading`() {
        val minilm = OnnxEmbeddingModel.MODEL_ID_MINILM
        val custom = OnnxEmbeddingModel.MODEL_ID_CUSTOM
        OnnxEmbeddingModel.validateCliOptions(minilm, null, null, null, 512)
        OnnxEmbeddingModel.validateCliOptions(custom, directory.resolve("m.onnx"), directory.resolve("t.json"), 384, 512)
        assertEquals(
            "Do not use --onnx, --tokenizer, or --embedding-dim with bundled model $minilm",
            assertFailsWith<IllegalArgumentException> {
                OnnxEmbeddingModel.validateCliOptions(minilm, directory.resolve("m.onnx"), null, null, 512)
            }.message,
        )
        assertEquals(
            "maxTokens must be between 1 and 512",
            assertFailsWith<IllegalArgumentException> { OnnxEmbeddingModel.validateCliOptions(minilm, null, null, null, 513) }.message,
        )
        assertEquals(
            "--onnx is required when --model $custom",
            assertFailsWith<IllegalArgumentException> {
                OnnxEmbeddingModel.validateCliOptions(custom, null, directory.resolve("t.json"), 384, 512)
            }.message,
        )
        assertFailsWith<IllegalArgumentException> { OnnxEmbeddingModel.validateCliOptions("bogus", null, null, null, 512) }
    }
}
