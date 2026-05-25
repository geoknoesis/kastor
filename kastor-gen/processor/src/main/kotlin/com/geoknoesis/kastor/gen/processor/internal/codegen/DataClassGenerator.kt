package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.utils.KotlinPoetUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.NamingUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.TypeMapper
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
class DataClassGenerator(
    private val logger: KSPLogger,
    private val suffix: String,
    private val nestedMode: NestedMode,
    private val implementsInterface: Boolean,
    private val validationAnnotations: ValidationAnnotations,
) {

    fun generateDataClasses(model: OntologyModel, packageName: String): Map<String, FileSpec> =
        model.shapes
            .sortedBy { it.targetClass }
            .associate { shape ->
                val name = dataClassName(shape.targetClass)
                name to generateDataClass(shape, model.context, packageName)
            }

    internal fun dataClassName(classIri: String): String =
        NamingUtils.extractInterfaceName(classIri) + suffix

    // ── Per-shape generation ──────────────────────────────────────────────────

    private fun generateDataClass(
        shape: ShaclShape,
        context: JsonLdContext,
        packageName: String,
    ): FileSpec {
        val className = dataClassName(shape.targetClass)
        val interfaceName = NamingUtils.extractInterfaceName(shape.targetClass)

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
                "Immutable data-class snapshot for [%L].\n" +
                "All fields are loaded eagerly from the RDF graph by the companion factory.\n" +
                "Generated from SHACL shape: %L",
                interfaceName, shape.shapeIri,
            )

        if (implementsInterface) {
            classBuilder.addSuperinterface(ClassName(packageName, interfaceName))
        }

        // Implement RdfProjection marker so callers can distinguish snapshots from live wrappers
        classBuilder.addSuperinterface(
            ClassName("com.geoknoesis.kastor.gen.runtime", "RdfProjection")
        )

        shape.properties.sortedBy { it.path }.forEach { property ->
            val (param, prop) = buildConstructorParam(property, context)
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
    ): Pair<ParameterSpec, PropertySpec> {
        val name = NamingUtils.toValidKotlinIdentifier(property.name)
        val type = TypeMapper.toKotlinType(property, context, nestedMode, suffix)

        val isList     = property.maxCount == null || property.maxCount > 1
        val isRequired = !isList && property.minCount != null && property.minCount > 0

        val paramBuilder = ParameterSpec.builder(name, type)
        if (isList)          paramBuilder.defaultValue("emptyList()")
        else if (!isRequired) paramBuilder.defaultValue("null")

        // Validation annotations on the constructor parameter (field target)
        validationAnnotationsForProperty(property).forEach { paramBuilder.addAnnotation(it) }

        val propBuilder = PropertySpec.builder(name, type)
            .initializer(name)
            .addKdoc("%L\nPath: %L", property.description, property.path)

        if (implementsInterface) propBuilder.addModifiers(KModifier.OVERRIDE)

        return paramBuilder.build() to propBuilder.build()
    }

    // ── Validation annotations ────────────────────────────────────────────────

    private fun validationAnnotationsForProperty(property: ShaclProperty): List<AnnotationSpec> {
        if (validationAnnotations == ValidationAnnotations.NONE) return emptyList()

        val annotations = mutableListOf<AnnotationSpec>()
        val isList = property.maxCount == null || property.maxCount > 1
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
