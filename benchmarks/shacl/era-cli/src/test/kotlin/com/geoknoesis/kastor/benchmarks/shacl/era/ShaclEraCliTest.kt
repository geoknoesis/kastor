package com.geoknoesis.kastor.benchmarks.shacl.era

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShaclEraCliTest {
    @TempDir lateinit var dir: Path

    private val shapesTtl =
        """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        ex:ThingShape a sh:NodeShape ; sh:targetClass ex:Thing ;
          sh:property [ sh:path ex:name ; sh:minCount 1 ] .
        """.trimIndent()

    private fun data(): Path = dir.resolve("data.ttl").also { Files.writeString(it, "@prefix ex: <http://example.org/> .\nex:a a ex:Thing .\n") }

    private fun shapes(name: String = "shapes.ttl", content: String = shapesTtl): Path = dir.resolve(name).also { Files.writeString(it, content) }

    private class Result(val status: Int, val out: List<String>, val err: List<String>)

    private fun run(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val status =
            PrintStream(out, true, Charsets.UTF_8).use { o ->
                PrintStream(err, true, Charsets.UTF_8).use { e -> runEraCli(args.toList(), o, e) }
            }
        fun lines(buffer: ByteArrayOutputStream) = buffer.toString(Charsets.UTF_8).lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        return Result(status, lines(out), lines(err))
    }

    @Test
    fun `stdout contains exactly the two ERA timing lines with sub-millisecond precision`() {
        val report = dir.resolve("report.ttl")
        val result = run(data().toString(), shapes().toString(), report.toString())

        assertEquals(EXIT_OK, result.status, result.err.toString())
        assertEquals(emptyList(), result.err)
        assertEquals(2, result.out.size, "unexpected stdout: ${result.out}")
        assertTrue(Regex("""Load time: \d+\.\d{6}""").matches(result.out[0]), result.out[0])
        assertTrue(Regex("""Validation time: \d+\.\d{6}""").matches(result.out[1]), result.out[1])
        assertTrue(Files.readString(report).contains("ValidationReport"))
    }

    @Test
    fun `seconds keep microsecond precision`() {
        assertEquals("0.000250", formatSeconds(250_000))
        assertEquals("1.234568", formatSeconds(1_234_567_890))
    }

    @Test
    fun `wrong argument count is a usage error`() {
        val result = run(data().toString(), shapes().toString())
        assertEquals(EXIT_USAGE, result.status)
        assertEquals(listOf("Usage: shacl-era-cli <data.ttl> <shapes.ttl> <report.ttl>"), result.err)
        assertEquals(emptyList(), result.out)
    }

    @Test
    fun `missing input file or a directory is a usage error without a stack trace`() {
        val missing = dir.resolve("missing.ttl")
        val result = run(missing.toString(), shapes().toString(), dir.resolve("r.ttl").toString())
        assertEquals(EXIT_USAGE, result.status)
        assertEquals(listOf("shacl-era-cli: data file not found or not a regular file: $missing"), result.err)
        assertEquals(emptyList(), result.out)

        val directory = run(data().toString(), dir.toString(), dir.resolve("r.ttl").toString())
        assertEquals(EXIT_USAGE, directory.status)
        assertEquals(listOf("shacl-era-cli: shapes file not found or not a regular file: $dir"), directory.err)
    }

    @Test
    fun `unparseable input is exit 2 with a sanitised one-line message`() {
        val bad = shapes("bad\u202Eltt.ttl", "@prefix ex: <http://example.org/> .\nex:a ex:b")
        val result = run(data().toString(), bad.toString(), dir.resolve("r.ttl").toString())
        assertEquals(EXIT_INPUT_ERROR, result.status, result.err.toString())
        val visiblePath = bad.toString().replace("\u202E", "\\u202E")
        assertTrue(result.err.first().startsWith("shacl-era-cli: failed to parse shapes file $visiblePath as Turtle: "), result.err.toString())
        assertFalse(result.err.any { it.startsWith("\tat ") }, result.err.toString())
        assertFalse(result.err.joinToString("\n").any { it == '\u202E' || (it.isISOControl() && it != '\n' && it != '\t') })
        // Nothing is timed when loading fails.
        assertEquals(emptyList(), result.out)
    }

    @Test
    fun `unwritable report is a runtime error after the timing lines`() {
        val result = run(data().toString(), shapes().toString(), dir.toString())
        assertEquals(EXIT_RUNTIME_ERROR, result.status, result.err.toString())
        assertEquals(1, result.err.size, result.err.toString())
        assertTrue(result.err.single().startsWith("shacl-era-cli: failed to write report $dir: "), result.err.toString())
        assertEquals(2, result.out.size, result.out.toString())
    }

    @Test
    fun `sanitize renders control and bidi characters visibly`() {
        assertEquals("a\\u001B[31m\\u202Eb\\u2066c\n\td", sanitize("a\u001B[31m\u202Eb\u2066c\n\td"))
        assertEquals("(no message)", sanitize(null))
    }

    @Test
    fun `the report is written as UTF-8 and an existing report survives a failed run`() {
        val report = dir.resolve("report.ttl")
        writeReport(report, "# caf\u00e9 \u2713\n")
        assertEquals("# caf\u00e9 \u2713\n", Files.readString(report, Charsets.UTF_8))
        assertEquals(listOf("report.ttl"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })

        // A run that fails before the report is written must not truncate the previous one.
        Files.writeString(report, "previous")
        val bad = shapes("bad.ttl", "@prefix ex: <http://example.org/> .\nex:a ex:b")
        assertEquals(EXIT_INPUT_ERROR, run(data().toString(), bad.toString(), report.toString()).status)
        assertEquals("previous", Files.readString(report))
        // A report path that is a directory fails without leaving a temporary file behind.
        val target = Files.createDirectory(dir.resolve("out"))
        Files.writeString(target.resolve("keep"), "x")
        assertFailsWith<IOException> { writeReport(target, "text") }
        assertEquals(listOf("keep"), Files.list(target).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `a read failure is a runtime error, not a parse error`() {
        val unreadable = dir.resolve("data.ttl").also { Files.writeString(it, "ex:a a ex:Thing .") }
        // The parser reports a read that fails half-way like a syntax error; the stream tells it was I/O.
        val failing =
            object : java.io.InputStream() {
                override fun read(): Int {
                    throw IOException("disk gone")
                }
            }
        val e = assertFailsWith<EraCliException> { parseTurtleStream(failing, "data", unreadable.toString()) }
        assertEquals(EXIT_RUNTIME_ERROR, e.status)
        assertTrue(e.message!!.startsWith("failed to read data file"), e.message)
    }

    @Test
    fun `an error such as OutOfMemoryError is one line and a runtime status, not a stack trace`() {
        val err = ByteArrayOutputStream()
        val status = runEraCli(listOf("a", "b", "c"), PrintStream(ByteArrayOutputStream()), PrintStream(err, true, Charsets.UTF_8), benchmark = { _, _, _, _ -> throw OutOfMemoryError("heap") })
        assertEquals(EXIT_RUNTIME_ERROR, status)
        assertEquals("shacl-era-cli: internal error: OutOfMemoryError: heap", err.toString(Charsets.UTF_8).trim())
    }
}
