package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.utils.Cardinality
import com.geoknoesis.kastor.gen.processor.internal.utils.GenerationNames
import com.geoknoesis.kastor.gen.processor.internal.utils.NamingUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.TypeMapper
import com.geoknoesis.kastor.gen.processor.internal.utils.kdocText
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.*

/**
 * Generator for Kotlin domain interfaces from SHACL shapes using KotlinPoet.
 * Creates pure domain interfaces with no RDF dependencies.
 */
public class InterfaceGenerator(
    private val logger: KSPLogger,
    private val validationAnnotations: ValidationAnnotations = ValidationAnnotations.JAKARTA
) {

    /**
     * Generates Kotlin interface code from SHACL shapes.
     *
     * @param ontologyModel The combined SHACL + JSON-LD model
     * @param packageName The target package name
     * @param fallbackUnshapedToIri when true, object properties whose `sh:class` target has no shape in
     *   [ontologyModel] fall back to the IRI (String) instead of a dangling reference. Requires the model
     *   to contain ALL shapes (the Gradle task and KSP processor pass the full model); leave false for partial models.
     * @return Map of interface names to generated FileSpec
     * @throws com.geoknoesis.kastor.gen.processor.api.exceptions.InvalidConfigurationException when two shapes or
     *   two properties of a shape map to the same Kotlin name
     */
    public fun generateInterfaces(
        ontologyModel: OntologyModel,
        packageName: String,
        fallbackUnshapedToIri: Boolean = false,
    ): Map<String, FileSpec> {
        GenerationNames.checkCollisions(ontologyModel)
        val interfaces = sortedMapOf<String, FileSpec>()

        val knownTypes: Set<String>? = if (fallbackUnshapedToIri) GenerationNames.knownTypes(ontologyModel) else null
        val parents = GenerationNames.superTypes(ontologyModel)

        // Sort shapes by targetClass IRI to ensure deterministic output
        ontologyModel.shapes
            .sortedBy { it.targetClass }
            .forEach { shape ->
                val interfaceName = NamingUtils.domainName(shape.targetClass, ontologyModel.context)
                interfaces[interfaceName] = generateInterface(
                    shape, ontologyModel.context, packageName, knownTypes,
                    parents[shape.targetClass].orEmpty(), GenerationNames.inheritedPaths(shape, parents),
                )
                logger.info("Generated interface: $interfaceName")
            }

        return interfaces
    }

    private fun generateInterface(
        shape: ShaclShape,
        context: JsonLdContext,
        packageName: String,
        knownTypes: Set<String>?,
        superTypes: List<ShaclShape>,
        inherited: Set<String>,
    ): FileSpec {
        val interfaceName = NamingUtils.domainName(shape.targetClass, context)

        val fileBuilder = FileSpec.builder(packageName, interfaceName)
            .addFileComment("GENERATED FILE - DO NOT EDIT")
            .addFileComment("Generated from SHACL shape: %L", shape.shapeIri)

        val interfaceBuilder = TypeSpec.interfaceBuilder(interfaceName)
            .addKdoc(
                "%L",
                kdocText(
                    "Domain interface for ${shape.targetClass}\nPure domain interface with no RDF dependencies.\n" +
                        "Generated from SHACL shape: ${shape.shapeIri}"
                )
            )
            .addAnnotation(
                AnnotationSpec.builder(ClassName("com.geoknoesis.kastor.gen.annotations", "Rdf"))
                    .addMember("iri = %S", shape.targetClass)
                    .build()
            )
        superTypes.forEach { parent ->
            interfaceBuilder.addSuperinterface(ClassName(packageName, NamingUtils.domainName(parent.targetClass, context)))
        }

        // Generate properties - sort by path IRI to ensure deterministic output
        shape.properties
            .sortedBy { it.path }
            .forEach { property ->
                interfaceBuilder.addProperty(
                    generateProperty(property, context, packageName, knownTypes, override = property.path in inherited)
                )
            }

        fileBuilder.addType(interfaceBuilder.build())
        return fileBuilder.build()
    }

    private fun generateProperty(
        property: ShaclProperty,
        context: JsonLdContext,
        packageName: String,
        knownTypes: Set<String>?,
        override: Boolean,
    ): PropertySpec {
        val kotlinType = TypeMapper.toKotlinType(property, context, objectPackage = packageName, knownTypes = knownTypes)
        val propertyName = NamingUtils.propertyName(property)

        val kdoc = buildString {
            append(property.description)
            append("\nPath: ${property.path}")
            if (property.minCount != null) {
                append("\nMin count: ${property.minCount}")
            }
            if (property.maxCount != null) {
                append("\nMax count: ${property.maxCount}")
            }
        }

        val propertyBuilder = PropertySpec.builder(propertyName, kotlinType)
            .addKdoc("%L", kdocText(kdoc))
            .addAnnotation(
                AnnotationSpec.builder(ClassName("com.geoknoesis.kastor.gen.annotations", "Rdf"))
                    .addMember("iri = %S", property.path)
                    .build()
            )
        if (override) propertyBuilder.addModifiers(KModifier.OVERRIDE)

        // Add validation annotations
        validationAnnotationsForProperty(property).forEach { annotationSpec ->
            propertyBuilder.addAnnotation(annotationSpec)
        }

        return propertyBuilder.build()
    }


    private fun validationAnnotationsForProperty(property: ShaclProperty): List<AnnotationSpec> {
        if (validationAnnotations == ValidationAnnotations.NONE) return emptyList()
        val annotations = mutableListOf<AnnotationSpec>()
        val isList = Cardinality.isList(property)
        val min = property.minCount
        val max = property.maxCount

        if (!isList) {
            if (min != null && min > 0) {
                annotations.add(
                    AnnotationSpec.builder(ClassName("${validationPackage()}.constraints", "NotNull"))
                        .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                        .build()
                )
            }
        } else {
            if (min != null && min > 0) {
                annotations.add(
                    AnnotationSpec.builder(ClassName("${validationPackage()}.constraints", "NotEmpty"))
                        .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                        .build()
                )
            }
            if (min != null || max != null) {
                val annotationBuilder = AnnotationSpec.builder(ClassName("${validationPackage()}.constraints", "Size"))
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                min?.let { annotationBuilder.addMember("min = %L", it) }
                max?.let { annotationBuilder.addMember("max = %L", it) }
                annotations.add(annotationBuilder.build())
            }
        }

        return annotations
    }

    private fun validationPackage(): String {
        return when (validationAnnotations) {
            ValidationAnnotations.JAKARTA -> "jakarta.validation"
            ValidationAnnotations.JAVAX -> "javax.validation"
            ValidationAnnotations.NONE -> "jakarta.validation"
        }
    }

}
