package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.*
import java.time.Duration

/**
 * Comprehensive SHACL validation report.
 */
data class ValidationReport(
    val isValid: Boolean,
    val violations: List<ValidationViolation>,
    val warnings: List<ValidationWarning>,
    val statistics: ValidationStatistics,
    val validationTime: Duration,
    val validatedResources: Int,
    val validatedConstraints: Int,
    val shapeViolations: Map<String, List<ValidationViolation>> = emptyMap(),
    val constraintViolations: Map<String, List<ValidationViolation>> = emptyMap()
) {
    
    /**
     * Get all violations by severity.
     */
    fun getViolationsBySeverity(severity: ViolationSeverity): List<ValidationViolation> {
        return violations.filter { it.severity == severity }
    }
    
    /**
     * Get violations for a specific resource.
     */
    fun getViolationsForResource(resource: RdfResource): List<ValidationViolation> {
        return violations.filter { it.resource == resource }
    }
    
    /**
     * Get violations for a specific shape.
     */
    fun getViolationsForShape(shapeUri: String): List<ValidationViolation> {
        return shapeViolations[shapeUri] ?: emptyList()
    }
    
    /**
     * Get violations for a specific constraint type.
     */
    fun getViolationsForConstraint(constraintType: ConstraintType): List<ValidationViolation> {
        return violations.filter { it.constraint.constraintType == constraintType }
    }
    
    /**
     * Get summary of validation results.
     */
    fun getSummary(): ValidationSummary {
        return ValidationSummary(
            isValid = isValid,
            totalViolations = violations.size,
            violationsBySeverity = violations.groupBy { it.severity }.mapValues { it.value.size },
            violationsByShape = shapeViolations.mapValues { it.value.size },
            violationsByConstraint = constraintViolations.mapValues { it.value.size },
            validationTime = validationTime,
            validatedResources = validatedResources,
            validatedConstraints = validatedConstraints
        )
    }
}

/**
 * SHACL validation violation.
 */
data class ValidationViolation(
    val severity: ViolationSeverity,
    val constraint: ShaclConstraint,
    val resource: RdfResource,
    val message: String,
    val path: List<RdfTerm>? = null,
    val explanation: String? = null,
    val suggestedFix: String? = null,
    val shapeUri: String? = null,
    val violationCode: String? = null,
    val context: Map<String, Any> = emptyMap()
) {
    
    /**
     * Get a human-readable description of the violation.
     */
    fun getDescription(): String {
        val sb = StringBuilder()
        sb.append("${severity.name}: $message")
        if (explanation != null) {
            sb.append("\n  Explanation: $explanation")
        }
        if (suggestedFix != null) {
            sb.append("\n  Suggested fix: $suggestedFix")
        }
        if (violationCode != null) {
            sb.append("\n  Violation code: $violationCode")
        }
        return sb.toString()
    }
}

/**
 * SHACL validation warning.
 */
data class ValidationWarning(
    val message: String,
    val resource: RdfResource? = null,
    val shapeUri: String? = null,
    val constraint: ShaclConstraint? = null,
    val explanation: String? = null
)

/**
 * Validation statistics.
 */
data class ValidationStatistics(
    val totalResources: Int,
    val validatedResources: Int,
    val totalConstraints: Int,
    val validatedConstraints: Int,
    val shapesProcessed: Int,
    val constraintsByType: Map<ConstraintType, Int>,
    val violationsByType: Map<ConstraintType, Int>,
    val warningsByType: Map<ConstraintType, Int>,
    val averageValidationTimePerResource: Duration,
    val memoryUsage: Long? = null
)

/**
 * Validation summary.
 */
data class ValidationSummary(
    val isValid: Boolean,
    val totalViolations: Int,
    val violationsBySeverity: Map<ViolationSeverity, Int>,
    val violationsByShape: Map<String, Int>,
    val violationsByConstraint: Map<String, Int>,
    val validationTime: Duration,
    val validatedResources: Int,
    val validatedConstraints: Int
) {
    
    /**
     * Get a human-readable summary.
     */
    fun getDescription(): String {
        val sb = StringBuilder()
        sb.append("Validation ${if (isValid) "PASSED" else "FAILED"}")
        sb.append("\n  Total violations: $totalViolations")
        if (violationsBySeverity.isNotEmpty()) {
            sb.append("\n  Violations by severity:")
            violationsBySeverity.forEach { (severity, count) ->
                sb.append("\n    $severity: $count")
            }
        }
        sb.append("\n  Validated resources: $validatedResources")
        sb.append("\n  Validated constraints: $validatedConstraints")
        sb.append("\n  Validation time: ${validationTime.toMillis()}ms")
        return sb.toString()
    }
}

/**
 * Detailed explanation of a validation result.
 */
data class ValidationExplanation(
    val violation: ValidationViolation,
    val reasoning: String,
    val examples: List<String> = emptyList(),
    val references: List<String> = emptyList()
)

/**
 * Validation suggestion for fixing violations.
 */
data class ValidationSuggestion(
    val violation: ValidationViolation,
    val suggestedAction: String,
    val codeExample: String? = null,
    val confidence: Double = 1.0
)
