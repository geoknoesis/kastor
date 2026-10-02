package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.rdf.jena.JenaBridge
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.ontoquality.catalog.EvaluationScopes
import com.geoknoesis.kastor.ontoquality.catalog.ShapeCatalog
import com.geoknoesis.kastor.ontoquality.catalog.ShapeMetadata
import com.geoknoesis.kastor.ontoquality.integration.MetricsContext
import com.geoknoesis.kastor.ontoquality.integration.MetricsProvider
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoning
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile
import com.geoknoesis.kastor.ontoquality.reasoning.toReasonerConfigOrNull
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator
import com.geoknoesis.kastor.rdf.shacl.ValidationReport
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ValidationWarning
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.slf4j.LoggerFactory

private val qualityCheckerLogger = LoggerFactory.getLogger(QualityChecker::class.java)

class QualityChecker private constructor(
    private val validator: ShaclValidator,
    private val catalogs: List<ShapeCatalog>,
    private val metricsProvider: MetricsProvider?,
) {
    private val mergedShapes: RdfGraph by lazy { Companion.mergeCatalogShapes(catalogs) }

    private val metadataByShape: Map<String, ShapeMetadata> by lazy {
        catalogs.fold(emptyMap()) { acc, c -> acc + c.shapeMetadata }
    }

    fun check(ontology: RdfGraph): QualityReport {
        val raw = validator.validate(ontology, mergedShapes)
        val metricsContext = computeMetricsContext(ontology)
        return QualityReport.fromKeyed(raw, catalogs, metricsContext, QualityReport.blankNodeKeys(raw, ontology, null) { mergedShapes })
    }

    private val scopes: EvaluationScopes by lazy { EvaluationScopes.of(mergedShapes) }
    private val assertedScopeShapes: RdfGraph? by lazy { scopes.shapesFor(mergedShapes, EvaluationScopes.Scope.ASSERTED) }
    private val entailedScopeShapes: RdfGraph? by lazy { scopes.shapesFor(mergedShapes, EvaluationScopes.Scope.ENTAILED) }

    /**
     * Validates after optional OWL/RDFS materialisation per [reasoning], and merges **reasoning preflight**
     * inconsistency rows (Kastor **K07**; pitfall metadata from **`OOPS_PITFALL_REGISTRY`**, included in [QualityChecker.default]).
     *
     * Each detector runs on the graph its shape declares with `oqsh:evaluatedOn`:
     * - `oqsh:AssertedGraph` (structural detectors: cycles, missing declarations, hierarchy shape, annotations,
     *   naming…) on the **asserted** [ontology]. A reasoner's closure (reflexive `C rdfs:subClassOf C`, axiomatic
     *   triples on the built-in vocabulary, entailed typing) would make them fire on every class or go silent;
     * - `oqsh:EntailedGraph`, and shapes without the annotation (custom catalogues), on the **materialised** graph:
     *   class-membership, disjointness and value-type conditions that are meant to see inferred triples.
     *
     * So a reasoner can only add findings of the second kind. The [MetricsProvider] always receives the asserted
     * [ontology] as well: structural metrics and importance scores are meaningless on a closure.
     */
    fun check(ontology: RdfGraph, reasoning: OntoQualityReasoningProfile): QualityReport {
        val materialized = OntoQualityReasoning.materializeWithReasoning(ontology, reasoning.toReasonerConfigOrNull())
        val metricsContext = computeMetricsContext(ontology)
        val raw =
            if (materialized.graph === ontology) {
                validator.validate(ontology, mergedShapes)
            } else {
                val onAsserted = assertedScopeShapes?.let { validator.validate(ontology, it) }
                val onEntailed = entailedScopeShapes?.let { validator.validate(materialized.graph, it) }
                when {
                    onAsserted == null -> onEntailed ?: validator.validate(materialized.graph, mergedShapes)
                    onEntailed == null -> onAsserted
                    else -> combineValidationReports(onAsserted, onEntailed)
                }
            }
        val merged =
            materialized.reasoningResult?.let { rr ->
                mergeValidationReport(raw, OntoQualityReasoning.inconsistencyViolationsForReport(rr), rr.consistencyCheck.warnings)
            } ?: raw
        // Blank-node keys come from the asserted graph (once), so finding refs do not change with the reasoner.
        val keys = QualityReport.blankNodeKeys(merged, ontology, materialized.graph) { mergedShapes }
        return QualityReport.fromKeyed(merged, catalogs, metricsContext, keys)
    }

    fun checkResource(ontology: RdfGraph, resource: RdfResource): QualityReport {
        val raw = validator.validateResource(ontology, mergedShapes, resource)
        return QualityReport.fromKeyed(raw, catalogs, metricsContext = null, keys = QualityReport.blankNodeKeys(raw, ontology, null) { mergedShapes })
    }

    /**
     * Violations as produced by the SHACL engine (catalogue metadata applied per row).
     * Does **not** run [metricsProvider], reorder findings, or attach [QualityReport.metricsContext].
     */
    fun checkAsync(ontology: RdfGraph): Flow<QualityFinding> =
        validator.validateViolationsFlow(ontology, mergedShapes).map { v ->
            QualityFinding.from(v, metadataByShape)
        }

    private fun computeMetricsContext(graph: RdfGraph): MetricsContext? =
        metricsProvider?.let { provider ->
            try {
                provider.compute(graph)
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Exception) {
                qualityCheckerLogger.warn("Metrics provider failed; continuing without importance sort", e)
                null
            }
        }

    class Builder(private val validator: ShaclValidator) {
        private val catalogs = mutableListOf<ShapeCatalog>()
        private var metricsProvider: MetricsProvider? = null

        fun addCatalog(catalog: ShapeCatalog): Builder {
            catalogs.add(catalog)
            return this
        }

        fun addCatalogs(iter: Iterable<ShapeCatalog>): Builder {
            for (c in iter) {
                catalogs.add(c)
            }
            return this
        }

        /**
         * Active validation bundles from [BundledCatalogs.all] plus [BundledCatalogs.OOPS_PITFALL_REGISTRY]
         * (deactivated documentation shapes only — adds pitfall text / metadata for portals and **K07** tagging).
         */
        fun withAllBundledCatalogs(): Builder {
            catalogs.clear()
            catalogs.addAll(BundledCatalogs.allWithOopsRegistry)
            return this
        }

        /**
         * Provide a metrics provider. When supplied, the resulting [QualityReport] sorts findings by entity importance.
         * If the provider fails at runtime, validation continues without sorting.
         */
        fun withMetricsProvider(provider: MetricsProvider): Builder =
            apply {
                this.metricsProvider = provider
            }

        fun build(): QualityChecker {
            require(catalogs.isNotEmpty()) { "At least one ShapeCatalog is required" }
            return QualityChecker(validator, catalogs.toList(), metricsProvider)
        }
    }

    companion object {
        fun default(validator: ShaclValidator): QualityChecker =
            builder(validator).withAllBundledCatalogs().build()

        fun builder(validator: ShaclValidator): Builder = Builder(validator)

        internal fun mergeCatalogShapes(catalogs: List<ShapeCatalog>): RdfGraph {
            val merged = JenaBridge.createEmptyModel()
            for (c in catalogs) {
                merged.addTriples(c.loadShapesGraph().getTriples())
            }
            return merged
        }
    }
}

