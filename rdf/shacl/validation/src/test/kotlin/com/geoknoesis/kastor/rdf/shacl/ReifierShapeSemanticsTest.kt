package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SHACL 1.2 `sh:reifierShape` constrains the reifiers a triple has; only `sh:reificationRequired true` requires a
 * reifier to exist (W3C `core/property/reifierShape-001` / `-002`).
 */
class ReifierShapeSemanticsTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
    """.trimIndent()

    private fun g(ttl: String): RdfGraph =
        (prefixes + "\n" + ttl).byteInputStream().use { JenaProvider().parseGraph(it, "TURTLE", "http://example.org/") }

    private fun validate(data: String, shapes: String) = NativeShaclValidator(ValidationConfig.default()).validate(g(data), g(shapes))

    private val reifyShape = """
        ex:ReifyShape a sh:NodeShape ; sh:property [ sh:path ex:source ; sh:in ( ex:trusted ) ] .
    """

    private fun shapes(extra: String) = """
        ex:S a sh:NodeShape ; sh:targetNode ex:a ;
          sh:property [ sh:path ex:p ; $extra ] .
        $reifyShape
    """

    @Test fun `reifierShape without reificationRequired accepts an unreified triple`() {
        val report = validate("ex:a ex:p 1 .", shapes("sh:reifierShape ex:ReifyShape"))
        assertTrue(report.isValid, report.violations.toString())
    }

    @Test fun `reifierShape with reificationRequired false accepts an unreified triple`() {
        val report = validate("ex:a ex:p 1 .", shapes("sh:reifierShape ex:ReifyShape ; sh:reificationRequired false"))
        assertTrue(report.isValid, report.violations.toString())
    }

    @Test fun `reifierShape with reificationRequired true rejects an unreified triple`() {
        val report = validate("ex:a ex:p 1 .", shapes("sh:reifierShape ex:ReifyShape ; sh:reificationRequired true"))
        assertFalse(report.isValid)
        val result = report.violations.single()
        // sh:reificationRequired is a parameter of sh:ReifierShapeConstraintComponent (W3C reifierShape-002).
        assertEquals(ConstraintType.REIFIER_SHAPE, result.constraint.constraintType)
        assertEquals(Iri("http://example.org/a"), result.focusNode)
    }

    @Test fun `reificationRequired true alone rejects an unreified triple`() {
        val report = validate("ex:a ex:p 1 .", shapes("sh:reificationRequired true"))
        assertEquals(ConstraintType.REIFICATION_REQUIRED, report.violations.single().constraint.constraintType)
    }

    @Test fun `a reifier failing the reifierShape is a violation with or without reificationRequired`() {
        val data = "ex:a ex:p 1 {| ex:source ex:untrusted |} ."
        for (extra in listOf("sh:reifierShape ex:ReifyShape", "sh:reifierShape ex:ReifyShape ; sh:reificationRequired true")) {
            val report = validate(data, shapes(extra))
            assertEquals(ConstraintType.REIFIER_SHAPE, report.violations.single().constraint.constraintType, extra)
        }
        assertTrue(validate("ex:a ex:p 1 {| ex:source ex:trusted |} .", shapes("sh:reifierShape ex:ReifyShape")).isValid)
    }
}
