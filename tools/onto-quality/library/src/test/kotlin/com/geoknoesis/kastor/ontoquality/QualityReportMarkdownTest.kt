package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.integration.MetricsContext
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

class QualityReportMarkdownTest {

    @Test
    fun asciiSeverityMarkersWhenRequested() {
        val focus = "http://ex.org/Cls"
        val violation =
            ValidationViolation(
                severity = ViolationSeverity.WARNING,
                constraint = ShaclConstraint(constraintType = ConstraintType.NODE, severity = ViolationSeverity.WARNING),
                focusNode = Iri(focus),
                message = "Something is wrong.",
                shapeUri = "http://ex.org/shapes#S",
            )
        val raw =
            ValidationReport(
                isValid = false,
                violations = listOf(violation),
                warnings = emptyList(),
                statistics =
                    ValidationStatistics(
                        totalResources = 1,
                        validatedResources = 1,
                        totalConstraints = 1,
                        validatedConstraints = 1,
                        shapesProcessed = 1,
                        constraintsByType = emptyMap(),
                        violationsByType = emptyMap(),
                        warningsByType = emptyMap(),
                        averageValidationTimePerResource = Duration.ZERO,
                    ),
                validationTime = Duration.ZERO,
                validatedResources = 1,
                validatedConstraints = 1,
            )
        val ctx =
            MetricsContext(
                summary = "**Summary** line.",
                entityImportance = mapOf(focus to 0.95),
            )
        val report = QualityReport.from(raw, emptyList(), ctx)
        val md =
            report.describeMarkdown(
                MarkdownReportOptions(useAsciiSeverityMarkers = true, maxTopFindings = 5),
            )
        assertTrue(md.contains("[WARNING]"), md)
        assertFalse(md.contains("🟡"), md)
    }

    @Test
    fun violationsBySeverityReflectsRows() {
        val v1 =
            ValidationViolation(
                severity = ViolationSeverity.VIOLATION,
                constraint = ShaclConstraint(constraintType = ConstraintType.NODE, severity = ViolationSeverity.VIOLATION),
                focusNode = Iri("http://ex.org/a"),
                message = "v",
            )
        val v2 =
            ValidationViolation(
                severity = ViolationSeverity.WARNING,
                constraint = ShaclConstraint(constraintType = ConstraintType.MIN_COUNT, severity = ViolationSeverity.WARNING),
                focusNode = Iri("http://ex.org/b"),
                message = "w",
            )
        val raw =
            ValidationReport(
                isValid = false,
                violations = listOf(v1, v2),
                warnings = emptyList(),
                statistics =
                    ValidationStatistics(
                        totalResources = 1,
                        validatedResources = 1,
                        totalConstraints = 1,
                        validatedConstraints = 1,
                        shapesProcessed = 1,
                        constraintsByType = emptyMap(),
                        violationsByType = emptyMap(),
                        warningsByType = emptyMap(),
                        averageValidationTimePerResource = Duration.ZERO,
                    ),
                validationTime = Duration.ZERO,
                validatedResources = 1,
                validatedConstraints = 1,
            )
        val report = QualityReport.from(raw, emptyList())
        val counts = report.violationsBySeverity()
        assertEquals(1, counts[ViolationSeverity.VIOLATION])
        assertEquals(1, counts[ViolationSeverity.WARNING])
    }
}
