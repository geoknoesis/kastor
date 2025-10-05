package com.example.ontomapper.processor.codegen

import com.example.ontomapper.processor.model.OntologyModel
import com.example.ontomapper.processor.model.ShaclProperty
import com.example.ontomapper.processor.model.ShaclShape
import com.google.devtools.ksp.processing.KSPLogger

/**
 * Generator for Kotlin domain interfaces from SHACL shapes.
 * Creates pure domain interfaces with no RDF dependencies.
 */
class InterfaceGenerator(private val logger: KSPLogger) {

    /**
     * Generates Kotlin interface code from SHACL shapes.
     * 
     * @param ontologyModel The combined SHACL + JSON-LD model
     * @param packageName The target package name
     * @return Map of interface names to generated code
     */
    fun generateInterfaces(ontologyModel: OntologyModel, packageName: String): Map<String, String> {
        val interfaces = mutableMapOf<String, String>()
        
        ontologyModel.shapes.forEach { shape ->
            val interfaceName = extractInterfaceName(shape.targetClass)
            val interfaceCode = generateInterface(shape, ontologyModel.context, packageName)
            interfaces[interfaceName] = interfaceCode
            
            logger.info("Generated interface: $interfaceName")
        }
        
        return interfaces
    }

    private fun generateInterface(shape: ShaclShape, context: com.example.ontomapper.processor.model.JsonLdContext, packageName: String): String {
        val interfaceName = extractInterfaceName(shape.targetClass)
        
        return buildString {
            appendLine("// GENERATED FILE - DO NOT EDIT")
            appendLine("// Generated from SHACL shape: ${shape.shapeIri}")
            appendLine("package $packageName")
            appendLine()
            appendLine("import com.example.ontomapper.annotations.RdfClass")
            appendLine("import com.example.ontomapper.annotations.RdfProperty")
            appendLine()
            
            // Interface declaration with RdfClass annotation
            appendLine("/**")
            appendLine(" * Domain interface for ${shape.targetClass}")
            appendLine(" * Pure domain interface with no RDF dependencies.")
            appendLine(" * Generated from SHACL shape: ${shape.shapeIri}")
            appendLine(" */")
            appendLine("@RdfClass(iri = \"${shape.targetClass}\")")
            appendLine("interface $interfaceName {")
            appendLine()
            
            // Generate properties
            shape.properties.forEach { property ->
                appendLine(generateProperty(property, context))
                appendLine()
            }
            
            appendLine("}")
        }
    }

    private fun generateProperty(property: ShaclProperty, context: com.example.ontomapper.processor.model.JsonLdContext): String {
        val kotlinType = determineKotlinType(property, context)
        val propertyName = property.name
        
        return buildString {
            appendLine("    /**")
            appendLine("     * ${property.description}")
            appendLine("     * Path: ${property.path}")
            if (property.minCount != null) appendLine("     * Min count: ${property.minCount}")
            if (property.maxCount != null) appendLine("     * Max count: ${property.maxCount}")
            appendLine("     */")
            appendLine("    @get:RdfProperty(iri = \"${property.path}\")")
            appendLine("    val $propertyName: $kotlinType")
        }
    }

    private fun determineKotlinType(property: ShaclProperty, context: com.example.ontomapper.processor.model.JsonLdContext): String {
        // Check if it's an object property (has targetClass)
        if (property.targetClass != null) {
            val targetInterfaceName = extractInterfaceName(property.targetClass)
            return if (property.maxCount == 1) {
                targetInterfaceName
            } else {
                "List<$targetInterfaceName>"
            }
        }
        
        // It's a literal property
        val kotlinType = when (property.datatype) {
            "http://www.w3.org/2001/XMLSchema#string" -> "String"
            "http://www.w3.org/2001/XMLSchema#int" -> "Int"
            "http://www.w3.org/2001/XMLSchema#integer" -> "Int"
            "http://www.w3.org/2001/XMLSchema#double" -> "Double"
            "http://www.w3.org/2001/XMLSchema#float" -> "Double"
            "http://www.w3.org/2001/XMLSchema#boolean" -> "Boolean"
            "http://www.w3.org/2001/XMLSchema#anyURI" -> "String"
            else -> "String" // Default to String for unknown types
        }
        
        // Return as list if maxCount > 1 or maxCount is null (unbounded)
        return if (property.maxCount == null || property.maxCount > 1) {
            "List<$kotlinType>"
        } else {
            kotlinType
        }
    }

    private fun extractInterfaceName(classIri: String): String {
        // Extract local name from IRI
        val localName = classIri.substringAfterLast('/').substringAfterLast('#')
        
        // Convert to PascalCase if needed
        return localName.split('-', '_').joinToString("") { word ->
            word.replaceFirstChar { it.uppercaseChar() }
        }
    }
}
