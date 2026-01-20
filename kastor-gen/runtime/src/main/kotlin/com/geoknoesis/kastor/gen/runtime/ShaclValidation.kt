package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm

enum class ShaclSeverity {
    Violation,
    Warning,
    Info
}

/**
 * Represents a SHACL validation violation.
 */
data class ShaclViolation(
    val path: Iri?,
    val message: String,
    val severity: ShaclSeverity = ShaclSeverity.Violation
)

sealed interface ValidationResult {
    data object Ok : ValidationResult
    data class Violations(val items: List<ShaclViolation>) : ValidationResult
    data object NotConfigured : ValidationResult
}

interface ValidationContext {
    fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult
}

class ValidationException(
    message: String,
    val violations: List<ShaclViolation> = emptyList(),
    cause: Throwable? = null
) : RuntimeException(message, cause)

fun ValidationResult.orThrow() {
    when (this) {
        is ValidationResult.Ok -> Unit
        is ValidationResult.Violations -> {
            val message = items.joinToString("; ") { it.message }
            throw ValidationException(message.ifBlank { "SHACL validation failed" }, items)
        }
        is ValidationResult.NotConfigured -> Unit
    }
}













