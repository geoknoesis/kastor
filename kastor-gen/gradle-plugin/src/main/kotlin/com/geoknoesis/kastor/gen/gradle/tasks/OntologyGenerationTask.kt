package com.geoknoesis.kastor.gen.gradle.tasks

import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InstanceDslGenerator
import com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest
import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.internal.parsers.ShaclParser
import com.geoknoesis.kastor.gen.processor.internal.parsers.JsonLdContextParser
import com.geoknoesis.kastor.gen.gradle.VocabularyGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.FileSpec
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import com.geoknoesis.kastor.gen.gradle.GradleKspLogger
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

/**
 * Gradle task for generating domain interfaces and wrappers from SHACL and JSON-LD context files.
 */
@CacheableTask
abstract class OntologyGenerationTask : DefaultTask() {
    
    @get:Internal
    abstract val shaclPath: Property<String>
    
    @get:Internal
    abstract val contextPath: Property<String>
    
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    open val shaclFile: RegularFileProperty = project.objects.fileProperty()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    open val contextFile: RegularFileProperty = project.objects.fileProperty()

    // Preserve the legacy getters while keeping execution independent of Project.
    @get:Internal
    val shaclInput: File get() = shaclFile.get().asFile
    @get:Internal
    val contextInput: File get() = contextFile.get().asFile

    init {
        val root = project.layout.projectDirectory.asFile
        shaclFile.convention(project.layout.file(shaclPath.map { resolveOntologyInput(root, it) }))
        contextFile.convention(project.layout.file(contextPath.map { resolveOntologyInput(root, it) }))
    }

    @get:Input
    @get:Optional
    abstract val interfacePackage: Property<String>
    
    @get:Input
    @get:Optional
    abstract val wrapperPackage: Property<String>
    
    @get:Input
    @get:Optional
    abstract val vocabularyPackage: Property<String>
    
    @get:Input
    @get:Optional
    abstract val generateInterfaces: Property<Boolean>
    
    @get:Input
    @get:Optional
    abstract val generateWrappers: Property<Boolean>
    
    @get:Input
    @get:Optional
    abstract val generateVocabulary: Property<Boolean>
    
    @get:Input
    @get:Optional
    abstract val vocabularyName: Property<String>
    
    @get:Input
    @get:Optional
    abstract val vocabularyNamespace: Property<String>
    
    @get:Input
    @get:Optional
    abstract val vocabularyPrefix: Property<String>
    
    @get:Input
    @get:Optional
    abstract val generateDsl: Property<Boolean>
    
    @get:Input
    @get:Optional
    abstract val dslPackage: Property<String>
    
    @get:Input
    @get:Optional
    abstract val dslName: Property<String>
    
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
    
