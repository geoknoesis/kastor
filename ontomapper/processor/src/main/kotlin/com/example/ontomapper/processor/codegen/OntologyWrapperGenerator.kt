package com.example.ontomapper.processor.codegen

import com.example.ontomapper.processor.model.OntologyModel
import com.example.ontomapper.processor.model.ShaclProperty
import com.example.ontomapper.processor.model.ShaclShape
import com.google.devtools.ksp.processing.KSPLogger

/**
 * Generator for Kotlin wrapper classes from SHACL shapes and JSON-LD context.
 * Creates RDF-backed wrapper implementations.
 */
class OntologyWrapperGenerator(private val logger: KSPLogger) {

    /**
     * Generates Kotlin wrapper code from SHACL shapes.
     * 
     * @param ontologyModel The combined SHACL + JSON-LD model
     * @param packageName The target package name
     * @return Map of wrapper class names to generated code
     */
    fun generateWrappers(ontologyModel: OntologyModel, packageName: String): Map<String, String> {
        val wrappers = mutableMapOf<String, String>()
        
        ontologyModel.shapes.forEach { shape ->
            val interfaceName = extractInterfaceName(shape.targetClass)
            val wrapperName = "${interfaceName}Wrapper"
            val wrapperCode = generateWrapper(shape, ontologyModel.context, packageName)
            wrappers[wrapperName] = wrapperCode
            
            logger.info("Generated wrapper: $wrapperName")
        }
        
        return wrappers
    }

    private fun generateWrapper(shape: ShaclShape, context: com.example.ontomapper.processor.model.JsonLdContext, packageName: String): String {
        val interfaceName = extractInterfaceName(shape.targetClass)
        val wrapperName = "${interfaceName}Wrapper"
        
        return buildString {
            appendLine("// GENERATED FILE - DO NOT EDIT")
            appendLine("// Generated from SHACL shape: ${shape.shapeIri}")
            appendLine("package $packageName")
            appendLine()
            appendLine("import com.example.ontomapper.runtime.*")
            appendLine("import com.geoknoesis.kastor.rdf.*")
            appendLine()
            
            // Wrapper class declaration
            appendLine("/**")
            appendLine(" * RDF-backed wrapper for $interfaceName")
            appendLine(" * Generated from SHACL shape: ${shape.shapeIri}")
            appendLine(" */")
            appendLine("internal class $wrapperName(")
            appendLine("  override val rdf: RdfHandle")
            appendLine(") : $interfaceName, RdfBacked {")
            appendLine()
            
            // Known predicates set
            appendLine("  private val known: Set<Iri> = setOf(")
            appendLine(shape.properties.joinToString(",\n") { "    Iri(\"${it.path}\")" })
            appendLine("  )")
            appendLine()
            
            // Generate property implementations
            shape.properties.forEach { property ->
                appendLine(generatePropertyImplementation(property, context))
                appendLine()
            }
            
            // Companion object with registry
            appendLine("  companion object {")
            appendLine("    init {")
            appendLine("      OntoMapper.registry[$interfaceName::class.java] = { handle -> $wrapperName(handle) }")
            appendLine("    }")
            appendLine("  }")
            appendLine("}")
        }
    }

    private fun generatePropertyImplementation(property: ShaclProperty, context: com.example.ontomapper.processor.model.JsonLdContext): String {
        val propertyName = property.name
        val kotlinType = determineKotlinType(property, context)
        
        return buildString {
            appendLine("  /**")
            appendLine("   * ${property.description}")
            appendLine("   * Path: ${property.path}")
            appendLine("   */")
            appendLine("  override val $propertyName: $kotlinType by lazy {")
            
            if (property.targetClass != null) {
                // Object property
                val targetInterfaceName = extractInterfaceName(property.targetClass)
                if (property.maxCount == 1) {
                    appendLine("    KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(\"${property.path}\")) { child ->")
                    appendLine("      OntoMapper.materialize(RdfRef(child, rdf.graph), $targetInterfaceName::class.java, false)")
                    appendLine("    }.firstOrNull() ?: error(\"Required object $propertyName missing\")")
                } else {
                    appendLine("    KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(\"${property.path}\")) { child ->")
                    appendLine("      OntoMapper.materialize(RdfRef(child, rdf.graph), $targetInterfaceName::class.java, false)")
                    appendLine("    }")
                }
            } else {
                // Literal property
                val baseType = when (property.datatype) {
                    "http://www.w3.org/2001/XMLSchema#string" -> "String"
                    "http://www.w3.org/2001/XMLSchema#int", "http://www.w3.org/2001/XMLSchema#integer" -> "Int"
                    "http://www.w3.org/2001/XMLSchema#double", "http://www.w3.org/2001/XMLSchema#float" -> "Double"
                    "http://www.w3.org/2001/XMLSchema#boolean" -> "Boolean"
                    "http://www.w3.org/2001/XMLSchema#anyURI" -> "String"
                    else -> "String"
                }
                
                // Use single value method only if maxCount is exactly 1
                val isSingleValue = property.maxCount == 1
                
                if (isSingleValue) {
                    val defaultValue = getDefaultValue(baseType)
                    appendLine("    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"${property.path}\")).map { it.lexical }${getSingleValueConversionMethod(baseType)} ?: $defaultValue")
                } else {
                    appendLine("    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"${property.path}\")).map { it.lexical }${getConversionMethod(baseType)}")
                }
            }
            
            appendLine("  }")
        }
    }

    private fun getConversionMethod(kotlinType: String): String {
        return when (kotlinType) {
            "Int" -> ".mapNotNull { it.toIntOrNull() }"
            "Double" -> ".mapNotNull { it.toDoubleOrNull() }"
            "Boolean" -> ".mapNotNull { it.toBooleanStrictOrNull() }"
            else -> ""
        }
    }

    private fun getSingleValueConversionMethod(kotlinType: String): String {
        return when (kotlinType) {
            "Int" -> ".firstOrNull()?.toIntOrNull()"
            "Double" -> ".firstOrNull()?.toDoubleOrNull()"
            "Boolean" -> ".firstOrNull()?.toBooleanStrictOrNull()"
            else -> ".firstOrNull()"
        }
    }

    private fun getDefaultValue(kotlinType: String): String {
        return when (kotlinType) {
            "Int" -> "0"
            "Double" -> "0.0"
            "Boolean" -> "false"
            else -> "\"\""
        }
    }

    private fun determineKotlinType(property: ShaclProperty, @Suppress("UNUSED_PARAMETER") context: com.example.ontomapper.processor.model.JsonLdContext): String {
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
                return if (property.maxCount == 1) {
                    kotlinType
                } else {
                    "List<$kotlinType>"
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
