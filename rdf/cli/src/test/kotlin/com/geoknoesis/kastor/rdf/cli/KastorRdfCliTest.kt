package com.geoknoesis.kastor.rdf.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class KastorRdfCliTest {
    @TempDir
    lateinit var dir: Path

    private class Result(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(args.toList(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"))
        return Result(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private fun file(name: String, content: String): String =
        dir.resolve(name).also { Files.writeString(it, content) }.toString()

    @Test
    fun `diff of N-Quads files with different named graphs is not isomorphic`() {
        val a = file("a.nq", "<http://e/s> <http://e/p> <http://e/o> <http://e/g1> .\n")
        val b = file("b.nq", "<http://e/s> <http://e/p> <http://e/o> <http://e/g2> .\n")
        val result = run("diff", a, b)
        assertEquals(2, result.code, result.err)
        assertTrue(result.err.contains("NOT ISOMORPHIC"), result.err)
    }

    @Test
    fun `diff of equivalent TriG datasets is isomorphic`() {
        val a = file("a.trig", "<http://e/g> { _:x <http://e/p> \"v\" }\n")
        val b = file("b.trig", "<http://e/g> { _:y <http://e/p> \"v\" }\n")
        val result = run("diff", a, b)
        assertEquals(0, result.code, result.err)
        assertTrue(result.out.contains("ISOMORPHIC"))
    }

    @Test
    fun `diff compares blank nodes shared across graphs as one dataset`() {
        val shared = file("shared.trig", "<http://e/g1> { _:x <http://e/p> \"1\" } <http://e/g2> { _:x <http://e/q> \"2\" }\n")
        val relabelled = file("relabelled.trig", "<http://e/g1> { _:y <http://e/p> \"1\" } <http://e/g2> { _:y <http://e/q> \"2\" }\n")
        val separate = file("separate.trig", "<http://e/g1> { _:y <http://e/p> \"1\" } <http://e/g2> { _:z <http://e/q> \"2\" }\n")

        val same = run("diff", shared, relabelled)
        assertEquals(0, same.code, same.err)
        assertTrue(same.out.contains("ISOMORPHIC"), same.out)

        // Each named graph is isomorphic on its own; only the cross-graph blank node identity differs.
        val different = run("diff", shared, separate)
        assertEquals(2, different.code, different.out)
        assertTrue(different.err.contains("NOT ISOMORPHIC") && different.err.contains("shared across graphs"), different.err)
        assertEquals(2, run("diff", separate, shared).code)
    }

    @Test
    fun `diff detects a triple moved between the default and a named graph`() {
        val a = file("a.nq", "<http://e/s> <http://e/p> <http://e/o> .\n<http://e/s> <http://e/p> <http://e/x> <http://e/g> .\n")
        val b = file("b.nq", "<http://e/s> <http://e/p> <http://e/x> .\n<http://e/s> <http://e/p> <http://e/o> <http://e/g> .\n")
        assertEquals(2, run("diff", a, b).code)
    }

    @Test
    fun `unknown extensions require an explicit format`() {
        val path = file("data.txt", "<http://e/s> <http://e/p> <http://e/o> .\n")
        val result = run("parse", path)
        assertEquals(1, result.code)
        assertTrue(result.err.contains("FORMAT"), result.err)
        assertEquals(0, run("parse", path, "NTRIPLES").code)
    }

    @Test
    fun `parse reports named graphs and parse errors`() {
        val trig = file("d.trig", "<http://e/s> <http://e/p> 1 . <http://e/g> { <http://e/s> <http://e/p> 2 }\n")
        val ok = run("parse", trig)
        assertEquals(0, ok.code, ok.err)
        assertTrue(ok.out.contains("triples: 2") && ok.out.contains("named graphs: 1"), ok.out)
        val broken = file("broken.ttl", "<http://e/s> <http://e/p> .")
        assertEquals(1, run("parse", broken).code)
    }

    @Test
    fun `to-turtle refuses to drop named graphs`() {
        val trig = file("d.trig", "<http://e/g> { <http://e/s> <http://e/p> 2 }\n")
        assertEquals(1, run("to-turtle", trig).code)
    }

    @Test
    fun `extra arguments are usage errors`() {
        val ttl = file("a.ttl", "<http://e/s> <http://e/p> <http://e/o> .\n")
        for (args in listOf(arrayOf("parse", ttl, "TURTLE", "extra"), arrayOf("to-turtle", ttl, "TURTLE", "extra"), arrayOf("diff", ttl, ttl, "TURTLE", "extra"), arrayOf("help", "extra"))) {
            val result = run(*args)
            assertEquals(EXIT_USAGE, result.code, "${args.toList()}: ${result.err}")
            assertTrue(result.err.contains("Unexpected argument"), result.err)
        }
    }

    @Test
    fun `an invalid path is a usage error with a one-line message`() {
        val result = run("parse", "bad\u0000path.ttl")
        assertEquals(EXIT_USAGE, result.code, result.err)
        assertEquals(1, result.err.trim().lines().size, result.err)
    }

    @Test
    fun `runtime failures map to a distinct exit status with a one-line message`() {
        val failures =
            listOf(
                java.io.IOException("disk on fire"),
                IllegalArgumentException("bad value"),
                org.apache.jena.riot.RiotException("jena broke"),
                IllegalStateException("internal"),
            )
        for (failure in failures) {
            val err = java.io.ByteArrayOutputStream()
            val code = exitCodeForFailure(failure, PrintStream(err, true, "UTF-8"))
            val text = err.toString("UTF-8")
            assertEquals(EXIT_RUNTIME_ERROR, code, text)
            assertEquals(1, text.trim().lines().size, text)
            assertTrue(text.startsWith("kastor-rdf: error: ${failure.javaClass.simpleName}: ${failure.message}"), text)
        }
        assertTrue(EXIT_RUNTIME_ERROR != EXIT_USAGE && EXIT_RUNTIME_ERROR != EXIT_NOT_ISOMORPHIC)
    }

    @Test
    fun `usage lists the exit statuses`() {
        val result = run("help")
        assertEquals(EXIT_OK, result.code)
        assertTrue(result.out.contains("Exit status: 0 success; 1 usage or input error"), result.out)
    }

    @Test
    fun `an SLF4J binding prints library warnings to stderr at WARN level`() {
        assertEquals("org.slf4j.simple.SimpleLoggerFactory", org.slf4j.LoggerFactory.getILoggerFactory().javaClass.name)
        val log = org.slf4j.LoggerFactory.getLogger("org.apache.jena.riot")
        assertTrue(log.isWarnEnabled)
        assertFalse(log.isInfoEnabled)
    }
}
