package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `PREFIX` prologue of a SHACL-SPARQL query declares only the prefixes the query may use: with every declaration
 * of the shapes graph in every query, n declarations and n queries cost n * n in text, checks and memory.
 */
class SparqlPrologueScaleTest {

    private val header = "@prefix sh: <http://www.w3.org/ns/shacl#> .\n@prefix ex: <http://example.org/> .\n"

    private fun shapes(n: Int, query: (Int) -> String = { "SELECT \$this WHERE { \$this p$it:q 1 }" }) = Rdf.parse(
        header + buildString {
            append("ex:G a sh:ShapesGraph .\n")
            repeat(n) { append("ex:G sh:declare [ sh:prefix \"p$it\" ; sh:namespace \"http://example.org/p$it/\" ] .\n") }
            repeat(n) { append("ex:S$it a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:select \"\"\"${query(it)}\"\"\" ] .\n") }
        },
        RdfFormat.TURTLE,
    ).getTriples()

    private fun work(n: Int): Long {
        var reads = 0L
        ShapesCompiler.compile(shapes(n), ValidationConfig(), ValidationBudget(Duration.ofDays(1)) { reads++; 0L })
        return reads
    }

    @Test
    fun `n declarations and n queries compile in linear work`() {
        val small = work(500)
        val large = work(2_000)
        // Quadratic work grows 16 times; linear work 4 times.
        assertTrue(large < small * 8, "work for 500: $small, for 2000: $large")
    }

    @Test
    fun `the prologue holds the prefixes the query uses, in every decoding view`() {
        val graph = ShapesCompiler.compile(shapes(3) { "SELECT \$this WHERE { \$this p1:q 1 }" }, ValidationConfig())
        val texts = graph.orderedNodeShapes.flatMap { it.nodeConstraints }.map { it.unwrapped() }.filterIsInstance<PropertyConstraint.Sparql>()
            .map { it.template.bind(emptyMap()).text }
        assertEquals(3, texts.size)
        for (text in texts) {
            assertTrue("PREFIX p1: <http://example.org/p1/>" in text, text)
            assertTrue("PREFIX p0:" !in text && "PREFIX p2:" !in text, text)
        }
        // A prefixed name spelled with a codepoint escape for its colon (RDF4J decodes it first) keeps its prefix.
        val escaped = ShapesCompiler.compile(shapes(3) { "SELECT \$this WHERE { \$this p2\\\\u003Aq 1 }" }, ValidationConfig())
        val text = escaped.orderedNodeShapes.flatMap { it.nodeConstraints }.map { it.unwrapped() }.filterIsInstance<PropertyConstraint.Sparql>().first().template.bind(emptyMap()).text
        assertTrue("PREFIX p2: <http://example.org/p2/>" in text, text)
    }

    @Test
    fun `an ill-formed declaration is rejected even when no query uses it`() {
        val bad = Rdf.parse(
            header + "ex:G a sh:ShapesGraph ; sh:declare [ sh:prefix \"not a prefix\" ; sh:namespace \"http://example.org/x/\" ] .\n" +
                "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:select \"SELECT \$this WHERE { \$this <http://example.org/q> 1 }\" ] .\n",
            RdfFormat.TURTLE,
        ).getTriples()
        assertThrows(ShapeCompileException::class.java) { ShapesCompiler.compile(bad, ValidationConfig()) }
    }
}
