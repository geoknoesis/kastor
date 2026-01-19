package com.example.ontomapper.runtime

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
}

interface ShaclValidator {
    fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult
}

object ShaclValidation {
    @Volatile
    private var validator: ShaclValidator? = null

    private const val ERROR_NO_VALIDATOR = "No ShaclValidator registered"

    @JvmStatic
    fun register(v: ShaclValidator?) {
        validator = v
    }

    @JvmStatic
    fun current(): ShaclValidator = validator ?: error(ERROR_NO_VALIDATOR)
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













