package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A failure of the byte source while a body is being parsed (reset, timeout, interrupt) is an I/O failure, not a
 * syntax error: it surfaces as the [IOException] the source threw, not as an [RdfFormatException].
 */
class UrlLoadingIoFailureTest {
    /** Serves some valid Turtle, then throws [failure] from the next read. */
    private class FailingStream(private val failure: IOException) : InputStream() {
        private val head = "<urn:s> <urn:p> <urn:o> .\n<urn:s2> <urn:p> ".toByteArray()
        private var pos = 0
        override fun read(): Int {
            if (pos < head.size) return head[pos++].toInt() and 0xFF
            throw failure
        }
    }

    private val failures = listOf<IOException>(
        SocketException("Connection reset"),
        SocketTimeoutException("Read timed out"),
        InterruptedIOException("interrupted"),
    )

    @Test
    fun `parseFromInputStream surfaces the failure of the stream`() {
        for (failure in failures) {
            val thrown = assertThrows(IOException::class.java, { Rdf.parseFromInputStream(FailingStream(failure), "TURTLE", null) }, failure.message)
            assertSame(failure, thrown)
        }
    }

    @Test
    fun `parseDataset surfaces the failure of the stream`() {
        for (failure in failures) {
            val repository = Rdf.memory()
            val thrown = assertThrows(IOException::class.java, { Rdf.parseDataset(repository, FailingStream(failure), "TRIG", null) }, failure.message)
            assertSame(failure, thrown)
        }
    }

    @Test
    fun `a syntax error is still a format error`() {
        assertThrows(RdfFormatException::class.java) {
            Rdf.parseFromInputStream("<urn:s> <urn:p> .".byteInputStream(), "TURTLE", null)
        }
    }

    @Test
    fun `a connection reset in the middle of the body is not reported as a parse error`() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val served = CountDownLatch(1)
            val server1 = Thread {
                server.accept().use { socket ->
                    socket.getInputStream().let { input ->
                        // Read the request head (it ends with an empty line).
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                    }
                    val out = socket.getOutputStream()
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/turtle\r\nContent-Length: 100000\r\n\r\n" +
                            "<urn:s> <urn:p> <urn:o> .\n<urn:s2> <urn:p> ").toByteArray(),
                    )
                    out.flush()
                    socket.setSoLinger(true, 0) // close sends a reset instead of a clean end
                    served.countDown()
                }
            }.apply { isDaemon = true; start() }
            val error = assertThrows(IOException::class.java) {
                Rdf.parseFromUrl("http://127.0.0.1:${server.localPort}/doc.ttl", RdfFormat.TURTLE)
            }
            assertTrue(served.await(60, TimeUnit.SECONDS))
            server1.join(60_000)
            assertTrue(error !is RdfLoadTimeoutException, error.toString())
        }
    }

    @Test
    fun `an interrupt while the body is read ends the load with an InterruptedIOException`() {
        LoopbackHttp(threads = 2).use { http ->
            val sent = CountDownLatch(1)
            val release = CountDownLatch(1)
            val root = http.serve { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/turtle")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write("<urn:s> <urn:p> <urn:o> .\n<urn:s2> <urn:p> ".toByteArray())
                exchange.responseBody.flush()
                sent.countDown()
                release.await(60, TimeUnit.SECONDS)
            }
            val outcome = AtomicReference<Throwable?>()
            val loader = Thread {
                try {
                    Rdf.parseFromUrl("$root/doc.ttl", RdfFormat.TURTLE)
                } catch (e: Throwable) {
                    outcome.set(e)
                }
            }.apply { isDaemon = true; start() }
            try {
                assertTrue(sent.await(60, TimeUnit.SECONDS))
                loader.interrupt()
                loader.join(60_000)
                assertTrue(!loader.isAlive, "an interrupted load must end")
                assertInstanceOf(InterruptedIOException::class.java, outcome.get(), outcome.get().toString())
            } finally {
                release.countDown()
            }
        }
    }
}
