package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unsupported-feature detection only looks at the shape-reachable part of the shapes graph, so data that shares a
 * graph with the shapes (`validate(g, g)`) is never mistaken for a node expression.
 */
class UnsupportedFeatureDetectionTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
    """.trimIndent()

    private fun g(ttl: String): RdfGraph = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)
    private fun validateSelf(ttl: String): ValidationReport = g(ttl).let { NativeShaclValidator(ValidationConfig.default()).validate(it, it) }

    @Test fun `a blank node target with data triples in the same graph is a plain target`() {
        val report = validateSelf(
            """
            ex:S a sh:NodeShape ; sh:targetNode _:ok , _:bad ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .
            _:ok ex:p 1 .
            _:bad ex:q 1 .
            """,
        )
        val violation = report.violations.single()
        assertEquals(ConstraintType.MIN_COUNT, violation.constraint.constraintType)
        assertTrue(violation.focusNode is BlankNode)
        assertTrue(report.warnings.isEmpty(), report.warnings.toString())
    }

    @Test fun `a data blank node shaped like a function call is a plain target node`() {
        // `[ ex:items ( ex:x ) ]` has the syntax of a call, but ex:items is not a declared function.
        val report = validateSelf(
            """
            ex:S a sh:NodeShape ; sh:targetNode _:list ; sh:property [ sh:path ex:items ; sh:minCount 1 ] ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .
            _:list ex:items ( ex:x ) .
            """,
        )
        val violation = report.violations.single()
        assertEquals(ConstraintType.MIN_COUNT, violation.constraint.constraintType)
        assertTrue(violation.focusNode is BlankNode)
        assertTrue(report.warnings.isEmpty(), report.warnings.toString())
    }

    @Test fun `sh values and sh expression on data nodes are not node expressions`() {
        val report = validateSelf(
            """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .
            ex:a ex:p 1 .
            ex:doc sh:values ex:somethingElse ; sh:expression "not a shape" .
            """,
        )
        assertTrue(report.isValid, report.violations.toString())
        assertTrue(report.warnings.isEmpty(), report.warnings.toString())
    }

    @Test fun `node expressions reachable from shapes are still rejected`() {
        val cases = listOf(
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:values [ sh:path ex:q ] ] .",
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:node ex:T ] . ex:T sh:expression [ sh:path ex:q ] .",
            "ex:S a sh:NodeShape ; sh:targetNode [ sh:path ex:q ] ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .",
            "ex:S a sh:NodeShape ; sh:targetNode [ shnex:concat ( ex:a ex:b ) ] ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .",
            "ex:S a sh:NodeShape ; sh:targetNode [ ex:concat ( ex:a ex:b ) ] ; sh:property [ sh:path ex:p ; sh:minCount 1 ] . ex:concat a sh:SPARQLFunction .",
            "ex:S a sh:NodeShape ; sh:targetNode [ ex:concat ( ex:a ex:b ) ] ; sh:property [ sh:path ex:p ; sh:minCount 1 ] . ex:concat sh:parameter [ sh:path ex:x ] .",
        )
        for (shapes in cases) {
            val error = assertThrows(ShaclValidationException::class.java, { validateSelf("$shapes\nex:a ex:p 1 .") }, shapes)
            assertTrue(error.message.orEmpty().contains("Unsupported SHACL feature"), "$shapes: ${error.message}")
        }
    }
}
