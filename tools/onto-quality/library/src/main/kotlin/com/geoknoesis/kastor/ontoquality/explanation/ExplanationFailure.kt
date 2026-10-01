package com.geoknoesis.kastor.ontoquality.explanation

/**
 * A batch of findings for which no LLM explanation could be produced (transport error, timeout after
 * retries, unparseable or incomplete reply). Recorded so partial results stay usable and callers can decide
 * whether a failure is fatal.
 *
 * @param findingRefs findings left without an explanation.
 * @param reason short diagnostic (exception type and message, or a description of the bad reply). Implementations
 *   cap and sanitise it, as it may contain provider error text.
 */
data class ExplanationFailure(
    val findingRefs: List<FindingRef>,
    val reason: String,
) {
    /**
     * The exception behind the failure (HTTP error, timeout, …), for diagnostics such as `onto-qa --debug`; `null`
     * when the failure is not an exception (for example an incomplete reply).
     *
     * It is deliberately not part of the value: it is ignored by [equals], [hashCode] and [toString], dropped by
     * [copy], and never written to reports. Its message is raw provider text: sanitise it before printing.
     */
    var cause: Throwable? = null
        private set

    constructor(findingRefs: List<FindingRef>, reason: String, cause: Throwable?) : this(findingRefs, reason) {
        this.cause = cause
    }
}
