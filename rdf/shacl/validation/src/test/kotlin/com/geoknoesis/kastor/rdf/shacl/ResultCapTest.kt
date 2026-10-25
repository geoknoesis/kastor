package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.shacl.native.graphFromTriples
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A constraint whose results are pairs (`sh:lessThan`, `sh:lessThanOrEquals`) or query solutions (`sh:sparql`) can
 * have quadratically many results for one focus node. The engine builds at most `maxViolations + 1` of them per
 * evaluation (the rest could not be reported), so the report is the same, truncated, without the memory.
 */
class ResultCapTest {

    private val ex = "http://example.org/"
    private fun ex(local: String) = Iri(ex + local)
    private fun int(n: Int) = TypedLiteral(n.toString(), XSD.integer)

    /** ex:a has [n] ex:p values 1_000_000.. and [n] ex:q values 0..: every (p, q) pair violates p < q. */
    private fun data(n: Int) = graphFromTriples((0 until n).flatMap { listOf(RdfTriple(ex("a"), ex("p"), int(1_000_000 + it)), RdfTriple(ex("a"), ex("q"), int(it))) })

    /** A validator that counts the reads of its budget clock: one per 64 ticks of the work done. */
    private class Counted(config: ValidationConfig = ValidationConfig.default()) {
        var reads = 0L
        val validator = NativeShaclValidator(config).also { it.budgetClock = { reads++; 0L } }
    }

    private fun shapes(vararg extra: RdfTriple) = graphFromTriples(
        listOf(
            RdfTriple(ex("S"), RDF.type, SHACL.NodeShape),
            RdfTriple(ex("S"), SHACL.targetNode, ex("a")),
        ) + extra,
    )

    @Test
    fun `quadratically many lessThan pairs are built only up to the cap`() {
        val shapes = shapes(
            RdfTriple(ex("S"), SHACL.`property`, BlankNode("ps")),
            RdfTriple(BlankNode("ps"), SHACL.path, ex("p")),
            RdfTriple(BlankNode("ps"), SHACL.lessThan, ex("q")),
        )
        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            // 4,000,000 violating pairs: comparing them all would read the clock about 62,500 times.
            val counted = Counted()
            val report = counted.validator.validate(data(2_000), shapes)
            assertFalse(report.isValid)
            assertTrue(report.violationsTruncated)
            assertEquals(1_000, report.violations.size)
            assertTrue(counted.reads < 5_000, "${counted.reads}")
        }
        // Below the cap every pair is reported.
        assertEquals(9, NativeShaclValidator(ValidationConfig.default()).validate(data(3), shapes).violations.size)
    }

    private fun sparql(select: String) = shapes(
        RdfTriple(ex("S"), SHACL.sparql, BlankNode("q")),
        RdfTriple(BlankNode("q"), SHACL.select, TypedLiteral(select, XSD.string)),
    )

    @Test
    fun `quadratically many SPARQL solutions are built only up to the cap`() {
        val shapes = sparql("SELECT \$this ?x ?y WHERE { \$this <${ex}p> ?x . \$this <${ex}p> ?y }")
        assertTimeoutPreemptively(Duration.ofSeconds(120)) {
            // 4,000,000 solutions: reading them all would read the clock about 62,500 times.
            val counted = Counted()
            val report = counted.validator.validate(data(2_000), shapes)
            assertTrue(report.violationsTruncated)
            assertEquals(1_000, report.violations.size)
            assertTrue(counted.reads < 5_000, "${counted.reads}")
        }
        assertEquals(4, NativeShaclValidator(ValidationConfig(maxViolations = 10)).validate(data(2), shapes).violations.size)
    }

    @Test
    fun `a failure solution after the cap still fails validation`() {
        // The ?failure row comes last: the rows before it exceed the cap, and the query must still be read to its end.
        val shapes = sparql(
            "SELECT \$this ?failure WHERE { { \$this <${ex}p> ?x . BIND (false AS ?failure) } UNION { BIND (true AS ?failure) } }",
        )
        assertThrows(ShaclValidationException::class.java) {
            NativeShaclValidator(ValidationConfig(maxViolations = 5)).validate(data(50), shapes)
        }
    }
}
