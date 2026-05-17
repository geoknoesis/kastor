package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.ontoquality.QualityReport

/**
 * v0.3: enriches a [QualityReport] with LLM-generated explanations.
 * Implementations live in optional modules (e.g. `:tools:onto-quality-llm-koog`).
 */
fun interface QualityExplanationEnricher {
    suspend fun enrich(
        report: QualityReport,
        options: ExplanationOptions,
    ): ExplainedQualityReport
}

suspend fun QualityExplanationEnricher.enrich(report: QualityReport): ExplainedQualityReport =
    enrich(report, ExplanationOptions())
