package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Dataset
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException

internal object SparqlConstraintEvaluator {

    private val shaclThisMarker = Regex(Regex.escape("\$this") + "\\b")

    /**
     * SHACL SPARQL constraints use `sh:select`; non-empty result set indicates a violation.
 * When [focusNode] is an [Iri], occurrences of `$this` are rewritten to that IRI term
 * (SHACL-AF style pre-binding). Other focus kinds are left unbound.
     */
    fun selectReturnsRows(query: String, mergedDefaultGraph: RdfGraph, focusNode: RdfTerm? = null): Boolean {
        val bound = bindThis(query, focusNode)
        val ds = Dataset { defaultGraph(mergedDefaultGraph) }
        return try {
            ds.use { it.select(SparqlSelectQuery(bound)).iterator().hasNext() }
        } catch (e: Exception) {
            throw ShaclValidationException(
                "SPARQL SELECT constraint failed: ${e.message}. Add :rdf:jena or :rdf:rdf4j so Rdf.memory() and Dataset queries work.",
                e,
            )
        }
    }

    internal fun bindThis(query: String, focus: RdfTerm?): String {
        val iri = focus as? Iri ?: return query
        val term = "<${iri.value}>"
        return shaclThisMarker.replace(query, term)
    }
}
