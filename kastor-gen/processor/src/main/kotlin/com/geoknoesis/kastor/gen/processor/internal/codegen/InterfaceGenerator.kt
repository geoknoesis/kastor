package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.utils.Cardinality
import com.geoknoesis.kastor.gen.processor.internal.utils.EffectiveMember
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
     * Inherited members keep the supertype's name and a signature that is a valid override; see
     * [GenerationNames.effectiveProperties] for the rules.
     *
     * @param ontologyModel The combined SHACL + JSON-LD model
     * @param packageName The target package name
     * @param fallbackUnshapedToIri when true, object properties whose `sh:class` target has no shape in
     *   [ontologyModel] fall back to the IRI (String) instead of a dangling reference. Requires the model
     *   to contain ALL shapes (the Gradle task and KSP processor pass the full model); leave false for partial models.
     * @return Map of interface names to generated FileSpec
     * @throws com.geoknoesis.kastor.gen.processor.api.exceptions.InvalidConfigurationException when two shapes or
     *   two properties of a shape map to the same Kotlin name, or supertypes declare incompatible signatures
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
        val members = GenerationNames.effectiveMembers(ontologyModel, parents) { logger.warn(it) }

        // Sort shapes by targetClass IRI to ensure deterministic output
        ontologyModel.shapes
            .sortedBy { it.targetClass }
            .forEach { shape ->
                val interfaceName = NamingUtils.domainName(shape.targetClass, ontologyModel.context)
                interfaces[interfaceName] = generateInterface(
                    shape, ontologyModel.context, packageName, knownTypes,
                    parents[shape.targetClass].orEmpty(), members[shape.targetClass].orEmpty(),
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
        members: List<EffectiveMember>,
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

        // Members are sorted by path IRI (then name) for deterministic output; inherited members are only
        // re-declared when this shape restates them or their signature changes.
        members.filter { it.declared }.forEach { member ->
            interfaceBuilder.addProperty(generateProperty(member, context, packageName, knownTypes))
        }

        fileBuilder.addType(interfaceBuilder.build())
        return fileBuilder.build()
    }

    private fun generateProperty(
        member: EffectiveMember,
        context: JsonLdContext,
        packageName: String,
        knownTypes: Set<String>?,
    ): PropertySpec {
        val kotlinType = TypeMapper.toKotlinType(member.typing, context, objectPackage = packageName, knownTypes = knownTypes)
        val constraints = member.constraints

        val kdoc = buildString {
            append(constraints.description)
            append("\nPath: ${member.path}")
            if (constraints.minCount != null) {
                append("\nMin count: ${constraints.minCount}")
            }
            if (constraints.maxCount != null) {
                append("\nMax count: ${constraints.maxCount}")
            }
        }

        val propertyBuilder = PropertySpec.builder(member.name, kotlinType)
            .addKdoc("%L", kdocText(kdoc))
            .addAnnotation(
                AnnotationSpec.builder(ClassName("com.geoknoesis.kastor.gen.annotations", "Rdf"))
                    .addMember("iri = %S", member.path)
                    .build()
            )
        if (member.inherited) propertyBuilder.addModifiers(KModifier.OVERRIDE)

        // Add validation annotations
        validationAnnotationsForProperty(member).forEach { annotationSpec ->
            propertyBuilder.addAnnotation(annotationSpec)
        }

        return propertyBuilder.build()
    }


    private fun validationAnnotationsForProperty(member: EffectiveMember): List<AnnotationSpec> {
        if (validationAnnotations == ValidationAnnotations.NONE) return emptyList()
        val annotations = mutableListOf<AnnotationSpec>()
        val isList = Cardinality.isList(member.typing)
        val min = member.constraints.minCount
        val max = member.constraints.maxCount

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