    @TaskAction
    fun generateOntology() {
        logger.info("Starting ontology generation...")
        
        val shaclFile = this.shaclFile.get().asFile
        val contextFile = this.contextFile.get().asFile
        val kspLogger = GradleKspLogger(logger)
        val shaclParser = ShaclParser(kspLogger)
        val contextParser = JsonLdContextParser(kspLogger)
        val interfaceGenerator = InterfaceGenerator(kspLogger, ValidationAnnotations.NONE)
        val wrapperGenerator = OntologyWrapperGenerator(kspLogger)
        val vocabularyGenerator = VocabularyGenerator(kspLogger)
        val dslGenerator = InstanceDslGenerator(kspLogger)
        val basePackage = interfacePackage.getOrElse("com.example.generated")
        val actualInterfacePackage = interfacePackage.getOrElse(basePackage)
        val wrapperPackage = wrapperPackage.getOrElse(basePackage)
        val vocabularyPackage = vocabularyPackage.getOrElse(basePackage)
        val generateInterfaces = generateInterfaces.getOrElse(true)
        val generateWrappers = generateWrappers.getOrElse(true)
        val vocabularyName = vocabularyName.getOrElse("")
        val vocabularyNamespace = vocabularyNamespace.getOrElse("")
        val vocabularyPrefix = vocabularyPrefix.getOrElse("")
        // Auto-enable vocabulary generation if vocabulary metadata is provided
        val generateVocabulary = generateVocabulary.getOrElse(
            vocabularyName.isNotBlank() && vocabularyNamespace.isNotBlank() && vocabularyPrefix.isNotBlank()
        )
        val generateDsl = generateDsl.getOrElse(false)
        val dslPackage = dslPackage.getOrElse(basePackage + ".dsl")
        val dslName = dslName.getOrElse("")
        
        logger.info("SHACL file: ${shaclFile.absolutePath}")
        logger.info("Context file: ${contextFile.absolutePath}")
        logger.info("Base package: $basePackage")
        logger.info("Interface package: $actualInterfacePackage")
        logger.info("Wrapper package: $wrapperPackage")
        logger.info("Vocabulary package: $vocabularyPackage")
        logger.info("Generate interfaces: $generateInterfaces")
        logger.info("Generate wrappers: $generateWrappers")
        logger.info("Generate vocabulary: $generateVocabulary")
        logger.info("Generate DSL: $generateDsl")
        if (generateVocabulary) {
            logger.info("Vocabulary name: $vocabularyName")
            logger.info("Vocabulary namespace: $vocabularyNamespace")
            logger.info("Vocabulary prefix: $vocabularyPrefix")
        }
        if (generateDsl) {
            logger.info("DSL package: $dslPackage")
            logger.info("DSL name: ${if (dslName.isBlank()) "(auto)" else dslName}")
        }
        
        // Parse SHACL and JSON-LD context
        val shaclShapes = shaclFile.inputStream().use(shaclParser::parseShacl)
        val jsonLdContext = contextFile.inputStream().use(contextParser::parseContext)
        
        logger.info("Parsed ${shaclShapes.size} SHACL shapes")
        
        // Create output directories
        val outputDir = outputDirectory.get().asFile
        
        // Generate against the FULL model once, so cross-type references resolve and unshaped sh:class
        // targets fall back to IRI (String) correctly. (Previously each shape was generated in isolation,
        // which made every object property an IRI string because no other type was "known".)
        val fullModel = OntologyModel(shaclShapes, jsonLdContext)
        val allInterfaces = if (generateInterfaces) interfaceGenerator.generateInterfaces(fullModel, actualInterfacePackage, fallbackUnshapedToIri = true) else emptyMap()
        val allWrappers = if (generateWrappers) wrapperGenerator.generateWrappers(fullModel, wrapperPackage, actualInterfacePackage, fallbackUnshapedToIri = true) else emptyMap()

        val generated = mutableSetOf<String>()
        fun record(file: File) { generated.add(file.relativeTo(outputDir).invariantSeparatorsPath) }
        (allInterfaces.values + allWrappers.values).forEach { spec ->
            spec.writeTo(outputDir)
            record(File(outputDir, spec.packageName.replace('.', '/') + "/" + spec.name + ".kt"))
        }
        if (generateWrappers && wrapperPackage != actualInterfacePackage) {
            allWrappers.keys.forEach { wrapper ->
                val name = wrapper.removeSuffix("Wrapper")
                val factory = File(outputDir, actualInterfacePackage.replace('.', '/') + "/${name}Factory.kt")
                factory.parentFile.mkdirs()
                factory.writeText("// GENERATED FILE - DO NOT EDIT\npackage $actualInterfacePackage\ninternal object ${name}Factory { init { Class.forName(\"$wrapperPackage.$wrapper\") } }\n")
                record(factory)
            }
        }

        // Generate vocabulary file if requested
        if (generateVocabulary) {
            // Validate vocabulary metadata
            if (vocabularyName.isBlank()) {
                throw IllegalStateException("vocabularyName is required when generateVocabulary is true")
            }
            if (vocabularyNamespace.isBlank()) {
                throw IllegalStateException("vocabularyNamespace is required when generateVocabulary is true")
            }
            if (vocabularyPrefix.isBlank()) {
                throw IllegalStateException("vocabularyPrefix is required when generateVocabulary is true")
            }
            
            val vocabularyDir = File(outputDir, vocabularyPackage.replace('.', '/'))
            vocabularyDir.mkdirs()
            val vocabularyCode = vocabularyGenerator.generateVocabulary(
                shaclFile,
                contextFile,
                vocabularyName,
                vocabularyNamespace,
                vocabularyPrefix,
                vocabularyPackage
            )
            val vocabularyFile = File(vocabularyDir, "${vocabularyName}.kt")
            vocabularyFile.writeText(vocabularyCode)
            record(vocabularyFile)
            logger.info("Generated vocabulary: ${vocabularyFile.absolutePath}")
        }
        
        // Generate DSL if requested
        if (generateDsl) {
            val dslDir = File(outputDir, dslPackage.replace('.', '/'))
            dslDir.mkdirs()
            
            // Determine DSL name - use provided name or derive from ontology
            val actualDslName = if (dslName.isNotBlank()) {
                dslName
            } else {
                // Derive from context file name or use default
                val contextFileName = contextFile.nameWithoutExtension
                contextFileName.replace("-", "").replace("_", "").lowercase()
            }
            
            // Create ontology model from all shapes
            val ontologyModel = OntologyModel(shaclShapes, jsonLdContext)
            
            // Generate DSL
            val dslRequest = InstanceDslRequest(
                dslName = actualDslName,
                ontologyModel = ontologyModel,
                packageName = dslPackage,
                options = DslGenerationOptions()
            )
            
            val dslFileSpec = dslGenerator.generate(dslRequest)
            val dslFile = File(dslDir, "${actualDslName.replaceFirstChar { it.uppercaseChar() }}Dsl.kt")
            dslFile.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                dslFileSpec.writeTo(writer)
            }
            record(dslFile)
            logger.info("Generated DSL: ${dslFile.absolutePath}")
        }
        
        val manifest = File(outputDir, ".kastor-generated-files")
        val root = outputDir.toPath().toAbsolutePath().normalize()
        if (manifest.exists()) manifest.readLines().filter { it !in generated }.forEach { relative ->
            val stale = root.resolve(relative).normalize()
            require(stale.startsWith(root)) { "Generated manifest escapes output directory" }
            java.nio.file.Files.deleteIfExists(stale)
        }
        manifest.writeText(generated.sorted().joinToString("\n"))
        logger.info("Ontology generation completed successfully")
    }
    
 }

private fun resolveOntologyInput(root: File, path: String): File {
    require(path.isNotBlank()) { "Ontology input path must not be blank" }
    val requested = File(path)
    if (requested.isAbsolute) return requested
    val projectFile = File(root, path)
    return if (projectFile.exists()) projectFile else File(root, "src/main/resources/$path")
}
