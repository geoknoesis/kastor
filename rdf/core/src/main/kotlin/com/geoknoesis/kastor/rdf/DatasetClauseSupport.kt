package com.geoknoesis.kastor.rdf

/**
 * Optional provider restriction on the named graphs that a [Dataset] can select with `FROM` / `FROM NAMED`.
 *
 * Source tracking identifies a graph even when its name has a special meaning to the provider's query engine.
 * Return false for such names so datasets copy their graph views instead of querying a different source graph.
 * A materialized dataset also requires support for its named graph names; otherwise another installed engine
 * is selected, or the query fails before copying or execution when none can preserve those identities.
 * Providers without this interface are assumed to support ordinary dataset clauses for every named graph.
 */
interface DatasetClauseSupport {
    fun supportsDatasetGraph(name: Iri): Boolean
}
