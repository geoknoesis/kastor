package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.model.ClassBuilderModel
import com.geoknoesis.kastor.gen.processor.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.model.InstanceDslRequest
import com.geoknoesis.kastor.gen.processor.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.model.OntologyClass
import com.geoknoesis.kastor.gen.processor.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.model.PropertyBuilderModel
import com.geoknoesis.kastor.gen.processor.model.PropertyConstraints
import com.geoknoesis.kastor.gen.processor.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.utils.CodegenConstants
import com.geoknoesis.kastor.gen.processor.utils.KotlinPoetUtils
import com.geoknoesis.kastor.gen.processor.utils.NamingUtils
import com.geoknoesis.kastor.gen.processor.utils.TypeMapper
import com.geoknoesis.kastor.gen.processor.extensions.collectRequiredImports
import com.geoknoesis.kastor.gen.processor.extensions.groupByTargetClass
import com.geoknoesis.kastor.gen.processor.utils.VocabularyMapper
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.KModifier.*
import com.squareup.kotlinpoet.CodeBlock

/**
 * Generator for instance DSL builders from ontology classes and SHACL shapes.
 * Creates type-safe DSL builders for creating RDF instances using KotlinPoet.
 */
internal class InstanceDslGenerator(
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
     * @throws InvalidConfigurationException if configuration is invalid
     *
     * @sample com.example.GenerateSkosDsl
     */
    fun generate(request: InstanceDslRequest): FileSpec {
        logger.info("Generating DSL '${request.dslName}' for ${request.ontologyModel.shapes.size} shapes")
        
        val classBuilders = buildClassBuilders(request.ontologyModel, request.options)
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
     * Builds ClassBuilderModel instances from ontology model.
     */
    private fun buildClassBuilders(
        model: OntologyModel,
        options: DslGenerationOptions
    ): List<ClassBuilderModel> {
        val shapeMap = model.shapes.groupByTargetClass()
        val classes = extractClasses(model)
        val classIris = classes.map { it.classIri }.toSet()
        
        val fromClasses = classes.mapNotNull { ontologyClass ->
            shapeMap[ontologyClass.classIri]?.let { shape ->
                buildClassBuilder(ontologyClass, shape, model.context, options)
            } ?: run {
                logger.warn("No SHACL shape found for class: ${ontologyClass.classIri}")
                null
            }
        }
        
        val fromShapes = model.shapes
            .filter { it.targetClass !in classIris }
            .map { buildClassBuilderFromShape(it, model.context, options) }
        
        return fromClasses + fromShapes
    }
    
    private fun extractClasses(model: OntologyModel): List<OntologyClass> {
        // Extract classes from shapes if no explicit ontology classes provided
        // This is a fallback - in practice, classes should come from the ontology
        return model.shapes.map { shape ->
            OntologyClass(
                classIri = shape.targetClass,
                className = VocabularyMapper.extractLocalName(shape.targetClass)
            )
        }
    }
    
    private fun buildClassBuilder(
        ontologyClass: OntologyClass,
        shape: ShaclShape,
        context: JsonLdContext,
        options: DslGenerationOptions
    ): ClassBuilderModel {
        val properties = buildPropertyBuilders(shape.properties, context, options)
        val builderName = NamingUtils.toCamelCase(ontologyClass.className)
        
        return ClassBuilderModel(
            className = ontologyClass.className,
            classIri = ontologyClass.classIri,
            builderName = builderName,
            properties = properties,
            shapeIri = shape.shapeIri
        )
    }
    
    private fun buildClassBuilderFromShape(
        shape: ShaclShape,
        context: JsonLdContext,
        options: DslGenerationOptions
    ): ClassBuilderModel {
        val className = VocabularyMapper.extractLocalName(shape.targetClass)
        val builderName = NamingUtils.toCamelCase(className)
        val properties = buildPropertyBuilders(shape.properties, context, options)
        
        return ClassBuilderModel(
            className = className,
            classIri = shape.targetClass,
            builderName = builderName,
            properties = properties,
            shapeIri = shape.shapeIri
        )
    }

    /**
     * Builds PropertyBuilderModel instances from SHACL properties.
     */
    private fun buildPropertyBuilders(
        properties: List<ShaclProperty>,
        context: JsonLdContext,
        @Suppress("UNUSED_PARAMETER") options: DslGenerationOptions
    ): List<PropertyBuilderModel> {
        return properties.map { property ->
            val kotlinType = TypeMapper.toKotlinType(property, context)
            val propertyName = determinePropertyName(property, options)
            val isRequired = (property.minCount ?: 0) >= 1
            val isList = property.maxCount == null || property.maxCount > 1
            
            PropertyBuilderModel(
                propertyName = propertyName,
                propertyIri = property.path,
                kotlinType = kotlinType,
                isRequired = isRequired,
                isList = isList,
                constraints = PropertyConstraints.from(property)
            )
        }
    }
    
    private fun determinePropertyName(
        property: ShaclProperty,
        options: DslGenerationOptions
    ): String {
        return when {
            options.naming.usePropertyNames && property.name.isNotEmpty() -> 
                NamingUtils.toCamelCase(property.name)
            else -> 
                NamingUtils.toCamelCase(VocabularyMapper.extractLocalName(property.path))
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
        val dslClassName = "${dslName.replaceFirstChar { it.uppercaseChar() }}Dsl"
        
        val fileBuilder = FileSpec.builder(packageName, "${dslClassName}")
            .addFileComment("GENERATED FILE - DO NOT EDIT")
            .addFileComment("Generated DSL for instance creation")
        
        // Add imports
        fileBuilder.addImport(CodegenConstants.RDF_PACKAGE, "RdfResource", "MutableRdfGraph", "Iri", "Literal", "Triple")
        fileBuilder.addImport(CodegenConstants.RDF_PROVIDER_PACKAGE, "MemoryGraph")
        fileBuilder.addImport(CodegenConstants.VOCAB_PACKAGE, "RDF", "XSD")
        fileBuilder.addImport(CodegenConstants.RUNTIME_PACKAGE, "ValidationException")
        
        // Add vocabulary imports
        requiredImports.forEach { importPackage ->
            val vocabName = importPackage.substringAfterLast(".")
            fileBuilder.addImport(importPackage, vocabName)
        }
        
        // Generate top-level DSL function
        val dslFunction = FunSpec.builder(dslName)
            .addKdoc("DSL for creating %L instances.\nGenerated from ontology and SHACL shapes.", dslName.uppercase())
            .addParameter("configure", LambdaTypeName.get(
                receiver = ClassName(packageName, dslClassName),
                returnType = Unit::class.asTypeName()
            ))
            .returns(ClassName(packageName, dslClassName))
            .addStatement("return %T().apply(configure)", ClassName(packageName, dslClassName))
            .build()
        
        fileBuilder.addFunction(dslFunction)
        fileBuilder.addType(generateMainDslClass(dslClassName, classBuilders, packageName, options))
        
        // Generate builder classes
        classBuilders.forEach { classBuilder ->
            fileBuilder.addType(generateBuilderClass(classBuilder, packageName, options))
        }
        
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
        
        val listType = KotlinPoetUtils.mutableListOf(
            ClassName(CodegenConstants.RDF_PACKAGE, "RdfResource")
        )
        classBuilder.addProperty(
            PropertySpec.builder("instances", listType)
                .addModifiers(PRIVATE)
                .initializer("mutableListOf<%T>()", ClassName(CodegenConstants.RDF_PACKAGE, "RdfResource"))
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
        val returnListType = KotlinPoetUtils.listOf(
            ClassName(CodegenConstants.RDF_PACKAGE, "RdfResource")
        )
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
            .addKdoc("Create a %L instance.\n\n@param iri The IRI of the %L\n@param configure Builder configuration\n@return The created %L resource",
                classBuilder.className, classBuilder.className.lowercase(), classBuilder.className.lowercase())
            .addParameter("iri", String::class)
            .addParameter("configure", LambdaTypeName.get(
                receiver = ClassName(packageName, builderClassName),
                returnType = Unit::class.asTypeName()
            ))
            .returns(ClassName(CodegenConstants.RDF_PACKAGE, "RdfResource"))
        
        functionBuilder.addStatement("val resource = %T(iri)", ClassName(CodegenConstants.RDF_PACKAGE, "Iri"))
        functionBuilder.addStatement("graph.addTriple(resource, %T.type, %L)", 
            ClassName(CodegenConstants.VOCAB_PACKAGE, "RDF"), classIriCodeBlock)
        functionBuilder.addStatement("val builder = %T(resource, graph)", ClassName(packageName, builderClassName))
        functionBuilder.addStatement("builder.configure()")
        
        if (options.validation.enabled) {
            functionBuilder.addStatement("builder.validate()")
        }
        
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
            .addKdoc("Builder for %L instances.", classBuilder.className)
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
        
        // Generate property methods
        classBuilder.properties.forEach { property ->
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

// Extension function moved to CollectionExtensions.kt
