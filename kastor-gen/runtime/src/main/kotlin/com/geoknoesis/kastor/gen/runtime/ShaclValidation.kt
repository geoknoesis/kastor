package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm

enum class ShaclSeverity {
    Violation,
    Warning,
    Info
}

/**
 * Represents a SHACL validation violation with structured information.
 * 
 * @property focusNode The resource that violated the constraint
 * @property shapeIri The IRI of the SHACL shape that was violated
 * @property constraintIri The IRI of the constraint component that failed (e.g., sh:minCount)
 * @property path The property path that was validated (null for node-level constraints)
 * @property actualValue The actual value that violated the constraint (if applicable)
 * @property expectedValue The expected value or constraint description (if applicable)
 * @property message Human-readable error message
 * @property severity The severity level of the violation
 */
data class ShaclViolation(
    val focusNode: RdfResource,
    val shapeIri: Iri,
    val constraintIri: Iri,
    val path: Iri? = null,
    val actualValue: RdfTerm? = null,
    val expectedValue: RdfTerm? = null,
    val message: String,
    val severity: ShaclSeverity = ShaclSeverity.Violation
)

sealed interface ValidationResult {
    data object Ok : ValidationResult
    data class Violations(val items: List<ShaclViolation>) : ValidationResult
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
    }
}













