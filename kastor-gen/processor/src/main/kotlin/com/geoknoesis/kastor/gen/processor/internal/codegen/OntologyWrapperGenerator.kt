package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.api.model.EnumMemberKind
import com.geoknoesis.kastor.gen.processor.api.model.EnumModel
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.utils.Cardinality
import com.geoknoesis.kastor.gen.processor.internal.utils.CodegenConstants
import com.geoknoesis.kastor.gen.processor.internal.utils.EffectiveMember
import com.geoknoesis.kastor.gen.processor.internal.utils.GenerationNames
import com.geoknoesis.kastor.gen.processor.internal.utils.KotlinPoetUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.NamingUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.ShaclPatterns
import com.geoknoesis.kastor.gen.processor.internal.utils.TypeMapper
import com.geoknoesis.kastor.gen.processor.internal.utils.ValueKind
import com.geoknoesis.kastor.gen.processor.internal.utils.kdocText
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
        val members: Map<String, List<EffectiveMember>>,
    )

    private val runtime = CodegenConstants.RUNTIME_PACKAGE
    private val iriClass = ClassName(CodegenConstants.RDF_PACKAGE, "Iri")
    private val graphOps = ClassName(runtime, "KastorGraphOps")
    private val xsdLiterals = ClassName(runtime, "XsdLiterals")
    private val materializationPolicy = ClassName(runtime, "MaterializationPolicy")

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
            members = GenerationNames.effectiveMembers(ontologyModel, GenerationNames.superTypes(ontologyModel)),
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
        val members = ctx.members[shape.targetClass].orEmpty()

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

        val companionBuilder = TypeSpec.companionObjectBuilder()

        // Known predicates set, shared by all instances - sorted by path IRI for deterministic output
        companionBuilder.addProperty(
            PropertySpec.builder("KNOWN", KotlinPoetUtils.setOf(iriClass))
                .addModifiers(PRIVATE)
                .initializer("setOf(%L)", members.map { it.path }.distinct().map { CodeBlock.of("Iri(%S)", it) }.joinToCode(", "))
                .build()
        )

        // RDF handle property
        classBuilder.addProperty(
            PropertySpec.builder("rdf", ClassName(runtime, "RdfHandle"))
                .addModifiers(OVERRIDE)
                .delegate(
                    CodeBlock.of(
                        "lazy(LazyThreadSafetyMode.PUBLICATION) {\n" +
                        "  if (input is DefaultRdfHandle) input.withKnownPredicates(KNOWN) else input\n" +
                        "}"
                    )
                )
                .build()
        )

        members.forEach { member ->
            classBuilder.addProperty(generatePropertyImplementation(member.typing, ctx, shape.shapeIri))
        }

        when (validationMode) {
            ValidationMode.NONE -> Unit
            ValidationMode.EXTERNAL -> {
                require(externalValidatorClass != null) {
                    "EXTERNAL validation mode requires externalValidatorClass to be specified"
                }
                classBuilder.addFunction(generateExternalValidation())
            }
            ValidationMode.EMBEDDED -> {
                classBuilder.addFunction(generateEmbeddedValidation(shape, members.filter { it.primaryForPath }, ctx, companionBuilder))
            }
        }

        classBuilder.addFunction(generateWriteToGraph())

        // Property mappings - sorted by path IRI for deterministic output
        val mappingEntries = members.map { it.typing }.map { property ->
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
            val validatorType = ClassName.bestGuess(validatorRef)
            // One process-wide validator per validator class (SharedValidators), shared by every wrapper type naming it:
            // validators parse shapes or keep a copy of the data (RDF4J), so one per wrapper class multiplies that cost.
            // The instance lives until SharedValidators.close / closeAll.
            functionBuilder.addStatement(
                "return %T.get(%T::class.java) { %T() }.validate(rdf.graph, rdf.node)",
                ClassName(runtime, "SharedValidators"), validatorType, validatorType,
            )
        } else {
            functionBuilder.addStatement("return rdf.validate()")
        }

        return functionBuilder.build()
    }

    private fun generateEmbeddedValidation(
        shape: ShaclShape,
        members: List<EffectiveMember>,
        ctx: Ctx,
        companion: TypeSpec.Builder,
    ): FunSpec {
        val violationClass = ClassName(runtime, "ShaclViolation")
        val shaclCn = ClassName(CodegenConstants.VOCAB_PACKAGE, "SHACL")
        val functionBuilder = FunSpec.builder("validate")
            .returns(ClassName(runtime, "ValidationResult"))
            .addStatement("val violations = mutableListOf<%T>()", violationClass)

        // The property shape whose constraints are being emitted: supplies sh:severity and sh:message.
        lateinit var current: ShaclProperty

        fun violation(constraintTerm: String, pathPred: String, message: String, value: CodeBlock?): CodeBlock =
            CodeBlock.builder()
                .add("%T(\n", violationClass).indent()
                .add("focusNode = rdf.node as RdfResource,\n")
                .add("shapeIri = Iri(%S),\n", shape.shapeIri)
                .apply {
                    // `class` has no usable vocabulary member; spell its IRI out.
                    if (constraintTerm == "class") add("constraintIri = Iri(%S),\n", "http://www.w3.org/ns/shacl#class")
                    else add("constraintIri = %T.%N,\n", shaclCn, constraintTerm)
                }
                .add("path = Iri(%S),\n", pathPred)
                .apply { if (value != null) add("actualValue = %L,\n", value) }
                .add("message = %S,\n", current.message ?: message)
                .apply {
                    // sh:Violation (and any custom severity IRI) keeps the default ShaclSeverity.Violation.
                    when (current.severity) {
                        "http://www.w3.org/ns/shacl#Warning" -> add("severity = %T.Warning,\n", ClassName(runtime, "ShaclSeverity"))
                        "http://www.w3.org/ns/shacl#Info" -> add("severity = %T.Info,\n", ClassName(runtime, "ShaclSeverity"))
                    }
                }
                .unindent().add(")")
                .build()

        fun check(condition: CodeBlock, constraintTerm: String, pred: String, message: String, value: CodeBlock?) {
            functionBuilder.beginControlFlow("if (%L)", condition)
            functionBuilder.addStatement("violations += %L", violation(constraintTerm, pred, message, value))
            functionBuilder.endControlFlow()
        }

        var patternIndex = 0
        // A deactivated node shape validates nothing; a deactivated property shape contributes no constraints.
        members.filterNot { shape.deactivated || it.constraints.deactivated }.forEach { member ->
            val property = member.constraints
            current = property
            val pred = property.path
            val kind = TypeMapper.valueKind(member.typing, ctx.model.context, ctx.knownTypes)
            val min = property.minCount
            val max = property.maxCount

            if (min != null || max != null) {
                // sh:minCount / sh:maxCount count every value node of the path, whatever its term kind; checking the
                // kind is the job of sh:datatype / sh:nodeKind / sh:class.
                functionBuilder.beginControlFlow("run")
                functionBuilder.addStatement("val count = %T.getValues(rdf.graph, rdf.node, Iri(%S)).size", graphOps, pred)
                min?.let { check(CodeBlock.of("count < %L", it), "minCount", pred, "minCount $it violated for $pred", null) }
                max?.let { check(CodeBlock.of("count > %L", it), "maxCount", pred, "maxCount $it violated for $pred", null) }
                functionBuilder.endControlFlow()
            }

            // Value-type constraints apply to every value of the path, whatever its term type.
            val values = CodeBlock.of("%T.getValues(rdf.graph, rdf.node, Iri(%S))", graphOps, pred)
            property.datatype?.takeIf { kind == ValueKind.LITERAL || kind == ValueKind.ENUM }?.let { datatype ->
                functionBuilder.beginControlFlow("%L.forEach { value ->", values)
                check(
                    CodeBlock.of("!%T.hasDatatype(value, Iri(%S))", xsdLiterals, datatype),
                    "datatype", pred, "sh:datatype <$datatype> violated for $pred", CodeBlock.of("value"),
                )
                functionBuilder.endControlFlow()
            }
            property.nodeKind?.let { nodeKind ->
                functionBuilder.beginControlFlow("%L.forEach { value ->", values)
                check(
                    CodeBlock.of("!%T.hasNodeKind(value, Iri(%S))", graphOps, nodeKind),
                    "nodeKind", pred, "sh:nodeKind <$nodeKind> violated for $pred", CodeBlock.of("value"),
                )
                functionBuilder.endControlFlow()
            }
            property.targetClass?.let { cls ->
                functionBuilder.beginControlFlow("%L.forEach { value ->", values)
                check(
                    CodeBlock.of("!%T.isInstanceOf(rdf.graph, value, Iri(%S))", graphOps, cls),
                    "class", pred, "sh:class <$cls> violated for $pred", CodeBlock.of("value"),
                )
                functionBuilder.endControlFlow()
            }

            if (kind == ValueKind.LITERAL || (kind == ValueKind.ENUM && property.inValuesTyped?.none { it.isIri } != false)) {
                val literals = CodeBlock.of("%T.getLiteralValues(rdf.graph, rdf.node, Iri(%S))", graphOps, pred)

                property.pattern?.let { pat ->
                    val constant = "PATTERN_${patternIndex++}"
                    // Lazy: a pattern the JVM cannot compile only fails validate(), never class initialisation.
                    companion.addProperty(ShaclPatterns.lazyProperty(pat, property.patternFlags, constant))
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
                    // Exact comparison (BigDecimal); a value that is not a well-formed numeric literal cannot be compared,
                    // which SHACL reports as a violation.
                    functionBuilder.beginControlFlow("%L.forEach { value ->", values)
                    fun bound(limit: java.math.BigDecimal?, term: String, violatedWhen: String) {
                        if (limit == null) return
                        val lexical = limit.toPlainString()
                        check(
                            CodeBlock.of("%T.compareNumeric(value, %S).let { it == null || %L }", xsdLiterals, lexical, violatedWhen),
                            term, pred, "$term $lexical violated for $pred", CodeBlock.of("value"),
                        )
                    }
                    bound(property.minInclusive, "minInclusive", "it < 0")
                    bound(property.maxInclusive, "maxInclusive", "it > 0")
                    bound(property.minExclusive, "minExclusive", "it <= 0")
                    bound(property.maxExclusive, "maxExclusive", "it >= 0")
                    functionBuilder.endControlFlow()
                }

                property.inValues?.takeIf { it.isNotEmpty() && property.inValuesTyped?.any { v -> v.isIri } != true }?.let { values ->
                    val allowed = values.map { CodeBlock.of("%S", it) }.joinToCode(", ")
                    functionBuilder.beginControlFlow("%L.forEach { lit ->", literals)
                    check(CodeBlock.of("lit.lexical !in listOf(%L)", allowed), "in", pred, "sh:in violated for $pred", CodeBlock.of("lit"))
                    functionBuilder.endControlFlow()
                }
            }

            // IRI-membered sh:in: every value (of any term kind) must be one of the listed IRIs.
            property.inValuesTyped?.takeIf { tv -> tv.isNotEmpty() && tv.all { it.isIri } }?.let { ivs ->
                val allowed = ivs.map { CodeBlock.of("Iri(%S)", it.value) }.joinToCode(", ")
                functionBuilder.beginControlFlow("%T.getValues(rdf.graph, rdf.node, Iri(%S)).forEach { obj ->", graphOps, pred)
                check(CodeBlock.of("obj !in listOf(%L)", allowed), "in", pred, "sh:in violated for $pred", CodeBlock.of("obj"))
                functionBuilder.endControlFlow()
            }
        }

        functionBuilder.addStatement("return if (violations.isEmpty()) ValidationResult.Ok else ValidationResult.Violations(violations)")

        return functionBuilder.build()
    }

    private fun generatePropertyImplementation(property: ShaclProperty, ctx: Ctx, shapeIri: String): PropertySpec {
        val context = ctx.model.context
        val propertyName = NamingUtils.propertyName(property)
        val kotlinType = TypeMapper.toKotlinType(property, context, objectPackage = ctx.domainPackage, knownTypes = ctx.knownTypes)

        val propertyBuilder = PropertySpec.builder(propertyName, kotlinType)
            .addModifiers(OVERRIDE)
            .addKdoc("%L", kdocText("${property.description}\nPath: ${property.path}"))

        val initializer = valuesInitializer(property, ctx, shapeIri)

        // The wrapper captures the MaterializationPolicy in effect when it is created; properties read later use it.
        propertyBuilder.delegate(
            CodeBlock.builder()
                .add("%T.lazyWithCurrentPolicy {\n", materializationPolicy).indent()
                .add(initializer)
                .unindent().add("\n}")
                .build()
        )

        return propertyBuilder.build()
    }

    private fun valuesInitializer(property: ShaclProperty, ctx: Ctx, shapeIri: String): CodeBlock {
        val context = ctx.model.context
        val path = property.path
        val label = "${NamingUtils.propertyName(property)} <$path> of shape <$shapeIri>"
        return when (TypeMapper.valueKind(property, context, ctx.knownTypes)) {
            ValueKind.ENUM -> {
                val enum = ctx.enumsByName.getValue(property.enumName!!)
                val enumType = ClassName(ctx.domainPackage, enum.name)
                // Values of the wrong term kind (a literal for an IRI enum, an IRI for a literal enum) follow
                // MaterializationPolicy instead of disappearing.
                val base = if (enum.memberKind == EnumMemberKind.IRI) {
                    CodeBlock.of("%T.getIriValues(rdf.graph, rdf.node, Iri(%S), %S).map { %T.from(it) }", graphOps, path, label, enumType)
                } else {
                    CodeBlock.of("%T.getLiteralValues(rdf.graph, rdf.node, Iri(%S), %S).map { %T.from(it.lexical) }", graphOps, path, label, enumType)
                }
                cardinalityWrap(base, property, label)
            }
            ValueKind.IRI -> cardinalityWrap(
                if (TypeMapper.isResourceReference(property)) {
                    // sh:BlankNodeOrIRI / sh:BlankNode: RdfResource keeps blank nodes.
                    CodeBlock.of("%T.getResourceValues(rdf.graph, rdf.node, Iri(%S), %S)", graphOps, path, label)
                } else {
                    CodeBlock.of("%T.getIriValues(rdf.graph, rdf.node, Iri(%S), %S).map { it.value }", graphOps, path, label)
                },
                property, label,
            )
            ValueKind.OBJECT -> {
                val target = ClassName(ctx.domainPackage, NamingUtils.domainName(property.targetClass!!, context))
                cardinalityWrap(
                    CodeBlock.of(
                        "%T.getObjectValues(rdf.graph, rdf.node, Iri(%S), %S) { child ->\n⇥OntoMapper.materialize(RdfRef(child, rdf.graph), %T::class.java)\n⇤}",
                        graphOps, path, label, target,
                    ),
                    property, label,
                )
            }
            ValueKind.LITERAL -> {
                val mapping = TypeMapper.literalMapping(property.datatype)
                val values = CodeBlock.of("%T.getLiteralValues(rdf.graph, rdf.node, Iri(%S), %S)", graphOps, path, label)
                val base = if (mapping.isString) CodeBlock.of("%L.map { it.lexical }", values)
                // Ill-typed values follow MaterializationPolicy (throw by default) instead of disappearing.
                else CodeBlock.of(
                    "%L.mapNotNull { lit -> %L ?: %T.illTyped(lit, %S, %S) }",
                    values, mapping.decode(CodeBlock.of("lit")), materializationPolicy, label, mapping.expectedDescription(),
                )
                cardinalityWrap(base, property, label)
            }
        }
    }

    /**
     * Applies the member's cardinality to the list of decoded values. Required members (`sh:minCount >= 1`) without
     * a value throw `MaterializationException` via `MaterializationPolicy.missingRequired`, for lists and singles.
     */
    private fun cardinalityWrap(base: CodeBlock, property: ShaclProperty, label: String): CodeBlock =
        when {
            Cardinality.isList(property) && Cardinality.isRequired(property) ->
                CodeBlock.of("%L.ifEmpty { %T.missingRequired(%S) }", base, materializationPolicy, label)
            Cardinality.isList(property) -> base
            Cardinality.isRequiredSingle(property) ->
                CodeBlock.of("%L.firstOrNull() ?: %T.missingRequired(%S)", base, materializationPolicy, label)
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
