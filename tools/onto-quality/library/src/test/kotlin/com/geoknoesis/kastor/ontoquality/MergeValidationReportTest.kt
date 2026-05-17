package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ValidationReport
import com.geoknoesis.kastor.rdf.shacl.ValidationStatistics
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MergeValidationReportTest {

    @Test
    fun recomputesViolationsByTypeFromMergedRows() {
        val baseRow =
            ValidationViolation(
                severity = ViolationSeverity.WARNING,
                constraint = ShaclConstraint(constraintType = ConstraintType.MIN_COUNT, severity = ViolationSeverity.WARNING),
                focusNode = Iri("http://ex.org/a"),
                message = "base",
            )
        val baseStats =
            ValidationStatistics(
                totalResources = 10,
                validatedResources = 10,
                totalConstraints = 20,
                validatedConstraints = 20,
                shapesProcessed = 3,
                constraintsByType = emptyMap(),
                violationsByType = mapOf(ConstraintType.MIN_COUNT to 99),
                warningsByType = emptyMap(),
                averageValidationTimePerResource = Duration.ZERO,
            )
        val base =
            ValidationReport(
                isValid = false,
                violations = listOf(baseRow),
                warnings = emptyList(),
                statistics = baseStats,
                validationTime = Duration.ofMillis(12),
                validatedResources = 10,
                validatedConstraints = 20,
            )

        val extra =
            ValidationViolation(
                severity = ViolationSeverity.ERROR,
                constraint =
                    ShaclConstraint(
                        constraintType = ConstraintType.CUSTOM_CONSTRAINT,
                        message = "synthetic",
                        severity = ViolationSeverity.ERROR,
                    ),
                focusNode = Iri("urn:kastor:x"),
                message = "merged inconsistency row",
            )

        val merged = mergeValidationReport(base, listOf(extra), emptyList())

        assertEquals(2, merged.violations.size)
        assertEquals(1, merged.statistics.violationsByType[ConstraintType.MIN_COUNT])
        assertEquals(1, merged.statistics.violationsByType[ConstraintType.CUSTOM_CONSTRAINT])
        assertEquals(Duration.ofMillis(12), merged.validationTime)
        assertFalse(merged.isValid)
    }
}
