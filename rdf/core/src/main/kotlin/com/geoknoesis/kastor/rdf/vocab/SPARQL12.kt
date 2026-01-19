package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * SPARQL 1.2 vocabulary.
 * Extensions and new features in SPARQL 1.2.
 */
object SPARQL12 : Vocabulary {
    override val namespace: String = "http://www.w3.org/ns/sparql#"
    override val prefix: String = "sparql"
    
    // SPARQL 1.2 specific classes
    val Sparql12Service: Iri by lazy { term("Sparql12Service") }
    val Sparql12Endpoint: Iri by lazy { term("Sparql12Endpoint") }
    
    // SPARQL 1.2 properties
    val supportsRdfStar: Iri by lazy { term("supportsRdfStar") }
    val supportsPropertyPaths: Iri by lazy { term("supportsPropertyPaths") }
    val supportsAggregation: Iri by lazy { term("supportsAggregation") }
    val supportsSubSelect: Iri by lazy { term("supportsSubSelect") }
    val supportsFederation: Iri by lazy { term("supportsFederation") }
    val supportsVersionDeclaration: Iri by lazy { term("supportsVersionDeclaration") }
    val supportedSparqlVersion: Iri by lazy { term("supportedSparqlVersion") }
    
    // RDF-star functions
    val TRIPLE: Iri by lazy { term("TRIPLE") }
    val isTRIPLE: Iri by lazy { term("isTRIPLE") }
    val SUBJECT: Iri by lazy { term("SUBJECT") }
    val PREDICATE: Iri by lazy { term("PREDICATE") }
    val OBJECT: Iri by lazy { term("OBJECT") }
    
    // String functions
    val replaceAll: Iri by lazy { term("replaceAll") }
    val encodeForUri: Iri by lazy { term("encodeForUri") }
    val decodeForUri: Iri by lazy { term("decodeForUri") }
    
    // Language and direction functions
    val LANGDIR: Iri by lazy { term("LANGDIR") }
    val hasLANG: Iri by lazy { term("hasLANG") }
    val hasLANGDIR: Iri by lazy { term("hasLANGDIR") }
    val STRLANGDIR: Iri by lazy { term("STRLANGDIR") }
    
    // Date/time functions
    val now: Iri by lazy { term("now") }
    val timezone: Iri by lazy { term("timezone") }
    val dateTime: Iri by lazy { term("dateTime") }
    val date: Iri by lazy { term("date") }
    val time: Iri by lazy { term("time") }
    val tz: Iri by lazy { term("tz") }
    
    // Random functions
    val rand: Iri by lazy { term("rand") }
    val random: Iri by lazy { term("random") }
}









