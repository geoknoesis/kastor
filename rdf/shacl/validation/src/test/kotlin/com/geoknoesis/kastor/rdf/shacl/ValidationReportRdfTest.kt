package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.FalseLiteral
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.vocab.SHACL
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValidationReportRdfTest {

    @Test
    fun `toShaclValidationReportRdf emits conforms and violation rows`() {
        val ex = "http://example.org/"
        val v =
            ValidationViolation(
                severity = ViolationSeverity.VIOLATION,
                constraint =
                    ShaclConstraint(
                        constraintType = ConstraintType.MIN_COUNT,
                        path = "${ex}label",
                    ),
                focusNode = Iri("${ex}a"),
                message = "Minimum cardinality 1 required",
                path = listOf(Iri("${ex}label")),
                value = TypedLiteral("bad", XSD.integer),
                shapeUri = "${ex}Shape",
            )
        val report =
            ValidationReport(
                isValid = false,
                violations = listOf(v),
                warnings = emptyList(),
                statistics =
                    ValidationStatistics(
                        totalResources = 1,
                        validatedResources = 1,
                        totalConstraints = 1,
                        validatedConstraints = 1,
                        shapesProcessed = 1,
                        constraintsByType = mapOf(ConstraintType.MIN_COUNT to 1),
                        violationsByType = mapOf(ConstraintType.MIN_COUNT to 1),
                        warningsByType = emptyMap(),
                        averageValidationTimePerResource = Duration.ZERO,
                    ),
                validationTime = Duration.ZERO,
                validatedResources = 1,
                validatedConstraints = 1,
            )
        val g = report.toShaclValidationReportRdf()
        val triples = g.getTriples()
        assertTrue(triples.any { it.predicate == SHACL.conforms && it.obj == FalseLiteral })
        assertTrue(triples.any { it.predicate == SHACL.result })
        assertTrue(triples.any { it.predicate == SHACL.focusNode && it.obj == v.focusNode })
        assertTrue(triples.any { it.predicate == SHACL.sourceShape && it.obj == Iri("${ex}Shape") })
        assertTrue(triples.any { it.predicate == SHACL.sourceConstraintComponent })
        assertTrue(triples.any { it.predicate == SHACL.resultPath && it.obj == Iri("${ex}label") })
        assertTrue(triples.any { it.predicate == SHACL.value && it.obj == TypedLiteral("bad", XSD.integer) })
    }
}