/**
 * Merges synthetic violations and reasoner warnings into [base].
 *
 * [ValidationReport.violations] and derived maps ([ValidationReport.shapeViolations], [ValidationReport.constraintViolations])
 * reflect the merged list. **violationsByType** inside [ValidationReport.statistics] is recomputed from merged violations
 * so constraint-type counts stay aligned.
 *
 * Other fields inside [ValidationReport.statistics], plus [ValidationReport.validationTime], [ValidationReport.validatedResources],
 * and [ValidationReport.validatedConstraints], remain from the SHACL engine baseline on [base].
 */
internal fun mergeValidationReport(
    base: ValidationReport,
    extraViolations: List<ValidationViolation>,
    consistencyWarningMessages: List<String>,
): ValidationReport {
    if (extraViolations.isEmpty() && consistencyWarningMessages.isEmpty()) return base
    val merged = base.violations + extraViolations
    fun blocking(v: ValidationViolation): Boolean =
        v.severity == ViolationSeverity.VIOLATION || v.severity == ViolationSeverity.ERROR
    val extraWarnings = consistencyWarningMessages.map { ValidationWarning(message = it) }
    val violationsByType = merged.groupingBy { it.constraint.constraintType }.eachCount()
    return base.copy(
        isValid = merged.none(::blocking),
        violations = merged,
        warnings = base.warnings + extraWarnings,
        shapeViolations = merged.groupBy { it.shapeUri ?: "unknown" },
        constraintViolations = merged.groupBy { it.constraint.constraintType.name },
        statistics = base.statistics.copy(violationsByType = violationsByType),
    )
}

/**
 * One report for two validation runs over disjoint sets of shapes (asserted-graph detectors, then entailed-graph
 * detectors): result rows and warnings are concatenated, counters added, and the report conforms when both do.
 */
internal fun combineValidationReports(first: ValidationReport, second: ValidationReport): ValidationReport {
    val violations = first.violations + second.violations
    fun <K> sum(a: Map<K, Int>, b: Map<K, Int>): Map<K, Int> = (a.keys + b.keys).associateWith { (a[it] ?: 0) + (b[it] ?: 0) }
    val a = first.statistics
    val b = second.statistics
    return first.copy(
        isValid = first.isValid && second.isValid,
        violations = violations,
        warnings = first.warnings + second.warnings,
        statistics =
            a.copy(
                totalResources = maxOf(a.totalResources, b.totalResources),
                validatedResources = maxOf(a.validatedResources, b.validatedResources),
                totalConstraints = a.totalConstraints + b.totalConstraints,
                validatedConstraints = a.validatedConstraints + b.validatedConstraints,
                shapesProcessed = a.shapesProcessed + b.shapesProcessed,
                constraintsByType = sum(a.constraintsByType, b.constraintsByType),
                violationsByType = sum(a.violationsByType, b.violationsByType),
                warningsByType = sum(a.warningsByType, b.warningsByType),
            ),
        validationTime = first.validationTime + second.validationTime,
        validatedResources = maxOf(first.validatedResources, second.validatedResources),
        validatedConstraints = first.validatedConstraints + second.validatedConstraints,
        shapeViolations = violations.groupBy { it.shapeUri ?: "unknown" },
        constraintViolations = violations.groupBy { it.constraint.constraintType.name },
        violationsTruncated = first.violationsTruncated || second.violationsTruncated,
    )
}
