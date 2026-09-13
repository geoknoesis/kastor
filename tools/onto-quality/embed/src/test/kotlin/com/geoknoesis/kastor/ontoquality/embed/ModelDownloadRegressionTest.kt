package com.geoknoesis.kastor.ontoquality.embed

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class ModelDownloadRegressionTest {
    @TempDir lateinit var directory: Path
    @Test fun `redirects are followed and corrupt downloads never become cache entries`() {
        val bytes = "model-test-data".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/asset")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.start()
        try {
            val url = URI("http://127.0.0.1:${server.address.port}/redirect")
            val target = directory.resolve("asset")
            ModelDownloader.ensureFile(target, url, hash, "fixture")
            assertContentEquals(bytes, Files.readAllBytes(target))
            val bad = directory.resolve("bad")
            assertFailsWith<IllegalArgumentException> { ModelDownloader.ensureFile(bad, url, "0".repeat(64), "bad") }
            assertFalse(Files.exists(bad))
            Files.list(directory).use { files -> assertEquals(listOf(target), files.toList()) }
        } finally { server.stop(0) }
    }
}
