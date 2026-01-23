package com.geoknoesis.kastor.gen.processor.internal.core

import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.internal.codegen.InstanceDslGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.api.exceptions.FileGenerationException
import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.FileSpec
import java.nio.charset.StandardCharsets

/**
 * Coordinates code generation from ontology models.
 */
class GenerationCoordinator(
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator
) {
    private val instanceDslGenerator = InstanceDslGenerator(logger)
    
    /**
     * Generates interfaces and wrappers from ontology model.
     */
    fun generateFromOntology(
        model: OntologyModel,
        packageName: String,
        generateInterfaces: Boolean,
        generateWrappers: Boolean,
        validationMode: ValidationMode,
        validationAnnotations: ValidationAnnotations,
        externalValidatorClass: String?
    ) {
        val interfaceGenerator = InterfaceGenerator(logger, validationAnnotations)
        val wrapperGenerator = OntologyWrapperGenerator(logger, validationMode, externalValidatorClass)
        
        if (generateInterfaces) {
            val interfaces = interfaceGenerator.generateInterfaces(model, packageName)
            interfaces.forEach { (name, fileSpec) ->
                writeFile(fileSpec, packageName)
            }
        }
        
        if (generateWrappers) {
            val wrappers = wrapperGenerator.generateWrappers(model, packageName)
            wrappers.forEach { (name, fileSpec) ->
                writeFile(fileSpec, packageName)
            }
        }
    }
    
    /**
     * Generates instance DSL from ontology model.
     */
    fun generateInstanceDsl(
        model: OntologyModel,
        dslName: String,
        packageName: String
    ) {
        logger.info("Processing instance DSL generation: $dslName")
        
        val request = InstanceDslRequest(
            dslName = dslName,
            ontologyModel = model,
            packageName = packageName,
            options = DslGenerationOptions()
        )
        
        val fileSpec = instanceDslGenerator.generate(request)
        writeFile(fileSpec, packageName)
        
        logger.info("Generated instance DSL: ${dslName}Dsl.kt")
    }
    
    private fun writeFile(fileSpec: FileSpec, packageName: String) {
        val fileName = "${fileSpec.name}.kt"
        try {
            codeGenerator.createNewFile(
                dependencies = Dependencies(false),
                packageName = packageName,
                fileName = fileName
            ).use { file ->
                file.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    fileSpec.writeTo(writer)
                }
            }
            logger.info("Generated file: $fileName in package $packageName")
        } catch (e: Exception) {
            throw FileGenerationException(
                fileSpec = fileSpec,
                packageName = packageName,
                cause = e
            )
        }
    }
}


