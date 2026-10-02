package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.processor.api.exceptions.MissingShapeException
import com.geoknoesis.kastor.gen.processor.api.exceptions.InvalidConfigurationException

import com.geoknoesis.kastor.gen.processor.api.model.ClassBuilderModel
import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.PropertyBuilderModel
import com.geoknoesis.kastor.gen.processor.api.model.PropertyConstraints
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.utils.Cardinality
import com.geoknoesis.kastor.gen.processor.internal.utils.CodegenConstants
import com.geoknoesis.kastor.gen.processor.internal.utils.EffectiveMember
import com.geoknoesis.kastor.gen.processor.internal.utils.GenerationNames
import com.geoknoesis.kastor.gen.processor.internal.utils.ShaclPatterns
import com.geoknoesis.kastor.gen.processor.internal.utils.KotlinPoetUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.NamingUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.TypeMapper
import com.geoknoesis.kastor.gen.processor.internal.utils.ValueKind
import com.geoknoesis.kastor.gen.processor.api.extensions.collectRequiredImports
import com.geoknoesis.kastor.gen.processor.internal.utils.VocabularyMapper
import com.geoknoesis.kastor.gen.processor.internal.utils.kdocText
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.KModifier.*
import com.squareup.kotlinpoet.CodeBlock

/**
 * Generator for instance DSL builders from ontology classes and SHACL shapes.
 * Creates type-safe DSL builders for creating RDF instances using KotlinPoet.
 */
