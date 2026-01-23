package com.geoknoesis.kastor.gen.processor.internal.codegen

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
import com.squareup.kotlinpoet.KModifier.*

/**
 * Generator for Kotlin domain interfaces from SHACL shapes using KotlinPoet.
 * Creates pure domain interfaces with no RDF dependencies.
 */
internal class InterfaceGenerator(
    private val logger: KSPLogger,
    private val validationAnnotations: ValidationAnnotations = ValidationAnnotations.JAKARTA
) {

    /**
     * Generates Kotlin interface code from SHACL shapes.
     * 
     * @param ontologyModel The combined SHACL + JSON-LD model
     * @param packageName The target package name
     * @return Map of interface names to generated FileSpec
     */
    fun generateInterfaces(ontologyModel: OntologyModel, packageName: String): Map<String, FileSpec> {
        val interfaces = mutableMapOf<String, FileSpec>()
        
        ontologyModel.shapes.forEach { shape ->
            val interfaceName = NamingUtils.extractInterfaceName(shape.targetClass)
            val fileSpec = generateInterface(shape, ontologyModel.context, packageName)
            interfaces[interfaceName] = fileSpec
            
            logger.info("Generated interface: $interfaceName")
        }
        
        return interfaces
    }

    private fun generateInterface(
        shape: ShaclShape,
        context: JsonLdContext,
        packageName: String
    ): FileSpec {
        val interfaceName = NamingUtils.extractInterfaceName(shape.targetClass)
        
        val fileBuilder = FileSpec.builder(packageName, interfaceName)
            .addFileComment("GENERATED FILE - DO NOT EDIT")
            .addFileComment("Generated from SHACL shape: %L", shape.shapeIri)
        
        // Add imports
        fileBuilder.addImport("com.geoknoesis.kastor.gen.annotations", "RdfClass", "RdfProperty")
        if (validationAnnotations != ValidationAnnotations.NONE) {
            fileBuilder.addImport("${validationPackage()}.constraints", "NotNull", "NotEmpty", "Size", "Min", "Max", "Pattern", "NotBlank")
        }
        
        // Build interface
        val interfaceBuilder = TypeSpec.interfaceBuilder(interfaceName)
            .addKdoc(
                "Domain interface for %L\nPure domain interface with no RDF dependencies.\nGenerated from SHACL shape: %L",
                shape.targetClass, shape.shapeIri
            )
            .addAnnotation(
                AnnotationSpec.builder(ClassName("com.geoknoesis.kastor.gen.annotations", "RdfClass"))
                    .addMember("iri = %S", shape.targetClass)
                    .build()
            )
        
        // Generate properties
        shape.properties.forEach { property ->
            interfaceBuilder.addProperty(generateProperty(property, context))
        }
        
        fileBuilder.addType(interfaceBuilder.build())
        return fileBuilder.build()
    }

    private fun generateProperty(
        property: ShaclProperty,
        @Suppress("UNUSED_PARAMETER") context: JsonLdContext
    ): PropertySpec {
        val kotlinType = TypeMapper.toKotlinType(property, context)
        val propertyName = NamingUtils.toValidKotlinIdentifier(property.name)
        
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
            .addKdoc(kdoc)
            .addAnnotation(
                AnnotationSpec.builder(ClassName("com.geoknoesis.kastor.gen.annotations", "RdfProperty"))
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                    .addMember("iri = %S", property.path)
                    .build()
            )
        
        // Add validation annotations
        validationAnnotationsForProperty(property).forEach { annotationSpec ->
            propertyBuilder.addAnnotation(annotationSpec)
        }
        
        return propertyBuilder.build()
    }


    private fun validationAnnotationsForProperty(property: ShaclProperty): List<AnnotationSpec> {
        if (validationAnnotations == ValidationAnnotations.NONE) return emptyList()
        val annotations = mutableListOf<AnnotationSpec>()
        val isList = property.maxCount == null || property.maxCount > 1
        val min = property.minCount
        val max = property.maxCount

        if (!isList) {
            if (min != null && min > 0) {
                annotations.add(
                    AnnotationSpec.builder(ClassName(validationPackage(), "constraints", "NotNull"))
                        .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                        .build()
                )
            }
        } else {
            if (min != null && min > 0) {
                annotations.add(
                    AnnotationSpec.builder(ClassName(validationPackage(), "constraints", "NotEmpty"))
                        .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                        .build()
                )
            }
            if (min != null || max != null) {
                val annotationBuilder = AnnotationSpec.builder(ClassName(validationPackage(), "constraints", "Size"))
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

