package com.geoknoesis.kastor.ontoquality.llm

import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.ontoquality.explanation.ExplanationOptions
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ValidationReport
import com.geoknoesis.kastor.rdf.shacl.ValidationStatistics
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import java.time.Duration
import com.geoknoesis.kastor.ontoquality.explanation.FindingRef
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Live call to OpenAI via Koog. Skipped unless `OPENAI_API_KEY` is set (non-blank).
 *
 * Example (PowerShell): `./gradlew :tools:onto-quality-llm-koog:test`
 *
 * To skip even when a key is present (e.g. shared CI agent), set `KASTOR_SKIP_OPENAI_LLM_TESTS=1`.
 */
class OpenAiExplanationIntegrationTest {

    @Test
    fun enrichesReportWithOpenAi() =
        runBlocking {
            assumeFalse(
                System.getenv("KASTOR_SKIP_OPENAI_LLM_TESTS") == "1",
                "Skipped: KASTOR_SKIP_OPENAI_LLM_TESTS=1",
            )
            assumeTrue(
                !System.getenv("OPENAI_API_KEY").isNullOrBlank(),
                "Set OPENAI_API_KEY to run this integration test.",
            )

            val report = sampleQualityReport()
            val expectedRef = FindingRef.from(report.findings.single())

            val enricher =
                qualityExplanationEnricher(
                    LlmExplanationConfig(provider = LlmProvider.OPENAI),
                )
            val explained =
                enricher.enrich(
                    report,
                    ExplanationOptions(maxFindings = 3, batchSize = 3, minSeverity = ViolationSeverity.WARNING),
                )

            assertTrue(
                explained.hasLlmExplanations,
                "Expected hasLlmExplanations; LLM returned no parsed rows (check API key, model, and JSON output).",
            )
            assertTrue(
                explained.explanationsByRef().containsKey(expectedRef),
                "Expected explanation keyed to synthetic finding ${expectedRef.hexSha256}; got refs: ${explained.explanations.map { it.findingRef.hexSha256 }}",
            )
            val top = explained.explanationsByRef().getValue(expectedRef)
            assertTrue(top.summary.length >= 12, "Expected substantive summary, got: ${top.summary}")
            assertTrue(
                explained.describeMarkdown().contains("LLM explanations"),
                "Markdown report should advertise the LLM section",
            )
            assertTrue(
                explained.explanations.all { it.providerKind == "openai" },
                "Expected openai provider in metadata",
            )
            assertEquals(1, explained.explanationsByRef().size, "One finding should map to one explanation (by ref)")
        }

    private fun sampleQualityReport(): QualityReport {
        val violation =
            ValidationViolation(
                severity = ViolationSeverity.WARNING,
                constraint = ShaclConstraint(ConstraintType.MIN_COUNT, severity = ViolationSeverity.WARNING),
                focusNode = Iri("http://example.org/vocab/Person"),
                message = "Class has no rdfs:label in English; SKOS-style publishing expects at least one prefLabel or rdfs:label.",
                shapeUri = "http://example.org/shapes#LabelShape",
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
