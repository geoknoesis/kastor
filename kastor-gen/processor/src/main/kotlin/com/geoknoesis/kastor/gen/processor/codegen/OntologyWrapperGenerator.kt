package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.model.OntologyModel
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.model.ShaclShape
import com.google.devtools.ksp.processing.KSPLogger

/**
 * Generator for Kotlin wrapper classes from SHACL shapes and JSON-LD context.
 * Creates RDF-backed wrapper implementations.
 */
class OntologyWrapperGenerator(
    private val logger: KSPLogger,
    private val validationMode: ValidationMode = ValidationMode.EMBEDDED,
    private val externalValidatorClass: String? = null
) {

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

    private fun generateWrapper(shape: ShaclShape, context: com.geoknoesis.kastor.gen.processor.model.JsonLdContext, packageName: String): String {
        val interfaceName = extractInterfaceName(shape.targetClass)
        val wrapperName = "${interfaceName}Wrapper"
        
        return buildString {
            appendLine("// GENERATED FILE - DO NOT EDIT")
            appendLine("// Generated from SHACL shape: ${shape.shapeIri}")
            appendLine("package $packageName")
            appendLine()
            appendLine("import com.geoknoesis.kastor.gen.runtime.*")
            appendLine("import com.geoknoesis.kastor.rdf.*")
            appendLine()
            
            // Wrapper class declaration
            appendLine("/**")
            appendLine(" * RDF-backed wrapper for $interfaceName")
            appendLine(" * Generated from SHACL shape: ${shape.shapeIri}")
            appendLine(" */")
            appendLine("internal class $wrapperName(")
            appendLine("  private val input: RdfHandle")
            appendLine(") : $interfaceName, RdfBacked {")
            appendLine()
            
            // Known predicates set
            appendLine("  private val known: Set<Iri> = setOf(")
            appendLine(shape.properties.joinToString(",\n") { "    Iri(\"${it.path}\")" })
            appendLine("  )")
            appendLine()
            appendLine("  override val rdf: RdfHandle by lazy(LazyThreadSafetyMode.PUBLICATION) {")
            appendLine("    if (input is DefaultRdfHandle) DefaultRdfHandle(input.node, input.graph, known) else input")
            appendLine("  }")
            appendLine()
            
            // Generate property implementations
            shape.properties.forEach { property ->
                appendLine(generatePropertyImplementation(property, context))
                appendLine()
            }

            appendLine(generateValidationBlock(shape))
            appendLine()
            
            // Companion object with registry and mapping metadata
            appendLine("  companion object {")
            appendLine("    /**")
            appendLine("     * Mapping metadata: JSON-LD property names → RDF predicate IRIs")
            appendLine("     */")
            appendLine("    val propertyMappings: Map<String, Iri> = mapOf(")
            shape.properties.forEach { property ->
                val jsonLdName = context.propertyMappings.entries
                    .find { it.value.id.value == property.path }
                    ?.key
                    ?: property.name
                appendLine("      \"$jsonLdName\" to Iri(\"${property.path}\"),")
            }
            appendLine("    )")
            appendLine()
            appendLine("    init {")
            appendLine("      OntoMapper.registry[$interfaceName::class.java] = { handle -> $wrapperName(handle) }")
            appendLine("    }")
            appendLine("  }")
            appendLine("}")
        }
    }

    private fun generateValidationBlock(shape: ShaclShape): String {
        return when (validationMode) {
            ValidationMode.NONE -> ""
            ValidationMode.EXTERNAL -> generateExternalValidation()
            ValidationMode.EMBEDDED -> generateEmbeddedValidation(shape)
        }
    }

    private fun generateExternalValidation(): String {
        val validatorRef = externalValidatorClass?.takeIf { it.isNotBlank() }
        return buildString {
            appendLine("  fun validate(): ValidationResult {")
            if (validatorRef != null) {
                appendLine("    return $validatorRef().validate(rdf.graph, rdf.node)")
            } else {
                appendLine("    return rdf.validate()")
            }
            appendLine("  }")
        }
    }

    private fun generateEmbeddedValidation(shape: ShaclShape): String {
        return buildString {
            appendLine("  fun validate(): ValidationResult {")
            appendLine("    val violations = mutableListOf<ShaclViolation>()")
            shape.properties.forEach { property ->
                val pred = property.path
                val min = property.minCount
                val max = property.maxCount
                if (min != null || max != null) {
                    val countExpr = if (property.targetClass != null) {
                        "KastorGraphOps.countObjectValues(rdf.graph, rdf.node, Iri(\"$pred\"))"
                    } else {
                        "KastorGraphOps.countLiteralValues(rdf.graph, rdf.node, Iri(\"$pred\"))"
                    }
                    appendLine("    run {")
                    appendLine("      val count = $countExpr")
                    min?.let {
                        appendLine("      if (count < $it) violations.add(ShaclViolation(")
                        appendLine("        focusNode = rdf.node as RdfResource,")
                        appendLine("        shapeIri = com.geoknoesis.kastor.rdf.vocab.SHACL.NodeShape,")
                        appendLine("        constraintIri = com.geoknoesis.kastor.rdf.vocab.SHACL.minCount,")
                        appendLine("        path = Iri(\"$pred\"),")
                        appendLine("        message = \"minCount $it violated\"")
                        appendLine("      ))")
                    }
                    max?.let {
                        appendLine("      if (count > $it) violations.add(ShaclViolation(")
                        appendLine("        focusNode = rdf.node as RdfResource,")
                        appendLine("        shapeIri = com.geoknoesis.kastor.rdf.vocab.SHACL.NodeShape,")
                        appendLine("        constraintIri = com.geoknoesis.kastor.rdf.vocab.SHACL.maxCount,")
                        appendLine("        path = Iri(\"$pred\"),")
                        appendLine("        message = \"maxCount $it violated\"")
                        appendLine("      ))")
                    }
                    appendLine("    }")
                }
            }
            appendLine("    return if (violations.isEmpty()) ValidationResult.Ok else ValidationResult.Violations(violations)")
            appendLine("  }")
        }
    }

    private fun generatePropertyImplementation(property: ShaclProperty, context: com.geoknoesis.kastor.gen.processor.model.JsonLdContext): String {
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
                    appendLine("      OntoMapper.materialize(RdfRef(child, rdf.graph), $targetInterfaceName::class.java)")
                    if (property.minCount != null && property.minCount > 0) {
                        appendLine("    }.firstOrNull() ?: error(\"Required object $propertyName missing\")")
                    } else {
                        appendLine("    }.firstOrNull()")
                    }
                } else {
                    appendLine("    KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(\"${property.path}\")) { child ->")
                    appendLine("      OntoMapper.materialize(RdfRef(child, rdf.graph), $targetInterfaceName::class.java)")
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
                val isRequired = property.minCount != null && property.minCount > 0
                
                if (isSingleValue) {
                    if (isRequired) {
                        appendLine("    ${requiredLiteralAccessor(baseType, property.path)}")
                    } else {
                        appendLine("    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"${property.path}\")).map { it.lexical }${getSingleValueConversionMethod(baseType)}")
                    }
                } else {
                    if (isRequired) {
                        appendLine("    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"${property.path}\")).map { it.lexical }${getConversionMethod(baseType)}.ifEmpty { error(\"Required literal $propertyName missing\") }")
                    } else {
                        appendLine("    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"${property.path}\")).map { it.lexical }${getConversionMethod(baseType)}")
                    }
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

    private fun requiredLiteralAccessor(kotlinType: String, path: String): String {
        return when (kotlinType) {
            "Int" -> "KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(\"$path\")).lexical.toInt()"
            "Double" -> "KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(\"$path\")).lexical.toDouble()"
            "Boolean" -> "KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(\"$path\")).lexical.toBooleanStrict()"
            else -> "KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(\"$path\")).lexical"
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
        return if (property.maxCount == 1) {
            if (property.minCount == null || property.minCount == 0) {
                "$kotlinType?"
            } else {
                kotlinType
            }
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












