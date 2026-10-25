package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A computed shape reference read from the data may name any shape, so each shape that has one depends on every
 * shape. Those dependencies stay symbolic: compiling n such shapes costs work linear in n, not n * n edges.
 */
class ComputedShapeDependencyScaleTest {

    private fun shapes(n: Int) = Rdf.parse(
        buildString {
            append("@prefix sh: <http://www.w3.org/ns/shacl#> .\n@prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .\n")
            append("@prefix ex: <http://example.org/> .\n")
            repeat(n) { append("ex:S$it a sh:NodeShape ; sh:targetNode ex:a ; sh:nodeByExpression [ shnex:pathValues ex:p ] .\n") }
            append("ex:Leaf a sh:NodeShape ; sh:class ex:C .\n")
        },
        RdfFormat.TURTLE,
    ).getTriples()

    /** The work of compiling [n] shapes, as the number of budget clock reads (one per 64 ticks, plus checks). */
    private fun work(n: Int): Long {
        var reads = 0L
        val budget = ValidationBudget(Duration.ofDays(1)) { reads++; 0L }
        val compiled = ShapesCompiler.compile(shapes(n), ValidationConfig(), budget)
        // Every ex:S<i> can name every shape, itself included: one recursive component; ex:Leaf depends on nothing.
        assertEquals(n, compiled.recursiveComponents.size)
        assertEquals(1, compiled.recursiveComponents.values.toSet().size)
        assertTrue(Iri("http://example.org/Leaf") !in compiled.recursiveComponents)
        return reads
    }

    @Test
    fun `dependencies on every shape cost linear work`() {
        val small = work(1_000)
        val large = work(4_000)
        // Quadratic work would grow 16 times; linear work 4 times (plus noise).
        assertTrue(large < small * 8, "work for 1000 shapes: $small, for 4000: $large")
    }
}
