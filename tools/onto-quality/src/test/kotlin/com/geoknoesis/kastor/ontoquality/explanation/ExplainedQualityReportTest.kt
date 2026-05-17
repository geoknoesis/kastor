package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ValidationReport
import com.geoknoesis.kastor.rdf.shacl.ValidationStatistics
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExplainedQualityReportTest {

    @Test
    fun hasLlmExplanationsWhenListNonEmpty() {
        val report = minimalQualityReport()
        val ref = FindingRef.from(report.findings.single(), 0)
        val explained =
            ExplainedQualityReport(
                report = report,
                explanations =
                    listOf(
                        FindingExplanation(
                            findingRef = ref,
                            summary = "Synthetic explanation for test.",
                            whyItMatters = "Readable output.",
                            suggestedActions = listOf("Add rdfs:label"),
                            confidenceNote = null,
                            modelId = "test-model",
                            providerKind = "test",
                            promptRunId = "abc",
                        ),
                    ),
            )
        assertTrue(explained.hasLlmExplanations)
        assertTrue(explained.describeMarkdown().contains("LLM explanations"))
        assertTrue(explained.describeText().contains("LLM explanations"))
        assertTrue(explained.explanationsByRef().containsKey(ref))
    }

    @Test
    fun hasLlmExplanationsFalseWhenNoRows() {
        val report = minimalQualityReport()
        val explained = ExplainedQualityReport(report, emptyList())
        assertFalse(explained.hasLlmExplanations)
        assertFalse(explained.describeMarkdown().contains("## LLM explanations"))
    }

    private fun minimalQualityReport(): QualityReport {
        val violation =
            ValidationViolation(
                severity = ViolationSeverity.WARNING,
                constraint = ShaclConstraint(ConstraintType.MIN_COUNT, severity = ViolationSeverity.WARNING),
                focusNode = Iri("http://ex.org/A"),
                message = "example",
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
        return QualityReport.from(raw, emptyList())
    }
}
