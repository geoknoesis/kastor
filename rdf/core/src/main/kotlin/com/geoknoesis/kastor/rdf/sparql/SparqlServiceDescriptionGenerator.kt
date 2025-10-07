package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.SPARQL_SD
import com.geoknoesis.kastor.rdf.vocab.SPARQL12
import com.geoknoesis.kastor.rdf.dsl.StandaloneGraph

/**
 * SPARQL Service Description generator.
 * Creates machine-readable service descriptions following W3C SPARQL Service Description specification.
 */
class SparqlServiceDescriptionGenerator(
    private val serviceUri: String,
    private val capabilities: ProviderCapabilities
) {
    
    /**
     * Generate a complete SPARQL Service Description graph.
     */
    fun generateServiceDescription(): RdfGraph {
        val triples = mutableListOf<RdfTriple>()
        
        // Service URI
        val service = iri(serviceUri)
        
        // Basic service information
        triples.add(RdfTriple(service, iri("${SPARQL_SD.namespace}Service"), iri("${SPARQL_SD.namespace}Service")))
        triples.add(RdfTriple(service, SPARQL12.Sparql12Service, iri("${SPARQL12.namespace}Sparql12Service")))
        triples.add(RdfTriple(service, SPARQL_SD.endpointProp, iri("$serviceUri/sparql")))
        triples.add(RdfTriple(service, SPARQL_SD.updateEndpointProp, iri("$serviceUri/update")))
        
        // SPARQL version support
        triples.add(RdfTriple(service, SPARQL12.supportedSparqlVersion, string(capabilities.sparqlVersion)))
        
        // Supported languages
        capabilities.supportedLanguages.forEach { lang ->
            triples.add(RdfTriple(service, SPARQL_SD.supportedLanguageProp, iri("${SPARQL_SD.namespace}$lang")))
        }
        
        // Result formats
        capabilities.supportedResultFormats.forEach { format ->
            triples.add(RdfTriple(service, SPARQL_SD.resultFormatProp, iri(format)))
        }
        
        // Input formats
        capabilities.supportedInputFormats.forEach { format ->
            triples.add(RdfTriple(service, SPARQL_SD.inputFormatProp, iri(format)))
        }
        
        // SPARQL 1.2 features
        if (capabilities.supportsRdfStar) {
            triples.add(RdfTriple(service, SPARQL12.supportsRdfStar, boolean(true)))
        }
        
        if (capabilities.supportsPropertyPaths) {
            triples.add(RdfTriple(service, SPARQL12.supportsPropertyPaths, boolean(true)))
        }
        
        if (capabilities.supportsAggregation) {
            triples.add(RdfTriple(service, SPARQL12.supportsAggregation, boolean(true)))
        }
        
        if (capabilities.supportsSubSelect) {
            triples.add(RdfTriple(service, SPARQL12.supportsSubSelect, boolean(true)))
        }
        
        if (capabilities.supportsFederation) {
            triples.add(RdfTriple(service, SPARQL12.supportsFederation, boolean(true)))
        }
        
        if (capabilities.supportsVersionDeclaration) {
            triples.add(RdfTriple(service, SPARQL12.supportsVersionDeclaration, boolean(true)))
        }
        
        // Extension functions
        capabilities.extensionFunctions.forEach { func ->
            val functionUri = iri(func.iri)
            triples.add(RdfTriple(service, SPARQL_SD.extensionFunction, functionUri))
            triples.add(RdfTriple(functionUri, SPARQL_SD.functionName, string(func.name)))
            triples.add(RdfTriple(functionUri, SPARQL_SD.description, string(func.description)))
            
            if (func.isAggregate) {
                triples.add(RdfTriple(functionUri, SPARQL_SD.isAggregate, boolean(true)))
            }
            
            if (func.returnType != null) {
                triples.add(RdfTriple(functionUri, SPARQL_SD.returnType, iri(func.returnType)))
            }
        }
        
        // Dataset information
        val dataset = bnode("dataset")
        triples.add(RdfTriple(service, SPARQL_SD.defaultDatasetProp, dataset))
        triples.add(RdfTriple(dataset, iri("${SPARQL_SD.namespace}Dataset"), iri("${SPARQL_SD.namespace}Dataset")))
        
        // Default graphs
        capabilities.defaultGraphs.forEach { graphUri ->
            val graphResource = iri(graphUri)
            triples.add(RdfTriple(dataset, SPARQL_SD.defaultGraphProp, graphResource))
            triples.add(RdfTriple(graphResource, iri("${SPARQL_SD.namespace}DefaultGraph"), iri("${SPARQL_SD.namespace}DefaultGraph")))
        }
        
        // Named graphs
        capabilities.namedGraphs.forEach { graphUri ->
            val graphResource = iri(graphUri)
            triples.add(RdfTriple(dataset, SPARQL_SD.namedGraphProp, graphResource))
            triples.add(RdfTriple(graphResource, iri("${SPARQL_SD.namespace}NamedGraph"), iri("${SPARQL_SD.namespace}NamedGraph")))
        }
        
        return StandaloneGraph(triples)
    }
    
    /**
     * Generate service description as SPARQL query result.
     */
    fun generateAsSparqlResult(): String {
        val graph = generateServiceDescription()
        val triples = graph.getTriples()
        
        return buildString {
            appendLine("SELECT ?subject ?predicate ?object WHERE {")
            appendLine("    ?subject ?predicate ?object .")
            appendLine("} ORDER BY ?subject ?predicate")
        }
    }
    
    /**
     * Generate service description as Turtle format.
     */
    fun generateAsTurtle(): String {
        val graph = generateServiceDescription()
        val triples = graph.getTriples()
        
        return buildString {
            appendLine("@prefix sd: <${SPARQL_SD.namespace}> .")
            appendLine("@prefix sparql: <${SPARQL12.namespace}> .")
            appendLine()
            triples.forEach { triple ->
                appendLine("${triple.subject} ${triple.predicate} ${triple.obj} .")
            }
        }
    }
    
    /**
     * Generate service description as JSON-LD format.
     */
    fun generateAsJsonLd(): String {
        val graph = generateServiceDescription()
        val triples = graph.getTriples()
        
        return buildString {
            appendLine("{")
            appendLine("  \"@context\": {")
            appendLine("    \"sd\": \"${SPARQL_SD.namespace}\",")
            appendLine("    \"sparql\": \"${SPARQL12.namespace}\"")
            appendLine("  },")
            appendLine("  \"@id\": \"$serviceUri\",")
            appendLine("  \"@type\": \"sd:Service\"")
            appendLine("}")
        }
    }
}
