package com.geoknoesis.kastor.rdf

/**
 * Copies of a query with new text. A [Dataset] view rewrites the text of a query it runs in place (dataset clauses,
 * `GRAPH` patterns); the copy must keep everything else the query carries, above all the sandbox and the function
 * library of a [SandboxedSelectQuery], or untrusted text would reach the provider as a trusted query.
 */
internal object SparqlQueries {
    /** [query] itself when [sparql] is its text; otherwise a query of the same kind with [sparql] as its text. */
    fun retarget(query: SparqlSelect, sparql: String): SparqlSelect = when {
        sparql == query.sparql -> query
        query is SandboxedSelectQuery -> SandboxedSelectQuery(sparql, query.functions)
        else -> SparqlSelectQuery(sparql)
    }

    fun retarget(query: SparqlAsk, sparql: String): SparqlAsk =
        if (sparql == query.sparql) query else SparqlAskQuery(sparql)

    fun retarget(query: SparqlConstruct, sparql: String): SparqlConstruct =
        if (sparql == query.sparql) query else SparqlConstructQuery(sparql)

    fun retarget(query: SparqlDescribe, sparql: String): SparqlDescribe =
        if (sparql == query.sparql) query else SparqlDescribeQuery(sparql)
}
