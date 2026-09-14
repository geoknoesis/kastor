package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SPARQL_SD
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import java.security.MessageDigest

/**
 * SPARQL Service Description generator.
 * Creates machine-readable service descriptions following W3C SPARQL Service Description specification.
 *
 * Only terms that exist in the W3C `sd:` vocabulary are emitted in that namespace. Kastor capability
 * flags that the standard cannot express (RDF-star, property paths, ...) use
 * [KastorSparqlVocabulary]. SPARQL built-in functions are part of the query language and are not
 * advertised; `sd:extensionFunction` lists only real extension functions
 * ([SparqlExtensionFunction.isBuiltIn] `== false`). Federation is advertised with the standard
 * `sd:feature sd:BasicFederatedQuery`.
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
        triples.add(RdfTriple(service, RDF.type, SPARQL_SD.Service))
        if (capabilities.sparqlVersion.startsWith("1.2")) {
            triples.add(RdfTriple(service, RDF.type, KastorSparqlVocabulary.Sparql12Service))
        }
        triples.add(RdfTriple(service, SPARQL_SD.endpointProp, Iri("$serviceUri/sparql")))
        triples.add(RdfTriple(service, SPARQL_SD.updateEndpointProp, Iri("$serviceUri/update")))

        // SPARQL version support
        triples.add(RdfTriple(service, KastorSparqlVocabulary.supportedSparqlVersion, string(capabilities.sparqlVersion)))

        // Supported languages
        capabilities.supportedLanguages.forEach { lang ->
            triples.add(RdfTriple(service, SPARQL_SD.supportedLanguageProp, languageIri(lang)))
        }

        // Result formats
        capabilities.supportedResultFormats.forEach { format ->
            triples.add(RdfTriple(service, SPARQL_SD.resultFormatProp, iriOrLiteral(format)))
        }

        // Input formats
        capabilities.supportedInputFormats.forEach { format ->
            triples.add(RdfTriple(service, SPARQL_SD.inputFormatProp, iriOrLiteral(format)))
        }

        // Capability flags (Kastor vocabulary: sd: has no terms for them)
        if (capabilities.supportsRdfStar) {
            triples.add(RdfTriple(service, KastorSparqlVocabulary.supportsRdfStar, boolean(true)))
        }

        if (capabilities.supportsPropertyPaths) {
            triples.add(RdfTriple(service, KastorSparqlVocabulary.supportsPropertyPaths, boolean(true)))
        }

        if (capabilities.supportsAggregation) {
            triples.add(RdfTriple(service, KastorSparqlVocabulary.supportsAggregation, boolean(true)))
        }

        if (capabilities.supportsSubSelect) {
            triples.add(RdfTriple(service, KastorSparqlVocabulary.supportsSubSelect, boolean(true)))
        }

        if (capabilities.supportsFederation) {
            triples.add(RdfTriple(service, SD_FEATURE, SD_BASIC_FEDERATED_QUERY))
        }

        if (capabilities.supportsVersionDeclaration) {
            triples.add(RdfTriple(service, KastorSparqlVocabulary.supportsVersionDeclaration, boolean(true)))
        }

        // Extension functions (built-ins are part of the language and are not advertised)
        capabilities.extensionFunctions.filterNot { it.isBuiltIn }.forEach { func ->
            val functionUri = Iri(func.iri)
            triples.add(RdfTriple(service, SPARQL_SD.extensionFunction, functionUri))
            triples.add(RdfTriple(functionUri, SPARQL_SD.functionName, string(func.name)))
            triples.add(RdfTriple(functionUri, SPARQL_SD.description, string(func.description)))

            if (func.isAggregate) {
                triples.add(RdfTriple(functionUri, SPARQL_SD.isAggregate, boolean(true)))
            }

            val returnType = func.returnType
            if (returnType != null) {
                triples.add(RdfTriple(functionUri, SPARQL_SD.returnType, iriOrLiteral(returnType)))
            }
        }

        // Dataset information
        val dataset = bnode("dataset")
        triples.add(RdfTriple(service, SPARQL_SD.defaultDatasetProp, dataset))
        triples.add(RdfTriple(dataset, RDF.type, SPARQL_SD.Dataset))

        // Default graphs
        capabilities.defaultGraphs.forEach { graphUri ->
            val graphResource = Iri(graphUri)
            triples.add(RdfTriple(dataset, SPARQL_SD.defaultGraphProp, graphResource))
            triples.add(RdfTriple(graphResource, RDF.type, SPARQL_SD.DefaultGraph))
        }

        // Named graphs
        capabilities.namedGraphs.forEach { graphUri ->
            val graphResource = Iri(graphUri)
            triples.add(RdfTriple(dataset, SPARQL_SD.namedGraphProp, graphResource))
            triples.add(RdfTriple(graphResource, RDF.type, SPARQL_SD.NamedGraph))
        }

        return MemoryGraph(triples)
    }

    /**
     * `sd:SPARQL10Query`, `sd:SPARQL11Query` and `sd:SPARQL11Update` are the only standard language
     * instances; absolute IRIs are used as given and anything else goes to the Kastor namespace.
     */
    private fun languageIri(lang: String): Iri = when {
        lang in STANDARD_LANGUAGES -> Iri("${SPARQL_SD.namespace}$lang")
        ABSOLUTE_IRI.containsMatchIn(lang) -> Iri(lang)
        else -> Iri("${KastorSparqlVocabulary.NAMESPACE}language-$lang")
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

    /** Render a term in syntax shared by SPARQL and Turtle (full IRIs, escaped literals). */
    private fun toSparqlTerm(term: RdfTerm): String {
        return when (term) {
            is Iri -> SparqlSyntax.iriRef(term.value)
            is BlankNode -> SparqlSyntax.blankNode(term.id)
            is Literal -> SparqlSyntax.literal(term)
            is TripleTerm -> "<<( ${toSparqlTerm(term.triple.subject)} ${toSparqlTerm(term.triple.predicate)} ${toSparqlTerm(term.triple.obj)} )>>"
            is Var -> throw IllegalArgumentException("A service description cannot contain variables")
        }
    }

    private fun jsonEscape(value: String): String = buildString(value.length) {
        for (c in value) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }

    private fun resourceId(resource: RdfResource): String {
        return when (resource) {
            is Iri -> resource.value
            is BlankNode -> if (resource.id.startsWith("_:")) resource.id else "_:${resource.id}"
        }
    }

    private fun toJsonLdObject(term: RdfTerm): String {
        return when (term) {
            is Iri -> """{"@id":"${jsonEscape(term.value)}"}"""
            is BlankNode -> {
                val id = if (term.id.startsWith("_:")) term.id else "_:${term.id}"
                """{"@id":"${jsonEscape(id)}"}"""
            }
            is LangString -> {
                val direction = term.direction?.let { ""","@direction":"${jsonEscape(it.token)}"""" }.orEmpty()
                """{"@value":"${jsonEscape(term.lexical)}","@language":"${jsonEscape(term.lang)}"$direction}"""
            }
            is Literal -> {
                val typeValue = jsonEscape(term.datatype.value)
                """{"@value":"${jsonEscape(term.lexical)}","@type":"$typeValue"}"""
            }
            else -> throw IllegalArgumentException("Unsupported term in a service description: ${term::class.simpleName}")
        }
    }

    /**
     * SPARQL forbids blank nodes in `VALUES`, so they are replaced by skolem IRIs. For hierarchical
     * service URIs these are RDF 1.1 well-known IRIs (`<scheme://authority/.well-known/genid/label>`);
     * opaque or authority-less URIs such as `urn:` get `urn:kastor:genid:<service hash>:<label>`.
     */
    private fun skolemize(term: RdfTerm): RdfTerm {
        if (term !is BlankNode) return term
        val label = term.id.removePrefix("_:")
        val uri = runCatching { java.net.URI(serviceUri) }.getOrNull()
        val authority = uri?.rawAuthority
        return if (uri != null && !uri.isOpaque && !authority.isNullOrEmpty()) {
            Iri("${uri.scheme}://$authority/.well-known/genid/$label")
        } else {
            Iri("urn:kastor:genid:${serviceHash()}:$label")
        }
    }

    private fun serviceHash(): String =
        MessageDigest.getInstance("SHA-256").digest(serviceUri.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }

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
                        "        (${toSparqlTerm(skolemize(triple.subject))} " +
                            "${toSparqlTerm(triple.predicate)} " +
                            "${toSparqlTerm(skolemize(triple.obj))})"
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
            appendLine("@prefix sd: ${SparqlSyntax.iriRef(SPARQL_SD.namespace)} .")
            appendLine("@prefix ${KastorSparqlVocabulary.PREFIX}: ${SparqlSyntax.iriRef(KastorSparqlVocabulary.NAMESPACE)} .")
            appendLine()
            triples.forEach { triple ->
                appendLine("${toSparqlTerm(triple.subject)} ${toSparqlTerm(triple.predicate)} ${toSparqlTerm(triple.obj)} .")
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
            appendLine("    \"${KastorSparqlVocabulary.PREFIX}\": \"${KastorSparqlVocabulary.NAMESPACE}\"")
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

    private companion object {
        val SD_FEATURE = Iri("${SPARQL_SD.namespace}feature")
        val SD_BASIC_FEDERATED_QUERY = Iri("${SPARQL_SD.namespace}BasicFederatedQuery")
        val STANDARD_LANGUAGES = setOf("SPARQL10Query", "SPARQL11Query", "SPARQL11Update")
        val ABSOLUTE_IRI = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    }
}
