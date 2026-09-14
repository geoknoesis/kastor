package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Recursion through monotone operators is a greatest fixpoint, only cycles through non-monotone operators are
 * undefined, and undefined answers follow Kleene three-valued logic: results never depend on operand or constraint
 * order, and definite violations are never discarded because another value is undefined.
 */
class ThreeValuedRecursionTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
    """.trimIndent()

    private fun g(ttl: String): RdfGraph = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)
    private fun ex(local: String) = Iri("http://example.org/$local")
    private fun validate(data: RdfGraph, shapes: String, config: ValidationConfig = ValidationConfig.default()) =
        NativeShaclValidator(config).validate(data, g(shapes))

    private fun summary(report: ValidationReport) =
        report.violations.map { listOf(it.focusNode, it.constraint.constraintType, it.value, it.severity) }.toSet()

    /** `ex:Undef` is undefined exactly for nodes with an `ex:self` loop (recursion through negation). */
    private val undef = "ex:Undef sh:property [ sh:path ex:self ; sh:not ex:Undef ] ."
    private val named = "ex:Named sh:property [ sh:path ex:name ; sh:minCount 1 ] ."

    // --- P1: monotone recursion -----------------------------------------------------------------------------------

    @Test fun `recursion through sh or is a greatest fixpoint (audit scenario)`() {
        val shapes = """
            ex:T sh:targetClass ex:Person ; sh:node ex:P .
            ex:P sh:property [ sh:path ex:knows ; sh:or ( ex:Named ex:P ) ] .
            $named
        """
        val data = g("ex:a a ex:Person ; ex:name 'a' ; ex:knows ex:b . ex:b a ex:Person ; ex:name 'b' ; ex:knows ex:a .")
        val report = validate(data, shapes)
        assertTrue(report.isValid, report.violations.toString())
        assertTrue(validate(data, shapes, ValidationConfig(strictMode = true)).isValid)
    }

    @Test fun `unnamed ring conforms through the recursive sh or operand`() {
        val shapes = """
            ex:T sh:targetClass ex:Person ; sh:node ex:P .
            ex:P sh:property [ sh:path ex:knows ; sh:or ( ex:Named ex:P ) ] .
            $named
        """
        val ring = g((0 until 500).joinToString("\n") { "ex:p$it a ex:Person ; ex:knows ex:p${(it + 1) % 500} ." })
        val report = validate(ring, shapes, ValidationConfig(strictMode = true))
        assertTrue(report.isValid, report.violations.take(3).toString())
    }

    @Test fun `recursion through sh or reaching a failing node is a definite violation`() {
        val shapes = """
            ex:T sh:targetNode ex:a ; sh:node ex:P .
            ex:P sh:property [ sh:path ex:knows ; sh:minCount 1 ; sh:or ( ex:Named ex:P ) ] .
            $named
        """
        // a -> b -> c, nobody named, c knows nobody: P(c) fails its minCount, so P(b) and P(a) fail.
        val report = validate(g("ex:a ex:knows ex:b . ex:b ex:knows ex:c ."), shapes)
        assertFalse(report.isValid)
        assertTrue(report.violations.all { it.severity == ViolationSeverity.VIOLATION }, report.violations.toString())
        assertEquals(ConstraintType.NODE, report.violations.single().constraint.constraintType)
    }

    @Test fun `recursion through sh someValue and sh qualifiedMinCount is a greatest fixpoint`() {
        val ring = g("ex:a a ex:Person ; ex:knows ex:b . ex:b a ex:Person ; ex:knows ex:a .")
        val someValue = "ex:P a sh:NodeShape ; sh:targetClass ex:Person ; sh:property [ sh:path ex:knows ; sh:someValue ex:P ] ."
        assertTrue(validate(ring, someValue, ValidationConfig(strictMode = true)).isValid)
        val qualified = """
            ex:P a sh:NodeShape ; sh:targetClass ex:Person ;
              sh:property [ sh:path ex:knows ; sh:qualifiedValueShape ex:P ; sh:qualifiedMinCount 1 ] .
        """
        assertTrue(validate(ring, qualified, ValidationConfig(strictMode = true)).isValid)
    }

    @Test fun `recursion through sh xone and sh qualifiedMaxCount stays undefined`() {
        val loop = g("ex:a a ex:Person ; ex:knows ex:a .")
        val xone = "ex:P a sh:NodeShape ; sh:targetClass ex:Person ; sh:property [ sh:path ex:knows ; sh:xone ( ex:P ex:Any ) ] . ex:Any a sh:NodeShape ."
        val qualifiedMax = """
            ex:P a sh:NodeShape ; sh:targetClass ex:Person ;
              sh:property [ sh:path ex:knows ; sh:qualifiedValueShape ex:P ; sh:qualifiedMaxCount 0 ] .
        """
        for (shapes in listOf(xone, qualifiedMax)) {
            val report = validate(loop, shapes)
            assertFalse(report.isValid)
            assertTrue(report.violations.all { it.severity == ViolationSeverity.WARNING && it.message.contains("undefined") }, report.violations.toString())
            assertThrows(ShaclValidationException::class.java) { validate(loop, shapes, ValidationConfig(strictMode = true)) }
        }
    }

    @Test fun `qualifiedValueShapesDisjoint sibling exclusion is a negative dependency`() {
        val shapes = """
            ex:P a sh:NodeShape ; sh:targetClass ex:Person ;
              sh:property [ sh:path ex:knows ; sh:qualifiedValueShape ex:Any ; sh:qualifiedMinCount 1 ; sh:qualifiedValueShapesDisjoint true ] ;
              sh:property [ sh:path ex:knows ; sh:qualifiedValueShape ex:P ; sh:qualifiedMinCount 0 ] .
            ex:Any a sh:NodeShape .
        """
        val report = validate(g("ex:a a ex:Person ; ex:knows ex:a ."), shapes)
        assertFalse(report.isValid)
        assertTrue(report.violations.any { it.message.contains("undefined") }, report.violations.toString())
    }

    // --- P2: Kleene logic and order independence -------------------------------------------------------------------

    @Test fun `sh or and sh and follow Kleene logic in every operand order`() {
        val data = g("ex:v ex:self ex:v ; ex:name 'x' .")
        val orForward = validate(data, "ex:T sh:targetNode ex:v ; sh:or ( ex:Undef ex:Named ) . $undef $named")
        val orBackward = validate(data, "ex:T sh:targetNode ex:v ; sh:or ( ex:Named ex:Undef ) . $undef $named")
        assertTrue(orForward.isValid, orForward.violations.toString())
        assertEquals(summary(orForward), summary(orBackward))

        val unnamed = g("ex:v ex:self ex:v .")
        val andForward = validate(unnamed, "ex:T sh:targetNode ex:v ; sh:and ( ex:Undef ex:Named ) . $undef $named")
        val andBackward = validate(unnamed, "ex:T sh:targetNode ex:v ; sh:and ( ex:Named ex:Undef ) . $undef $named")
        assertEquals(setOf(listOf(ex("v"), ConstraintType.AND, ex("v"), ViolationSeverity.VIOLATION)), summary(andForward))
        assertEquals(summary(andForward), summary(andBackward))
    }

    @Test fun `nested shape with a failing and an undefined constraint fails in every constraint order`() {
        val data = g("ex:v ex:self ex:v .")
        val nodeFirst = "ex:T sh:targetNode ex:v ; sh:node ex:Inner . ex:Inner sh:node ex:Undef ; sh:property [ sh:path ex:name ; sh:minCount 1 ] . $undef"
        val propertyFirst = "ex:T sh:targetNode ex:v ; sh:node ex:Inner . ex:Inner sh:property [ sh:path ex:name ; sh:minCount 1 ] ; sh:or ( ex:Undef ex:Undef ) . $undef"
        val expected = setOf(listOf(ex("v"), ConstraintType.NODE, ex("v"), ViolationSeverity.VIOLATION))
        assertEquals(expected, summary(validate(data, nodeFirst)))
        assertEquals(expected, summary(validate(data, propertyFirst)))
        assertTrue(validate(data, nodeFirst.replace("ex:T sh:targetNode ex:v ; sh:node ex:Inner .", "ex:T sh:targetNode ex:v ; sh:not ex:Inner .")).isValid)
    }

    // --- P2: definite per-value results survive undefined values --------------------------------------------------

    @Test fun `an undefined value does not discard the violation of another value`() {
        val shapes = """
            ex:T sh:targetNode ex:f ; sh:property [ sh:path ex:knows ; sh:node ex:S ] .
            ex:S sh:property [ sh:path ex:name ; sh:minCount 1 ] ; sh:property [ sh:path ex:self ; sh:not ex:S ] .
        """
        val data = g("ex:f ex:knows ex:v1 , ex:v2 . ex:v2 ex:name 'v2' ; ex:self ex:v2 .")
        val report = validate(data, shapes)
        assertEquals(
            setOf(
                listOf(ex("f"), ConstraintType.NODE, ex("v1"), ViolationSeverity.VIOLATION),
                listOf(ex("f"), ConstraintType.NODE, ex("v2"), ViolationSeverity.WARNING),
            ),
            summary(report),
        )
    }

    @Test fun `qualified counts are decided from definite values when possible`() {
        val shapes = """
            ex:T sh:targetNode ex:f ;
              sh:property [ sh:path ex:knows ; sh:qualifiedValueShape ex:Named ; sh:qualifiedMinCount 1 ] ;
              sh:property [ sh:path ex:knows ; sh:qualifiedValueShape ex:Undef ; sh:qualifiedMaxCount 1 ] .
            $named $undef
        """
        // Named: v1 true, v2 false -> min satisfied. Undef: v2 undefined, v1 true (no self loop) -> count in [1, 2]: undecided.
        val data = g("ex:f ex:knows ex:v1 , ex:v2 . ex:v1 ex:name 'n' . ex:v2 ex:self ex:v2 .")
        val report = validate(data, shapes)
        assertEquals(1, report.violations.size, report.violations.toString())
        assertEquals(ViolationSeverity.WARNING, report.violations.single().severity)

        // Undef count is at least 2 once both values are definitely conforming or undefined above the bound.
        val over = g("ex:f ex:knows ex:v1 , ex:v2 , ex:v3 . ex:v1 ex:name 'n' . ex:v3 ex:self ex:v3 .")
        val definite = validate(over, shapes)
        assertEquals(
            setOf(listOf(ex("f"), ConstraintType.QUALIFIED_MAX_COUNT, null, ViolationSeverity.VIOLATION)),
            summary(definite),
        )
    }

    // --- P2: undecidable sh:targetWhere ----------------------------------------------------------------------------

    @Test fun `an undecidable targetWhere membership is a blocking result`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetWhere ex:W ; sh:property [ sh:path ex:name ; sh:minCount 1 ] .
            ex:W sh:property [ sh:path ex:self ; sh:not ex:W ] .
        """
        val data = g("ex:u ex:self ex:u .")
        val report = validate(data, shapes)
        assertFalse(report.isValid)
        val result = report.violations.single()
        assertEquals(ViolationSeverity.WARNING, result.severity)
        assertEquals(ex("u"), result.focusNode)
        assertTrue(result.message.contains("sh:targetWhere"), result.message)
        assertThrows(ShaclValidationException::class.java) { validate(data, shapes, ValidationConfig(strictMode = true)) }
    }

    // --- P3: cost --------------------------------------------------------------------------------------------------

    @Test fun `targetWhere skips candidates the membership shape's node kind excludes`() {
        val n = 1_000
        val data = Rdf.graph { for (i in 0 until n) ex("s$i") - ex("p") - string("v$i") }
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetWhere ex:W ; sh:class ex:Missing .
            ex:W sh:nodeKind sh:IRI ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .
        """
        val validator = NativeShaclValidator(ValidationConfig(maxViolations = 2 * n))
        assertEquals(n, validator.validate(data, g(shapes)).violations.size)
        // W for each IRI subject (node + property shape) and one report per target; literals are never candidates.
        // Before: every literal object was also checked against W (4000 evaluations).
        assertTrue(validator.shapeEvaluations <= 3L * n, "shape evaluations: ${validator.shapeEvaluations}")
    }

    @Test fun `recursive component questions are evaluated once each`() {
        val n = 2_000
        val data = Rdf.graph {
            for (i in 0 until n) {
                val p = ex("p$i")
                p - RDF.type - ex("Person")
                p - ex("name") - string("person $i")
                p - ex("knows") - ex("p${(i + 1) % n}")
            }
        }
        val shapes = """
            ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
              sh:property [ sh:path ex:name ; sh:minCount 1 ] ;
              sh:property [ sh:path ex:knows ; sh:node ex:PersonShape ] .
        """
        val validator = NativeShaclValidator(ValidationConfig.default())
        assertTrue(validator.validate(data, g(shapes)).isValid)
        // One recorded evaluation per recursive question (node shape + two property shapes); its optimistic answer is
        // the first greatest-fixpoint iteration and conforming targets need no separate report evaluation.
        // Before (separate first pass, dependency predictor and re-evaluation, plus the report pass): 11 per node.
        assertTrue(validator.shapeEvaluations <= 4L * n, "shape evaluations: ${validator.shapeEvaluations}")
    }
}
