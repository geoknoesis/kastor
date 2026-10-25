package com.geoknoesis.kastor.rdf.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Inputs the CLI reads are untrusted: they cannot make it read other files, open connections or forge output lines. */
class CliUntrustedInputTest {
    @TempDir
    lateinit var dir: Path

    private class Result(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(args.toList(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"))
        return Result(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private fun file(name: String, content: String): String = dir.resolve(name).also { Files.writeString(it, content) }.toString()

    @Test
    fun `an RDF XML external entity never reads a local file`() {
        val secret = dir.resolve("secret.txt").also { Files.writeString(it, "TOP-SECRET-CONTENT") }
        val input = file(
            "xxe.rdf",
            """<?xml version="1.0"?>
            <!DOCTYPE rdf:RDF [ <!ENTITY leak SYSTEM "${secret.toUri()}"> ]>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:ex="http://ex.org/">
              <rdf:Description rdf:about="http://ex.org/s"><ex:p>&leak;</ex:p></rdf:Description>
            </rdf:RDF>
            """.trimIndent(),
        )
        val result = run("to-turtle", input)
        assertFalse(result.out.contains("TOP-SECRET-CONTENT"), result.out)
        assertFalse(result.err.contains("TOP-SECRET-CONTENT"), result.err)
    }

    @Test
    fun `a JSON-LD context named by URL is refused without a request`() {
        val connections = AtomicInteger()
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val acceptor = thread(isDaemon = true) {
                try {
                    while (true) server.accept().use { connections.incrementAndGet() }
                } catch (_: Exception) {
                    // closed
                }
            }
            val input = file("remote.jsonld", """{ "@context": "http://127.0.0.1:${server.localPort}/ctx", "@id": "http://ex.org/s", "p": "v" }""")
            val result = run("parse", input)
            assertEquals(EXIT_USAGE, result.code, result.out)
            assertTrue(result.err.contains("Parse error in"), result.err)
            server.close()
            acceptor.join(5_000)
        }
        assertEquals(0, connections.get(), "the CLI connected to the context URL")
    }

    @Test
    fun `control characters from the input never reach the terminal raw`() {
        // A Turtle syntax error whose token holds an ANSI escape sequence: the message quotes the input.
        val input = file("ansi.ttl", "<http://ex.org/s> <http://ex.org/p> \u001b[31mRED\u001b[0m .\n")
        val result = run("parse", input)
        assertEquals(EXIT_USAGE, result.code)
        assertFalse(result.err.contains('\u001b'), "raw ESC in: ${result.err}")
    }

    @Test
    fun `messages are escaped to one printable line`() {
        assertEquals("a b \\u001B[31m c", oneLine("a\nb \u001b[31m\r\nc"))
        assertEquals("tab\\u0009 bell\\u0007 del\\u007F", oneLine("tab\t bell\u0007 del\u007f"))
        assertEquals("Grüße ✓", oneLine("Grüße ✓"))
    }
}
