package com.example.ontomapper.processor.parsers

import com.example.ontomapper.processor.model.ShaclShape
import com.example.ontomapper.processor.model.ShaclProperty
import com.google.devtools.ksp.processing.KSPLogger
import java.io.InputStream

/**
 * Parser for SHACL (Shapes Constraint Language) files.
 * Extracts NodeShapes and their property constraints for code generation.
 */
class ShaclParser(private val logger: KSPLogger) {

    /**
     * Parses a SHACL file and extracts NodeShapes.
     * 
     * @param inputStream The SHACL file input stream
     * @return List of extracted SHACL shapes
     */
    fun parseShacl(inputStream: InputStream): List<ShaclShape> {
        val content = inputStream.bufferedReader().use { it.readText() }
        return parseShaclContent(content)
    }

    /**
     * Parses SHACL content from a string.
     * 
     * @param content The SHACL content as string
     * @return List of extracted SHACL shapes
     */
    fun parseShaclContent(content: String): List<ShaclShape> {
        val shapes = mutableListOf<ShaclShape>()
        
        // Simple regex-based parsing for Turtle format
        // In a production system, you'd use a proper RDF parser like Apache Jena
        
        // Extract prefixes
        val prefixes = extractPrefixes(content)
        logger.info("Extracted prefixes: $prefixes")
        
        // Find NodeShape definitions
        val nodeShapePattern = """<([^>]+)>\s*a\s+sh:NodeShape\s*;.*?sh:targetClass\s+([^;\s]+)\s*;(.*?)(?=<[^>]+>\s*a\s+sh:NodeShape|$)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        nodeShapePattern.findAll(content).forEach { matchResult ->
            val shapeIri = matchResult.groupValues[1]
            val targetClass = expandPrefix(matchResult.groupValues[2], prefixes)
            val propertiesBlock = matchResult.groupValues[3]
            
            val properties = extractProperties(propertiesBlock, prefixes)
            
            shapes.add(ShaclShape(
                shapeIri = shapeIri,
                targetClass = targetClass,
                properties = properties
            ))
            
            logger.info("Extracted shape: $shapeIri -> $targetClass with ${properties.size} properties")
        }
        
        return shapes
    }

    private fun extractPrefixes(content: String): Map<String, String> {
        val prefixPattern = """@prefix\s+(\w+):\s*<([^>]+)>\s*\.""".toRegex()
        return prefixPattern.findAll(content).associate { match ->
            match.groupValues[1] to match.groupValues[2]
        }
    }

    private fun expandPrefix(term: String, prefixes: Map<String, String>): String {
        if (term.startsWith("<") && term.endsWith(">")) {
            return term.substring(1, term.length - 1)
        }
        
        val colonIndex = term.indexOf(':')
        if (colonIndex > 0) {
            val prefix = term.substring(0, colonIndex)
            val localName = term.substring(colonIndex + 1)
            return prefixes[prefix]?.let { "$it$localName" } ?: term
        }
        
        return term
    }

    private fun extractProperties(propertiesBlock: String, prefixes: Map<String, String>): List<ShaclProperty> {
        val properties = mutableListOf<ShaclProperty>()
        
        // Extract property constraints
        val propertyPattern = """sh:property\s+\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        propertyPattern.findAll(propertiesBlock).forEach { matchResult ->
            val propertyBlock = matchResult.groupValues[1]
            
            val path = extractPropertyValue(propertyBlock, "sh:path")?.let { expandPrefix(it, prefixes) }
            val name = extractPropertyValue(propertyBlock, "sh:name")?.removeSurrounding("\"")
            val description = extractPropertyValue(propertyBlock, "sh:description")?.removeSurrounding("\"")
            val datatype = extractPropertyValue(propertyBlock, "sh:datatype")?.let { expandPrefix(it, prefixes) }
            val targetClass = extractPropertyValue(propertyBlock, "sh:class")?.let { expandPrefix(it, prefixes) }
            val minCount = extractPropertyValue(propertyBlock, "sh:minCount")?.toIntOrNull()
            val maxCount = extractPropertyValue(propertyBlock, "sh:maxCount")?.toIntOrNull()
            
            if (path != null && name != null) {
                properties.add(ShaclProperty(
                    path = path,
                    name = name,
                    description = description ?: "",
                    datatype = datatype,
                    targetClass = targetClass,
                    minCount = minCount,
                    maxCount = maxCount
                ))
            }
        }
        
        return properties
    }

    private fun extractPropertyValue(block: String, property: String): String? {
        val pattern = """$property\s+([^;\s]+)""".toRegex()
        return pattern.find(block)?.groupValues?.get(1)
    }
}
