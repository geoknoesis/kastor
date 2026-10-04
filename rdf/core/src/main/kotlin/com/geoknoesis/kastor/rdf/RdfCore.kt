package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.EmptySparqlQueryResult

// These public top-level functions retain their RdfCoreKt JVM owner for compiled callers.

/**
 * Factory function to create a SPARQL query result from a list of binding sets.
 * 
 * **Example:**
 * ```kotlin
 * val bindings = listOf(
 *     MapBindingSet(mapOf("name" to Literal("Alice"))),
 *     MapBindingSet(mapOf("name" to Literal("Bob")))
 * )
 * val result = sparqlQueryResult(bindings)
 * ```
 * 
 * @param rows List of binding sets representing query result rows
 * @return A SparqlQueryResult containing the provided rows
 */
fun sparqlQueryResult(rows: List<BindingSet>): SparqlQueryResult = ListSparqlQueryResult(rows)

/**
 * Factory function to create an empty SPARQL query result.
 * 
 * **Example:**
 * ```kotlin
 * val emptyResult = emptySparqlQueryResult()
 * assertTrue(emptyResult.count() == 0)
 * ```
 * 
 * @return An empty SparqlQueryResult (singleton instance)
 */
fun emptySparqlQueryResult(): SparqlQueryResult = EmptySparqlQueryResult

/** True if these capabilities declare a SPARQL query engine (see [Rdf.memory]). */
internal fun ProviderCapabilities.declaresSparqlSupport(): Boolean =
    sparqlFeatures.isNotEmpty() || supportsPropertyPaths || supportsAggregation || supportsSubSelect ||
        supportedLanguages.any { it.contains("SPARQL", ignoreCase = true) }

/**
 * Map legacy booleans to a typed feature set.
 */
fun ProviderCapabilities.featureSet(): Set<SparqlFeature> = buildSet {
    if (supportsRdfStar) add(SparqlFeature.RDF_STAR)
    if (supportsPropertyPaths) add(SparqlFeature.PROPERTY_PATHS)
    if (supportsAggregation) add(SparqlFeature.AGGREGATION)
    if (supportsSubSelect) add(SparqlFeature.SUBSELECT)
    if (supportsInference) add(SparqlFeature.INFERENCE)
    if (supportsFederation) add(SparqlFeature.FEDERATION)
    if (supportsServiceDescription) add(SparqlFeature.SERVICE_DESCRIPTION)
    if (supportsVersionDeclaration) add(SparqlFeature.VERSION_DECLARATION)
}
