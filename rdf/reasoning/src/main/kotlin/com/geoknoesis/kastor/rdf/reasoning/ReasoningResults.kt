package com.geoknoesis.kastor.rdf.reasoning

import com.geoknoesis.kastor.rdf.*
import java.time.Duration

data class ReasoningResult(
    val originalGraph: RdfGraph,
    val inferredTriples: List<RdfTriple>,
    val classification: ClassificationResult?,
    val consistencyCheck: ConsistencyResult,
    val reasoningTime: Duration,
    val statistics: ReasoningStatistics
)

data class ClassificationResult(
    val classHierarchy: Map<Iri, List<Iri>>, // class -> superclasses
    val instanceClassifications: Map<Iri, List<Iri>>, // instance -> types
    val propertyHierarchy: Map<Iri, List<Iri>>, // property -> superproperties
    val equivalentClasses: Map<Iri, Set<Iri>> = emptyMap(),
    val disjointClasses: Map<Iri, Set<Iri>> = emptyMap()
)

data class ConsistencyResult(
    val isConsistent: Boolean,
    val inconsistencies: List<Inconsistency>,
    val warnings: List<String>,
    val explanations: List<Explanation> = emptyList()
)

data class Inconsistency(
    val type: InconsistencyType,
    val description: String,
    val affectedResources: List<RdfTerm>,
    val severity: Severity = Severity.ERROR
)

enum class InconsistencyType {
    CLASS_CONFLICT, PROPERTY_CONFLICT, DOMAIN_RANGE_VIOLATION, 
    FUNCTIONAL_PROPERTY_VIOLATION, CARDINALITY_VIOLATION, 
    DISJOINTNESS_VIOLATION, EQUIVALENCE_CONFLICT
}

enum class Severity {
    INFO, WARNING, ERROR, CRITICAL
}

data class Explanation(
    val rule: String,
    val premises: List<RdfTriple>,
    val conclusion: RdfTriple
)

data class ReasoningStatistics(
    val totalTriples: Int,
    val inferredTriples: Int,
    val classesProcessed: Int,
    val propertiesProcessed: Int,
    val rulesApplied: Map<String, Int>,
    val memoryUsage: Long,
    val cpuTime: Duration
)

data class ValidationReport(
    val isValid: Boolean,
    val violations: List<ValidationViolation>,
    val warnings: List<String>,
    val statistics: ValidationStatistics
)

data class ValidationViolation(
    val constraint: String,
    val resource: RdfTerm,
    val message: String,
    val severity: Severity
)

data class ValidationStatistics(
    val constraintsChecked: Int,
    val violationsFound: Int,
    val warningsFound: Int,
    val validationTime: Duration
)









