package com.geoknoesis.kastor.rdf.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Every command against every input format: a format that can carry named graphs (TriG, N-Quads, JSON-LD) keeps them
 * in every command, and a command whose output cannot carry them (`to-turtle`) refuses the input instead of dropping
 * them. Graph-only formats (Turtle, N-Triples, RDF/XML) read as a default graph in every command.
 */
class CliFormatMatrixTest {
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

    /** A dataset format and how it writes `ex:s ex:p "d"` in the default graph plus `ex:s ex:p <value>` in graph `ex:g`. */
    private class DatasetFormat(val extension: String, val format: String, val write: (String) -> String)

    private val datasetFormats =
        listOf(
            DatasetFormat("trig", "TRIG") { v -> "<http://ex.org/s> <http://ex.org/p> \"d\" .\n<http://ex.org/g> { <http://ex.org/s> <http://ex.org/p> \"$v\" }\n" },
            DatasetFormat("nq", "NQUADS") { v -> "<http://ex.org/s> <http://ex.org/p> \"d\" .\n<http://ex.org/s> <http://ex.org/p> \"$v\" <http://ex.org/g> .\n" },
            DatasetFormat("jsonld", "JSON-LD") { v ->
                """
                {
                  "@graph": [
                    { "@id": "http://ex.org/s", "http://ex.org/p": "d" },
                    { "@id": "http://ex.org/g", "@graph": [ { "@id": "http://ex.org/s", "http://ex.org/p": "$v" } ] }
                  ]
                }
                """.trimIndent()
            },
        )

    /** A graph format and how it writes `ex:s ex:p "d"`. */
    private class GraphFormat(val extension: String, val format: String, val content: String)

    private val graphFormats =
        listOf(
            GraphFormat("ttl", "TURTLE", "<http://ex.org/s> <http://ex.org/p> \"d\" .\n"),
            GraphFormat("nt", "NTRIPLES", "<http://ex.org/s> <http://ex.org/p> \"d\" .\n"),
            GraphFormat("rdf", "RDFXML", """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="http://ex.org/s"><p xmlns="http://ex.org/">d</p></rdf:Description></rdf:RDF>"""),
            // Datasets with an empty set of named graphs read like graphs.
            GraphFormat("trig", "TRIG", "<http://ex.org/s> <http://ex.org/p> \"d\" .\n"),
            GraphFormat("nq", "NQUADS", "<http://ex.org/s> <http://ex.org/p> \"d\" .\n"),
            GraphFormat("jsonld", "JSON-LD", """{ "@id": "http://ex.org/s", "http://ex.org/p": "d" }"""),
        )

    @TestFactory
    fun `dataset formats keep their named graphs in every command`(): List<DynamicTest> =
        datasetFormats.flatMap { f ->
            listOf(
                dynamicTest("parse ${f.format}") {
                    val result = run("parse", file("p.${f.extension}", f.write("one")))
                    assertEquals(0, result.code, result.err)
                    assertEquals("OK - triples: 2, named graphs: 1", result.out.trim())
                },
                dynamicTest("parse ${f.format} with an explicit format") {
                    val result = run("parse", file("p-explicit.data", f.write("one")), f.format)
                    assertEquals(0, result.code, result.err)
                    assertEquals("OK - triples: 2, named graphs: 1", result.out.trim())
                },
                dynamicTest("to-turtle ${f.format} refuses named graphs") {
                    val result = run("to-turtle", file("t.${f.extension}", f.write("one")))
                    assertEquals(EXIT_USAGE, result.code, result.out)
                    assertTrue(result.err.contains("1 named graph(s)"), result.err)
                    assertEquals("", result.out, "nothing is printed for a refused input")
                },
                dynamicTest("diff ${f.format}: equal datasets") {
                    val result = run("diff", file("a.${f.extension}", f.write("one")), file("b.${f.extension}", f.write("one")))
                    assertEquals(EXIT_OK, result.code, result.err)
                    assertTrue(result.out.contains("(2 triples in first file, 2 in second)"), result.out)
                },
                dynamicTest("diff ${f.format}: datasets that differ only in a named graph") {
                    val result = run("diff", file("c.${f.extension}", f.write("one")), file("d.${f.extension}", f.write("TWO")))
                    assertEquals(EXIT_NOT_ISOMORPHIC, result.code, result.out)
                    assertTrue(result.err.contains("<http://ex.org/g> is not isomorphic"), result.err)
                    assertFalse(result.out.contains("ISOMORPHIC"), result.out)
                },
            )
        } +
            // Across formats: the same dataset written in each pair of dataset formats is isomorphic.
            datasetFormats.flatMap { a ->
                datasetFormats.filter { it !== a }.map { b ->
                    dynamicTest("diff ${a.format} against ${b.format}") {
                        val first = file("x-${a.extension}.${a.extension}", a.write("one"))
                        val second = file("y-${b.extension}.${b.extension}", b.write("one"))
                        // diff takes one FORMAT for both inputs, so each is inferred from its extension.
                        val result = run("diff", first, second)
                        assertEquals(EXIT_OK, result.code, result.err)
                    }
                }
            }

    @TestFactory
    fun `a dataset never compares equal to its default graph alone`(): List<DynamicTest> =
        datasetFormats.map { f ->
            dynamicTest("diff ${f.format} against Turtle") {
                val result = run("diff", file("ds.${f.extension}", f.write("one")), file("g.ttl", "<http://ex.org/s> <http://ex.org/p> \"d\" .\n"))
                assertEquals(EXIT_NOT_ISOMORPHIC, result.code, result.out)
                assertTrue(result.err.contains("named graphs differ"), result.err)
            }
        }

    @TestFactory
    fun `graph inputs read as a default graph in every command`(): List<DynamicTest> =
        graphFormats.flatMap { f ->
            listOf(
                dynamicTest("parse ${f.format}") {
                    val result = run("parse", file("g.${f.extension}", f.content))
                    assertEquals(0, result.code, result.err)
                    assertEquals("OK - triples: 1", result.out.trim())
                },
                dynamicTest("to-turtle ${f.format}") {
                    val result = run("to-turtle", file("g.${f.extension}", f.content))
                    assertEquals(0, result.code, result.err)
                    assertTrue(result.out.contains("http://ex.org/s") && result.out.contains("\"d\""), result.out)
                },
                dynamicTest("diff ${f.format} against N-Triples") {
                    val result = run("diff", file("g.${f.extension}", f.content), file("ref.nt", "<http://ex.org/s> <http://ex.org/p> \"d\" .\n"))
                    assertEquals(EXIT_OK, result.code, result.err)
                },
            )
        }
}
