package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.Iri

/**
 * Kastor-specific service-description terms.
 *
 * The W3C SPARQL Service Description vocabulary has no terms for these capability flags, and the
 * `http://www.w3.org/ns/sparql#` namespace is owned by the W3C, so Kastor publishes them in its own
 * namespace instead of inventing terms in someone else's.
 */
object KastorSparqlVocabulary {
    const val NAMESPACE: String = "https://kastor.geoknoesis.com/ns/sparql#"
    const val PREFIX: String = "ksparql"

    /** Namespace for Kastor's identifiers of built-in SPARQL functions (registry keys, not W3C IRIs). */
    const val FUNCTION_NAMESPACE: String = "https://kastor.geoknoesis.com/ns/sparql/function#"

    private fun term(local: String) = Iri(NAMESPACE + local)

    /** A service whose provider declares SPARQL 1.2 support. */
    val Sparql12Service: Iri = term("Sparql12Service")

    val supportedSparqlVersion: Iri = term("supportedSparqlVersion")
    val supportsRdfStar: Iri = term("supportsRdfStar")
    val supportsPropertyPaths: Iri = term("supportsPropertyPaths")
    val supportsAggregation: Iri = term("supportsAggregation")
    val supportsSubSelect: Iri = term("supportsSubSelect")
    val supportsVersionDeclaration: Iri = term("supportsVersionDeclaration")

    /** Kastor identifier of a SPARQL built-in function, e.g. `function("NOW")`. */
    fun function(name: String): Iri = Iri(FUNCTION_NAMESPACE + name)
}
