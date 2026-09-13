package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.api.model.EnumMemberKind
import com.geoknoesis.kastor.gen.processor.api.model.EnumModel
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.utils.Cardinality
import com.geoknoesis.kastor.gen.processor.internal.utils.CodegenConstants
import com.geoknoesis.kastor.gen.processor.internal.utils.GenerationNames
import com.geoknoesis.kastor.gen.processor.internal.utils.KotlinPoetUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.NamingUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.TypeMapper
import com.geoknoesis.kastor.gen.processor.internal.utils.ValueKind
import com.geoknoesis.kastor.gen.processor.internal.utils.kdocText
import com.geoknoesis.kastor.gen.processor.internal.utils.regexCode
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.KModifier.*

/**
 * Generator for Kotlin wrapper classes from SHACL shapes and JSON-LD context using KotlinPoet.
 * Creates RDF-backed wrapper implementations.
 *
 * The generator is stateless: all per-call information travels in an immutable context, so one instance
 * can be reused (also concurrently) for different models and packages.
 */
public class OntologyWrapperGenerator(
    private val logger: KSPLogger,
    private val validationMode: ValidationMode = ValidationMode.EMBEDDED,
    private val externalValidatorClass: String? = null
) {

    private class Ctx(
        val model: OntologyModel,
        val packageName: String,
        val domainPackage: String,
        val knownTypes: Set<String>?,
        val enumsByName: Map<String, EnumModel>,
        val supers: Map<String, List<ShaclShape>>,
    )

    private val runtime = CodegenConstants.RUNTIME_PACKAGE
    private val iriClass = ClassName(CodegenConstants.RDF_PACKAGE, "Iri")
    private val graphOps = ClassName(runtime, "KastorGraphOps")

    /**
     * Generates Kotlin wrapper code from SHACL shapes.
     *
     * @param ontologyModel The combined SHACL + JSON-LD model
     * @param packageName The target package name
     * @param interfacePackage package of the generated domain interfaces and enums
     * @param fallbackUnshapedToIri when true, `sh:class` targets without a shape are exposed as IRI strings
     * @return Map of wrapper class names to generated FileSpec
     */
    public fun generateWrappers(ontologyModel: OntologyModel, packageName: String, interfacePackage: String = packageName, fallbackUnshapedToIri: Boolean = false): Map<String, FileSpec> {
        GenerationNames.checkCollisions(ontologyModel)
        val ctx = Ctx(
            model = ontologyModel,
            packageName = packageName,
            domainPackage = interfacePackage,
            knownTypes = if (fallbackUnshapedToIri) GenerationNames.knownTypes(ontologyModel) else null,
            enumsByName = ontologyModel.enums.associateBy { it.name },
            supers = GenerationNames.superTypes(ontologyModel),
        )
        val wrappers = sortedMapOf<String, FileSpec>()

        ontologyModel.shapes.sortedBy { it.targetClass }.forEach { shape ->
            val wrapperName = "${NamingUtils.domainName(shape.targetClass, ontologyModel.context)}Wrapper"
            wrappers[wrapperName] = generateWrapper(shape, ctx)
            logger.info("Generated wrapper: $wrapperName")
        }

        return wrappers
    }

    private fun generateWrapper(shape: ShaclShape, ctx: Ctx): FileSpec {
        val context = ctx.model.context
        val interfaceName = NamingUtils.domainName(shape.targetClass, context)
        val wrapperName = "${interfaceName}Wrapper"
        val properties = GenerationNames.effectiveProperties(shape, ctx.supers)

        val fileBuilder = FileSpec.builder(ctx.packageName, wrapperName)
            .addFileComment("GENERATED FILE - DO NOT EDIT")
            .addFileComment("Generated from SHACL shape: %L", shape.shapeIri)

        // Add imports
        fileBuilder.addImport(runtime, "RdfBacked", "OntoMapper", "KastorGraphOps", "RdfRef", "RdfHandle", "DefaultRdfHandle", "ShaclViolation", "ValidationResult")
        fileBuilder.addImport(CodegenConstants.RDF_PACKAGE, "Iri", "RdfResource", "MutableRdfGraph", "BlankNode", "getCbdClosure")

        // Build wrapper class
        val classBuilder = TypeSpec.classBuilder(wrapperName)
            .addModifiers(INTERNAL)
            .addKdoc("%L", kdocText("RDF-backed wrapper for $interfaceName\nGenerated from SHACL shape: ${shape.shapeIri}"))
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("input", ClassName(runtime, "RdfHandle"))
                    .addModifiers(PRIVATE)
                    .build()
            )
            .addSuperinterface(ClassName(ctx.domainPackage, interfaceName))
            .addSuperinterface(ClassName(runtime, "RdfBacked"))

        // Known predicates set - sorted by path IRI for deterministic output
        classBuilder.addProperty(
            PropertySpec.builder("known", KotlinPoetUtils.setOf(iriClass))
                .addModifiers(PRIVATE)
                .initializer("setOf(%L)", properties.map { CodeBlock.of("Iri(%S)", it.path) }.joinToCode(", "))
                .build()
        )

        // RDF handle property
        classBuilder.addProperty(
            PropertySpec.builder("rdf", ClassName(runtime, "RdfHandle"))
                .addModifiers(OVERRIDE)
                .delegate(
                    CodeBlock.of(
                        "lazy(LazyThreadSafetyMode.PUBLICATION) {\n" +
                        "  if (input is DefaultRdfHandle) input.withKnownPredicates(known) else input\n" +
                        "}"
                    )
                )
                .build()
        )

        properties.forEach { property ->
            classBuilder.addProperty(generatePropertyImplementation(property, ctx))
        }

        val companionBuilder = TypeSpec.companionObjectBuilder()

        when (validationMode) {
            ValidationMode.NONE -> Unit
            ValidationMode.EXTERNAL -> {
                require(externalValidatorClass != null) {
                    "EXTERNAL validation mode requires externalValidatorClass to be specified"
                }
                classBuilder.addFunction(generateExternalValidation())
            }
            ValidationMode.EMBEDDED -> {
                classBuilder.addFunction(generateEmbeddedValidation(shape, properties, ctx, companionBuilder))
            }
        }

        classBuilder.addFunction(generateWriteToGraph())

        // Property mappings - sorted by path IRI for deterministic output
        val mappingEntries = properties.map { property ->
            val jsonLdName = context.propertyMappings.entries
                .filter { it.value.id.value == property.path }
                .minByOrNull { it.key }
                ?.key
                ?: property.name
            CodeBlock.of("%S to Iri(%S)", jsonLdName, property.path)
        }
        companionBuilder.addProperty(
            PropertySpec.builder("propertyMappings", KotlinPoetUtils.mapOf(String::class.asTypeName(), iriClass))
                .addKdoc("Mapping metadata: JSON-LD property names → RDF predicate IRIs")
                .initializer("mapOf(%L)", mappingEntries.joinToCode(", "))
                .build()
        )

        companionBuilder.addInitializerBlock(
            CodeBlock.builder().addStatement(
                "OntoMapper.register(%T::class.java) { handle -> %T(handle) }",
                ClassName(ctx.domainPackage, interfaceName),
                ClassName(ctx.packageName, wrapperName)
            ).build()
        )

        classBuilder.addType(companionBuilder.build())
        fileBuilder.addType(classBuilder.build())

        return fileBuilder.build()
    }

    private fun generateExternalValidation(): FunSpec {
        val validatorRef = externalValidatorClass?.takeIf { it.isNotBlank() }
        val functionBuilder = FunSpec.builder("validate")
            .returns(ClassName(runtime, "ValidationResult"))

        if (validatorRef != null) {
            functionBuilder.addStatement("return %T().validate(rdf.graph, rdf.node)", ClassName.bestGuess(validatorRef))
        } else {
            functionBuilder.addStatement("return rdf.validate()")
        }

        return functionBuilder.build()
    }

    private fun generateEmbeddedValidation(
        shape: ShaclShape,
        properties: List<ShaclProperty>,
        ctx: Ctx,
        companion: TypeSpec.Builder,
    ): FunSpec {
        val violationClass = ClassName(runtime, "ShaclViolation")
        val shaclCn = ClassName(CodegenConstants.VOCAB_PACKAGE, "SHACL")
        val functionBuilder = FunSpec.builder("validate")
            .returns(ClassName(runtime, "ValidationResult"))
            .addStatement("val violations = mutableListOf<%T>()", violationClass)

        fun violation(constraintTerm: String, pathPred: String, message: String, value: CodeBlock?): CodeBlock =
            CodeBlock.builder()
                .add("%T(\n", violationClass).indent()
                .add("focusNode = rdf.node as RdfResource,\n")
                .add("shapeIri = Iri(%S),\n", shape.shapeIri)
                .add("constraintIri = %T.%N,\n", shaclCn, constraintTerm)
                .add("path = Iri(%S),\n", pathPred)
                .apply { if (value != null) add("actualValue = %L,\n", value) }
                .add("message = %S,\n", message)
                .unindent().add(")")
                .build()

        fun check(condition: CodeBlock, constraintTerm: String, pred: String, message: String, value: CodeBlock?) {
            functionBuilder.beginControlFlow("if (%L)", condition)
            functionBuilder.addStatement("violations += %L", violation(constraintTerm, pred, message, value))
            functionBuilder.endControlFlow()
        }

        var patternIndex = 0
        properties.forEach { property ->
            val pred = property.path
            val kind = TypeMapper.valueKind(property, ctx.model.context, ctx.knownTypes)
            val min = property.minCount
            val max = property.maxCount

            if (min != null || max != null) {
                val countFn = if (kind == ValueKind.LITERAL) "countLiteralValues" else "countObjectValues"
                functionBuilder.beginControlFlow("run")
                functionBuilder.addStatement("val count = %T.%N(rdf.graph, rdf.node, Iri(%S))", graphOps, countFn, pred)
                min?.let { check(CodeBlock.of("count < %L", it), "minCount", pred, "minCount $it violated for $pred", null) }
                max?.let { check(CodeBlock.of("count > %L", it), "maxCount", pred, "maxCount $it violated for $pred", null) }
                functionBuilder.endControlFlow()
            }

            if (kind == ValueKind.LITERAL || (kind == ValueKind.ENUM && property.inValuesTyped?.none { it.isIri } != false)) {
                val literals = CodeBlock.of("%T.getLiteralValues(rdf.graph, rdf.node, Iri(%S))", graphOps, pred)

                property.pattern?.let { pat ->
                    val constant = "PATTERN_${patternIndex++}"
                    companion.addProperty(
                        PropertySpec.builder(constant, Regex::class)
                            .addModifiers(PRIVATE)
                            .initializer(regexCode(pat, property.patternFlags))
                            .build()
                    )
                    functionBuilder.beginControlFlow("%L.forEach { lit ->", literals)
                    check(CodeBlock.of("!%N.containsMatchIn(lit.lexical)", constant), "pattern", pred, "pattern $pat violated for $pred", CodeBlock.of("lit"))
                    functionBuilder.endControlFlow()
                }

                if (property.minLength != null || property.maxLength != null) {
                    functionBuilder.beginControlFlow("%L.forEach { lit ->", literals)
                    property.minLength?.let {
                        check(CodeBlock.of("lit.lexical.length < %L", it), "minLength", pred, "minLength $it violated for $pred", CodeBlock.of("lit"))
                    }
                    property.maxLength?.let {
                        check(CodeBlock.of("lit.lexical.length > %L", it), "maxLength", pred, "maxLength $it violated for $pred", CodeBlock.of("lit"))
                    }
                    functionBuilder.endControlFlow()
                }

                if (property.minInclusive != null || property.maxInclusive != null ||
                    property.minExclusive != null || property.maxExclusive != null
                ) {
                    functionBuilder.beginControlFlow("%L.forEach { lit ->", literals)
                    functionBuilder.addStatement("val num = lit.lexical.trim().toDoubleOrNull()")
                    functionBuilder.beginControlFlow("if (num != null)")
                    property.minInclusive?.let { check(CodeBlock.of("num < %L", it), "minInclusive", pred, "minInclusive $it violated for $pred", CodeBlock.of("lit")) }
                    property.maxInclusive?.let { check(CodeBlock.of("num > %L", it), "maxInclusive", pred, "maxInclusive $it violated for $pred", CodeBlock.of("lit")) }
                    property.minExclusive?.let { check(CodeBlock.of("num <= %L", it), "minExclusive", pred, "minExclusive $it violated for $pred", CodeBlock.of("lit")) }
                    property.maxExclusive?.let { check(CodeBlock.of("num >= %L", it), "maxExclusive", pred, "maxExclusive $it violated for $pred", CodeBlock.of("lit")) }
                    functionBuilder.endControlFlow()
                    functionBuilder.endControlFlow()
                }

                property.inValues?.takeIf { it.isNotEmpty() && property.inValuesTyped?.any { v -> v.isIri } != true }?.let { values ->
                    val allowed = values.map { CodeBlock.of("%S", it) }.joinToCode(", ")
                    functionBuilder.beginControlFlow("%L.forEach { lit ->", literals)
                    check(CodeBlock.of("lit.lexical !in listOf(%L)", allowed), "in", pred, "sh:in violated for $pred", CodeBlock.of("lit"))
                    functionBuilder.endControlFlow()
                }
            }

            // IRI-membered sh:in: every object value (IRI or blank node) must be one of the listed IRIs.
            property.inValuesTyped?.takeIf { tv -> tv.isNotEmpty() && tv.all { it.isIri } }?.let { ivs ->
                val allowed = ivs.map { CodeBlock.of("Iri(%S)", it.value) }.joinToCode(", ")
                functionBuilder.beginControlFlow("%T.getObjectValues(rdf.graph, rdf.node, Iri(%S)) { it }.forEach { obj ->", graphOps, pred)
                check(CodeBlock.of("obj !in listOf(%L)", allowed), "in", pred, "sh:in violated for $pred", CodeBlock.of("obj"))
                functionBuilder.endControlFlow()
            }
        }

        functionBuilder.addStatement("return if (violations.isEmpty()) ValidationResult.Ok else ValidationResult.Violations(violations)")

        return functionBuilder.build()
    }

    private fun generatePropertyImplementation(property: ShaclProperty, ctx: Ctx): PropertySpec {
        val context = ctx.model.context
        val propertyName = NamingUtils.propertyName(property)
        val kotlinType = TypeMapper.toKotlinType(property, context, objectPackage = ctx.domainPackage, knownTypes = ctx.knownTypes)

        val propertyBuilder = PropertySpec.builder(propertyName, kotlinType)
            .addModifiers(OVERRIDE)
            .addKdoc("%L", kdocText("${property.description}\nPath: ${property.path}"))

        val initializer = valuesInitializer(property, ctx)

        propertyBuilder.delegate(
            CodeBlock.builder()
                .add("lazy {\n").indent()
                .add(initializer)
                .unindent().add("\n}")
                .build()
        )

        return propertyBuilder.build()
    }

    private fun valuesInitializer(property: ShaclProperty, ctx: Ctx): CodeBlock {
        val context = ctx.model.context
        val path = property.path
        val label = NamingUtils.propertyName(property)
        return when (TypeMapper.valueKind(property, context, ctx.knownTypes)) {
            ValueKind.ENUM -> {
                val enum = ctx.enumsByName.getValue(property.enumName!!)
                val enumType = ClassName(ctx.domainPackage, enum.name)
                val base = if (enum.memberKind == EnumMemberKind.IRI) {
                    CodeBlock.of(
                        "%T.getObjectValues(rdf.graph, rdf.node, Iri(%S)) { it }.filterIsInstance<Iri>().map { %T.from(it) }",
                        graphOps, path, enumType,
                    )
                } else {
                    CodeBlock.of("%T.getLiteralValues(rdf.graph, rdf.node, Iri(%S)).map { %T.from(it.lexical) }", graphOps, path, enumType)
                }
                cardinalityWrap(base, property, "Required enum $label missing")
            }
            ValueKind.IRI -> cardinalityWrap(
                CodeBlock.of("%T.getObjectValues(rdf.graph, rdf.node, Iri(%S)) { it }.filterIsInstance<Iri>().map { it.value }", graphOps, path),
                property, "Required IRI $label missing",
            )
            ValueKind.OBJECT -> {
                val target = ClassName(ctx.domainPackage, NamingUtils.domainName(property.targetClass!!, context))
                cardinalityWrap(
                    CodeBlock.of(
                        "%T.getObjectValues(rdf.graph, rdf.node, Iri(%S)) { child ->\n⇥OntoMapper.materialize(RdfRef(child, rdf.graph), %T::class.java)\n⇤}",
                        graphOps, path, target,
                    ),
                    property, "Required object $label missing",
                )
            }
            ValueKind.LITERAL -> {
                val mapping = TypeMapper.literalMapping(property.datatype)
                val values = CodeBlock.of("%T.getLiteralValues(rdf.graph, rdf.node, Iri(%S))", graphOps, path)
                val base = if (mapping.isString) CodeBlock.of("%L.map { it.lexical }", values)
                else CodeBlock.of("%L.mapNotNull { %L }", values, mapping.decode(CodeBlock.of("it")))
                when {
                    Cardinality.isList(property) && Cardinality.isRequired(property) ->
                        CodeBlock.of("%L.ifEmpty { error(%S) }", base, "Required literal $label missing")
                    Cardinality.isRequiredSingle(property) -> {
                        val required = CodeBlock.of("%T.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(%S))", graphOps, path)
                        if (mapping.isString) CodeBlock.of("%L.lexical", required)
                        else CodeBlock.of(
                            "%L ?: error(%S)",
                            mapping.decode(required),
                            "Literal $label is not a valid ${property.datatype}",
                        )
                    }
                    else -> cardinalityWrap(base, property, "Required literal $label missing")
                }
            }
        }
    }

    private fun cardinalityWrap(base: CodeBlock, property: ShaclProperty, missingMessage: String): CodeBlock =
        when {
            Cardinality.isList(property) -> base
            Cardinality.isRequiredSingle(property) -> CodeBlock.of("%L.firstOrNull() ?: error(%S)", base, missingMessage)
            else -> CodeBlock.of("%L.firstOrNull()", base)
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
                ParameterSpec.builder("subject", iriClass.copy(nullable = true))
                    .defaultValue("null")
                    .build()
            )
            .addCode(
                CodeBlock.builder()
                    .addStatement("val originalSubject = (rdf.node as? %T)", iriClass)
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
