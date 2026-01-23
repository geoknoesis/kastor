package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.SPARQL_SD
import com.geoknoesis.kastor.rdf.vocab.SPARQL12
import com.geoknoesis.kastor.rdf.provider.MemoryGraph

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
        val service = Iri(serviceUri)
        
        // Basic service information
        triples.add(RdfTriple(service, Iri("${SPARQL_SD.namespace}Service"), Iri("${SPARQL_SD.namespace}Service")))
        triples.add(RdfTriple(service, SPARQL12.Sparql12Service, Iri("${SPARQL12.namespace}Sparql12Service")))
        triples.add(RdfTriple(service, SPARQL_SD.endpointProp, Iri("$serviceUri/sparql")))
        triples.add(RdfTriple(service, SPARQL_SD.updateEndpointProp, Iri("$serviceUri/update")))
        
        // SPARQL version support
        triples.add(RdfTriple(service, SPARQL12.supportedSparqlVersion, string(capabilities.sparqlVersion)))
        
        // Supported languages
        capabilities.supportedLanguages.forEach { lang ->
            triples.add(RdfTriple(service, SPARQL_SD.supportedLanguageProp, Iri("${SPARQL_SD.namespace}$lang")))
        }
        
        // Result formats
        capabilities.supportedResultFormats.forEach { format ->
            triples.add(RdfTriple(service, SPARQL_SD.resultFormatProp, iriOrLiteral(format)))
        }
        
        // Input formats
        capabilities.supportedInputFormats.forEach { format ->
            triples.add(RdfTriple(service, SPARQL_SD.inputFormatProp, iriOrLiteral(format)))
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
            val functionUri = Iri(func.iri)
            triples.add(RdfTriple(service, SPARQL_SD.extensionFunction, functionUri))
            triples.add(RdfTriple(functionUri, SPARQL_SD.functionName, string(func.name)))
            triples.add(RdfTriple(functionUri, SPARQL_SD.description, string(func.description)))
            
            if (func.isAggregate) {
                triples.add(RdfTriple(functionUri, SPARQL_SD.isAggregate, boolean(true)))
            }
            
            if (func.returnType != null) {
                triples.add(RdfTriple(functionUri, SPARQL_SD.returnType, iriOrLiteral(func.returnType)))
            }
        }
        
        // Dataset information
        val dataset = bnode("dataset")
        triples.add(RdfTriple(service, SPARQL_SD.defaultDatasetProp, dataset))
        triples.add(RdfTriple(dataset, Iri("${SPARQL_SD.namespace}Dataset"), Iri("${SPARQL_SD.namespace}Dataset")))
        
        // Default graphs
        capabilities.defaultGraphs.forEach { graphUri ->
            val graphResource = Iri(graphUri)
            triples.add(RdfTriple(dataset, SPARQL_SD.defaultGraphProp, graphResource))
            triples.add(RdfTriple(graphResource, Iri("${SPARQL_SD.namespace}DefaultGraph"), Iri("${SPARQL_SD.namespace}DefaultGraph")))
        }
        
        // Named graphs
        capabilities.namedGraphs.forEach { graphUri ->
            val graphResource = Iri(graphUri)
            triples.add(RdfTriple(dataset, SPARQL_SD.namedGraphProp, graphResource))
            triples.add(RdfTriple(graphResource, Iri("${SPARQL_SD.namespace}NamedGraph"), Iri("${SPARQL_SD.namespace}NamedGraph")))
        }
        
        return MemoryGraph(triples)
    }

    private fun iriOrLiteral(value: String): RdfTerm {
        val trimmed = value.trim()
        val mediaType = trimmed.substringBefore(";").trim().lowercase()
        return try {
            Iri(trimmed)
        } catch (_: IllegalArgumentException) {
            if (isMediaType(mediaType)) {
                Iri("https://www.iana.org/assignments/media-types/$mediaType")
            } else {
                Literal(trimmed, com.geoknoesis.kastor.rdf.vocab.XSD.string)
            }
        }
    }

    private fun isMediaType(value: String): Boolean {
        return Regex("^[A-Za-z0-9!#\\$&\\-\\^_.+]+/[A-Za-z0-9!#\\$&\\-\\^_.+]+$").matches(value)
    }

    private fun toSparqlTerm(term: RdfTerm): String {
        return when (term) {
            is Iri -> "<${term.value}>"
            is BlankNode -> if (term.id.startsWith("_:")) term.id else "_:${term.id}"
            is Literal -> {
                val escaped = escapeLiteral(term.lexical)
                when (term) {
                    is LangString -> "\"$escaped\"@${term.lang}"
                    is TypedLiteral -> "\"$escaped\"^^<${term.datatype.value}>"
                    else -> "\"$escaped\""
                }
            }
            else -> term.toString()
        }
    }

    private fun escapeLiteral(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun jsonEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun resourceId(resource: RdfResource): String {
        return when (resource) {
            is Iri -> resource.value
            is BlankNode -> if (resource.id.startsWith("_:")) resource.id else "_:${resource.id}"
            else -> resource.toString()
        }
    }

    private fun toJsonLdObject(term: RdfTerm): String {
        return when (term) {
            is Iri -> """{"@id":"${jsonEscape(term.value)}"}"""
            is BlankNode -> {
                val id = if (term.id.startsWith("_:")) term.id else "_:${term.id}"
                """{"@id":"${jsonEscape(id)}"}"""
            }
            is LangString -> """{"@value":"${jsonEscape(term.lexical)}","@language":"${jsonEscape(term.lang)}"}"""
            is TypedLiteral -> {
                val typeValue = jsonEscape(term.datatype.value)
                """{"@value":"${jsonEscape(term.lexical)}","@type":"$typeValue"}"""
            }
            is Literal -> """{"@value":"${jsonEscape(term.lexical)}"}"""
            else -> """{"@value":"${jsonEscape(term.toString())}"}"""
        }
    }
    
    /**
     * Generate service description as SPARQL query result.
     */
    fun generateAsSparqlResult(): String {
        val graph = generateServiceDescription()
        val triples = graph.getTriples()

        return buildString {
            appendLine("SELECT ?subject ?predicate ?object WHERE {")
            if (triples.isNotEmpty()) {
                appendLine("    VALUES (?subject ?predicate ?object) {")
                triples.forEach { triple ->
                    appendLine(
                        "        (${toSparqlTerm(triple.subject)} " +
                            "${toSparqlTerm(triple.predicate)} " +
                            "${toSparqlTerm(triple.obj)})"
                    )
                }
                appendLine("    }")
            } else {
                appendLine("    ?subject ?predicate ?object .")
            }
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
        val subjectGroups = triples.groupBy { it.subject }

        return buildString {
            appendLine("{")
            appendLine("  \"@context\": {")
            appendLine("    \"sd\": \"${SPARQL_SD.namespace}\",")
            appendLine("    \"sparql\": \"${SPARQL12.namespace}\"")
            appendLine("  },")
            appendLine("  \"@graph\": [")
            subjectGroups.entries.forEachIndexed { subjectIndex, entry ->
                val subject = entry.key
                val predicateGroups = entry.value.groupBy { it.predicate }
                appendLine("    {")
                appendLine("      \"@id\": \"${jsonEscape(resourceId(subject))}\",")
                predicateGroups.entries.forEachIndexed { predicateIndex, predicateEntry ->
                    val predicate = predicateEntry.key
                    val objects = predicateEntry.value.map { triple -> triple.obj }
                    append("      \"${jsonEscape(predicate.value)}\": [")
                    append(objects.joinToString(",") { toJsonLdObject(it) })
                    append("]")
                    val isLastPredicate = predicateIndex == predicateGroups.size - 1
                    appendLine(if (isLastPredicate) "" else ",")
                }
                append("    }")
                val isLastSubject = subjectIndex == subjectGroups.size - 1
                appendLine(if (isLastSubject) "" else ",")
            }
            appendLine("  ]")
            appendLine("}")
        }
    }
}









