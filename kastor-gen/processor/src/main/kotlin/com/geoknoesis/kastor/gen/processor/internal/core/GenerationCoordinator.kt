package com.geoknoesis.kastor.gen.processor.internal.core

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassFactoryGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassWriterGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InstanceDslGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.EnumGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.ShaclEnumExtractor
import com.geoknoesis.kastor.gen.processor.api.exceptions.FileGenerationException
import com.geoknoesis.kastor.gen.processor.api.exceptions.InvalidConfigurationException
import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.internal.utils.GenerationNames
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.FileSpec
import java.nio.charset.StandardCharsets

/**
 * Coordinates code generation from ontology models.
 *
 * All files for one request are generated first, checked for (case-insensitive) file-name collisions, and
 * only then written. Outputs are registered as *aggregating* over the annotated source files passed in
 * `sources`, so KSP regenerates them whenever those sources change. KSP cannot observe SHACL / JSON-LD files
 * under `src/main/resources`; after editing those, touch the annotated source or run a clean build (the
 * `com.geoknoesis.kastor.gen` Gradle plugin tracks ontology files as real task inputs).
 */
public class GenerationCoordinator(
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator
) {
    private val instanceDslGenerator = InstanceDslGenerator(logger)

    /**
     * Generates interfaces and wrappers from ontology model.
     *
     * @throws InvalidConfigurationException when data classes would collide with interfaces (empty
     *   [dataClassSuffix]) or when generated names collide
     */
    public fun generateFromOntology(
        model: OntologyModel,
        packageName: String,
        generateInterfaces: Boolean,
        generateWrappers: Boolean,
        validationMode: ValidationMode,
        validationAnnotations: ValidationAnnotations,
        externalValidatorClass: String?,
        generateDataClass: Boolean = false,
        dataClassSuffix: String = DEFAULT_DATA_CLASS_SUFFIX,
        dataClassImplementsInterface: Boolean = false,
        nestedMode: NestedMode = NestedMode.INTERFACE,
        generateWriteSupport: Boolean = false,
        sources: List<KSFile> = emptyList(),
    ) {
        if (generateDataClass && dataClassSuffix.isBlank() && (generateInterfaces || generateWrappers || dataClassImplementsInterface)) {
            throw InvalidConfigurationException(
                config = "dataClassSuffix",
                reason = "must not be empty when data classes are generated together with interfaces/wrappers in package " +
                    "'$packageName' (the data class and the interface would both be named after the shape); use e.g. " +
                    "\"$DEFAULT_DATA_CLASS_SUFFIX\"",
            )
        }
        if (dataClassImplementsInterface && !generateInterfaces) {
            logger.warn("dataClassImplementsInterface = true but generateInterfaces = false; the interfaces must exist in '$packageName'")
        }

        val enriched = ShaclEnumExtractor(logger).enrich(model)
        GenerationNames.checkCollisions(enriched)
        val files = mutableListOf<FileSpec>()
        files += EnumGenerator(logger).generateEnums(enriched, packageName).values

        if (generateInterfaces) {
            files += InterfaceGenerator(logger, validationAnnotations)
                .generateInterfaces(enriched, packageName, fallbackUnshapedToIri = true).values
        }

        if (generateWrappers) {
            files += OntologyWrapperGenerator(logger, validationMode, externalValidatorClass)
                .generateWrappers(enriched, packageName, fallbackUnshapedToIri = true).values
        }

        if (generateDataClass) {
            val dcGenerator = DataClassGenerator(
                logger = logger,
                suffix = dataClassSuffix,
                nestedMode = nestedMode,
                implementsInterface = dataClassImplementsInterface,
                validationAnnotations = validationAnnotations,
            )
            val writerGen = if (generateWriteSupport) {
                DataClassWriterGenerator(
                    logger = logger,
                    suffix = dataClassSuffix,
                    nestedMode = nestedMode,
                )
            } else null
            val factoryGenerator = DataClassFactoryGenerator(
                logger = logger,
                suffix = dataClassSuffix,
                nestedMode = nestedMode,
                writerGenerator = writerGen,
            )
            files += dcGenerator.generateDataClasses(enriched, packageName, fallbackUnshapedToIri = true).values
            files += factoryGenerator.generateFactories(enriched, packageName, fallbackUnshapedToIri = true).values
        }

        GenerationNames.checkUniqueFiles(files)
        files.sortedBy { it.name }.forEach { writeFile(it, sources) }
    }

    /**
     * Generates instance DSL from ontology model.
     */
    public fun generateInstanceDsl(
        model: OntologyModel,
        dslName: String,
        packageName: String,
        sources: List<KSFile> = emptyList(),
    ) {
        logger.info("Processing instance DSL generation: $dslName")

        val request = InstanceDslRequest(
            dslName = dslName,
            ontologyModel = model,
            packageName = packageName,
            options = DslGenerationOptions()
        )

        val fileSpec = instanceDslGenerator.generate(request)
        writeFile(fileSpec, sources)

        logger.info("Generated instance DSL: ${fileSpec.name}.kt")
    }

    private fun writeFile(fileSpec: FileSpec, sources: List<KSFile>) {
        val packageName = fileSpec.packageName
        val kspFileName = fileSpec.name.removeSuffix(".kt")
        try {
            codeGenerator.createNewFile(
                dependencies = Dependencies(aggregating = true, *sources.distinct().toTypedArray()),
                packageName = packageName,
                fileName = kspFileName
            ).use { file ->
                file.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    fileSpec.writeTo(writer)
                }
            }
            logger.info("Generated file: $kspFileName.kt in package $packageName")
        } catch (e: Exception) {
            throw FileGenerationException(
                fileSpec = fileSpec,
                packageName = packageName,
                cause = e
            )
        }
    }

    public companion object {
        /** Default suffix for generated data classes (`Person` → `PersonRecord`). */
        public const val DEFAULT_DATA_CLASS_SUFFIX: String = "Record"
    }
}
