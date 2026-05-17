package com.geoknoesis.kastor.ontoquality.integration

import com.geoknoesis.kastor.rdf.RdfGraph

/**
 * Abstraction over a metrics computation that produces an entity-level
 * importance score, used by [com.geoknoesis.kastor.ontoquality.QualityChecker] to prioritize SHACL findings.
 *
 * This interface lives in `:tools:onto-quality` so that the module has no
 * build-time dependency on `:tools:onto-quality-metrics`. Concrete
 * implementations (notably `KastorMetricsProvider`) live in the metrics
 * module.
 *
 * If no provider is supplied to [com.geoknoesis.kastor.ontoquality.QualityChecker], findings are returned
 * in the order produced by the underlying SHACL engine.
 */
interface MetricsProvider {

    /**
     * Compute an importance context for the given ontology.
     *
     * @param ontology the graph to analyze; should be the same graph
     *                 being validated.
     * @return a [MetricsContext] containing both the raw metrics summary
     *         and a per-entity importance score map. May throw if the
     *         underlying computation fails; callers may catch and fall
     *         back to unsorted findings.
     */
    fun compute(ontology: RdfGraph): MetricsContext
}

/**
 * The result of a metrics-provider computation. Designed to be opaque
 * to the SHACL module — [entityImportance] (and optionally [entityHints]) are consumed for sorting and Markdown.
 *
 * @param summary a human-readable summary of the metrics (e.g., key
 *                values to mention in reports). Treated as a string blob
 *                by the SHACL module; the metrics module formats it.
 * @param entityImportance for each entity IRI present in the ontology,
 *                a score in [0.0, 1.0] where higher means more central.
 *                Entities not in the map are treated as having default
 *                importance (see [MetricsContext.DEFAULT_IMPORTANCE]).
 * @param entityHints optional one-line context per entity IRI for Markdown (subclass counts, incoming references, etc.).
 */
data class MetricsContext(
    val summary: String,
    val entityImportance: Map<String, Double>,
    val entityHints: Map<String, String> = emptyMap(),
) {
    companion object {
        /**
         * Default importance for entities not in the importance map.
         * Set to 0.5 (midpoint) so that absent entities are neither
         * promoted nor demoted unfairly. Findings on absent entities
         * remain at their natural position in the secondary sort
         * (severity, then alphabetical IRI).
         */
        const val DEFAULT_IMPORTANCE: Double = 0.5
    }
}
