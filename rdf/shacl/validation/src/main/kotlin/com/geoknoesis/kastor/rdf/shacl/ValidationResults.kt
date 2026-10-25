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
    val constraintViolations: Map<String, List<ValidationViolation>> = emptyMap(),
    /**
     * True when the engine produced more violation rows than returned (see [ValidationConfig.maxViolations]).
     */
    val violationsTruncated: Boolean = false,
) {

    /**
     * True when at least one result has severity [ViolationSeverity.VIOLATION] or [ViolationSeverity.ERROR].
     *
     * Unlike [isValid] (SHACL `sh:conforms`, which is false as soon as a result of severity `sh:Violation`,
     * `sh:Warning`, `sh:Info` or a custom severity exists), this helper lets callers treat lower severities as
     * non-blocking.
     *
     * When [violationsTruncated] is true the results beyond [ValidationConfig.maxViolations] are not in [violations],
     * so their severities are unknown: the helper then fails closed and returns true, so that a flood of lower-severity
     * results cannot hide a Violation reported after them.
     */
    val hasViolations: Boolean
        get() = violationsTruncated ||
            violations.any { it.severity == ViolationSeverity.VIOLATION || it.severity == ViolationSeverity.ERROR }

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
        return violations.filter { it.focusNode == resource }
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
    /** Focus node or literal targeted by this violation (`sh:focusNode`). */
    val focusNode: RdfTerm,
    val message: String,
    val path: List<RdfTerm>? = null,
    /** Offending RDF term when known; emitted as `sh:value` on `sh:ValidationResult`. */
    val value: RdfTerm? = null,
    val explanation: String? = null,
    val suggestedFix: String? = null,
    val shapeUri: String? = null,
    val violationCode: String? = null,
    val context: Map<String, Any> = emptyMap(),
    /** Non-standard `sh:resultSeverity` IRI (SHACL allows user-defined severities). */
    val resultSeverityIri: String? = null,
    /**
     * Full `sh:resultPath` term when the path is not representable by [path] (inverse, alternative, closures…).
     * Blank-node path structures are described by [resultPathTriples].
     */
    val resultPathNode: RdfTerm? = null,
    /** Triples describing the blank-node structure rooted at [resultPathNode] (empty for IRI paths). */
    val resultPathTriples: List<RdfTriple> = emptyList(),
    /** `sh:message` values of the source shape/constraint (with language tags), emitted as `sh:resultMessage`. */
    val resultMessages: List<Literal> = emptyList(),
    /**
     * `sh:sourceConstraint` (e.g. the SHACL-SPARQL constraint node), when applicable. For a blank-node expression of
     * `sh:expression`, or a blank-node shape of `sh:nodeByExpression`, the native engine also puts a handle on the
     * shapes graph in [context] (key `"sourceConstraintTriples"`), from which [toShaclValidationReportRdf] writes the
     * node's deep copy; nothing is copied until then.
     */
    val sourceConstraint: RdfTerm? = null,
    /**
     * `sh:sourceConstraintComponent` when it is not implied by [constraint] (a SPARQL-based constraint component);
     * when non-null it replaces the component derived from the constraint type.
     */
    val sourceConstraintComponent: String? = null,
    /** SHACL-SPARQL result annotations (`sh:resultAnnotation`): property to values, emitted on the `sh:ValidationResult`. */
    val resultAnnotations: Map<Iri, List<RdfTerm>> = emptyMap(),
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

    /**
     * True when this result does not report a failure but an **undefined** answer: the constraint's outcome depends
     * on a recursive shape dependency through a non-monotone operator (`sh:not`, `sh:xone`, `sh:qualifiedMaxCount`,
     * disjoint qualified value shapes), or a `sh:targetWhere` membership that is undefined for the same reason.
     * Such a result keeps the source shape's declared severity and constraint component, so it affects
     * [ValidationReport.isValid] exactly as a failure of that constraint would; its [violationCode] is
     * [UNDEFINED_RECURSION_CODE].
     */
    val isUndefinedRecursion: Boolean get() = violationCode == UNDEFINED_RECURSION_CODE

    /**
     * True when this result reports that a constraint could not be decided because one `sh:pattern` evaluation used
     * up [ValidationConfig.patternTimeout] ([violationCode] is [PATTERN_TIMEOUT_CODE]). The value is not accepted as
     * conforming: the result has the source shape's severity and blocks conformance as a failure would.
     */
    val isPatternTimeout: Boolean get() = violationCode == PATTERN_TIMEOUT_CODE

    /**
     * True when this result reports that a constraint could not be decided because the regular expression engine ran
     * out of stack while matching a `sh:pattern` ([violationCode] is [PATTERN_TOO_COMPLEX_CODE]). Like
     * [isPatternTimeout], it blocks conformance as a failure would.
     */
    val isPatternTooComplex: Boolean get() = violationCode == PATTERN_TOO_COMPLEX_CODE

    /**
     * True when this result does not report a definite failure but a constraint the engine could **not decide**
     * ([isUndefinedRecursion], [isPatternTimeout] or [isPatternTooComplex]); [resultStatus] says which.
     */
    val isUndecided: Boolean get() = resultStatus != null

    /**
     * The `ksh:resultStatus` of this result ([KastorShaclVocabulary.UndefinedRecursion],
     * [KastorShaclVocabulary.PatternTimeout] or [KastorShaclVocabulary.PatternTooComplex]), or `null` for an ordinary
     * failure. Exported by [toShaclValidationReportRdf].
     */
    val resultStatus: Iri?
        get() = when (violationCode) {
            UNDEFINED_RECURSION_CODE -> KastorShaclVocabulary.UndefinedRecursion
            PATTERN_TIMEOUT_CODE -> KastorShaclVocabulary.PatternTimeout
            PATTERN_TOO_COMPLEX_CODE -> KastorShaclVocabulary.PatternTooComplex
            else -> null
        }

    companion object {
        /** [violationCode] of results about undefined recursive shape dependencies (see [isUndefinedRecursion]). */
        const val UNDEFINED_RECURSION_CODE: String = "kastor:UndefinedRecursion"

        /** [violationCode] of results about a `sh:pattern` evaluation that exceeded its budget (see [isPatternTimeout]). */
        const val PATTERN_TIMEOUT_CODE: String = "kastor:PatternTimeout"

        /** [violationCode] of results about a `sh:pattern` evaluation that exhausted the stack (see [isPatternTooComplex]). */
        const val PATTERN_TOO_COMPLEX_CODE: String = "kastor:PatternTooComplex"

        /**
         * Key of the [context] entry that holds the reifier (an [RdfResource]) on results of `sh:reifierShape` — the
         * reifier that fails the shape, or whose conformance could not be decided ([isUndecided]). [value] of such a
         * result is the object of the reified triple, so several reifiers of one triple are told apart by this entry
         * (exported as `ksh:reifier`, see [KastorShaclVocabulary.reifier]).
         */
        const val REIFIER_CONTEXT_KEY: String = "reifier"
    }
}