public class InstanceDslGenerator(
    private val logger: KSPLogger
) {
    private val propertyMethodGenerator = PropertyMethodGenerator(logger)
    private val validationCodeGenerator = ValidationCodeGenerator(logger)

    /**
     * Generates DSL code for creating RDF instances.
     *
     * This method generates a complete Kotlin DSL file that provides type-safe builders
     * for creating instances of classes defined in the ontology model. The generated DSL
     * includes builder methods for each class, property setters with validation, and
     * a main DSL entry point.
     *
     * @param request Generation request containing ontology model, options, and target package
     * @return Generated FileSpec representing the DSL file
     * @throws MissingShapeException if a required SHACL shape is missing
     * @throws InvalidConfigurationException if configuration is invalid (including name collisions)
     *
     */
    public fun generate(request: InstanceDslRequest): FileSpec {
        logger.info("Generating DSL '${request.dslName}' for ${request.ontologyModel.shapes.size} shapes")
        GenerationNames.checkCollisions(request.ontologyModel)

        val classBuilders = buildClassBuilders(request.ontologyModel, request.options, request.enumPackage ?: request.packageName)
        val requiredImports = classBuilders.collectRequiredImports()

        return generateDslFile(
            request.dslName,
            classBuilders,
            request.packageName,
            requiredImports,
            request.options
        )
    }

    /**
     * Builds ClassBuilderModel instances from ontology model (one per shape, sorted by class IRI).
     */
    private fun buildClassBuilders(
        model: OntologyModel,
        options: DslGenerationOptions,
        enumPackage: String,
    ): List<ClassBuilderModel> {
        val enumsByName = model.enums.associateBy { it.name }
        val members = GenerationNames.effectiveMembers(model, GenerationNames.superTypes(model)) { logger.warn(it) }
        val knownTypes = GenerationNames.knownTypes(model)
        return model.shapes
            .sortedBy { it.targetClass }
            .map { shape ->
                val className = NamingUtils.domainName(shape.targetClass, model.context)
                ClassBuilderModel(
                    className = className,
                    classIri = shape.targetClass,
                    builderName = NamingUtils.toMemberIdentifier(className),
                    // One setter per path; aliases (a path inherited under two names) write the same triples.
                    properties = buildPropertyBuilders(
                        members[shape.targetClass].orEmpty().filter { it.primaryForPath }, model.context, options, enumsByName, knownTypes,
                        enumPackage,
                    ),
                    shapeIri = shape.shapeIri
                )
            }
    }

    /**
     * Builds PropertyBuilderModel instances from SHACL properties.
     */
    private fun buildPropertyBuilders(
        members: List<EffectiveMember>,
        context: JsonLdContext,
        options: DslGenerationOptions,
        enumsByName: Map<String, com.geoknoesis.kastor.gen.processor.api.model.EnumModel>,
        knownTypes: Set<String>,
        enumPackage: String,
    ): List<PropertyBuilderModel> {
        return members.map { member ->
            val property = member.typing
            val kotlinType = TypeMapper.toKotlinType(property, context)
            val enumModel = property.enumName?.let { enumName ->
                val found = enumsByName[enumName]
                if (found == null) {
                    logger.warn("DSL: enumName '$enumName' not found in model.enums; falling back to String setter")
                }
                found
            }
            val kind = TypeMapper.valueKind(property, context, knownTypes)

            PropertyBuilderModel(
                propertyName = determinePropertyName(property, options),
                propertyIri = property.path,
                kotlinType = kotlinType,
                isRequired = Cardinality.isRequired(member.constraints),
                isList = Cardinality.isList(property),
                constraints = PropertyConstraints.from(member.constraints),
                // Qualified, so setters compile when the DSL and the enums are generated into different packages.
                enumName = enumModel?.name?.let { "$enumPackage.$it" },
                enumMemberKind = enumModel?.memberKind,
                datatype = if (kind == ValueKind.LITERAL || kind == ValueKind.ENUM) property.datatype else null,
                isIriValued = kind == ValueKind.IRI || kind == ValueKind.OBJECT,
            )
        }
    }

    private fun determinePropertyName(
        property: ShaclProperty,
        options: DslGenerationOptions
    ): String {
        return when {
            options.naming.usePropertyNames && property.name.isNotEmpty() -> NamingUtils.propertyName(property)
            else -> NamingUtils.toMemberIdentifier(VocabularyMapper.extractLocalName(property.path))
        }
    }

    /**
     * Generates the complete DSL file using KotlinPoet.
     */
    private fun generateDslFile(
        dslName: String,
        classBuilders: List<ClassBuilderModel>,
        packageName: String,
        requiredImports: Set<String>,
        options: DslGenerationOptions
    ): FileSpec {
        val dslClassName = "${NamingUtils.toTypeIdentifier(dslName)}Dsl"
        val dslFunctionName = NamingUtils.toMemberIdentifier(dslName)

        val fileBuilder = FileSpec.builder(packageName, dslClassName)
            .addFileComment("GENERATED FILE - DO NOT EDIT")
            .addFileComment("Generated DSL for instance creation")

        // Add imports
        fileBuilder.addImport(CodegenConstants.RDF_PACKAGE, "RdfResource", "MutableRdfGraph", "Iri", "Literal", "RdfTriple")
        fileBuilder.addImport(CodegenConstants.RDF_PROVIDER_PACKAGE, "MemoryGraph")
        fileBuilder.addImport(CodegenConstants.VOCAB_PACKAGE, "RDF", "XSD")
        fileBuilder.addImport(CodegenConstants.RUNTIME_PACKAGE, "ValidationException")

        // Add vocabulary imports
        requiredImports.forEach { importPackage ->
            val vocabName = importPackage.substringAfterLast(".")
            fileBuilder.addImport(importPackage, vocabName)
        }

        // Generate top-level DSL function
        val dslFunction = FunSpec.builder(dslFunctionName)
            .addKdoc("%L", kdocText("DSL for creating ${dslName.uppercase()} instances.\nGenerated from ontology and SHACL shapes."))
            .addParameter("configure", LambdaTypeName.get(
                receiver = ClassName(packageName, dslClassName),
                returnType = Unit::class.asTypeName()
            ))
            .returns(ClassName(packageName, dslClassName))
            .addStatement("return %T().apply(configure)", ClassName(packageName, dslClassName))
            .build()

        fileBuilder.addFunction(dslFunction)
        // MutableRdfGraph only exposes addTriple(RdfTriple); setters use this private 3-argument helper.
        fileBuilder.addFunction(
            FunSpec.builder("addTriple")
                .addModifiers(PRIVATE)
                .receiver(ClassName(CodegenConstants.RDF_PACKAGE, "MutableRdfGraph"))
                .addParameter("subject", ClassName(CodegenConstants.RDF_PACKAGE, "RdfResource"))
                .addParameter("predicate", ClassName(CodegenConstants.RDF_PACKAGE, "Iri"))
                .addParameter("obj", ClassName(CodegenConstants.RDF_PACKAGE, "RdfTerm"))
                .addStatement("addTriple(%T(subject, predicate, obj))", ClassName(CodegenConstants.RDF_PACKAGE, "RdfTriple"))
                .build()
        )
        fileBuilder.addType(generateMainDslClass(dslClassName, classBuilders, packageName, options))

        // Generate builder classes
        classBuilders.forEach { classBuilder ->
            fileBuilder.addType(generateBuilderClass(classBuilder, packageName, options))
        }

        // sh:pattern regexes referenced by setters and validate(): compiled lazily once per pattern, not per call.
        classBuilders.flatMap { it.properties }
            .flatMap { p -> p.constraints.patterns }
            .distinct()
            .sortedBy { (pattern, flags) -> ShaclPatterns.constantName(pattern, flags) }
            .forEach { (pattern, flags) -> fileBuilder.addProperty(ShaclPatterns.lazyProperty(pattern, flags)) }

        return fileBuilder.build()
    }

    /**
     * Generates the main DSL class.
     */
    private fun generateMainDslClass(
        dslClassName: String,
        classBuilders: List<ClassBuilderModel>,
        packageName: String,
        options: DslGenerationOptions
    ): TypeSpec {
        val classBuilder = TypeSpec.classBuilder(dslClassName)
            .addModifiers(PUBLIC)

        // Add properties
        classBuilder.addProperty(
            PropertySpec.builder("graph", ClassName(CodegenConstants.RDF_PROVIDER_PACKAGE, "MemoryGraph"))
                .addModifiers(PRIVATE)
                .initializer("MemoryGraph()")
                .build()
        )

        val rdfResourceType = ClassName(CodegenConstants.RDF_PACKAGE, "RdfResource")
        val listType = KotlinPoetUtils.mutableListOf(rdfResourceType)
        classBuilder.addProperty(
            PropertySpec.builder("instances", listType)
                .addModifiers(PRIVATE)
                .initializer("mutableListOf<%T>()", rdfResourceType)
                .build()
        )

        // Add builder methods for each class
        classBuilders.forEach { classBuilderModel ->
            classBuilder.addFunction(generateBuilderMethod(classBuilderModel, packageName, options))
        }

        // Add build method
        classBuilder.addFunction(
            FunSpec.builder("build")
                .addKdoc("Get the generated graph.")
                .returns(ClassName(CodegenConstants.RDF_PACKAGE, "MutableRdfGraph"))
                .addStatement("return %L", "graph")
                .build()
        )

        // Add instances method
        val returnListType = KotlinPoetUtils.listOf(rdfResourceType)
        classBuilder.addFunction(
            FunSpec.builder("instances")
                .addKdoc("Get all created instances.")
                .returns(returnListType)
                .addStatement("return %L.toList()", "instances")
                .build()
        )

        return classBuilder.build()
    }

    /**
     * Generates a builder method in the main DSL class.
     */
    private fun generateBuilderMethod(
        classBuilder: ClassBuilderModel,
        packageName: String,
        options: DslGenerationOptions
    ): FunSpec {
        val builderClassName = "${classBuilder.className}Builder"
        val classIriCodeBlock = CodegenConstants.iriConstant(classBuilder.classIri)

        val functionBuilder = FunSpec.builder(classBuilder.builderName)
            .addKdoc(
                "%L",
                kdocText(
                    "Create a ${classBuilder.className} instance.\n\n@param iri The IRI of the ${classBuilder.className.lowercase()}\n" +
                        "@param configure Builder configuration\n@return The created ${classBuilder.className.lowercase()} resource"
                )
            )
            .addParameter("iri", String::class)
            .addParameter("configure", LambdaTypeName.get(
                receiver = ClassName(packageName, builderClassName),
                returnType = Unit::class.asTypeName()
            ))
            .returns(ClassName(CodegenConstants.RDF_PACKAGE, "RdfResource"))

        functionBuilder.addStatement("val resource = %T(iri)", ClassName(CodegenConstants.RDF_PACKAGE, "Iri"))
        // The instance is built in a scratch graph and only written to the DSL's graph once configure and validate
        // succeeded: a rejected value or a failed validation leaves no triple of it behind. The scratch graph starts
        // with what the graph already says about the resource, so a second block for one resource is validated
        // together with the first.
        functionBuilder.addStatement("val scratch = %T()", ClassName(CodegenConstants.RDF_PROVIDER_PACKAGE, "MemoryGraph"))
        functionBuilder.addStatement("scratch.addTriples(graph.find(resource))")
        functionBuilder.addStatement("scratch.addTriple(resource, %T.type, %L)",
            ClassName(CodegenConstants.VOCAB_PACKAGE, "RDF"), classIriCodeBlock)
        functionBuilder.addStatement("val builder = %T(resource, scratch)", ClassName(packageName, builderClassName))
        functionBuilder.addStatement("builder.configure()")

        if (options.validation.enabled) {
            functionBuilder.addStatement("builder.validate()")
        }

        functionBuilder.addStatement("graph.addTriples(scratch.getTriples())")
        functionBuilder.addStatement("instances.add(resource)")
        functionBuilder.addStatement("return resource")

        return functionBuilder.build()
    }

    /**
     * Generates a builder class for a specific ontology class.
     */
    private fun generateBuilderClass(
        classBuilder: ClassBuilderModel,
        @Suppress("UNUSED_PARAMETER") packageName: String,
        options: DslGenerationOptions
    ): TypeSpec {
        val builderClassName = "${classBuilder.className}Builder"
        val classBuilderSpec = TypeSpec.classBuilder(builderClassName)
            .addModifiers(PUBLIC)
            .addKdoc("%L", kdocText("Builder for ${classBuilder.className} instances."))
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("resource", ClassName(CodegenConstants.RDF_PACKAGE, "RdfResource"))
                    .addParameter("graph", ClassName(CodegenConstants.RDF_PACKAGE, "MutableRdfGraph"))
                    .build()
            )
            .addProperty(
                PropertySpec.builder("resource", ClassName(CodegenConstants.RDF_PACKAGE, "RdfResource"))
                    .addModifiers(PRIVATE)
                    .initializer("resource")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("graph", ClassName(CodegenConstants.RDF_PACKAGE, "MutableRdfGraph"))
                    .addModifiers(PRIVATE)
                    .initializer("graph")
                    .build()
            )

        // Generate property methods - sort by propertyIri for deterministic output
        classBuilder.properties
            .sortedBy { it.propertyIri }
            .forEach { property ->
            classBuilderSpec.addFunctions(
                propertyMethodGenerator.generatePropertyMethods(property, options)
            )
        }

        // Generate validation method
        if (options.validation.enabled) {
            classBuilderSpec.addFunction(
                validationCodeGenerator.generateValidationMethod(classBuilder)
            )
        }

        return classBuilderSpec.build()
    }
}
