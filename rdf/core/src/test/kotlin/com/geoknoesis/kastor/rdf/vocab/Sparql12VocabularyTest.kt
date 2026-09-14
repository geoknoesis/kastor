package com.geoknoesis.kastor.rdf.vocab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Non-standard SPARQL12 terms are deprecated, not removed or renamed: every term must keep resolving to the same
 * IRI so existing data and callers are unaffected.
 */
class Sparql12VocabularyTest {

    @Test
    @Suppress("DEPRECATION")
    fun `all terms keep their IRIs in the sparql namespace`() {
        val ns = "http://www.w3.org/ns/sparql#"
        assertEquals(ns, SPARQL12.namespace)
        assertEquals("sparql", SPARQL12.prefix)
        val terms = mapOf(
            "Sparql12Service" to SPARQL12.Sparql12Service, "Sparql12Endpoint" to SPARQL12.Sparql12Endpoint,
            "supportsRdfStar" to SPARQL12.supportsRdfStar, "supportsPropertyPaths" to SPARQL12.supportsPropertyPaths,
            "supportsAggregation" to SPARQL12.supportsAggregation, "supportsSubSelect" to SPARQL12.supportsSubSelect,
            "supportsFederation" to SPARQL12.supportsFederation,
            "supportsVersionDeclaration" to SPARQL12.supportsVersionDeclaration,
            "supportedSparqlVersion" to SPARQL12.supportedSparqlVersion,
            "TRIPLE" to SPARQL12.TRIPLE, "isTRIPLE" to SPARQL12.isTRIPLE, "SUBJECT" to SPARQL12.SUBJECT,
            "PREDICATE" to SPARQL12.PREDICATE, "OBJECT" to SPARQL12.OBJECT,
            "replaceAll" to SPARQL12.replaceAll, "encodeForUri" to SPARQL12.encodeForUri, "decodeForUri" to SPARQL12.decodeForUri,
            "LANGDIR" to SPARQL12.LANGDIR, "hasLANG" to SPARQL12.hasLANG, "hasLANGDIR" to SPARQL12.hasLANGDIR,
            "STRLANGDIR" to SPARQL12.STRLANGDIR,
            "now" to SPARQL12.now, "timezone" to SPARQL12.timezone, "dateTime" to SPARQL12.dateTime,
            "date" to SPARQL12.date, "time" to SPARQL12.time, "tz" to SPARQL12.tz,
            "rand" to SPARQL12.rand, "random" to SPARQL12.random,
        )
        terms.forEach { (local, iri) -> assertEquals(ns + local, iri.value, local) }
    }
}
