package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.BindingSet
import com.geoknoesis.kastor.rdf.SparqlQueryResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [Flow] over SELECT result rows, with optional validation that each solution binds the given variables.
 *
 * Cancellation is cooperative: collectors that cancel stop iteration over the backing [SparqlQueryResult.asSequence].
 *
 * @param eachRowMustBind SPARQL variable names **without** `?` (e.g. `"s"`, `"p"`, `"o"` for `?s ?p ?o`).
 */
fun SparqlQueryResult.asFlow(vararg eachRowMustBind: String): Flow<BindingSet> {
    val required = eachRowMustBind.toSet()
    return flow {
        this@asFlow.asSequence().forEach { row ->
            if (required.isNotEmpty()) {
                val missing = required.filter { !row.hasBinding(it) }
                if (missing.isNotEmpty()) {
                    throw IllegalStateException(
                        "SPARQL result row missing variable(s): $missing (row has: ${row.getVariableNames()})",
                    )
                }
            }
            emit(row)
        }
    }
}
