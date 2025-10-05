package com.example.ontomapper.processor.parsers

import com.example.ontomapper.processor.model.JsonLdContext
import com.example.ontomapper.processor.model.JsonLdProperty
import com.google.devtools.ksp.processing.KSPLogger
import kotlinx.serialization.json.Json
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
        val context = jsonObject["@context"]?.jsonObject ?: throw IllegalArgumentException("No @context found")
        
        val prefixes = mutableMapOf<String, String>()
        val typeMappings = mutableMapOf<String, String>()
        val propertyMappings = mutableMapOf<String, JsonLdProperty>()
        
        context.entries.forEach { (key, value) ->
            when {
                // Simple prefix mappings (e.g., "dcat": "http://www.w3.org/ns/dcat#")
                value is JsonPrimitive && value.isString && !key.contains(":") -> {
                    val uri = value.content
                    if (uri.endsWith("#") || uri.endsWith("/")) {
                        prefixes[key] = uri
                        logger.info("Extracted prefix: $key -> $uri")
                    } else {
                        // Type mapping (e.g., "Catalog": "dcat:Catalog")
                        typeMappings[key] = expandPrefix(uri, prefixes)
                        logger.info("Extracted type mapping: $key -> $uri")
                    }
                }
                
                // Property definitions (e.g., "title": {"@id": "dcterms:title", "@type": "xsd:string"})
                value is JsonObject -> {
                    val id = value["@id"]?.jsonPrimitive?.content
                    val type = value["@type"]?.jsonPrimitive?.content
                    
                    if (id != null) {
                        val expandedId = expandPrefix(id, prefixes)
                        val expandedType = type?.let { expandPrefix(it, prefixes) }
                        
                        propertyMappings[key] = JsonLdProperty(
                            id = expandedId,
                            type = expandedType
                        )
                        logger.info("Extracted property: $key -> $expandedId (type: $expandedType)")
                    }
                }
            }
        }
        
        return JsonLdContext(
            prefixes = prefixes,
            typeMappings = typeMappings,
            propertyMappings = propertyMappings
        )
    }

    private fun expandPrefix(term: String, prefixes: Map<String, String>): String {
        val colonIndex = term.indexOf(':')
        if (colonIndex > 0) {
            val prefix = term.substring(0, colonIndex)
            val localName = term.substring(colonIndex + 1)
            return prefixes[prefix]?.let { "$it$localName" } ?: term
        }
        return term
    }
}
