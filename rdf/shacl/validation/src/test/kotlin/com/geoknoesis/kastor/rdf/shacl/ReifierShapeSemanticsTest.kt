package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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

    private fun validate(data: String, shapes: String, config: ValidationConfig = ValidationConfig.default()) =
        NativeShaclValidator(config).validate(g(data), g(shapes))

    private fun ex(local: String) = Iri("http://example.org/$local")

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

    // --- one result per failing reifier ------------------------------------------------------------------------------

    private val one = TypedLiteral("1", XSD.integer)

    /** `ex:a ex:p 1`, reified by `ex:r1` (untrusted), `ex:r2` (bogus) and `ex:r3` (trusted). */
    private val threeReifiers: RdfGraph = Rdf.graph {
        val claim = TripleTerm(RdfTriple(ex("a"), ex("p"), one))
        ex("a") - ex("p") - one
        ex("r1") - RDF.reifies - claim
        ex("r1") - ex("source") - ex("untrusted")
        ex("r2") - RDF.reifies - claim
        ex("r2") - ex("source") - ex("bogus")
        ex("r3") - RDF.reifies - claim
        ex("r3") - ex("source") - ex("trusted")
    }

    private fun validateThreeReifiers(shapes: String) = NativeShaclValidator(ValidationConfig.default()).validate(threeReifiers, g(shapes))

    @Test fun `each failing reifier yields a result that identifies it`() {
        val report = validateThreeReifiers(shapes("sh:reifierShape ex:ReifyShape"))
        assertEquals(2, report.violations.size, report.violations.toString())
        assertTrue(report.violations.all { it.constraint.constraintType == ConstraintType.REIFIER_SHAPE && it.value == one })
        assertEquals(setOf<Any?>(ex("r1"), ex("r2")), report.violations.map { it.context[ValidationViolation.REIFIER_CONTEXT_KEY] }.toSet())
        for (v in report.violations) {
            val reifier = v.context[ValidationViolation.REIFIER_CONTEXT_KEY] as Iri
            assertTrue(v.message.contains(reifier.value), v.message)
            assertTrue(v.message.contains("http://example.org/ReifyShape"), v.message)
        }
        // The reifier is exported with a kastor: property; the standard result properties are unchanged.
        val rdf = report.toShaclValidationReportRdf().getTriples()
        assertEquals(setOf<Any>(ex("r1"), ex("r2")), rdf.filter { it.predicate == KastorShaclVocabulary.reifier }.map { it.obj }.toSet())
        assertEquals(2, rdf.count { it.predicate == SHACL.value && it.obj == one })
    }

    @Test fun `an undecided reifier answer names its reifier too`() {
        // ex:Undef is undefined for a node with an ex:self loop (recursion through sh:not): r1 is undefined, r2
        // (no loop) conforms.
        val data = Rdf.graph {
            val claim = TripleTerm(RdfTriple(ex("a"), ex("p"), one))
            ex("a") - ex("p") - one
            ex("r1") - RDF.reifies - claim
            ex("r1") - ex("self") - ex("r1")
            ex("r2") - RDF.reifies - claim
        }
        val shapes = g(
            """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:reifierShape ex:Undef ] .
            ex:Undef sh:property [ sh:path ex:self ; sh:not ex:Undef ] .
            """,
        )
        val report = NativeShaclValidator(ValidationConfig.default()).validate(data, shapes)
        val result = report.violations.single()
        assertTrue(result.isUndefinedRecursion, result.toString())
        assertEquals(ConstraintType.REIFIER_SHAPE, result.constraint.constraintType)
        assertEquals(one, result.value)
        assertEquals(ex("r1"), result.context[ValidationViolation.REIFIER_CONTEXT_KEY])
        val rdf = report.toShaclValidationReportRdf().getTriples()
        val row = rdf.single { it.predicate == KastorShaclVocabulary.resultStatus }.subject
        assertEquals(listOf<Any>(ex("r1")), rdf.filter { it.subject == row && it.predicate == KastorShaclVocabulary.reifier }.map { it.obj })
    }

    @Test fun `results stay distinguishable when the shape overrides the message`() {
        val report = validateThreeReifiers(shapes("sh:reifierShape ex:ReifyShape ; sh:message 'bad provenance'"))
        assertEquals(2, report.violations.size, report.violations.toString())
        assertTrue(report.violations.all { it.message == "bad provenance" })
        assertEquals(2, report.violations.toSet().size, "results differ by their reifier")
    }

    @Test fun `identical results of one reifier are reported once`() {
        // Two reifier shapes fail for the same reifier and the shape's sh:message hides which one: the two results
        // would be indistinguishable.
        val message = "sh:message 'bad provenance' ;"
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; $message sh:reifierShape ex:ReifyShape , ex:OtherReifyShape ] .
            $reifyShape
            ex:OtherReifyShape a sh:NodeShape ; sh:property [ sh:path ex:source ; sh:hasValue ex:trusted ] .
        """
        val data = "ex:a ex:p 1 {| ex:source ex:untrusted |} ."
        val report = validate(data, shapes)
        assertEquals(1, report.violations.size, report.violations.toString())
        // Without the message override the two results name different shapes and are both kept.
        val distinct = validate(data, shapes.replace(message, ""))
        assertEquals(2, distinct.violations.size, distinct.violations.toString())
    }

    // --- non-predicate paths -----------------------------------------------------------------------------------------

    @Test fun `reifier constraints on a non-predicate path are an unsupported feature`() {
        val ignore = ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)
        val data = "ex:a ex:p ex:b . ex:b ex:q 1 ."
        val paths = listOf("( ex:p ex:q )", "[ sh:inversePath ex:p ]", "[ sh:zeroOrMorePath ex:p ]", "[ sh:alternativePath ( ex:p ex:q ) ]")
        for (path in paths) {
            for (parameter in listOf("sh:reifierShape ex:ReifyShape", "sh:reificationRequired true")) {
                val shapes = """
                    ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path $path ; $parameter ] .
                    $reifyShape
                """
                val error = assertThrows(ShaclValidationException::class.java, { validate(data, shapes) }, "$path $parameter")
                assertTrue(error.message.orEmpty().contains("Unsupported SHACL feature"), error.message)
                assertEquals(
                    setOf(UnsupportedShaclFeature.REIFIER_CONSTRAINT_ON_COMPLEX_PATH),
                    (error.cause as UnsupportedShaclFeatureException).features,
                )
                val report = validate(data, shapes, ignore)
                assertTrue(report.isValid)
                assertTrue(report.warnings.single().message.contains("non-predicate path"), report.warnings.toString())
            }
        }
        // `sh:reificationRequired false` and a deactivated property shape constrain nothing.
        val notRequired = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ( ex:p ex:q ) ; sh:reificationRequired false ] ."
        assertTrue(validate(data, notRequired).isValid)
        val deactivated = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; " +
            "sh:property [ sh:path ( ex:p ex:q ) ; sh:reifierShape ex:ReifyShape ; sh:deactivated true ] . $reifyShape"
        assertTrue(validate(data, deactivated).isValid)
    }

    // --- constraint component ----------------------------------------------------------------------------------------

    @Test fun `no constraint component IRI is minted in the SHACL namespace`() {
        // sh:reificationRequired is a parameter of sh:ReifierShapeConstraintComponent; SHACL 1.2 has no
        // sh:ReificationRequiredConstraintComponent.
        val standard = Iri(SHACL.namespace + "ReifierShapeConstraintComponent")
        assertEquals(standard, ConstraintType.REIFICATION_REQUIRED.toSourceConstraintComponentIri())
        assertEquals(standard, ConstraintType.REIFIER_SHAPE.toSourceConstraintComponentIri())
        val report = validate("ex:a ex:p 1 .", shapes("sh:reificationRequired true"))
        val components = report.toShaclValidationReportRdf().getTriples().filter { it.predicate == SHACL.sourceConstraintComponent }.map { it.obj }
        assertEquals(listOf<Any>(standard), components)
    }
}
