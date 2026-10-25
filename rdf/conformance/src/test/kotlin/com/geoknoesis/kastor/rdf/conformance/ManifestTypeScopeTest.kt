package com.geoknoesis.kastor.rdf.conformance

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every W3C test type is either run or deliberately out of scope: an unknown `rdft:Test*` type fails the manifest
 * parse instead of dropping its rows, and so does a test the harness cannot run.
 *
 * Tagged **`conformance-smoke`**: it needs no W3C data.
 */
@Tag("conformance-smoke")
class ManifestTypeScopeTest {
    @TempDir
    lateinit var dir: Path

    private fun manifest(vararg entries: Pair<String, String>): Path {
        Files.writeString(dir.resolve("data.ttl"), "<http://e/s> <http://e/p> <http://e/o> .\n")
        val body = entries.joinToString("\n") { (id, type) -> "<#$id> a $type ; mf:name \"$id\" ; mf:action <data.ttl> ." }
        val text = """
            @prefix mf: <http://www.w3.org/2001/sw/DataAccess/tests/test-manifest#> .
            @prefix rdft: <http://www.w3.org/ns/rdftest#> .
            <> a mf:Manifest ; mf:entries ( ${entries.joinToString(" ") { "<#${it.first}>" }} ) .
        """.trimIndent() + "\n" + body + "\n"
        return dir.resolve("manifest.ttl").also { Files.writeString(it, text) }
    }

    @Test
    fun `known types are run and out-of-scope types are left out`() {
        val cases = Rdf12ManifestParser.parse(
            manifest(
                "turtle" to "rdft:TestTurtlePositiveSyntax",
                "xml" to "rdft:TestXMLEval",
                "c14n" to "rdft:TestNTriplesPositiveC14N",
                "entailment" to "mf:PositiveEntailmentTest",
            ),
        )
        assertEquals(listOf("turtle"), cases.map { it.name })
    }

    @Test
    fun `an unknown test type fails the parse instead of vanishing`() {
        // The spelling the corpus README uses; the manifests spell it rdft:TestTrigPositiveSyntax today.
        val failure = assertFailsWith<IllegalStateException> {
            Rdf12ManifestParser.parse(manifest("turtle" to "rdft:TestTurtlePositiveSyntax", "trig" to "rdft:TestTriGPositiveSyntax"))
        }
        assertTrue("Unrecognised W3C test type" in failure.message.orEmpty() && "TestTriGPositiveSyntax" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a test without a local action fails the parse`() {
        val path = manifest("turtle" to "rdft:TestTurtlePositiveSyntax")
        Files.writeString(path, Files.readString(path).replace("mf:action <data.ttl>", "mf:action <http://example.org/remote.ttl>"))
        val failure = assertFailsWith<IllegalStateException> { Rdf12ManifestParser.parse(path) }
        assertTrue("is not a local file" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `the count floor reports each format that falls short`() {
        val cases = Rdf12ManifestParser.parse(manifest("turtle" to "rdft:TestTurtlePositiveSyntax"))
        val shortfall = checkNotNull(Rdf12ConformanceRunner.shortfall(cases))
        for (format in TestFormat.entries) assertTrue("$format:" in shortfall, shortfall)
    }
}
