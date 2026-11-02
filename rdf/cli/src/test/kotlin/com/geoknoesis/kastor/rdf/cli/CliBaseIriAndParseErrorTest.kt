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
    fun `relative IRIs in JSON-LD are an error without --base, not dropped statements`() {
        // JSON-LD's toRdf algorithm skips statements with a relative IRI: these used to parse to nothing (or lose
        // the one statement), so diff called different files ISOMORPHIC, parse said OK - triples: 0, and to-turtle
        // printed an empty document for a named graph it should have refused.
        val a = file("a.jsonld", """{"@id": "s", "http://e/p": {"@id": "o"}}""")
        val b = file("b.jsonld", """{"@id": "x", "http://e/p": {"@id": "y"}}""")
        val partly = file("partly.jsonld", """{"@id": "http://e/s", "http://e/p": {"@id": "o"}, "http://e/q": "v"}""")
        val withoutRelative = file("q.jsonld", """{"@id": "http://e/s", "http://e/q": "v"}""")
        val namedGraph = file("g.jsonld", """{"@id": "g", "@graph": [{"@id": "http://e/s", "http://e/p": "v"}]}""")
        val runs = listOf(
            arrayOf("diff", a, b) to a,
            arrayOf("parse", a) to a,
            arrayOf("diff", partly, withoutRelative) to partly,
            arrayOf("to-turtle", namedGraph) to namedGraph,
        )
        for ((args, culprit) in runs) {
            val result = run(*args)
            assertEquals(EXIT_USAGE, result.code, "${args.toList()}: ${result.out} ${result.err}")
            assertTrue(result.err.startsWith("Parse error in $culprit: ") && result.err.contains("relative IRI"), result.err)
            assertEquals(1, result.err.trim().lines().size, result.err)
        }

        assertEquals(EXIT_NOT_ISOMORPHIC, run("diff", "--base", "http://e/", a, b).code)
        val parsed = run("parse", "--base", "http://e/", a)
        assertEquals(EXIT_OK, parsed.code, parsed.err)
        assertTrue(parsed.out.contains("triples: 1"), parsed.out)
        val turtle = run("to-turtle", "--base=http://e/data/", a)
        assertEquals(EXIT_OK, turtle.code, turtle.err)
        assertTrue(turtle.out.contains("http://e/data/s") && turtle.out.contains("http://e/data/o"), turtle.out)
    }

    @Test
    fun `JSON-LD that drops statements is an error with or without --base`() {
        // "@base": null leaves relative references unresolved whatever --base says; an ill-formed language tag, a
        // malformed IRI or an undefined term would also be skipped by JSON-LD's toRdf. Each used to parse to fewer
        // statements (parse: OK - triples: 0; diff: ISOMORPHIC) instead of failing.
        val baseNull = file("bn.jsonld", """{"@context": {"@base": null}, "@id": "s", "http://e/p": {"@id": "o"}}""")
        val baseNull2 = file("bn2.jsonld", """{"@context": {"@base": null}, "@id": "x", "http://e/p": {"@id": "y"}}""")
        val mixed = file("bnm.jsonld", """{"@context": {"@base": null}, "@id": "http://e/s", "http://e/p": {"@id": "o"}, "http://e/q": "v"}""")
        val only = file("q.jsonld", """{"@id": "http://e/s", "http://e/q": "v"}""")
        val graph = file("bng.jsonld", """{"@context": {"@base": null}, "@id": "g", "@graph": [{"@id": "http://e/s", "http://e/p": "v"}]}""")
        val language = file("lang.jsonld", """{"@id": "http://e/s", "http://e/p": {"@value": "v", "@language": "not a tag!"}}""")
        val badIri = file("iri.jsonld", """{"@id": "http://e/a b", "http://e/p": "v"}""")
        val undefined = file("term.jsonld", """{"@id": "http://e/s", "name": "v", "http://e/q": "v"}""")
        val runs = listOf(
            arrayOf("parse", baseNull) to baseNull,
            arrayOf("to-turtle", baseNull) to baseNull,
            arrayOf("diff", baseNull, baseNull2) to baseNull,
            arrayOf("diff", mixed, only) to mixed,
            arrayOf("to-turtle", graph) to graph,
            arrayOf("parse", language) to language,
            arrayOf("parse", badIri) to badIri,
            arrayOf("diff", undefined, only) to undefined,
        )
        for ((args, culprit) in runs) {
            for (base in listOf(emptyList(), listOf("--base", "http://e/"))) {
                val result = run(*(listOf(args[0]) + base + args.drop(1)).toTypedArray())
                assertEquals(EXIT_USAGE, result.code, "${args.toList()} $base: ${result.out} ${result.err}")
                assertTrue(result.err.startsWith("Parse error in $culprit: "), result.err)
                assertEquals(1, result.err.trim().lines().size, result.err)
            }
        }
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
