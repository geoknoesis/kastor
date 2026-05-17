package com.geoknoesis.kastor.ontoquality

/**
 * Options for [QualityReport.describeMarkdown].
 *
 * @property maxTopFindings cap for the "Top findings by importance" section when [QualityReport.metricsContext] is set.
 * @property useAsciiSeverityMarkers use `[VIOLATION]`-style markers instead of emoji (better for plain terminals / CI logs).
 */
data class MarkdownReportOptions(
    val maxTopFindings: Int = 10,
    val useAsciiSeverityMarkers: Boolean = false,
)
