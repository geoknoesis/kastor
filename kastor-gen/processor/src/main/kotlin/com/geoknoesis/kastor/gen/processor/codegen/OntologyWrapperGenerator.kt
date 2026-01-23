package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.utils.CodegenConstants
import com.geoknoesis.kastor.gen.processor.utils.KotlinPoetUtils
import com.geoknoesis.kastor.gen.processor.utils.NamingUtils
import com.geoknoesis.kastor.gen.processor.utils.TypeMapper
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.KModifier.*

/**
 * Generator for Kotlin wrapper classes from SHACL shapes and JSON-LD context using KotlinPoet.
 * Creates RDF-backed wrapper implementations.
 */
internal class OntologyWrapperGenerator(
    private val logger: KSPLogger,
    private val validationMode: ValidationMode = ValidationMode.EMBEDDED,
    private val externalValidatorClass: String? = null
) {

    /**
     * Generates Kotlin wrapper code from SHACL shapes.
     * 
     * @param ontologyModel The combined SHACL + JSON-LD model
     * @param packageName The target package name
     * @return Map of wrapper class names to generated FileSpec
     */
    fun generateWrappers(ontologyModel: OntologyModel, packageName: String): Map<String, FileSpec> {
        val wrappers = mutableMapOf<String, FileSpec>()
        
        ontologyModel.shapes.forEach { shape ->
            val interfaceName = NamingUtils.extractInterfaceName(shape.targetClass)
            val wrapperName = "${interfaceName}Wrapper"
            val fileSpec = generateWrapper(shape, ontologyModel.context, packageName)
            wrappers[wrapperName] = fileSpec
            
            logger.info("Generated wrapper: $wrapperName")
        }
        
        return wrappers
    }

    private fun generateWrapper(
        shape: ShaclShape,
        context: JsonLdContext,
        packageName: String
    ): FileSpec {
        val interfaceName = NamingUtils.extractInterfaceName(shape.targetClass)
        val wrapperName = "${interfaceName}Wrapper"
        
        val fileBuilder = FileSpec.builder(packageName, wrapperName)
            .addFileComment("GENERATED FILE - DO NOT EDIT")
            .addFileComment("Generated from SHACL shape: %L", shape.shapeIri)
        
        // Add imports
        fileBuilder.addImport(CodegenConstants.RUNTIME_PACKAGE, "RdfBacked", "OntoMapper", "KastorGraphOps", "RdfRef", "RdfHandle", "DefaultRdfHandle", "ShaclViolation", "ValidationResult")
        fileBuilder.addImport(CodegenConstants.RDF_PACKAGE, "Iri", "RdfHandle", "RdfResource")
        
        // Build wrapper class
        val classBuilder = TypeSpec.classBuilder(wrapperName)
            .addModifiers(INTERNAL)
            .addKdoc(
                "RDF-backed wrapper for %L\nGenerated from SHACL shape: %L",
                interfaceName, shape.shapeIri
            )
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("input", ClassName(CodegenConstants.RDF_PACKAGE, "RdfHandle"))
                    .addModifiers(PRIVATE)
                    .build()
            )
            .addSuperinterface(ClassName("", interfaceName))
            .addSuperinterface(ClassName(CodegenConstants.RUNTIME_PACKAGE, "RdfBacked"))
        
        // Known predicates set
        val knownIris = shape.properties.map { 
            CodeBlock.of("Iri(%S)", it.path)
        }
        val setType = KotlinPoetUtils.setOf(
            ClassName(CodegenConstants.RDF_PACKAGE, "Iri")
        )
        val knownIrisCode = knownIris.joinToString(", ") { it.toString() }
        classBuilder.addProperty(
            PropertySpec.builder("known", setType)
                .addModifiers(PRIVATE)
                .initializer("setOf(%L)", CodeBlock.of(knownIrisCode))
                .build()
        )
        
        // RDF handle property
        classBuilder.addProperty(
            PropertySpec.builder("rdf", ClassName(CodegenConstants.RDF_PACKAGE, "RdfHandle"))
                .addModifiers(OVERRIDE)
                .delegate(
                    CodeBlock.of(
                        "lazy(LazyThreadSafetyMode.PUBLICATION) {\n" +
                        "  if (input is DefaultRdfHandle) DefaultRdfHandle(input.node, input.graph, known) else input\n" +
                        "}"
                    )
                )
                .build()
        )
        
        // Generate property implementations
        shape.properties.forEach { property ->
            classBuilder.addProperty(generatePropertyImplementation(property, context))
        }
        
        // Add validation method
        when (validationMode) {
            ValidationMode.NONE -> { 
                // No validation code generated
            }
            ValidationMode.EXTERNAL -> {
                require(externalValidatorClass != null) {
                    "EXTERNAL validation mode requires externalValidatorClass to be specified"
                }
                classBuilder.addFunction(generateExternalValidation())
            }
            ValidationMode.EMBEDDED -> {
                classBuilder.addFunction(generateEmbeddedValidation(shape))
            }
        }
        
        // Companion object
        val companionBuilder = TypeSpec.companionObjectBuilder()
        
        // Property mappings
        val mappingEntries = shape.properties.map { property ->
            val jsonLdName = context.propertyMappings.entries
                .find { it.value.id.value == property.path }
                ?.key
                ?: property.name
            CodeBlock.of("%S to Iri(%S)", jsonLdName, property.path)
        }
        val mapType = KotlinPoetUtils.mapOf(
            String::class.asTypeName(),
            ClassName(CodegenConstants.RDF_PACKAGE, "Iri")
        )
        val mappingCode = mappingEntries.joinToString(", ") { it.toString() }
        companionBuilder.addProperty(
            PropertySpec.builder("propertyMappings", mapType)
                .addKdoc("Mapping metadata: JSON-LD property names → RDF predicate IRIs")
                .initializer("mapOf(%L)", CodeBlock.of(mappingCode))
                .build()
        )
        
        // Registry init
        companionBuilder.addInitializerBlock(
            CodeBlock.of(
                "OntoMapper.registry[%T::class.java] = { handle -> %T(handle) }",
                ClassName("", interfaceName),
                ClassName("", wrapperName)
            )
        )
        
        classBuilder.addType(companionBuilder.build())
        fileBuilder.addType(classBuilder.build())
        
        return fileBuilder.build()
    }

    private fun generateExternalValidation(): FunSpec {
        val validatorRef = externalValidatorClass?.takeIf { it.isNotBlank() }
        val functionBuilder = FunSpec.builder("validate")
            .returns(ClassName(CodegenConstants.RUNTIME_PACKAGE, "ValidationResult"))
        
        if (validatorRef != null) {
            functionBuilder.addStatement("return %T().validate(rdf.graph, rdf.node)", ClassName.bestGuess(validatorRef))
        } else {
            functionBuilder.addStatement("return rdf.validate()")
        }
        
        return functionBuilder.build()
    }

    private fun generateEmbeddedValidation(shape: ShaclShape): FunSpec {
        val functionBuilder = FunSpec.builder("validate")
            .returns(ClassName(CodegenConstants.RUNTIME_PACKAGE, "ValidationResult"))
            .addStatement("val violations = mutableListOf<%T>()", ClassName(CodegenConstants.RUNTIME_PACKAGE, "ShaclViolation"))
        
        shape.properties.forEach { property ->
            val pred = property.path
            val min = property.minCount
            val max = property.maxCount
            
            if (min != null || max != null) {
                val countExpr = if (property.targetClass != null) {
                    CodeBlock.of("KastorGraphOps.countObjectValues(rdf.graph, rdf.node, Iri(%S))", pred)
                } else {
                    CodeBlock.of("KastorGraphOps.countLiteralValues(rdf.graph, rdf.node, Iri(%S))", pred)
                }
                
                functionBuilder.addCode("\n")
                functionBuilder.addStatement("run {")
                functionBuilder.addStatement("  val count = %L", countExpr)
                
                min?.let {
                    functionBuilder.addStatement("  if (count < %L) violations.add(ShaclViolation(", it)
                    functionBuilder.addStatement("    focusNode = rdf.node as RdfResource,")
                    functionBuilder.addStatement("    shapeIri = %T.NodeShape,", ClassName(CodegenConstants.VOCAB_PACKAGE, "SHACL"))
                    functionBuilder.addStatement("    constraintIri = %T.minCount,", ClassName(CodegenConstants.VOCAB_PACKAGE, "SHACL"))
                    functionBuilder.addStatement("    path = Iri(%S),", pred)
                    functionBuilder.addStatement("    message = %S", "minCount $it violated")
                    functionBuilder.addStatement("  ))")
                }
                
                max?.let {
                    functionBuilder.addStatement("  if (count > %L) violations.add(ShaclViolation(", it)
                    functionBuilder.addStatement("    focusNode = rdf.node as RdfResource,")
                    functionBuilder.addStatement("    shapeIri = %T.NodeShape,", ClassName(CodegenConstants.VOCAB_PACKAGE, "SHACL"))
                    functionBuilder.addStatement("    constraintIri = %T.maxCount,", ClassName(CodegenConstants.VOCAB_PACKAGE, "SHACL"))
                    functionBuilder.addStatement("    path = Iri(%S),", pred)
                    functionBuilder.addStatement("    message = %S", "maxCount $it violated")
                    functionBuilder.addStatement("  ))")
                }
                
                functionBuilder.addStatement("}")
            }
        }
        
        functionBuilder.addStatement("return if (violations.isEmpty()) ValidationResult.Ok else ValidationResult.Violations(violations)")
        
        return functionBuilder.build()
    }

    private fun generatePropertyImplementation(
        property: ShaclProperty,
        context: JsonLdContext
    ): PropertySpec {
        val propertyName = property.name
        val kotlinType = TypeMapper.toKotlinType(property, context)
        
        val propertyBuilder = PropertySpec.builder(propertyName, kotlinType)
            .addModifiers(OVERRIDE)
            .addKdoc(
                "%L\nPath: %L",
                property.description, property.path
            )
        
        val initializer = if (property.targetClass != null) {
            generateObjectPropertyInitializer(property)
        } else {
            generateLiteralPropertyInitializer(property)
        }
        
        propertyBuilder.delegate(CodeBlock.builder()
            .add("lazy {\n")
            .add(initializer)
            .add("\n}")
            .build())
        
        return propertyBuilder.build()
    }

    private fun generateObjectPropertyInitializer(property: ShaclProperty): CodeBlock {
        val targetInterfaceName = NamingUtils.extractInterfaceName(property.targetClass!!)
        val path = property.path
        
        return if (property.maxCount == 1) {
            if (property.minCount != null && property.minCount > 0) {
                CodeBlock.of(
                    "KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(%S)) { child ->\n" +
                    "  OntoMapper.materialize(RdfRef(child, rdf.graph), %T::class.java)\n" +
                    "}.firstOrNull() ?: error(%S)",
                    path, ClassName("", targetInterfaceName), "Required object ${property.name} missing"
                )
            } else {
                CodeBlock.of(
                    "KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(%S)) { child ->\n" +
                    "  OntoMapper.materialize(RdfRef(child, rdf.graph), %T::class.java)\n" +
                    "}.firstOrNull()",
                    path, ClassName("", targetInterfaceName)
                )
            }
        } else {
            CodeBlock.of(
                "KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(%S)) { child ->\n" +
                "  OntoMapper.materialize(RdfRef(child, rdf.graph), %T::class.java)\n" +
                "}",
                path, ClassName("", targetInterfaceName)
            )
        }
    }

    private fun generateLiteralPropertyInitializer(property: ShaclProperty): CodeBlock {
        val baseType = when (property.datatype) {
            "http://www.w3.org/2001/XMLSchema#string" -> "String"
            "http://www.w3.org/2001/XMLSchema#int", "http://www.w3.org/2001/XMLSchema#integer" -> "Int"
            "http://www.w3.org/2001/XMLSchema#double", "http://www.w3.org/2001/XMLSchema#float" -> "Double"
            "http://www.w3.org/2001/XMLSchema#boolean" -> "Boolean"
            "http://www.w3.org/2001/XMLSchema#anyURI" -> "String"
            else -> "String"
        }
        
        val isSingleValue = property.maxCount == 1
        val isRequired = property.minCount != null && property.minCount > 0
        val path = property.path
        
        return if (isSingleValue) {
            if (isRequired) {
                requiredLiteralAccessor(baseType, path)
            } else {
                CodeBlock.of(
                    "KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(%S)).map { it.lexical }%L",
                    path, getSingleValueConversionMethod(baseType)
                )
            }
        } else {
            if (isRequired) {
                CodeBlock.of(
                    "KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(%S)).map { it.lexical }%L.ifEmpty { error(%S) }",
                    path, getConversionMethod(baseType), "Required literal ${property.name} missing"
                )
            } else {
                CodeBlock.of(
                    "KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(%S)).map { it.lexical }%L",
                    path, getConversionMethod(baseType)
                )
            }
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

    private fun requiredLiteralAccessor(kotlinType: String, path: String): CodeBlock {
        return when (kotlinType) {
            "Int" -> CodeBlock.of("KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(%S)).lexical.toInt()", path)
            "Double" -> CodeBlock.of("KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(%S)).lexical.toDouble()", path)
            "Boolean" -> CodeBlock.of("KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(%S)).lexical.toBooleanStrict()", path)
            else -> CodeBlock.of("KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(%S)).lexical", path)
        }
    }

}
