package com.geoknoesis.kastor.ontoquality.explanation

/**
 * A batch of findings for which no LLM explanation could be produced (transport error, timeout after
 * retries, unparseable or incomplete reply). Recorded so partial results stay usable and callers can decide
 * whether a failure is fatal.
 *
 * @param findingRefs findings left without an explanation.
 * @param reason short diagnostic (exception type and message, or a description of the bad reply).
 */
data class ExplanationFailure(
    val findingRefs: List<FindingRef>,
    val reason: String,
)
