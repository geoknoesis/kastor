package com.geoknoesis.kastor.rdf.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** Parse errors name their input; relative IRIs need `--base`, which every command takes. */
class CliBaseIriAndParseErrorTest {
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
    fun `a parse error names the input it is in`() {
        val good = file("good.nt", "<http://e/s> <http://e/p> <http://e/o> .\n")
        val bad = file("bad.ttl", "<http://e/s> <http://e/p> .\n")
        for (args in listOf(arrayOf("diff", good, bad), arrayOf("diff", bad, good), arrayOf("parse", bad), arrayOf("to-turtle", bad))) {
            val result = run(*args)
            assertEquals(EXIT_USAGE, result.code, result.err)
            assertTrue(result.err.startsWith("Parse error in $bad: "), result.err)
            assertEquals(1, result.err.trim().lines().size, result.err)
        }
    }

    @Test
    fun `relative IRIs are an error without --base and resolved with it`() {
        val relative = file("rel.ttl", "<s> <http://e/p> <o> .\n")
        val without = run("parse", relative)
        assertEquals(EXIT_USAGE, without.code, without.err)
        assertTrue(without.err.contains("Relative IRI") && without.err.contains("rel.ttl"), without.err)

        assertEquals(EXIT_OK, run("parse", "--base", "http://e/", relative).code)
        val turtle = run("to-turtle", "--base=http://e/data/", relative)
        assertEquals(EXIT_OK, turtle.code, turtle.err)
        assertTrue(turtle.out.contains("http://e/data/s") && turtle.out.contains("http://e/data/o"), turtle.out)

        val absolute = file("abs.nt", "<http://e/s> <http://e/p> <http://e/o> .\n")
        assertEquals(EXIT_OK, run("diff", "--base", "http://e/", relative, absolute).code)
        assertEquals(EXIT_NOT_ISOMORPHIC, run("diff", "--base", "http://other/", relative, absolute).code)
    }

    @Test
    fun `--base must be an absolute IRI and must have a value`() {
        val path = file("data.nt", "<http://e/s> <http://e/p> <http://e/o> .\n")
        for (base in listOf("relative/path", "", "http://e/ space")) {
            val result = run("parse", "--base", base, path)
            assertEquals(EXIT_USAGE, result.code, result.err)
            assertTrue(result.err.contains("--base must be an absolute IRI"), result.err)
        }
        val missing = run("parse", path, "--base")
        assertEquals(EXIT_USAGE, missing.code)
        assertTrue(missing.err.contains("--base requires an absolute IRI"), missing.err)
        val unknown = run("parse", "--bogus", path)
        assertTrue(unknown.err.contains("Unknown option: --bogus"), unknown.err)
        // After -- a name that looks like an option is a file.
        assertEquals(EXIT_USAGE, run("parse", "--", "--base").code)
    }
}
