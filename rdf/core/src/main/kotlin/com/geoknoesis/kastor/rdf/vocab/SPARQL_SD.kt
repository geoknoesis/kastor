package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * SPARQL Service Description vocabulary.
 * Based on W3C SPARQL Service Description specification.
 * 
 * @see <a href="https://www.w3.org/TR/sparql11-service-description/">SPARQL Service Description</a>
 */
object SPARQL_SD : Vocabulary {
    override val namespace: String = "http://www.w3.org/ns/sparql-service-description#"
    override val prefix: String = "sd"
    
    // Core classes
    val Service: Iri by lazy { term("Service") }
    val Dataset: Iri by lazy { term("Dataset") }
    val Graph: Iri by lazy { term("Graph") }
    val NamedGraph: Iri by lazy { term("NamedGraph") }
    val DefaultGraph: Iri by lazy { term("DefaultGraph") }
    val Endpoint: Iri by lazy { term("Endpoint") }
    
    // Properties
    val endpointProp: Iri by lazy { term("endpoint") }
    val updateEndpointProp: Iri by lazy { term("updateEndpoint") }
    val graphEndpointProp: Iri by lazy { term("graphEndpoint") }
    val supportedLanguageProp: Iri by lazy { term("supportedLanguage") }
    val resultFormatProp: Iri by lazy { term("resultFormat") }
    val inputFormatProp: Iri by lazy { term("inputFormat") }
    val defaultDatasetProp: Iri by lazy { term("defaultDataset") }
    val namedGraphProp: Iri by lazy { term("namedGraph") }
    val defaultGraphProp: Iri by lazy { term("defaultGraph") }
    val defaultEntailmentRegime: Iri by lazy { term("defaultEntailmentRegime") }
    val entailmentRegime: Iri by lazy { term("entailmentRegime") }
    val supportedEntailmentProfile: Iri by lazy { term("supportedEntailmentProfile") }
    val extensionFunction: Iri by lazy { term("extensionFunction") }
    val functionName: Iri by lazy { term("functionName") }
    val description: Iri by lazy { term("description") }
    val isAggregate: Iri by lazy { term("isAggregate") }
    val returnType: Iri by lazy { term("returnType") }
}









