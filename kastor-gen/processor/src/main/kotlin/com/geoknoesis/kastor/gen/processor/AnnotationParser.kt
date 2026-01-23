package com.geoknoesis.kastor.gen.processor

import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*

/**
 * Parses KSP annotations and extracts generation requests.
 */
class AnnotationParser(private val logger: KSPLogger) {
    
    data class OntologyGenerationRequest(
        val shaclPath: String,
        val contextPath: String,
        val targetPackage: String,
        val generateInterfaces: Boolean,
        val generateWrappers: Boolean,
        val validationMode: ValidationMode,
        val validationAnnotations: ValidationAnnotations,
        val externalValidatorClass: String?
    )
    
    data class InstanceDslGenerationRequest(
        val ontologyPath: String?,
        val shaclPath: String,
        val contextPath: String?,
        val dslName: String,
        val targetPackage: String
    )
    
    fun parseOntologyAnnotations(resolver: Resolver): List<OntologyGenerationRequest> {
        val annotations = resolver.getSymbolsWithAnnotation(ANNOTATION_FROM_ONTOLOGY)
        logger.info("Found ${annotations.count()} ontology generation annotations")
        
        return annotations
            .filterIsInstance<KSClassDeclaration>()
            .mapNotNull { symbol ->
                symbol.findAnnotation(ANNOTATION_FROM_ONTOLOGY)
                    ?.let { parseOntologyAnnotationInternal(it, symbol.packageName.asString()) }
                    ?: run {
                        logger.error("GenerateFromOntology annotation can only be applied to classes", symbol)
                        null
                    }
            }
            .toList()
    }
    
    fun parseInstanceDslAnnotations(resolver: Resolver): List<InstanceDslGenerationRequest> {
        val annotations = resolver.getSymbolsWithAnnotation(ANNOTATION_INSTANCE_DSL)
        logger.info("Found ${annotations.count()} instance DSL generation annotations")
        
        return annotations
            .filterIsInstance<KSClassDeclaration>()
            .mapNotNull { symbol ->
                symbol.findAnnotation(ANNOTATION_INSTANCE_DSL)
                    ?.let { parseInstanceDslAnnotationInternal(it, symbol.packageName.asString()) }
                    ?: run {
                        logger.error("GenerateInstanceDsl annotation can only be applied to classes", symbol)
                        null
                    }
            }
            .toList()
    }
    
    companion object {
        private const val ANNOTATION_FROM_ONTOLOGY = "com.geoknoesis.kastor.gen.annotations.GenerateFromOntology"
        private const val ANNOTATION_INSTANCE_DSL = "com.geoknoesis.kastor.gen.annotations.GenerateInstanceDsl"
    }
    
    private fun KSClassDeclaration.findAnnotation(annotationName: String): KSAnnotation? {
        return annotations.find { 
            it.shortName.asString() == annotationName.substringAfterLast(".")
        }
    }
    
    internal fun parseOntologyAnnotation(annotation: KSAnnotation, packageName: String): OntologyGenerationRequest? {
        return parseOntologyAnnotationInternal(annotation, packageName)
    }
    
    internal fun parseInstanceDslAnnotation(annotation: KSAnnotation, packageName: String): InstanceDslGenerationRequest? {
        return parseInstanceDslAnnotationInternal(annotation, packageName)
    }
    
    private fun parseOntologyAnnotationInternal(annotation: KSAnnotation, packageName: String): OntologyGenerationRequest? {
        val shaclPath = getAnnotationValue(annotation, "shaclPath") as? String
        val contextPath = getAnnotationValue(annotation, "contextPath") as? String
        val targetPackage = getAnnotationValue(annotation, "packageName") as? String ?: packageName
        val generateInterfaces = getAnnotationValue(annotation, "generateInterfaces") as? Boolean ?: true
        val generateWrappers = getAnnotationValue(annotation, "generateWrappers") as? Boolean ?: true
        val validationMode = parseValidationMode(getAnnotationValue(annotation, "validationMode"))
        val validationAnnotations = parseValidationAnnotations(getAnnotationValue(annotation, "validationAnnotations"))
        val externalValidatorClass = getAnnotationValue(annotation, "externalValidatorClass") as? String
        
        if (shaclPath == null || contextPath == null) {
            logger.error("Both shaclPath and contextPath must be specified")
            return null
        }
        
        return OntologyGenerationRequest(
            shaclPath = shaclPath,
            contextPath = contextPath,
            targetPackage = targetPackage,
            generateInterfaces = generateInterfaces,
            generateWrappers = generateWrappers,
            validationMode = validationMode,
            validationAnnotations = validationAnnotations,
            externalValidatorClass = externalValidatorClass
        )
    }
    
    private fun parseInstanceDslAnnotationInternal(annotation: KSAnnotation, packageName: String): InstanceDslGenerationRequest? {
        val ontologyPath = getAnnotationValue(annotation, "ontologyPath") as? String
        val shaclPath = getAnnotationValue(annotation, "shaclPath") as? String
        val contextPath = getAnnotationValue(annotation, "contextPath") as? String
        val dslName = getAnnotationValue(annotation, "dslName") as? String
        val targetPackage = getAnnotationValue(annotation, "packageName") as? String ?: packageName
        
        if (shaclPath == null || dslName == null) {
            logger.error("Both shaclPath and dslName must be specified")
            return null
        }
        
        return InstanceDslGenerationRequest(
            ontologyPath = ontologyPath,
            shaclPath = shaclPath,
            contextPath = contextPath,
            dslName = dslName,
            targetPackage = targetPackage
        )
    }
    
    private fun getAnnotationValue(annotation: KSAnnotation, name: String): Any? {
        return annotation.arguments.find { it.name?.asString() == name }?.value
    }
    
    private fun parseValidationMode(value: Any?): ValidationMode {
        return when (value) {
            is ValidationMode -> value
            is KSType -> ValidationMode.valueOf(value.declaration.simpleName.asString())
            is KSName -> ValidationMode.valueOf(value.asString())
            is String -> ValidationMode.valueOf(value)
            else -> ValidationMode.EMBEDDED
        }
    }
    
    private fun parseValidationAnnotations(value: Any?): ValidationAnnotations {
        return when (value) {
            is ValidationAnnotations -> value
            is KSType -> ValidationAnnotations.valueOf(value.declaration.simpleName.asString())
            is KSName -> ValidationAnnotations.valueOf(value.asString())
            is String -> ValidationAnnotations.valueOf(value)
            else -> ValidationAnnotations.JAKARTA
        }
    }
}

