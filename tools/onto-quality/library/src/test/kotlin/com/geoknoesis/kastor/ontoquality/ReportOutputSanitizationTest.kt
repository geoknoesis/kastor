package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.explanation.ExplainedQualityReport
import com.geoknoesis.kastor.ontoquality.explanation.FindingExplanation
import com.geoknoesis.kastor.ontoquality.explanation.FindingRef
import com.geoknoesis.kastor.ontoquality.integration.MetricsContext
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ValidationReport
import com.geoknoesis.kastor.rdf.shacl.ValidationStatistics
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * SHACL messages interpolate ontology text (e.g. `{?label}`), so every ontology-derived field is untrusted in
 * Markdown (link / image / HTML injection) and in terminal text (ANSI escape injection).
 */
class ReportOutputSanitizationTest {
    private val hostile = "[verify](https://evil) <img src=x> [31mred"
    private val escapedHostile = "\\[verify\\]\\(https:\\/\\/evil\\) \\<img src=x\\>  \\[31mred"

    @Test
    fun `markdown category list escapes ontology-derived messages`() {
        val md = report(hostile).describeMarkdown()
        assertTrue(md.lines().contains("- $escapedHostile"), md)
        assertNoRawInjection(md)
    }

    @Test
    fun `markdown top findings escape message, focus label and shape`() {
        val focus = "http://ex.org/x#Evil_*bold*"
        val ctx = MetricsContext(summary = "**OQuaRE metric summary**", entityImportance = mapOf(focus to 0.9))
        val md =
            report(hostile, focus = focus, shapeUri = "http://ex.org/shapes#S<b>x</b>", context = ctx)
                .describeMarkdown(MarkdownReportOptions(useAsciiSeverityMarkers = true))
        assertTrue(md.lines().contains("### [VIOLATION] Evil\\_\\*bold\\* — $escapedHostile"), md)
        assertTrue(md.lines().contains(escapedHostile), md)
        assertTrue(md.lines().contains("Source shape: **S\\<b\\>x\\</b\\>**"), md)
        // Trusted, tool-generated Markdown (metrics summary) is kept as-is.
        assertTrue(md.contains("**OQuaRE metric summary**"), md)
        assertNoRawInjection(md)
    }

    @Test
    fun `text output renders control characters visibly but keeps newlines and tabs`() {
        val text = report("bad[2J\rover\n\tnext").describeText()
        assertTrue(text.contains(" - [STRUCTURAL] VIOLATION: bad\\u001B[2J\\u0007\\u009B\\u007F\\u000Dover\n\tnext"), text)
        assertNoRawControls(text)
    }

    @Test
    fun `explained text output renders LLM control characters visibly`() {
        val report = report("plain")
        val ref = FindingRef.from(report.findings.single())
        val evil = "ok]0;pwnedX"
        val explained =
            ExplainedQualityReport(
                report,
                listOf(FindingExplanation(ref, evil, evil, listOf(evil), evil, "m", "p", "r")),
            )
        val text = explained.describeText()
        val visible = "ok\\u001B]0;pwned\\u0007\\u009BX"
        assertTrue(text.lines().contains("  $visible"), text)
        assertTrue(text.lines().contains("  Why it matters: $visible"), text)
        assertTrue(text.lines().contains("    - $visible"), text)
        assertTrue(text.lines().contains("  Note: $visible"), text)
        assertEquals(1, text.lines().count { it.contains("(p\\u001B / m\\u001B)") }, text)
        assertNoRawControls(text)
    }

    /** Backslash-escaped punctuation (`\<img`, `\]\(`) renders literally; only unescaped syntax is dangerous. */
    private fun assertNoRawInjection(md: String) {
        listOf("""(?<!\\)<img""", """(?<!\\)<b>""", """(?<!\\)\]\(""", "https://evil", "").forEach {
            assertFalse(Regex(it).containsMatchIn(md), "unescaped '$it' in Markdown:\n$md")
        }
    }

    private fun assertNoRawControls(text: String) {
        val bad = text.filter { (it.isISOControl() && it != '\n' && it != '\t') }
        assertEquals("", bad, "raw control characters in text output")
    }

    private fun report(
        message: String,
        focus: String = "http://ex.org/A",
        shapeUri: String? = null,
        context: MetricsContext? = null,
    ): QualityReport {
        val violation =
            ValidationViolation(
                severity = ViolationSeverity.VIOLATION,
                constraint = ShaclConstraint(ConstraintType.MIN_COUNT, severity = ViolationSeverity.VIOLATION),
                focusNode = Iri(focus),
                message = message,
                shapeUri = shapeUri,
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
        return QualityReport.from(raw, emptyList(), context)
    }
}
