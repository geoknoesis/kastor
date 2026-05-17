package com.geoknoesis.kastor.ontoquality.explanation

/**
 * Advisory natural-language explanation for one [com.geoknoesis.kastor.ontoquality.QualityFinding]; not entailment.
 */
data class FindingExplanation(
    val findingRef: FindingRef,
    val summary: String,
    val whyItMatters: String?,
    val suggestedActions: List<String>,
    val confidenceNote: String?,
    val modelId: String,
    val providerKind: String,
    val promptRunId: String,
)
