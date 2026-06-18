package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.api.model.EnumModel
import com.geoknoesis.kastor.gen.processor.api.model.EnumMemberKind
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.utils.CodegenConstants
import com.geoknoesis.kastor.gen.processor.internal.utils.KotlinPoetUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.NamingUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.TypeMapper
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.KModifier.*

/**
 * Generator for Kotlin wrapper classes from SHACL shapes and JSON-LD context using KotlinPoet.
 * Creates RDF-backed wrapper implementations.
 */
class OntologyWrapperGenerator(
    private val logger: KSPLogger,
    private val validationMode: ValidationMode = ValidationMode.EMBEDDED,
    private val externalValidatorClass: String? = null
) {

    private var currentPackageName: String = ""

    /**
     * Generates Kotlin wrapper code from SHACL shapes.
     * 
     * @param ontologyModel The combined SHACL + JSON-LD model
     * @param packageName The target package name
     * @return Map of wrapper class names to generated FileSpec
     */
    fun generateWrappers(ontologyModel: OntologyModel, packageName: String): Map<String, FileSpec> {
        val wrappers = mutableMapOf<String, FileSpec>()
        val enumsByName = ontologyModel.enums.associateBy { it.name }

        ontologyModel.shapes.forEach { shape ->
            val interfaceName = NamingUtils.extractInterfaceName(shape.targetClass)
            val wrapperName = "${interfaceName}Wrapper"
            val fileSpec = generateWrapper(shape, ontologyModel.context, packageName, enumsByName)
            wrappers[wrapperName] = fileSpec

            logger.info("Generated wrapper: $wrapperName")
        }

        return wrappers
    }

    private fun generateWrapper(
        shape: ShaclShape,
        context: JsonLdContext,
        packageName: String,
        enumsByName: Map<String, EnumModel> = emptyMap(),
    ): FileSpec {
        val interfaceName = NamingUtils.extractInterfaceName(shape.targetClass)
        val wrapperName = "${interfaceName}Wrapper"
        
        currentPackageName = packageName
        val fileBuilder = FileSpec.builder(packageName, wrapperName)
            .addFileComment("GENERATED FILE - DO NOT EDIT")
            .addFileComment("Generated from SHACL shape: %L", shape.shapeIri)

        // Add imports
        fileBuilder.addImport(CodegenConstants.RUNTIME_PACKAGE, "RdfBacked", "OntoMapper", "KastorGraphOps", "RdfRef", "RdfHandle", "DefaultRdfHandle", "ShaclViolation", "ValidationResult")
        fileBuilder.addImport(CodegenConstants.RDF_PACKAGE, "Iri", "RdfResource", "MutableRdfGraph", "BlankNode", "getCbdClosure")
        
        // Build wrapper class
        val classBuilder = TypeSpec.classBuilder(wrapperName)
            .addModifiers(INTERNAL)
            .addKdoc(
                "RDF-backed wrapper for %L\nGenerated from SHACL shape: %L",
                interfaceName, shape.shapeIri
            )
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("input", ClassName(CodegenConstants.RUNTIME_PACKAGE, "RdfHandle"))
                    .addModifiers(PRIVATE)
                    .build()
            )
            .addSuperinterface(ClassName(packageName, interfaceName))
            .addSuperinterface(ClassName(CodegenConstants.RUNTIME_PACKAGE, "RdfBacked"))
        
        // Known predicates set - sort by path IRI for deterministic output
        val knownIris = shape.properties
            .sortedBy { it.path }
            .map { 
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
            PropertySpec.builder("rdf", ClassName(CodegenConstants.RUNTIME_PACKAGE, "RdfHandle"))
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
        
        // Generate property implementations - sort by path IRI for deterministic output
        shape.properties
            .sortedBy { it.path }
            .forEach { property ->
                classBuilder.addProperty(generatePropertyImplementation(property, context, enumsByName))
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
        
        // Add writeToGraph method
        classBuilder.addFunction(generateWriteToGraph())
        
        // Companion object
        val companionBuilder = TypeSpec.companionObjectBuilder()
        
        // Property mappings - sort by path IRI for deterministic output
        val mappingEntries = shape.properties
            .sortedBy { it.path }
            .map { property ->
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
                ClassName(packageName, interfaceName),
                ClassName(packageName, wrapperName)
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

        val shaclCn = ClassName(CodegenConstants.VOCAB_PACKAGE, "SHACL")
        // Emits the shared trailer of a ShaclViolation(...) constructor call.
        // [constraintTerm] is the SHACL member name (e.g. "pattern", "minLength", "`in`").
        fun violationTail(constraintTerm: String, pathPred: String, message: String) {
            functionBuilder.addStatement("    focusNode = rdf.node as RdfResource,")
            functionBuilder.addStatement("    shapeIri = %T.NodeShape,", shaclCn)
            functionBuilder.addStatement("    constraintIri = %T.%L,", shaclCn, constraintTerm)
            functionBuilder.addStatement("    path = Iri(%S),", pathPred)
            functionBuilder.addStatement("    message = %S", message)
            functionBuilder.addStatement("  ))")
        }

        // Sort properties by path IRI for deterministic output
        shape.properties
            .sortedBy { it.path }
            .forEach { property ->
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

            // Value constraints apply to literal-valued properties only.
            if (property.targetClass == null) {
                val literals = CodeBlock.of(
                    "KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(%S))", pred
                )

                property.pattern?.let { pat ->
                    functionBuilder.addCode("\n")
                    functionBuilder.addStatement("%L.forEach { lit ->", literals)
                    functionBuilder.addStatement("  if (!%T(%S).matches(lit.lexical)) violations.add(ShaclViolation(", Regex::class, pat)
                    violationTail("pattern", pred, "pattern $pat violated")
                    functionBuilder.addStatement("}")
                }

                if (property.minLength != null || property.maxLength != null) {
                    functionBuilder.addCode("\n")
                    functionBuilder.addStatement("%L.forEach { lit ->", literals)
                    property.minLength?.let {
                        functionBuilder.addStatement("  if (lit.lexical.length < %L) violations.add(ShaclViolation(", it)
                        violationTail("minLength", pred, "minLength $it violated")
                    }
                    property.maxLength?.let {
                        functionBuilder.addStatement("  if (lit.lexical.length > %L) violations.add(ShaclViolation(", it)
                        violationTail("maxLength", pred, "maxLength $it violated")
                    }
                    functionBuilder.addStatement("}")
                }

                if (property.minInclusive != null || property.maxInclusive != null ||
                    property.minExclusive != null || property.maxExclusive != null
                ) {
                    functionBuilder.addCode("\n")
                    functionBuilder.addStatement("%L.forEach { lit ->", literals)
                    functionBuilder.addStatement("  val num = lit.lexical.toDoubleOrNull()")
                    functionBuilder.addStatement("  if (num != null) {")
                    property.minInclusive?.let {
                        functionBuilder.addStatement("    if (num < %L) violations.add(ShaclViolation(", it)
                        violationTail("minInclusive", pred, "minInclusive $it violated")
                    }
                    property.maxInclusive?.let {
                        functionBuilder.addStatement("    if (num > %L) violations.add(ShaclViolation(", it)
                        violationTail("maxInclusive", pred, "maxInclusive $it violated")
                    }
                    property.minExclusive?.let {
                        functionBuilder.addStatement("    if (num <= %L) violations.add(ShaclViolation(", it)
                        violationTail("minExclusive", pred, "minExclusive $it violated")
                    }
                    property.maxExclusive?.let {
                        functionBuilder.addStatement("    if (num >= %L) violations.add(ShaclViolation(", it)
                        violationTail("maxExclusive", pred, "maxExclusive $it violated")
                    }
                    functionBuilder.addStatement("  }")
                    functionBuilder.addStatement("}")
                }

                property.inValues?.takeIf { it.isNotEmpty() }?.let { values ->
                    val allowed = values.joinToString(", ") { "\"$it\"" }
                    functionBuilder.addCode("\n")
                    functionBuilder.addStatement("%L.forEach { lit ->", literals)
                    functionBuilder.addStatement("  if (lit.lexical !in listOf(%L)) violations.add(ShaclViolation(", allowed)
                    violationTail("`in`", pred, "in violated")
                    functionBuilder.addStatement("}")
                }
            }

            // IRI-membered sh:in (enum) membership check on object values
            property.inValuesTyped?.takeIf { tv -> tv.isNotEmpty() && tv.all { it.isIri } }?.let { ivs ->
                val allowed = ivs.joinToString(", ") { "Iri(\"${it.value}\")" }
                functionBuilder.addCode("\n")
                functionBuilder.addStatement("KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(%S)) { it }.forEach { obj ->", pred)
                functionBuilder.addStatement("  if (obj !in listOf(%L)) violations.add(ShaclViolation(", allowed)
                violationTail("`in`", pred, "in violated")
                functionBuilder.addStatement("}")
            }
        }

        functionBuilder.addStatement("return if (violations.isEmpty()) ValidationResult.Ok else ValidationResult.Violations(violations)")
        
        return functionBuilder.build()
    }

    private fun generatePropertyImplementation(
        property: ShaclProperty,
        context: JsonLdContext,
        enumsByName: Map<String, EnumModel> = emptyMap(),
    ): PropertySpec {
        val propertyName = property.name
        val kotlinType = TypeMapper.toKotlinType(property, context)

        val propertyBuilder = PropertySpec.builder(propertyName, kotlinType)
            .addModifiers(OVERRIDE)
            .addKdoc(
                "%L\nPath: %L",
                property.description, property.path
            )

        val initializer = when {
            property.enumName != null -> generateEnumPropertyInitializer(property, enumsByName.getValue(property.enumName))
            property.targetClass != null -> generateObjectPropertyInitializer(property)
            else -> generateLiteralPropertyInitializer(property)
        }

        propertyBuilder.delegate(CodeBlock.builder()
            .add("lazy {\n")
            .add(initializer)
            .add("\n}")
            .build())

        return propertyBuilder.build()
    }

    private fun generateEnumPropertyInitializer(property: ShaclProperty, enum: EnumModel): CodeBlock {
        val path = property.path
        val name = enum.name
        val single = property.maxCount == 1
        val required = property.minCount != null && property.minCount!! > 0
        return if (enum.memberKind == EnumMemberKind.IRI) {
            val base = CodeBlock.of(
                "KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(%S)) { child ->\n" +
                "  $name.from(child as Iri)\n" +
                "}", path)
            cardinalityWrap(base, single, required, property.name)
        } else {
            val base = CodeBlock.of(
                "KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(%S)).map { $name.from(it.lexical) }", path)
            cardinalityWrap(base, single, required, property.name)
        }
    }

    private fun cardinalityWrap(base: CodeBlock, single: Boolean, required: Boolean, propName: String): CodeBlock =
        when {
            !single -> base // List<Enum>
            required -> CodeBlock.of("%L.firstOrNull() ?: error(%S)", base, "Required enum $propName missing")
            else -> CodeBlock.of("%L.firstOrNull()", base)
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
                    path, ClassName(currentPackageName, targetInterfaceName), "Required object ${property.name} missing"
                )
            } else {
                CodeBlock.of(
                    "KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(%S)) { child ->\n" +
                    "  OntoMapper.materialize(RdfRef(child, rdf.graph), %T::class.java)\n" +
                    "}.firstOrNull()",
                    path, ClassName(currentPackageName, targetInterfaceName)
                )
            }
        } else {
            CodeBlock.of(
                "KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(%S)) { child ->\n" +
                "  OntoMapper.materialize(RdfRef(child, rdf.graph), %T::class.java)\n" +
                "}",
                path, ClassName(currentPackageName, targetInterfaceName)
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

    private fun generateWriteToGraph(): FunSpec {
        return FunSpec.builder("writeToGraph")
            .addKdoc(
                "Writes the CBD (Concise Bounded Description) closure of this instance to the target graph.\n\n" +
                "CBD includes:\n" +
                "1. All triples where this resource is the subject (direct properties)\n" +
                "2. Recursively, for any blank node object, all triples where that blank node is the subject\n\n" +
                "This method extracts the complete resource description from the backing graph and writes it\n" +
                "to the target graph, following blank nodes recursively but not following IRIs.\n\n" +
                "@param targetGraph The mutable graph to write triples to\n" +
                "@param subject Optional subject IRI. If not provided, uses rdf.node as Iri\n" +
                "@throws IllegalArgumentException if subject is required but not available"
            )
            .addParameter(
                ParameterSpec.builder("targetGraph", ClassName(CodegenConstants.RDF_PACKAGE, "MutableRdfGraph"))
                    .build()
            )
            .addParameter(
                ParameterSpec.builder("subject", ClassName(CodegenConstants.RDF_PACKAGE, "Iri").copy(nullable = true))
                    .defaultValue("null")
                    .build()
            )
            .addCode(
                CodeBlock.builder()
                    .addStatement("val originalSubject = (rdf.node as? %T)", ClassName(CodegenConstants.RDF_PACKAGE, "Iri"))
                    .addStatement("  ?: (rdf.node as? %T)", ClassName(CodegenConstants.RDF_PACKAGE, "RdfResource"))
                    .addStatement("  ?: throw IllegalArgumentException(%S)", "Subject resource required")
                    .addStatement("")
                    .addStatement("// Extract CBD closure from backing graph using original subject")
                    .addStatement("val cbdTriples = rdf.graph.getCbdClosure(originalSubject)")
                    .addStatement("")
                    .addStatement("// If a different subject is provided, remap triples")
                    .addStatement("val triplesToWrite = if (subject != null && subject != originalSubject) {")
                    .addStatement("  cbdTriples.map { triple ->")
                    .addStatement("    if (triple.subject == originalSubject) {")
                    .addStatement("      %T(subject, triple.predicate, triple.obj)", ClassName(CodegenConstants.RDF_PACKAGE, "RdfTriple"))
                    .addStatement("    } else {")
                    .addStatement("      triple")
                    .addStatement("    }")
                    .addStatement("  }")
                    .addStatement("} else {")
                    .addStatement("  cbdTriples")
                    .addStatement("}")
                    .addStatement("")
                    .addStatement("// Write to target graph")
                    .addStatement("targetGraph.addTriples(triplesToWrite)")
                    .build()
            )
            .build()
    }

}

