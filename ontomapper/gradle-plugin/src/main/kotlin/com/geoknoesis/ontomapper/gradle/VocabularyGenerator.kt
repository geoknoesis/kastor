package com.geoknoesis.ontomapper.gradle

import com.example.ontomapper.processor.model.ShaclShape
import com.example.ontomapper.processor.model.JsonLdContext
import com.example.ontomapper.processor.parsers.ShaclParser
import com.example.ontomapper.processor.parsers.JsonLdContextParser
import com.google.devtools.ksp.processing.KSPLogger
import java.io.File
import java.io.FileInputStream

/**
 * Generator for vocabulary files from SHACL, RDFS, or OWL ontology files.
 * 
 * This generator creates Kotlin vocabulary objects following the Kastor pattern,
 * extracting classes and properties from ontology files and generating type-safe
 * vocabulary constants.
 */
class VocabularyGenerator(private val logger: KSPLogger) {
    
    private val shaclParser = ShaclParser(logger)
    private val contextParser = JsonLdContextParser(logger)
    
    /**
     * Generates a vocabulary file from SHACL and JSON-LD context files.
     * 
     * @param shaclFile The SHACL file
     * @param contextFile The JSON-LD context file
     * @param vocabularyName The name of the vocabulary (e.g., "DCAT")
     * @param namespace The namespace URI for the vocabulary
     * @param prefix The prefix for the vocabulary (e.g., "dcat")
     * @param packageName The target package for the generated vocabulary
     * @return The generated vocabulary code
     */
    fun generateVocabulary(
        shaclFile: File,
        contextFile: File,
        vocabularyName: String,
        namespace: String,
        prefix: String,
        packageName: String
    ): String {
        logger.info("Generating vocabulary for $vocabularyName")
        
        // Parse SHACL shapes
        val shapes = shaclParser.parseShacl(FileInputStream(shaclFile))
        logger.info("Parsed ${shapes.size} SHACL shapes")
        
        // Parse JSON-LD context
        val context = contextParser.parseContext(FileInputStream(contextFile))
        logger.info("Parsed context with ${context.prefixes.size} prefixes")
        
        // Extract vocabulary terms
        val classes = extractClasses(shapes, context)
        val properties = extractProperties(shapes, context)
        
        logger.info("Extracted ${classes.size} classes and ${properties.size} properties")
        
        // Generate vocabulary code
        return generateVocabularyCode(
            vocabularyName,
            namespace,
            prefix,
            packageName,
            classes,
            properties
        )
    }
    
    /**
     * Extracts class terms from SHACL shapes and JSON-LD context.
     */
    private fun extractClasses(shapes: List<ShaclShape>, context: JsonLdContext): List<VocabularyTerm> {
        val classes = mutableListOf<VocabularyTerm>()
        
        // Extract classes from SHACL shapes
        shapes.forEach { shape ->
            val className = context.typeMappings[shape.targetClass] 
                ?: shape.targetClass.substringAfterLast('#').substringAfterLast('/')
            
            if (className.isNotEmpty()) {
                classes.add(VocabularyTerm(
                    name = className,
                    iri = shape.targetClass,
                    type = TermType.CLASS,
                    description = ""
                ))
            }
        }
        
        // Extract additional classes from context
        context.typeMappings.forEach { (iri, name) ->
            if (!classes.any { it.iri == iri }) {
                classes.add(VocabularyTerm(
                    name = name,
                    iri = iri,
                    type = TermType.CLASS,
                    description = null
                ))
            }
        }
        
        return classes.distinctBy { it.name }
    }
    
    /**
     * Extracts property terms from SHACL shapes and JSON-LD context.
     */
    private fun extractProperties(shapes: List<ShaclShape>, context: JsonLdContext): List<VocabularyTerm> {
        val properties = mutableListOf<VocabularyTerm>()
        
        // Extract properties from SHACL shapes
        shapes.forEach { shape ->
            shape.properties.forEach { property ->
                val propertyName = context.propertyMappings[property.path]?.id
                    ?: property.path.substringAfterLast('#').substringAfterLast('/')
                
                if (propertyName.isNotEmpty()) {
                    properties.add(VocabularyTerm(
                        name = propertyName,
                        iri = property.path,
                        type = TermType.PROPERTY,
                        description = property.description
                    ))
                }
            }
        }
        
        // Extract additional properties from context
        context.propertyMappings.forEach { (iri, property) ->
            if (!properties.any { it.iri == iri }) {
                properties.add(VocabularyTerm(
                    name = property.id,
                    iri = iri,
                    type = TermType.PROPERTY,
                    description = null
                ))
            }
        }
        
        return properties.distinctBy { it.name }
    }
    
    /**
     * Generates the vocabulary code following the Kastor pattern.
     */
    private fun generateVocabularyCode(
        vocabularyName: String,
        namespace: String,
        prefix: String,
        packageName: String,
        classes: List<VocabularyTerm>,
        properties: List<VocabularyTerm>
    ): String {
        val className = vocabularyName.uppercase()
        
        val classTerms = classes.joinToString("\n    ") { term ->
            val kotlinName = toKotlinIdentifier(term.name)
            val comment = if (term.description != null) "    // ${term.description}" else ""
            "$comment\n    val $kotlinName: Iri by lazy { term(\"${term.name}\") }"
        }
        
        val propertyTerms = properties.joinToString("\n    ") { term ->
            val kotlinName = toKotlinIdentifier(term.name)
            val comment = if (term.description != null) "    // ${term.description}" else ""
            "$comment\n    val $kotlinName: Iri by lazy { term(\"${term.name}\") }"
        }
        
        return """
package $packageName

import com.geoknoesis.kastor.rdf.Iri

/**
 * $vocabularyName vocabulary.
 * Generated from ontology files.
 */
object $className : Vocabulary {
    override val namespace: String = "$namespace"
    override val prefix: String = "$prefix"
    
    // Classes
$classTerms
    
    // Properties
$propertyTerms
}
""".trimIndent()
    }
    
    /**
     * Converts a term name to a valid Kotlin identifier.
     */
    private fun toKotlinIdentifier(name: String): String {
        // Handle reserved keywords
        val reservedKeywords = setOf("class", "object", "package", "import", "val", "var", "fun", "return", "if", "else", "when", "for", "while", "do", "try", "catch", "finally", "throw", "type", "is", "as", "in", "out", "by", "get", "set", "init", "constructor", "this", "super", "override", "abstract", "final", "open", "private", "protected", "public", "internal", "external", "expect", "actual", "companion", "sealed", "enum", "annotation", "data", "inline", "noinline", "crossinline", "vararg", "tailrec", "operator", "infix", "suspend", "lateinit", "const", "inner", "interface", "typealias")
        
        val cleanName = name.replace("-", "_")
            .replace(":", "_")
            .replace(".", "_")
            .replace(" ", "_")
        
        return if (reservedKeywords.contains(cleanName.lowercase())) {
            "${cleanName}Prop"
        } else {
            cleanName
        }
    }
}

/**
 * Represents a vocabulary term (class or property).
 */
data class VocabularyTerm(
    val name: String,
    val iri: String,
    val type: TermType,
    val description: String?
)

/**
 * Type of vocabulary term.
 */
enum class TermType {
    CLASS,
    PROPERTY
}












