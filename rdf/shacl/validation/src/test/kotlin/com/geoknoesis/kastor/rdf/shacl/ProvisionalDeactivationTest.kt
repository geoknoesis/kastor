package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * A per-focus-node `sh:deactivated` (SHACL 1.2 Core §3.1.6) that reads a conformance answer the recursion solver has
 * not settled yet. "Deactivated, or the constraints hold" is a Kleene disjunction: with the deactivation undefined for
 * now, constraints that hold make the shape (or constraint) conform, whatever the deactivation turns out to be. Only
 * when they do not hold is the answer undefined for now.
 *
 * In every case below, ex:S1 is deactivated for ex:a iff ex:a conforms to ex:S2, and ex:S2 is `sh:not ex:S1`, so
 * the two shapes form one recursive component through a negative read. Whatever the deactivation, ex:S1's
 * constraints hold for ex:a: ex:S1 conforms, and so ex:S2 fails. The engine used to report ex:S1 undefined while the
 * deactivation was unsettled, which never settled, and aborted the run.
 */
class ProvisionalDeactivationTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
        @prefix ex: <http://example.com/ns#> .
    """.trimIndent()

    private val deactivated = """sh:deactivated [ shnex:conformsToShape ( [ shnex:var "focusNode" ] ex:S2 ) ]"""
    private val annotation = "{| $deactivated |}"
    private val data = "ex:a a ex:C ; ex:p ex:b ."
    private val select = "sh:select '''SELECT ?this WHERE { FILTER (false) }'''"

    private fun validate(shapes: String): ValidationReport {
        val all = """
            ex:T a sh:NodeShape ; sh:nodeKind sh:IRI .
            ex:S2 a sh:NodeShape ; sh:targetNode ex:a ; sh:not ex:S1 .
            ex:S1 a sh:NodeShape ; sh:targetNode ex:a ; $shapes .
        """
        val g = { ttl: String -> Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE) }
        return NativeShaclValidator(ValidationConfig.default()) { JenaProvider().createRepository("memory", RdfConfig()) }.validate(g(data), g(all))
    }

    private val cases = linkedMapOf(
        "node shape" to "sh:class ex:C ; $deactivated",
        "node shape without constraints" to deactivated,
        "property shape" to "sh:property [ sh:path ex:p ; sh:maxCount 1 ; $deactivated ]",
        "reifier of sh:node" to "sh:node ex:T $annotation",
        "reifier of sh:property" to "sh:property ex:P $annotation . ex:P sh:path ex:p ; sh:minCount 1",
        "reifier of a node shape constraint" to "sh:class ex:C $annotation",
        "reifier of a logical constraint" to "sh:or ( ex:T ) $annotation",
        "reifier of sh:closed" to "sh:closed true $annotation ; sh:ignoredProperties ( rdf:type ex:p )",
        "reifier of a property shape constraint" to "sh:property [ sh:path ex:p ; sh:nodeKind sh:IRI $annotation ]",
        "reifier of a property shape's logical constraint" to "sh:property [ sh:path ex:p ; sh:or ( ex:T ) $annotation ]",
        "reifier of a nested property shape" to "sh:property [ sh:path ex:p ; sh:property ex:Q $annotation ] . ex:Q sh:path ex:p ; sh:maxCount 0",
        "reifier of sh:reifierShape" to "sh:property [ sh:path ex:p ; sh:reifierShape ex:T $annotation ]",
        "SPARQL constraint" to "sh:sparql [ $select ; $deactivated ]",
    )

    @Test
    fun `constraints that hold make the shape conform while its deactivation is unsettled`() {
        // Every case is checked, so that a failure names all the positions that regress.
        val wrong = cases.mapNotNull { (name, shapes) ->
            val outcome = runCatching { validate(shapes).violations.map { it.shapeUri to it.constraint.constraintType to it.isUndefinedRecursion } }
            val expected = listOf("http://example.com/ns#S2" to ConstraintType.NOT to false)
            if (outcome.getOrNull() == expected) null else "$name: ${outcome.exceptionOrNull() ?: outcome.getOrNull()}"
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `constraints that fail leave the answer undefined while the deactivation is`() {
        // ex:S1 fails sh:class unless deactivated: S1 = S2 or false = S2 = not S1, a paradox. It is undefined, and the
        // deactivation reads an undefined answer, which fails closed.
        val error = runCatching { validate("sh:class ex:Missing ; $deactivated") }.exceptionOrNull()
        assertEquals(true, error is ShaclValidationException, "$error")
    }

    @Test
    fun `a definite deactivation is unchanged`() {
        // ex:S2 fails on its own (sh:minCount): ex:S1 is never deactivated and fails sh:class, so ex:S2's sh:not holds.
        val shapes = """
            ex:T a sh:NodeShape ; sh:nodeKind sh:IRI .
            ex:S2 a sh:NodeShape ; sh:targetNode ex:a ; sh:not ex:S1 ; sh:property ex:Q2 .
            ex:Q2 sh:path ex:missing ; sh:minCount 1 .
            ex:S1 a sh:NodeShape ; sh:targetNode ex:a ; sh:class ex:Missing ; $deactivated .
        """
        val g = { ttl: String -> Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE) }
        val report = NativeShaclValidator(ValidationConfig.default()) { JenaProvider().createRepository("memory", RdfConfig()) }.validate(g(data), g(shapes))
        assertEquals(
            listOf("http://example.com/ns#Q2" to ConstraintType.MIN_COUNT, "http://example.com/ns#S1" to ConstraintType.CLASS).sortedBy { it.first },
            report.violations.map { it.shapeUri to it.constraint.constraintType }.sortedBy { it.first },
            report.violations.toString(),
        )
        assertEquals(Iri("http://example.com/ns#a"), report.violations.first().focusNode)
    }
}
