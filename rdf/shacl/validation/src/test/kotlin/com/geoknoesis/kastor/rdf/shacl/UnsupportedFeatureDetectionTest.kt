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
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix ex: <http://example.org/> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
        @prefix sparql: <http://www.w3.org/ns/sparql#> .
    """.trimIndent()

    private fun g(ttl: String): RdfGraph = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)
    private fun validateSelf(ttl: String): ValidationReport = g(ttl).let { NativeShaclValidator(ValidationConfig.default()).validate(it, it) }
    private val separateData = "ex:a ex:p 1 . ex:b ex:q 1 ."
    private fun validateSeparate(shapes: String, config: ValidationConfig = ValidationConfig.default()): ValidationReport =
        NativeShaclValidator(config).validate(g(separateData), g(shapes))
    private val ignore = ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)

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
            "ex:S a sh:NodeShape ; sh:targetNode [ shnex:concat ( ex:a ex:b ) ] ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .",
            "ex:S a sh:NodeShape ; sh:targetNode [ ex:concat ( ex:a ex:b ) ] ; sh:property [ sh:path ex:p ; sh:minCount 1 ] . ex:concat a sh:SPARQLFunction .",
            "ex:S a sh:NodeShape ; sh:targetNode [ ex:concat ( ex:a ex:b ) ] ; sh:property [ sh:path ex:p ; sh:minCount 1 ] . ex:concat sh:parameter [ sh:path ex:x ] .",
        )
        for (shapes in cases) {
            val error = assertThrows(ShaclValidationException::class.java, { validateSelf("$shapes\nex:a ex:p 1 .") }, shapes)
            assertTrue(error.message.orEmpty().contains("Unsupported SHACL feature"), "$shapes: ${error.message}")
        }
    }

    // --- blank node sh:targetNode values ---------------------------------------------------------------------------

    @Test fun `a SPARQL function call in the sparql namespace is a node expression`() {
        val shapes = """ex:S a sh:NodeShape ; sh:targetNode [ sparql:concat ( "a" "b" ) ] ; sh:property [ sh:path ex:p ; sh:minCount 1 ] ."""
        for (run in listOf<() -> ValidationReport>({ validateSeparate(shapes) }, { validateSelf("$shapes\n$separateData") })) {
            val error = assertThrows(ShaclValidationException::class.java) { run() }
            assertTrue(error.message.orEmpty().contains("Unsupported SHACL feature"), error.message)
            assertEquals(setOf(UnsupportedShaclFeature.NODE_EXPRESSION), (error.cause as UnsupportedShaclFeatureException).features)
        }
        val report = validateSeparate(shapes, ignore)
        assertTrue(report.isValid)
        assertTrue(report.warnings.single().message.contains("sh:targetNode"), report.warnings.toString())
    }

    @Test fun `a call-shaped blank node target that cannot be a data node is an unsupported node expression`() {
        // ex:fn is not declared in the shapes graph (e.g. it comes from an import that was not resolved). The blank
        // node belongs to the shapes graph only, so it can never be a focus node: before, the shape was silently
        // never validated.
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode [ ex:fn ( ex:a ) ] ; sh:property [ sh:path ex:missing ; sh:minCount 1 ] ."
        val error = assertThrows(ShaclValidationException::class.java) { validateSeparate(shapes) }
        assertTrue(error.message.orEmpty().contains("Unsupported SHACL feature"), error.message)
        assertTrue(error.message.orEmpty().contains("http://example.org/fn"), error.message)
        assertEquals(setOf(UnsupportedShaclFeature.NODE_EXPRESSION), (error.cause as UnsupportedShaclFeatureException).features)

        val report = validateSeparate(shapes, ignore)
        assertTrue(report.isValid, report.violations.toString())
        val warning = report.warnings.single().message
        assertTrue(warning.contains("Unsupported SHACL feature ignored") && warning.contains("http://example.org/fn"), warning)
    }

    @Test fun `a blank node target that is not a data node is an unsupported node expression whatever its triples`() {
        // In SHACL 1.2 a blank node sh:targetNode value is a node expression. A blank node of the shapes graph that
        // is not a node of the data graph can never be a focus node, so the shape would silently go unvalidated:
        // plain, list-shaped, SHACL-vocabulary and call-shaped blank nodes are all handled per unsupportedFeatures.
        val targets = listOf("[ ex:p 1 ; ex:q 2 ]", "( ex:a ex:b )", "[ sh:path ex:q ]", "[ a sh:PropertyShape ; sh:path ex:q ]", "[ ex:fn ( ex:a ) ]")
        for (target in targets) {
            val shapes = "ex:S a sh:NodeShape ; sh:targetNode $target , ex:a ; sh:property [ sh:path ex:p ; sh:minCount 1 ] ."
            val error = assertThrows(ShaclValidationException::class.java, { validateSeparate(shapes) }, target)
            assertTrue(error.message.orEmpty().contains("Unsupported SHACL feature"), "$target: ${error.message}")
            assertTrue(error.message.orEmpty().contains("sh:targetNode"), "$target: ${error.message}")
            assertEquals(setOf(UnsupportedShaclFeature.NODE_EXPRESSION), (error.cause as UnsupportedShaclFeatureException).features, target)

            val report = validateSeparate(shapes, ignore)
            assertTrue(report.isValid, "$target: ${report.violations}")
            val warning = report.warnings.single().message
            assertTrue(warning.contains("Unsupported SHACL feature ignored") && warning.contains("sh:targetNode"), "$target: $warning")
        }
        // The other targets of the shape are still validated when the construct is ignored.
        val failing = "ex:S a sh:NodeShape ; sh:targetNode [ ex:p 1 ] , ex:b ; sh:property [ sh:path ex:p ; sh:minCount 1 ] ."
        assertEquals(1, validateSeparate(failing, ignore).violations.size)
    }

    @Test fun `a data blank node that is a list cell or carries SHACL predicates is an ordinary target`() {
        // validate(g, g): the blank nodes of the shapes graph are nodes of the data graph. Being an RDF list cell,
        // a (property) shape or any other subject of sh: predicates does not make such a node a node expression.
        val report = validateSelf(
            """
            ex:S a sh:NodeShape ; sh:targetNode _:list , _:shape , _:typed , _:path ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .
            _:list rdf:first 1 ; rdf:rest rdf:nil .
            _:shape sh:path ex:q ; sh:minCount 1 ; ex:p 1 .
            _:typed a sh:PropertyShape ; sh:path ex:q ; sh:name "typed" .
            _:path sh:path ex:q .
            """,
        )
        assertTrue(report.warnings.isEmpty(), report.warnings.toString())
        assertEquals(3, report.violations.size, report.violations.toString())
        assertTrue(report.violations.all { it.focusNode is BlankNode && it.constraint.constraintType == ConstraintType.MIN_COUNT })
        // The same holds under IGNORE_WITH_WARNING: nothing is ignored, nothing is warned about.
        val lenient = g("ex:S a sh:NodeShape ; sh:targetNode _:list ; sh:property [ sh:path ex:p ; sh:minCount 1 ] . _:list rdf:first 1 ; rdf:rest rdf:nil .")
            .let { NativeShaclValidator(ignore).validate(it, it) }
        assertEquals(1, lenient.violations.size)
        assertTrue(lenient.warnings.isEmpty(), lenient.warnings.toString())
    }

    @Test fun `expression-only vocabulary stays a node expression even when the blank node is a data node`() {
        // sh:select / sh:sparqlExpr, the shnex: and sparql: namespaces and calls of declared functions have no reading
        // as data: W3C sparql/targets/targetNode-select-001 validates such a shapes graph against itself.
        val cases = mapOf(
            "[ sh:select \"SELECT ?this WHERE { ?this a ex:T }\" ]" to UnsupportedShaclFeature.SPARQL_NODE_EXPRESSION,
            "[ shnex:pathValues ex:q ]" to UnsupportedShaclFeature.NODE_EXPRESSION,
            "[ a shnex:PathValuesExpression ; ex:q 1 ]" to UnsupportedShaclFeature.NODE_EXPRESSION,
        )
        for ((target, feature) in cases) {
            val shapes = "ex:S a sh:NodeShape ; sh:targetNode $target ; sh:property [ sh:path ex:p ; sh:minCount 1 ] ."
            for (run in listOf<() -> ValidationReport>({ validateSeparate(shapes) }, { validateSelf("$shapes\n$separateData") })) {
                val error = assertThrows(ShaclValidationException::class.java, { run() }, target)
                assertEquals(setOf(feature), (error.cause as UnsupportedShaclFeatureException).features, target)
            }
        }
    }

    @Test fun `call-shaped and plain blank node targets that are data nodes stay ordinary targets`() {
        // Shapes and data share blank nodes when they are the same graph: here the blank node is a real focus node.
        val report = validateSelf(
            """
            ex:S a sh:NodeShape ; sh:targetNode [ ex:fn ( ex:a ) ] , [ ex:q 2 ] ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .
            """,
        )
        assertEquals(2, report.violations.size, report.violations.toString())
        assertTrue(report.violations.all { it.focusNode is BlankNode && it.constraint.constraintType == ConstraintType.MIN_COUNT })
        assertTrue(report.warnings.isEmpty(), report.warnings.toString())
    }
}
