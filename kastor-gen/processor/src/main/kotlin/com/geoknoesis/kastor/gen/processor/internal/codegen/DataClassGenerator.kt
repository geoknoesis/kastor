package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.utils.Cardinality
import com.geoknoesis.kastor.gen.processor.internal.utils.GenerationNames
import com.geoknoesis.kastor.gen.processor.internal.utils.NamingUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.TypeMapper
import com.geoknoesis.kastor.gen.processor.internal.utils.ValueKind
import com.geoknoesis.kastor.gen.processor.internal.utils.kdocText
import com.geoknoesis.kastor.gen.processor.api.exceptions.InvalidConfigurationException
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.KModifier.DATA

/**
 * Generates immutable Kotlin data classes from SHACL shapes.
 *
 * The produced data class is a pure domain snapshot:
 * - No RDF types in the class body or constructor
 * - All properties are eagerly typed (no delegates)
 * - Structural equality, copy(), and toString() via Kotlin data class semantics
 * - Optional implementation of the corresponding generated interface
 *
 * A companion [DataClassFactoryGenerator] produces a separate factory file
 * that performs the eager RDF load and registers the factory in OntoMapper.
 */
public class DataClassGenerator(
    private val logger: KSPLogger,
    private val suffix: String,
    private val nestedMode: NestedMode,
    private val implementsInterface: Boolean,
    private val validationAnnotations: ValidationAnnotations,
) {

    /**
     * @param fallbackUnshapedToIri when true, `sh:class` targets without a shape in [model] are typed as IRI strings
     */
    public fun generateDataClasses(model: OntologyModel, packageName: String, fallbackUnshapedToIri: Boolean = false): Map<String, FileSpec> {
        GenerationNames.checkCollisions(model)
        val knownTypes = if (fallbackUnshapedToIri) GenerationNames.knownTypes(model) else null
        val members = GenerationNames.effectiveMembers(model, GenerationNames.superTypes(model))
        checkIriOnlyImplementsInterface(model, members)
        return model.shapes
            .sortedBy { it.targetClass }
            .associateTo(sortedMapOf()) { shape ->
                val name = dataClassName(shape.targetClass, model.context)
                val properties = members[shape.targetClass].orEmpty().map { it.typing }
                name to generateDataClass(shape, properties, model.context, packageName, knownTypes)
            }
    }

    internal fun dataClassName(classIri: String, context: JsonLdContext): String =
        NamingUtils.domainName(classIri, context) + suffix

    /**
     * [NestedMode.IRI_ONLY] types object references as IRI strings, while the generated interfaces type them with
     * the referenced interface; a data class cannot override such a member, so the combination with
     * [implementsInterface] is rejected (naming the members) instead of generating code that does not compile.
     */
    private fun checkIriOnlyImplementsInterface(
        model: OntologyModel,
        members: Map<String, List<com.geoknoesis.kastor.gen.processor.internal.utils.EffectiveMember>>,
    ) {
        if (!implementsInterface || nestedMode != NestedMode.IRI_ONLY) return
        // Interfaces are generated with unshaped sh:class targets typed as IRI strings, which stay compatible.
        val knownTypes = GenerationNames.knownTypes(model)
        val conflicts = model.shapes.flatMap { shape ->
            members[shape.targetClass].orEmpty().map { it.typing }
                .filter { TypeMapper.valueKind(it, model.context, knownTypes) == ValueKind.OBJECT }
                .map { "${NamingUtils.domainName(shape.targetClass, model.context)}.${NamingUtils.propertyName(it)}" }
        }.distinct().sorted()
        if (conflicts.isEmpty()) return
        throw InvalidConfigurationException(
            config = "nestedMode",
            reason = "NestedMode.IRI_ONLY types object references as IRI strings, but dataClassImplementsInterface = true " +
                "requires the data classes to override interface members typed with the referenced interfaces " +
                "(${conflicts.joinToString()}); use NestedMode.INTERFACE (or DATA_CLASS) with dataClassImplementsInterface, " +
                "or set dataClassImplementsInterface = false to keep IRI_ONLY",
        )
    }

    // ── Per-shape generation ──────────────────────────────────────────────────

    private fun generateDataClass(
        shape: ShaclShape,
        properties: List<ShaclProperty>,
        context: JsonLdContext,
        packageName: String,
        knownTypes: Set<String>?,
    ): FileSpec {
        val className = dataClassName(shape.targetClass, context)
        val interfaceName = NamingUtils.domainName(shape.targetClass, context)

        val file = FileSpec.builder(packageName, className)
            .addFileComment("GENERATED FILE - DO NOT EDIT")
            .addFileComment("Generated from SHACL shape: %L", shape.shapeIri)

        if (validationAnnotations != ValidationAnnotations.NONE) {
            file.addImport("${validationPackage()}.constraints", "NotNull", "NotEmpty", "Size")
        }

        val constructor = FunSpec.constructorBuilder()
        val classBuilder = TypeSpec.classBuilder(className)
            .addModifiers(DATA)
            .addKdoc(
                "%L",
                kdocText(
                    "Immutable data-class snapshot for [$interfaceName].\n" +
                        "All fields are loaded eagerly from the RDF graph by the companion factory.\n" +
                        "Generated from SHACL shape: ${shape.shapeIri}"
                ),
            )

        if (implementsInterface) {
            classBuilder.addSuperinterface(ClassName(packageName, interfaceName))
        }

        // Implement RdfProjection marker so callers can distinguish snapshots from live wrappers
        classBuilder.addSuperinterface(
            ClassName("com.geoknoesis.kastor.gen.runtime", "RdfProjection")
        )

        properties.forEach { property ->
            val (param, prop) = buildConstructorParam(property, context, packageName, knownTypes)
            constructor.addParameter(param)
            classBuilder.addProperty(prop)
        }

        classBuilder.primaryConstructor(constructor.build())
        file.addType(classBuilder.build())
        logger.info("Generated data class: $className")
        return file.build()
    }

    // ── Constructor parameter + property pair ─────────────────────────────────

    private fun buildConstructorParam(
        property: ShaclProperty,
        context: JsonLdContext,
        packageName: String,
        knownTypes: Set<String>?,
    ): Pair<ParameterSpec, PropertySpec> {
        val name = NamingUtils.propertyName(property)
        val type = TypeMapper.toKotlinType(property, context, nestedMode, suffix, objectPackage = packageName, knownTypes = knownTypes)

        val paramBuilder = ParameterSpec.builder(name, type)
        if (Cardinality.isList(property)) paramBuilder.defaultValue("emptyList()")
        else if (!Cardinality.isRequiredSingle(property)) paramBuilder.defaultValue("null")

        // Validation annotations on the constructor parameter (field target)
        validationAnnotationsForProperty(property).forEach { paramBuilder.addAnnotation(it) }

        val propBuilder = PropertySpec.builder(name, type)
            .initializer("%N", name)
            .addKdoc("%L", kdocText("${property.description}\nPath: ${property.path}"))

        if (implementsInterface) propBuilder.addModifiers(KModifier.OVERRIDE)

        return paramBuilder.build() to propBuilder.build()
    }

    // ── Validation annotations ────────────────────────────────────────────────

    private fun validationAnnotationsForProperty(property: ShaclProperty): List<AnnotationSpec> {
        if (validationAnnotations == ValidationAnnotations.NONE) return emptyList()

        val annotations = mutableListOf<AnnotationSpec>()
        val isList = Cardinality.isList(property)
        val min = property.minCount
        val max = property.maxCount

        val fieldTarget = AnnotationSpec.UseSiteTarget.FIELD

        if (!isList) {
            if (min != null && min > 0) {
                annotations += AnnotationSpec
                    .builder(ClassName(validationPackage(), "constraints", "NotNull"))
                    .useSiteTarget(fieldTarget)
                    .build()
            }
        } else {
            if (min != null && min > 0) {
                annotations += AnnotationSpec
                    .builder(ClassName(validationPackage(), "constraints", "NotEmpty"))
                    .useSiteTarget(fieldTarget)
                    .build()
            }
            if (min != null || max != null) {
                val sizeBuilder = AnnotationSpec
                    .builder(ClassName(validationPackage(), "constraints", "Size"))
                    .useSiteTarget(fieldTarget)
                min?.let { sizeBuilder.addMember("min = %L", it) }
                max?.let { sizeBuilder.addMember("max = %L", it) }
                annotations += sizeBuilder.build()
            }
        }

        return annotations
    }

    private fun validationPackage() = when (validationAnnotations) {
        ValidationAnnotations.JAKARTA -> "jakarta.validation"
        ValidationAnnotations.JAVAX   -> "javax.validation"
        ValidationAnnotations.NONE    -> "jakarta.validation"
    }
}