/**
 * Key of the [ValidationViolation.context] entry that holds the [SourceConstraintTriples] of the run, from which
 * [toShaclValidationReportRdf] writes the deep copy of a blank-node [ValidationViolation.sourceConstraint] into the
 * results graph (SHACL 1.2 Node Expressions §7.1 and §7.2). It is the only way from a result to its shapes graph
 * that leaves the public API unchanged.
 */
internal const val SOURCE_CONSTRAINT_TRIPLES_CONTEXT_KEY: String = "sourceConstraintTriples"

/**
 * The blank nodes of one shapes graph with their own triples, for the deep copies of `sh:sourceConstraint` values.
 * Nothing is computed until a report is written: [of] fetches the triples of a blank node from [describe] the first
 * time it is asked, and keeps them, so all the reports and results of a run fetch each blank node once, and in total
 * no more than the shapes graph [shapesTriples]. The writer follows the blank-node objects itself, one node at a time.
 * Thread-safe (a report can be written from several threads). Two instances are equal when their shapes graphs are,
 * so that results stay comparable across runs. Comparing results compares their handles, so equality is cheap: the
 * same instance or the same graph is equal at once, and otherwise the content hash of each graph, computed once,
 * tells most different graphs apart before their triples are compared.
 */
internal class SourceConstraintTriples(
    private val shapesTriples: List<RdfTriple>,
    private val describe: (BlankNode) -> List<RdfTriple>,
) {
    private val described = HashMap<BlankNode, List<RdfTriple>>()

    /** The most triples a deep copy, or all the deep copies of a report together, can hold: the shapes graph. */
    val maxTriples: Int get() = shapesTriples.size

    @Synchronized
    fun of(node: BlankNode): List<RdfTriple> = described.getOrPut(node) { describe(node) }

    /** The content hash of [shapesTriples], computed once. */
    private val contentHash: Int by lazy { shapesTriples.hashCode() }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SourceConstraintTriples &&
            (other.shapesTriples === shapesTriples || (other.contentHash == contentHash && other.shapesTriples == shapesTriples))

    override fun hashCode(): Int = contentHash

    override fun toString(): String = "SourceConstraintTriples(${shapesTriples.size} shapes graph triples)"
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









