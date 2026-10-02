package com.geoknoesis.kastor.rdf.cli

import com.geoknoesis.kastor.rdf.GraphIsomorphismLimitException
import com.geoknoesis.kastor.rdf.RdfErrorCode
import com.geoknoesis.kastor.rdf.RdfFormatException
import com.geoknoesis.kastor.rdf.isIsomorphicTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Exit statuses for read failures, the `diff` isomorphism limit and its output, and the charset of the streams. */
class CliDiffAndIoTest {
    @TempDir
    lateinit var dir: Path

    private class Result(val code: Int, val out: String, val err: String)

    private fun run(runtime: CliRuntime, vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(args.toList(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"), runtime)
        return Result(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private fun file(name: String, content: String): String = dir.resolve(name).also { Files.writeString(it, content) }.toString()

    /** Delivers [head], then fails like a device or network share that goes away. */
    private class FailingStream(head: String) : InputStream() {
        private val bytes = head.toByteArray(Charsets.UTF_8)
        private var position = 0

        override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xff else throw IOException("The device is not ready")
    }

    /** An exception some parser library derives from IOException for malformed content. */
    private class ThirdPartyParseException(message: String) : IOException(message)

    @Test
    fun `a read failure in the middle of a file is an IO error with status 3, not a parse error`() {
        val line = "<http://e/s> <http://e/p> <http://e/o> .\n"
        val runtime = CliRuntime(open = { FailingStream(line.repeat(3)) })
        for (name in listOf("data.nt", "data.ttl", "data.nq")) {
            val path = file(name, line)
            for (command in listOf(arrayOf("parse", path), arrayOf("diff", path, path))) {
                val result = run(runtime, *command)
                assertEquals(EXIT_RUNTIME_ERROR, result.code, "${command[0]} $name: ${result.err}")
                assertTrue(result.err.contains("I/O error") && result.err.contains("The device is not ready"), result.err)
                assertFalse(result.err.contains("Parse error"), result.err)
                assertEquals(1, result.err.trim().lines().size, result.err)
            }
        }
    }

    @Test
    fun `a read failure wrapped by the provider is an IO error, a malformed document is not`() {
        val path = file("data.ttl", "<http://e/s> <http://e/p> <http://e/o> .\n")
        fun failing(cause: Throwable): CliRuntime =
            CliRuntime(open = { throw RdfFormatException.Generic("Failed to read TURTLE data: wrapped", RdfErrorCode.FORMAT_PARSE_ERROR, cause) })

        val wrapped = run(failing(org.apache.jena.atlas.RuntimeIOException(IOException("Stale file handle"))), "parse", path)
        assertEquals(EXIT_RUNTIME_ERROR, wrapped.code, wrapped.err)
        assertTrue(wrapped.err.contains("I/O error") && wrapped.err.contains("Stale file handle"), wrapped.err)

        val unchecked = run(failing(java.io.UncheckedIOException(java.nio.file.AccessDeniedException("data.ttl"))), "parse", path)
        assertEquals(EXIT_RUNTIME_ERROR, unchecked.code, unchecked.err)

        for (cause in listOf(ThirdPartyParseException("Unexpected character"), java.nio.charset.MalformedInputException(2), IllegalStateException("syntax"))) {
            val result = run(failing(cause), "parse", path)
            assertEquals(EXIT_USAGE, result.code, "${cause.javaClass.simpleName}: ${result.err}")
            assertTrue(result.err.startsWith("Parse error"), result.err)
        }
    }

    @Test
    fun `diff takes its isomorphism time limit from --timeout`() {
        val a = file("a.ttl", "_:x <http://e/p> \"v\" .\n")
        val b = file("b.ttl", "_:y <http://e/p> \"v\" .\n")
        val seen = ArrayList<Duration?>()
        // A clock that does not move: every check sees the whole --timeout.
        val runtime = CliRuntime(isomorphic = { first, second, limits -> seen += limits.timeout; first.isIsomorphicTo(second, null, limits.timeout) }, nanoTime = { 0L })
        assertEquals(EXIT_OK, run(runtime, "diff", a, b).code)
        assertEquals(EXIT_OK, run(runtime, "diff", "--timeout", "5", a, b).code)
        assertEquals(EXIT_OK, run(runtime, "diff", a, b, "TURTLE", "--timeout=900").code)
        assertEquals(EXIT_OK, run(runtime, "diff", a, "--timeout", "0", b).code)
        assertEquals(listOf(Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofSeconds(900), null), seen)

        for (bad in listOf(arrayOf("--timeout", "-1"), arrayOf("--timeout", "soon"), arrayOf("--timeout"), arrayOf("--tiemout", "5"))) {
            val result = run(runtime, "diff", a, b, *bad)
            assertEquals(EXIT_USAGE, result.code, "${bad.toList()}: ${result.err}")
            assertEquals(1, result.err.trim().lines().size, result.err)
        }
        assertEquals(4, seen.size, "a usage error must not start a comparison")
        assertTrue(run(runtime, "help").out.contains("--timeout <seconds>"))
    }

    @Test
    fun `diff at the isomorphism limit is status 3 and says how to raise the limit`() {
        val a = file("a.ttl", "_:x <http://e/p> \"v\" .\n")
        val runtime =
            CliRuntime(
                isomorphic = { _, _, _ ->
                    throw GraphIsomorphismLimitException(GraphIsomorphismLimitException.Reason.TIME, "Graph isomorphism time limit exceeded")
                },
            )
        val result = run(runtime, "diff", "--timeout", "1", a, a)
        assertEquals(EXIT_RUNTIME_ERROR, result.code, result.err)
        assertTrue(result.err.contains("time limit exceeded") && result.err.contains("--timeout"), result.err)
        assertFalse(result.err.contains("NOT ISOMORPHIC") || result.out.contains("ISOMORPHIC"), "no answer is known at a limit")
        assertEquals(1, result.err.trim().lines().size, result.err)
    }

    @Test
    fun `diff compares the inputs once`() {
        var calls = 0
        val runtime = CliRuntime(isomorphic = { first, second, limits -> calls++; first.isIsomorphicTo(second, null, limits.timeout) })
        val a = file("a.ttl", "_:x <http://e/p> \"v\" ; <http://e/q> _:z .\n")
        val b = file("b.ttl", "_:y <http://e/p> \"v\" ; <http://e/q> _:w .\n")
        assertEquals(EXIT_OK, run(runtime, "diff", a, b).code)
        assertEquals(1, calls, "graph formats")
        calls = 0
        val c = file("c.trig", "_:x <http://e/p> 1 . <http://e/g1> { _:x <http://e/p> \"1\" } <http://e/g2> { _:x <http://e/q> \"2\" }\n")
        val d = file("d.trig", "_:y <http://e/p> 1 . <http://e/g1> { _:y <http://e/p> \"1\" } <http://e/g2> { _:y <http://e/q> \"2\" }\n")
        assertEquals(EXIT_OK, run(runtime, "diff", c, d).code)
        assertEquals(1, calls, "datasets")
    }

    @Test
    fun `diff shows a bounded, sorted sample of the triples that differ`() {
        fun lines(tag: String): String =
            (1..500).joinToString("") { "<http://e/s$tag${"%03d".format(501 - it)}> <http://e/p> <http://e/o> .\n" } +
                "<http://e/same> <http://e/p> <http://e/o> .\n_:b <http://e/p> \"blank\" .\n"
        val a = file("a.nt", lines("A"))
        val b = file("b.nt", lines("B"))
        val result = run(CliRuntime(), "diff", a, b)
        assertEquals(EXIT_NOT_ISOMORPHIC, result.code, result.err)
        val shown = result.err.lines().filter { it.startsWith("<http://e/s") }
        assertEquals(128, shown.size, "64 lines per file: ${result.err}")
        assertEquals((1..64).map { "<http://e/sA${"%03d".format(it)}> <http://e/p> <http://e/o> ." }, shown.take(64), "smallest lines first")
        assertFalse(result.err.contains("<http://e/same>") || result.err.contains("blank"), "triples present in both files are not shown")
        assertTrue(result.err.contains("436 more"), result.err)
        assertTrue(result.err.contains("NOT ISOMORPHIC"), result.err)
    }

    @Test
    fun `diff of graphs that differ only in their blank nodes shows those triples`() {
        val a = file("a.nt", "<http://e/s> <http://e/p> <http://e/o> .\n_:x <http://e/p> \"one\" .\n")
        val b = file("b.nt", "<http://e/s> <http://e/p> <http://e/o> .\n_:x <http://e/p> \"two\" .\n")
        val result = run(CliRuntime(), "diff", a, b)
        assertEquals(EXIT_NOT_ISOMORPHIC, result.code, result.err)
        assertTrue(result.err.contains("\"one\"") && result.err.contains("\"two\""), result.err)
        assertFalse(result.err.contains("<http://e/o>"), result.err)
    }

    @Test
    fun `an interactive console gets its own charset, redirected output is UTF-8`() {
        assertEquals(Charsets.UTF_8, standardStreamCharset(null))
        assertEquals(Charset.forName("IBM850"), standardStreamCharset(Charset.forName("IBM850")))
        assertEquals(Charset.forName("windows-1252"), standardStreamCharset(Charset.forName("windows-1252")))
        // Whether this JVM has an interactive console depends on how the tests are run: both answers are valid.
        val console = interactiveConsoleCharset()
        assertEquals(console ?: Charsets.UTF_8, standardStreamCharset(console))
        val bytes = ByteArrayOutputStream()
        printStream(bytes, Charset.forName("IBM850")).use { it.print("café — …") }
        assertEquals("café ? ?", bytes.toString(Charset.forName("IBM850")), "characters the console cannot show become '?'")
        assertTrue(run(CliRuntime(), "help").out.contains("interactive console"))
    }
}
