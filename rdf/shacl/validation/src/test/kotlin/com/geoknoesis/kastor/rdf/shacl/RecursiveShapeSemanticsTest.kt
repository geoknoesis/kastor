package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Recursive shapes: data recursion must not consume JVM stack proportional to the data depth, positive recursion is
 * a greatest fixpoint, and recursion through negation / disjunction is reported as undefined instead of being
 * silently assumed to conform.
 */
class RecursiveShapeSemanticsTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
    """.trimIndent()

    private fun g(ttl: String): RdfGraph = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)
    private fun ex(local: String) = Iri("http://example.org/$local")
    private fun validate(data: RdfGraph, shapes: String, config: ValidationConfig = ValidationConfig.default()) =
        NativeShaclValidatorProvider().createValidator(config).validate(data, g(shapes))

    /** `ex:p0 ex:knows ex:p1 ... ex:p(n-1)`; every node is an `ex:Person` with a name except [nameless]. */
    private fun chain(n: Int, nameless: Int? = null): RdfGraph = Rdf.graph {
        for (i in 0 until n) {
            val p = ex("p$i")
            p - RDF.type - ex("Person")
            if (i != nameless) p - ex("name") - string("person $i")
            if (i + 1 < n) p - ex("knows") - ex("p${i + 1}")
        }
    }

    private val personShape = """
        ex:PersonShape a sh:NodeShape ; TARGET ;
          sh:property [ sh:path ex:name ; sh:minCount 1 ; sh:datatype xsd:string ] ;
          sh:property [ sh:path ex:knows ; sh:node ex:PersonShape ] .
    """

    @Test fun `recursive sh node validates a 10000 node acyclic chain`() {
        val report = assertTimeoutPreemptively<ValidationReport>(Duration.ofSeconds(60)) {
            validate(chain(10_000), personShape.replace("TARGET", "sh:targetNode ex:p0"))
        }
        assertTrue(report.isValid, report.violations.take(3).toString())
    }

    @Test fun `violation at the tail of a 10000 node chain is detected at the head`() {
        val report = assertTimeoutPreemptively<ValidationReport>(Duration.ofSeconds(60)) {
            validate(chain(10_000, nameless = 9_999), personShape.replace("TARGET", "sh:targetNode ex:p0"))
        }
        assertFalse(report.isValid)
        val v = report.violations.single()
        assertEquals(ConstraintType.NODE, v.constraint.constraintType)
        assertEquals(ex("p0"), v.focusNode)
        assertEquals(ex("p1"), v.value)
    }

    @Test fun `class targeted recursive shape reports every node of a failing 10000 node chain`() {
        val report = assertTimeoutPreemptively<ValidationReport>(Duration.ofSeconds(60)) {
            validate(
                chain(10_000, nameless = 9_999),
                personShape.replace("TARGET", "sh:targetClass ex:Person"),
                ValidationConfig(maxViolations = 20_000),
            )
        }
        assertEquals(10_000, report.violations.size)
        assertEquals(9_999, report.violations.count { it.constraint.constraintType == ConstraintType.NODE })
        assertEquals(ConstraintType.MIN_COUNT, report.violations.single { it.focusNode == ex("p9999") }.constraint.constraintType)
    }

    @Test fun `a failing non-recursive part decides conformance before recursion through negation`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:x ;
              sh:property [ sh:path ex:name ; sh:minCount 1 ] ;
              sh:property [ sh:path ex:self ; sh:not ex:S ] .
        """
        val report = validate(g("ex:x ex:self ex:x ."), shapes)
        assertEquals(listOf(ConstraintType.MIN_COUNT), report.violations.map { it.constraint.constraintType })
    }

    @Test fun `genuine recursion through negation is reported as undefined and throws in strict mode`() {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:x ;
              sh:property [ sh:path ex:name ; sh:minCount 1 ] ;
              sh:property [ sh:path ex:self ; sh:not ex:S ] .
        """
        val data = g("ex:x ex:self ex:x ; ex:name 'x' .")
        val report = validate(data, shapes)
        assertFalse(report.isValid)
        // The constraint is not evaluated: no sh:not violation, only the warning (which names the NOT constraint).
        assertTrue(
            report.violations.none { it.constraint.constraintType == ConstraintType.NOT && it.severity == ViolationSeverity.VIOLATION },
            report.violations.toString(),
        )
        val undefined = report.violations.single()
        assertEquals(ViolationSeverity.WARNING, undefined.severity)
        assertTrue(undefined.message.contains("undefined"), undefined.message)
        assertThrows(ShaclValidationException::class.java) { validate(data, shapes, ValidationConfig(strictMode = true)) }
    }

    @Test fun `self-referential sh or does not silently make the target conform`() {
        val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:or ( [ sh:node ex:S ] [ sh:class ex:Missing ] ) ."
        val report = validate(g("ex:x ex:p 1 ."), shapes)
        assertFalse(report.isValid)
        assertTrue(report.violations.any { it.message.contains("undefined") }, report.violations.toString())
    }

    @Test fun `positive recursion over cyclic data is a greatest fixpoint`() {
        val shapes = personShape.replace("TARGET", "sh:targetClass ex:Person")
        val ring = "ex:a a ex:Person ; ex:name 'a' ; ex:knows ex:b . ex:b a ex:Person ; ex:name 'b' ; ex:knows ex:a ."
        assertTrue(validate(g(ring), shapes).isValid)

        val broken = """
            ex:a a ex:Person ; ex:name 'a' ; ex:knows ex:b .
            ex:b a ex:Person ; ex:name 'b' ; ex:knows ex:c .
            ex:c a ex:Person ; ex:knows ex:a .
        """
        val report = validate(g(broken), shapes)
        assertEquals(setOf(ex("a"), ex("b"), ex("c")), report.violations.map { it.focusNode }.toSet())
        assertEquals(1, report.violations.count { it.constraint.constraintType == ConstraintType.MIN_COUNT })
    }

    @Test fun `results do not depend on target evaluation order`() {
        val data = g(
            """
            ex:a ex:name 'a' ; ex:knows ex:b .
            ex:b ex:name 'b' ; ex:knows ex:c .
            ex:c ex:knows ex:a .
            """,
        )
        fun summary(targets: String) =
            validate(data, personShape.replace("TARGET", "sh:targetNode $targets")).violations
                .map { Triple(it.focusNode, it.constraint.constraintType, it.value) }.toSet()
        val forward = summary("ex:a , ex:b , ex:c")
        assertEquals(forward, summary("ex:c , ex:b , ex:a"))
        assertEquals(forward, summary("ex:b , ex:c , ex:a"))
        assertEquals(4, forward.size)
    }
}
