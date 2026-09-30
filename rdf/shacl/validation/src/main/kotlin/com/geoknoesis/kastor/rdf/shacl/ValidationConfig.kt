package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.vocab.SHACL
import java.time.Duration

/**
 * Configuration for SHACL validation operations.
 */
data class ValidationConfig(
    val profile: ValidationProfile = ValidationProfile.SHACL_CORE,
    val strictMode: Boolean = false,
    val includeWarnings: Boolean = true,
    val maxViolations: Int = 1000,
    val timeout: Duration = Duration.ofMinutes(5),
    val parallelValidation: Boolean = false,
    val streamingMode: Boolean = false,
    val batchSize: Int = 1000,
    val enableExplanations: Boolean = true,
    val enableSuggestions: Boolean = true,
    val validateClosedShapes: Boolean = true,
    val validateInactiveShapes: Boolean = false,
    val customParameters: Map<String, Any> = emptyMap(),
    /**
     * Forces a specific provider; must match [ShaclValidatorProvider.getType] (e.g. `kastor`, `memory`).
     */
    val providerId: String? = null,
    /**
     * Provider ordering when several engines satisfy [profile].
     */
    val enginePreference: EnginePreference = EnginePreference.AUTO,
    /**
     * Maximum `sh:node` / logical nesting depth per validation root (native engine).
     * Default follows architecture §9.1.
     */
    val maxRecursionDepth: Int = 64,
    val cache: CacheConfig = CacheConfig(),
    val imports: ImportConfig = ImportConfig(),
    val streaming: StreamingConfigExtension = StreamingConfigExtension(),
    val dataset: DatasetValidationConfig = DatasetValidationConfig(),
    /** When false (default P1a), triple terms inside `sh:in` / `sh:hasValue` cause compile failure. */
    val allowTripleTermsInShapeParameters: Boolean = false,
    /**
     * Hard cap on **data graph + shapes graph** triple count (estimated via `RdfGraph.size`) before
     * validation runs. Default [Long.MAX_VALUE] (no limit). Lower this for untrusted input (e.g. `500_000`)
     * to bound memory; enforced by the RDF4J bridge, native engine, and memory provider.
     */
    val maxCombinedGraphTriples: Long = Long.MAX_VALUE,
    /**
     * What the native engine does with SHACL features it recognises but cannot evaluate (SHACL-SPARQL constraint
     * components, SHACL 1.2 node expressions such as `sh:values` / `sh:expression` / SPARQL expressions used as
     * targets). [UnsupportedFeatureHandling.FAIL] (default) rejects the shapes graph so such constraints are never
     * silently skipped.
     */
    val unsupportedFeatures: UnsupportedFeatureHandling = UnsupportedFeatureHandling.FAIL,
    /**
     * Maximum number of nodes in any intermediate or final node set of one SHACL property path evaluation (native
     * engine). Exceeding it fails validation instead of exhausting memory.
     */
    val maxPathValueNodes: Int = 1_000_000,
    /**
     * SHACL 1.2 `sh:conformanceDisallows` (native, RDF4J and memory validators): the result severities — `sh:Violation`, `sh:Warning`,
     * `sh:Info`, `sh:Debug`, `sh:Trace` or a custom severity IRI — whose results make [ValidationReport.isValid]
     * false. `null` (default) applies the SHACL default: every severity except `sh:Debug` and `sh:Trace`. Results of
     * other severities are still reported. Results about undefined recursive dependencies
     * ([ValidationViolation.isUndefinedRecursion]) carry the source shape's declared severity, so they block exactly when
     * a failure of that shape would.
     */
    val conformanceDisallows: Set<Iri>? = null,
) {

    /**
     * Whether [violation] makes a report non-conforming under [conformanceDisallows]. Its severity is the
     * violation's `sh:resultSeverity` IRI ([ValidationViolation.resultSeverityIri]) when set, otherwise the IRI of
     * its [ValidationViolation.severity]. Every validator (native, RDF4J, memory) decides
     * [ValidationReport.isValid] with this rule.
     */
    fun disallowsConformance(violation: ValidationViolation): Boolean =
        disallowsSeverity(violation.severity, violation.resultSeverityIri?.let { Iri(it) })

    internal fun disallowsSeverity(severity: ViolationSeverity, resultSeverityIri: Iri?): Boolean {
        val iri = resultSeverityIri ?: severity.toShaclSeverityIri()
        val disallowed = conformanceDisallows ?: return iri != SHACL.Debug && iri != SHACL.Trace
        return iri in disallowed
    }
    
    companion object {
        /**
         * Create a default configuration.
         */
        fun default(): ValidationConfig = ValidationConfig()
        
        /**
         * Create a configuration for SHACL Core validation.
         */
        fun shaclCore(): ValidationConfig = ValidationConfig(
            profile = ValidationProfile.SHACL_CORE
        )
        
        /**
         * Create a configuration for SHACL SPARQL validation.
         */
        fun shaclSparql(): ValidationConfig = ValidationConfig(
            profile = ValidationProfile.SHACL_SPARQL
        )
        
        /**
         * Create a configuration for strict validation.
         */
        fun strict(): ValidationConfig = ValidationConfig(
            strictMode = true,
            validateClosedShapes = true,
            validateInactiveShapes = true
        )
        
        /**
         * Create a configuration for large graphs.
         */
        fun forLargeGraphs(): ValidationConfig = ValidationConfig(
            streamingMode = false,
            parallelValidation = false,
            batchSize = 5000,
            maxViolations = 10000
        )
        
        /**
         * Create a configuration for fast validation.
         */
        fun forFastValidation(): ValidationConfig = ValidationConfig(
            includeWarnings = false,
            enableExplanations = false,
            enableSuggestions = false,
            timeout = Duration.ofMinutes(1)
        )

        /**
         * Tighter caps for validating **untrusted** RDF: limits combined triples and violation collection.
         * Applies to RDF4J, native (`kastor`), and memory providers.
         */
        fun rdf4jUntrustedInputLimits(
            maxCombinedGraphTriples: Long = 500_000L,
            maxViolations: Int = 1_000,
        ): ValidationConfig =
            ValidationConfig(
                maxCombinedGraphTriples = maxCombinedGraphTriples,
                maxViolations = maxViolations,
            )
        
        /**
         * Create a configuration for memory-constrained environments.
         */
        fun forMemoryConstrained(): ValidationConfig = ValidationConfig(
            streamingMode = false,
            maxCombinedGraphTriples = 100_000,
            batchSize = 100,
            maxViolations = 100
        )
    }
}

