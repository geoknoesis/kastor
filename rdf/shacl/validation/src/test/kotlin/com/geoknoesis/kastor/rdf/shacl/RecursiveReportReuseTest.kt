package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The results of a focus node of a recursive shape come from the evaluation by which the recursion solver settled
 * its conformance question: a failing focus node is not evaluated a second time to produce its results.
 *
 * The tests count shape evaluations ([NativeShaclValidator.shapeEvaluations]: one per node shape or property shape
 * applied to a node), so they are independent of the machine.
 */
class RecursiveReportReuseTest {

    private val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://example.org/> .\n"
    private fun g(turtle: String): RdfGraph = Rdf.parse(prefixes + turtle, RdfFormat.TURTLE)
    private fun ex(local: String) = Iri("http://example.org/$local")

    /** A person has a name and knows only persons: one node shape and two property shapes per evaluation. */
    private val person = """
        ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
          sh:property [ sh:path ex:name ; sh:minCount 1 ] ;
          sh:property [ sh:path ex:knows ; sh:node ex:PersonShape ] .
    """

    private fun rows(report: ValidationReport) = report.violations.map { Triple(it.focusNode, it.constraint.constraintType, it.value) }

    @Test
    fun `a failing recursive focus node is evaluated once`() {
        // ex:a has no name and knows itself. The solver decides the question by its recorded evaluation (it fails
        // whatever the recursive answer is); the results come from that evaluation, including the sh:node result that
        // depends on the answer the evaluation had assumed.
        val validator = NativeShaclValidator(ValidationConfig())
        val report = validator.validate(g("ex:a a ex:Person ; ex:knows ex:a ."), g(person))
        assertEquals(
            setOf<Any>(Triple(ex("a"), ConstraintType.MIN_COUNT, null), Triple(ex("a"), ConstraintType.NODE, ex("a"))),
            rows(report).toSet(),
        )
        assertEquals(2, report.violations.size)
        // The node shape and its two property shapes, once. Before: once by the solver and once more for the report.
        assertEquals(3L, validator.shapeEvaluations)
    }

    @Test
    fun `every failing node of a recursive ring is evaluated once`() {
        val n = 500
        val data = Rdf.graph {
            for (i in 0 until n) {
                ex("p$i") - RDF.type - ex("Person")
                ex("p$i") - ex("knows") - ex("p${(i + 1) % n}")
            }
        }
        val validator = NativeShaclValidator(ValidationConfig(maxViolations = 10 * n))
        val report = validator.validate(data, g(person))
        // Nobody has a name, and everybody knows somebody who fails: two results per node.
        assertEquals(2 * n, report.violations.size)
        assertEquals(n, report.violations.count { it.constraint.constraintType == ConstraintType.MIN_COUNT })
        assertEquals(n, report.violations.count { it.constraint.constraintType == ConstraintType.NODE })
        assertEquals(3L * n, validator.shapeEvaluations)
    }

    @Test
    fun `a focus node that fails because of another one is not evaluated for its report`() {
        // ex:y has a name and knows ex:x, which has none. Whatever the order in which the two targets are validated,
        // the report costs no evaluation: with recording disabled, each failing focus node costs one more evaluation
        // of the node shape and its two property shapes.
        val data = g("ex:y a ex:Person ; ex:name 'y' ; ex:knows ex:x . ex:x a ex:Person . ex:z a ex:Person ; ex:name 'z' ; ex:knows ex:z .")
        val shapes = g(person)
        val recording = NativeShaclValidator(ValidationConfig())
        val report = recording.validate(data, shapes)
        assertEquals(
            setOf<Any>(Triple(ex("x"), ConstraintType.MIN_COUNT, null), Triple(ex("y"), ConstraintType.NODE, ex("x"))),
            rows(report).toSet(),
        )
        assertEquals(2L, recording.recordedReports)
        assertEquals(0L, recording.separateReportEvaluations)

        val separate = NativeShaclValidator(ValidationConfig()).also { it.reportRecordingCapacity = 0 }
        assertEquals(report.violations, separate.validate(data, shapes).violations)
        assertEquals(2L, separate.separateReportEvaluations)
        assertEquals(separate.shapeEvaluations - 2 * 3, recording.shapeEvaluations)
    }

    @Test
    fun `an undefined recursive focus node is not evaluated again for its result`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:property [ sh:path ex:self ; sh:not ex:S ] ."
        val validator = NativeShaclValidator(ValidationConfig())
        val report = validator.validate(g("ex:x ex:self ex:x ."), g(shapes))
        val result = report.violations.single()
        assertTrue(result.isUndefinedRecursion, result.toString())
        assertEquals(ConstraintType.NOT, result.constraint.constraintType)
        // The recorded evaluation and the one that finds the answer undefined (node shape and property shape each).
        // Before: a third one for the report.
        assertEquals(4L, validator.shapeEvaluations)
    }

    @Test
    fun `strict mode fails on an answer that is undefined in the end and not on one that is undefined on the way`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:x , ex:y ; sh:property [ sh:path ex:self ; sh:not ex:S ] ."
        val strict = ValidationConfig(strictMode = true)
        val error = assertThrows(ShaclValidationException::class.java) {
            NativeShaclValidator(strict).validate(g("ex:x ex:self ex:x ."), g(shapes))
        }
        assertTrue(error.message.orEmpty().contains("undefined"), error.message)

        // A two-way chain in one component with negative dependencies: while the solver settles it from the far end,
        // the evaluations of the other questions read answers that are still undefined. All answers are definite in
        // the end, so strict mode must not fail on those intermediate evaluations.
        val chain = """
            ex:S a sh:NodeShape ; sh:targetClass ex:Link ;
              sh:property [ sh:path ex:next ; sh:not ex:S ] ;
              sh:property [ sh:path ex:prev ; sh:or ( ex:Any ex:S ) ] .
            ex:Any sh:property [ sh:path ex:none ; sh:maxCount 0 ] .
        """
        val k = 6
        val data = Rdf.graph {
            for (i in 0 until k) {
                ex("n$i") - RDF.type - ex("Link")
                if (i + 1 < k) {
                    ex("n$i") - ex("next") - ex("n${i + 1}")
                    ex("n${i + 1}") - ex("prev") - ex("n$i")
                }
            }
        }
        val report = NativeShaclValidator(strict).validate(data, g(chain))
        assertFalse(report.isValid)
        assertEquals(setOf<Any>(ex("n0"), ex("n2"), ex("n4")), report.violations.map { it.focusNode }.toSet())
        assertTrue(report.violations.none { it.isUndefinedRecursion }, report.violations.toString())
    }
}
