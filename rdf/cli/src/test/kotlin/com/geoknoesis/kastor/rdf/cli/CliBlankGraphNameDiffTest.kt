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

/**
 * `diff` on datasets whose graphs are named by blank nodes: graph names are compared up to blank node relabelling,
 * like every other blank node (dataset isomorphism), and the provider's skolem IRIs never reach the output.
 */
class CliBlankGraphNameDiffTest {
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

    private fun assertIsomorphic(a: String, b: String) {
        val result = run("diff", a, b)
        assertEquals(EXIT_OK, result.code, result.err)
        assertTrue(result.out.startsWith("ISOMORPHIC"), result.out)
    }

    private fun assertNotIsomorphic(a: String, b: String): String {
        val result = run("diff", a, b)
        assertEquals(EXIT_NOT_ISOMORPHIC, result.code, result.out + result.err)
        assertTrue(result.err.startsWith("NOT ISOMORPHIC"), result.err)
        assertFalse(result.err.contains("urn:kastor:skolem"), "internal skolem graph names leaked: ${result.err}")
        return result.err
    }

    @Test
    fun `a TriG file with a blank-node graph name is isomorphic to itself`() {
        val trig = file("blank.trig", "_:g { <http://e/s> <http://e/p> <http://e/o> . }\n")
        assertIsomorphic(trig, trig)
    }

    @Test
    fun `an N-Quads file with a blank-node graph label is isomorphic to itself`() {
        val nq = file("blank.nq", "<http://e/s> <http://e/p> <http://e/o> _:h .\n")
        assertIsomorphic(nq, nq)
    }

    @Test
    fun `relabelled blank-node graph names are isomorphic, across TriG and N-Quads too`() {
        val trig = file(
            "a.trig",
            "<http://e/g> { <http://e/s> <http://e/p> _:x . }\n" +
                "_:g1 { <http://e/s> <http://e/p> \"one\" . }\n" +
                "_:g2 { <http://e/s> <http://e/p> \"two\" . _:x <http://e/q> <http://e/o> . }\n",
        )
        val relabelled = file(
            "b.trig",
            "_:other2 { _:y <http://e/q> <http://e/o> . <http://e/s> <http://e/p> \"two\" . }\n" +
                "_:other1 { <http://e/s> <http://e/p> \"one\" . }\n" +
                "<http://e/g> { <http://e/s> <http://e/p> _:y . }\n",
        )
        val nquads = file(
            "c.nq",
            "<http://e/s> <http://e/p> _:z <http://e/g> .\n" +
                "<http://e/s> <http://e/p> \"one\" _:k1 .\n" +
                "<http://e/s> <http://e/p> \"two\" _:k2 .\n" +
                "_:z <http://e/q> <http://e/o> _:k2 .\n",
        )
        assertIsomorphic(trig, relabelled)
        assertIsomorphic(trig, nquads)
    }

    @Test
    fun `a blank node naming a graph must correspond to the same node inside the triples`() {
        val linked = file("linked.trig", "_:g { _:g <http://e/p> <http://e/o> . }\n")
        val relinked = file("relinked.trig", "_:h { _:h <http://e/p> <http://e/o> . }\n")
        val unlinked = file("unlinked.trig", "_:h { _:k <http://e/p> <http://e/o> . }\n")
        assertIsomorphic(linked, relinked)
        assertNotIsomorphic(linked, unlinked)
    }

    @Test
    fun `different content in a blank-node graph is reported without skolem names`() {
        val a = file("a.trig", "_:g { <http://e/s> <http://e/p> \"a\" . }\n")
        val b = file("b.trig", "_:g { <http://e/s> <http://e/p> \"b\" . }\n")
        val err = assertNotIsomorphic(a, b)
        assertTrue(err.contains("graph(s) named by blank nodes"), err)
    }

    @Test
    fun `a different number of blank-node graphs is reported as such`() {
        val one = file("one.trig", "_:g { <http://e/s> <http://e/p> <http://e/o> . }\n")
        val two = file("two.trig", "_:g { <http://e/s> <http://e/p> <http://e/o> . }\n_:h { <http://e/s> <http://e/p> <http://e/o> . }\n")
        val err = assertNotIsomorphic(one, two)
        assertTrue(err.contains("graphs named by blank nodes: 1 vs 2"), err)
    }

    @Test
    fun `an IRI-named graph does not match a blank-node-named graph`() {
        val iri = file("iri.trig", "<http://e/g> { <http://e/s> <http://e/p> <http://e/o> . }\n")
        val blank = file("blank.trig", "_:g { <http://e/s> <http://e/p> <http://e/o> . }\n")
        val err = assertNotIsomorphic(iri, blank)
        assertTrue(err.contains("named graphs differ: [http://e/g] vs []"), err)
    }
}