/**
 * Validation profiles for different SHACL constraint types.
 */
enum class ValidationProfile {
    SHACL_CORE,           // Core SHACL constraints (PropertyShape, NodeShape)
    SHACL_SPARQL,         // SPARQL-based constraints
    SHACL_JS,             // JavaScript-based constraints
    SHACL_PY,             // Python-based constraints
    SHACL_DASH,           // DASH extensions
    CUSTOM,               // Custom constraint types
    STRICT,               // Strict validation mode
    PERMISSIVE,           // Permissive validation mode
    COMPREHENSIVE         // All available constraint types
}

/**
 * SHACL Shape representation.
 */
data class ShaclShape(
    val shapeUri: String,
    val targetClass: String? = null,
    val targetNode: String? = null,
    val targetSubjectsOf: String? = null,
    val targetObjectsOf: String? = null,
    val deactivated: Boolean = false,
    val closed: Boolean = false,
    val ignoredProperties: List<String> = emptyList(),
    val constraints: List<ShaclConstraint> = emptyList()
)

/**
 * SHACL Constraint representation.
 */
data class ShaclConstraint(
    val constraintType: ConstraintType,
    val path: String? = null,
    val severity: ViolationSeverity = ViolationSeverity.VIOLATION,
    val message: String? = null,
    val parameters: Map<String, Any> = emptyMap()
)

/**
 * SHACL constraint types.
 */
enum class ConstraintType {
    // Property constraints
    PROPERTY_SHAPE,
    /** Native use: `sh:closed` violations (maps to `ClosedConstraintComponent`). */
    CLOSED,
    MIN_COUNT,
    MAX_COUNT,
    UNIQUE_LANG,
    LANGUAGE_IN,
    EQUALS,
    DISJOINT,
    LESS_THAN,
    LESS_THAN_OR_EQUALS,
    NOT,
    AND,
    OR,
    XONE,
    NODE,
    NODE_BY_EXPRESSION,
    
    // Value constraints
    DATATYPE,
    CLASS,
    NODE_KIND,
    MIN_LENGTH,
    MAX_LENGTH,
    PATTERN,
    FLAGS,
    MIN_INCLUSIVE,
    MAX_INCLUSIVE,
    MIN_EXCLUSIVE,
    MAX_EXCLUSIVE,
    IN,
    HAS_VALUE,
    
    // SPARQL constraints
    SPARQL_CONSTRAINT,
    SPARQL_CONSTRAINT_COMPONENT,
    
    // Qualified value shapes (native)
    QUALIFIED_VALUE_SHAPE,
    QUALIFIED_MIN_COUNT,
    QUALIFIED_MAX_COUNT,

    /** SHACL 1.2 list constraints */
    MIN_LIST_LENGTH,
    MAX_LIST_LENGTH,
    MEMBER_SHAPE,
    UNIQUE_MEMBERS,
    SUBSET_OF,
    SINGLE_LINE,
    SOME_VALUE,
    ROOT_CLASS,
    UNIQUE_VALUES_FOR,
    /** Like `sh:node`; maps to `sh:ShapeConstraintComponent`. */
    SHAPE,
    REIFIER_SHAPE,
    REIFICATION_REQUIRED,

    // JavaScript constraints
    JS_CONSTRAINT,
    
    // Python constraints
    PY_CONSTRAINT,
    
    // Custom constraints
    CUSTOM_CONSTRAINT
}

/**
 * Violation severity levels.
 */
enum class ViolationSeverity {
    INFO, // Informational message
    WARNING, // Warning that should be addressed
    VIOLATION, // Constraint violation
    ERROR, // Critical error
    /** SHACL 1.2 diagnostic severity (lighter than Info). */
    DEBUG,
    TRACE,
}









