package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.model.OntologyModel
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.processor.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.model.ShaclShape
import com.google.devtools.ksp.processing.KSPLogger

/**
 * Generator for Kotlin domain interfaces from SHACL shapes.
 * Creates pure domain interfaces with no RDF dependencies.
 */
class InterfaceGenerator(
    private val logger: KSPLogger,
    private val validationAnnotations: ValidationAnnotations = ValidationAnnotations.JAKARTA
) {

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

    private fun generateInterface(shape: ShaclShape, context: com.geoknoesis.kastor.gen.processor.model.JsonLdContext, packageName: String): String {
        val interfaceName = extractInterfaceName(shape.targetClass)
        
        return buildString {
            appendLine("// GENERATED FILE - DO NOT EDIT")
            appendLine("// Generated from SHACL shape: ${shape.shapeIri}")
            appendLine("package $packageName")
            appendLine()
            appendLine("import com.geoknoesis.kastor.gen.annotations.RdfClass")
            appendLine("import com.geoknoesis.kastor.gen.annotations.RdfProperty")
            if (validationAnnotations != ValidationAnnotations.NONE) {
                appendLine("import ${validationPackage()}.constraints.*")
            }
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

    private fun generateProperty(property: ShaclProperty, context: com.geoknoesis.kastor.gen.processor.model.JsonLdContext): String {
        val kotlinType = determineKotlinType(property, context)
        val propertyName = toValidKotlinIdentifier(property.name)
        
        return buildString {
            appendLine("    /**")
            appendLine("     * ${property.description}")
            appendLine("     * Path: ${property.path}")
            if (property.minCount != null) appendLine("     * Min count: ${property.minCount}")
            if (property.maxCount != null) appendLine("     * Max count: ${property.maxCount}")
            appendLine("     */")
            appendLine("    @get:RdfProperty(iri = \"${property.path}\")")
            validationAnnotationsForProperty(property).forEach { annotation ->
                appendLine("    $annotation")
            }
            appendLine("    val $propertyName: $kotlinType")
        }
    }

    private fun determineKotlinType(property: ShaclProperty, @Suppress("UNUSED_PARAMETER") context: com.geoknoesis.kastor.gen.processor.model.JsonLdContext): String {
        // Check if it's an object property (has targetClass)
        if (property.targetClass != null) {
            val targetInterfaceName = extractInterfaceName(property.targetClass)
            return if (property.maxCount == 1) {
                if (property.minCount == null || property.minCount == 0) {
                    "$targetInterfaceName?"
                } else {
                    targetInterfaceName
                }
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
        } else if (property.minCount == null || property.minCount == 0) {
            "$kotlinType?"
        } else {
            kotlinType
        }
    }

    private fun validationAnnotationsForProperty(property: ShaclProperty): List<String> {
        if (validationAnnotations == ValidationAnnotations.NONE) return emptyList()
        val annotations = mutableListOf<String>()
        val isList = property.maxCount == null || property.maxCount > 1
        val min = property.minCount
        val max = property.maxCount

        if (!isList) {
            if (min != null && min > 0) {
                annotations.add("@get:NotNull")
            }
        } else {
            if (min != null && min > 0) {
                annotations.add("@get:NotEmpty")
            }
            if (min != null || max != null) {
                val parts = mutableListOf<String>()
                min?.let { parts.add("min = $it") }
                max?.let { parts.add("max = $it") }
                if (parts.isNotEmpty()) {
                    annotations.add("@get:Size(${parts.joinToString(", ")})")
                }
            }
        }

        return annotations
    }

    private fun validationPackage(): String {
        return when (validationAnnotations) {
            ValidationAnnotations.JAKARTA -> "jakarta.validation"
            ValidationAnnotations.JAVAX -> "javax.validation"
            ValidationAnnotations.NONE -> "jakarta.validation"
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

    /**
     * Converts a property name to a valid Kotlin identifier by converting hyphens and underscores to camelCase.
     */
    private fun toValidKotlinIdentifier(name: String): String {
        // Split on hyphens and underscores
        val parts = name.split('-', '_')
        
        // First part stays lowercase, rest are capitalized
        return parts.mapIndexed { index, part ->
            if (index == 0) part.replaceFirstChar { it.lowercaseChar() }
            else part.replaceFirstChar { it.uppercaseChar() }
        }.joinToString("")
    }
}












