package com.geoknoesis.kastor.gen.processor.parsers

import com.geoknoesis.kastor.gen.processor.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.model.JsonLdContainer
import com.geoknoesis.kastor.gen.processor.model.JsonLdProperty
import com.geoknoesis.kastor.gen.processor.model.JsonLdType
import com.geoknoesis.kastor.rdf.Iri as RdfIri
import com.google.devtools.ksp.processing.KSPLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream

/**
 * Parser for JSON-LD context files.
 * Extracts type mappings and property definitions for code generation.
 */
class JsonLdContextParser(private val logger: KSPLogger) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses a JSON-LD context file.
     * 
     * @param inputStream The JSON-LD context file input stream
     * @return Parsed JSON-LD context
     */
    fun parseContext(inputStream: InputStream): JsonLdContext {
        val content = inputStream.bufferedReader().use { it.readText() }
        return parseContextContent(content)
    }

    /**
     * Parses JSON-LD context content from a string.
     * 
     * @param content The JSON-LD context content as string
     * @return Parsed JSON-LD context
     */
    fun parseContextContent(content: String): JsonLdContext {
        val jsonObject = json.parseToJsonElement(content).jsonObject
        val contexts = extractContexts(jsonObject["@context"])
        if (contexts.isEmpty()) throw IllegalArgumentException("No @context found")

        val prefixes = mutableMapOf<String, String>()
        val typeMappings = mutableMapOf<String, RdfIri>()
        val propertyMappings = mutableMapOf<String, JsonLdProperty>()
        var baseIri: RdfIri? = null
        var vocabIri: RdfIri? = null

        contexts.forEach { context ->
            extractPrefixes(context, prefixes)
            val localBase = context["@base"]?.jsonPrimitive?.content
            val localVocab = context["@vocab"]?.jsonPrimitive?.content
            if (localBase != null) baseIri = RdfIri(localBase)
            if (localVocab != null) vocabIri = RdfIri(localVocab)

            context.entries.forEach { (key, value) ->
                if (key.startsWith("@")) return@forEach
                when {
                    value is JsonPrimitive && value.isString && !key.contains(":") -> {
                        val term = value.content
                        if (isPrefixDefinition(term)) return@forEach

                        val expanded = expandTerm(term, prefixes, baseIri, vocabIri)
                        typeMappings[key] = RdfIri(expanded)
                        logger.info("Extracted type mapping: $key -> $expanded")
                    }

                    value is JsonObject -> {
                        val id = value["@id"]?.jsonPrimitive?.content
                        val type = value["@type"]?.jsonPrimitive?.content
                        val container = value["@container"]?.jsonPrimitive?.content

                        if (id != null) {
                            val expandedId = expandTerm(id, prefixes, baseIri, vocabIri)
                            val resolvedType = when (type) {
                                null -> null
                                "@id" -> JsonLdType.Id
                                else -> JsonLdType.Iri(RdfIri(expandTerm(type, prefixes, baseIri, vocabIri)))
                            }
                            val resolvedContainer = container?.let { resolveContainer(it) }

                            propertyMappings[key] = JsonLdProperty(
                                id = RdfIri(expandedId),
                                type = resolvedType,
                                container = resolvedContainer
                            )
                            logger.info("Extracted property: $key -> $expandedId (type: $resolvedType, container: $resolvedContainer)")
                        }
                    }
                }
            }
        }

        return JsonLdContext(
            prefixes = prefixes,
            baseIri = baseIri,
            vocabIri = vocabIri,
            typeMappings = typeMappings,
            propertyMappings = propertyMappings
        )
    }

    private fun extractContexts(contextElement: JsonElement?): List<JsonObject> {
        return when (contextElement) {
            null -> emptyList()
            is JsonObject -> listOf(contextElement)
            is JsonArray -> contextElement.mapNotNull { it as? JsonObject }
            is JsonPrimitive -> throw IllegalArgumentException("External @context references are not supported: ${contextElement.content}")
            else -> emptyList()
        }
    }

    private fun extractPrefixes(context: JsonObject, prefixes: MutableMap<String, String>) {
        context.entries.forEach { (key, value) ->
            if (key.startsWith("@")) return@forEach
            if (value is JsonPrimitive && value.isString && !key.contains(":")) {
                val uri = value.content
                if (isPrefixDefinition(uri)) {
                    prefixes[key] = uri
                    logger.info("Extracted prefix: $key -> $uri")
                }
            }
        }
    }

    private fun isPrefixDefinition(uri: String): Boolean {
        return uri.endsWith("#") || uri.endsWith("/")
    }

    private fun expandTerm(
        term: String,
        prefixes: Map<String, String>,
        baseIri: RdfIri?,
        vocabIri: RdfIri?
    ): String {
        val colonIndex = term.indexOf(':')
        if (colonIndex > 0) {
            val prefix = term.substring(0, colonIndex)
            val localName = term.substring(colonIndex + 1)
            val namespace = prefixes[prefix]
            if (namespace != null) return "$namespace$localName"
            if (isAbsoluteIri(term)) return term
            throw IllegalArgumentException("Unknown prefix: $prefix")
        }
        if (isAbsoluteIri(term)) return term
        if (vocabIri != null) return "${vocabIri.value}$term"
        if (baseIri != null) return "${baseIri.value}$term"
        throw IllegalArgumentException("Unqualified term with no @base or @vocab: $term")
    }

    private fun resolveContainer(value: String): JsonLdContainer {
        return when (value) {
            "@list" -> JsonLdContainer.List
            "@set" -> JsonLdContainer.Set
            "@index" -> JsonLdContainer.Index
            "@language" -> JsonLdContainer.Language
            else -> JsonLdContainer.Unknown(value)
        }
    }

    private fun isAbsoluteIri(term: String): Boolean {
        return term.contains("://") || term.startsWith("urn:")
    }
}












