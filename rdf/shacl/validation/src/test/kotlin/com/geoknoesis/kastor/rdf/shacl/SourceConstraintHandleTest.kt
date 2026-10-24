package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.shacl.native.CompiledShapeGraph
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The run-wide handle a result's `sh:sourceConstraint` deep copy is written from ([SourceConstraintTriples]). */
class SourceConstraintHandleTest {

    private fun ex(local: String) = Iri("http://example.com/ns#$local")

    private fun triples(n: Int, last: String): List<RdfTriple> =
        (0 until n).map { i -> RdfTriple(ex("s$i"), ex("p"), if (i == n - 1) ex(last) else TypedLiteral(i.toString(), XSD.integer)) }

    /** A list that counts how often its elements are read. */
    private class CountingList(private val inner: List<RdfTriple>) : AbstractList<RdfTriple>() {
        var reads = 0
        override val size: Int get() = inner.size
        override fun get(index: Int): RdfTriple { reads++; return inner[index] }
    }

    @Test
    fun `the handle of a validation run does not retain the compiled shapes graph`() {
        val shapes = Rdf.parse(
            """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
            @prefix sparql: <http://www.w3.org/ns/sparql#> .
            @prefix ex: <http://example.com/ns#> .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:expression [ sparql:less-than ( [ shnex:var "value" ] 5 ) ] ] .
            """.trimIndent(),
            RdfFormat.TURTLE,
        )
        val data = Rdf.parse("<http://example.com/ns#a> <http://example.com/ns#p> 7 .", RdfFormat.TURTLE)
        val report = NativeShaclValidator(ValidationConfig.default()) { JenaProvider().createRepository("memory", RdfConfig()) }
            .validate(data, shapes)
        val handle = report.violations.single().context[SOURCE_CONSTRAINT_TRIPLES_CONTEXT_KEY] as SourceConstraintTriples
        val describe = SourceConstraintTriples::class.java.getDeclaredField("describe").apply { isAccessible = true }.get(handle)
        val captured = describe.javaClass.declaredFields.map { it.apply { isAccessible = true }.get(describe) }
        assertTrue(captured.none { it is CompiledShapeGraph }, "the describe function captures ${captured.map { it?.javaClass }}")
    }

    @Test
    fun `handles of different shapes graphs of the same size have different hash codes`() {
        val a = SourceConstraintTriples(triples(50, "x")) { emptyList() }
        val b = SourceConstraintTriples(triples(50, "y")) { emptyList() }
        assertNotEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, b)
    }

    @Test
    fun `handles of equal shapes graphs compare equal across runs`() {
        val a = SourceConstraintTriples(triples(50, "x")) { emptyList() }
        val b = SourceConstraintTriples(triples(50, "x")) { emptyList() }
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `comparing handles of different shapes graphs does not walk the graphs each time`() {
        val left = CountingList(triples(10_000, "x"))
        val right = CountingList(triples(10_000, "y"))
        val a = SourceConstraintTriples(left) { emptyList() }
        val b = SourceConstraintTriples(right) { emptyList() }
        repeat(1_000) { assertFalse(a == b) }
        // At most one walk of each graph (the cached content hash), not one per comparison.
        assertTrue(left.reads <= 2 * 10_000 && right.reads <= 2 * 10_000, "reads: ${left.reads} / ${right.reads}")
    }
}
