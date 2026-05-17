package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity

data class ExplanationOptions(
    val maxFindings: Int = 50,
    val minSeverity: ViolationSeverity = ViolationSeverity.WARNING,
    /** Max findings per LLM request (smaller batches = safer token limits). */
    val batchSize: Int = 12,
) {
    init {
        require(maxFindings > 0) { "maxFindings must be positive" }
        require(batchSize > 0) { "batchSize must be positive" }
    }
}

private fun ViolationSeverity.rank(): Int =
    when (this) {
        ViolationSeverity.TRACE,
        ViolationSeverity.DEBUG,
        ViolationSeverity.INFO,
        -> 0
        ViolationSeverity.WARNING -> 1
        ViolationSeverity.VIOLATION,
        ViolationSeverity.ERROR,
        -> 2
    }

fun ViolationSeverity.isAtLeast(min: ViolationSeverity): Boolean =
    this.rank() >= min.rank()
