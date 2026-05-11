package com.geoknoesis.kastor.gen.gradle.tasks

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
    
    @get:Input
    abstract val shaclPath: Property<String>
    
    @get:Input
    abstract val contextPath: Property<String>
    
    
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
    
    private val shaclParser = ShaclParser(object : KSPLogger {
        override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.info(message)
        }
        override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.info(message)
        }
        override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.warn(message)
        }
        override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.error(message)
        }
        override fun exception(e: Throwable) {
            logger.error("Exception occurred", e)
        }
    })
    
    private val contextParser = JsonLdContextParser(object : KSPLogger {
        override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.info(message)
        }
        override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.info(message)
        }
        override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.warn(message)
        }
        override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.error(message)
        }
        override fun exception(e: Throwable) {
            logger.error("Exception occurred", e)
        }
    })
    
    private val interfaceGenerator = InterfaceGenerator(object : KSPLogger {
        override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.info(message)
        }
        override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.info(message)
        }
        override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.warn(message)
        }
        override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.error(message)
        }
        override fun exception(e: Throwable) {
            logger.error("Exception occurred", e)
        }
    })
    
    private val wrapperGenerator = OntologyWrapperGenerator(object : KSPLogger {
        override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.info(message)
        }
        override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.info(message)
        }
        override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.warn(message)
        }
        override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.error(message)
        }
        override fun exception(e: Throwable) {
            logger.error("Exception occurred", e)
        }
    })
    
    private val vocabularyGenerator = VocabularyGenerator(object : KSPLogger {
        override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.info(message)
        }
        override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.info(message)
        }
        override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.warn(message)
        }
        override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.error(message)
        }
        override fun exception(e: Throwable) {
            logger.error("Exception occurred", e)
        }
    })
    
    private val dslGenerator = InstanceDslGenerator(object : KSPLogger {
        override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.info(message)
        }
        override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.info(message)
        }
        override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.warn(message)
        }
        override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {
            logger.error(message)
        }
        override fun exception(e: Throwable) {
            logger.error("Exception occurred", e)
        }
    })
    
    @TaskAction
    fun generateOntology() {
        logger.info("Starting ontology generation...")
        
        val shaclFile = resolveFile(shaclPath.get())
        val contextFile = resolveFile(contextPath.get())
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
        val shaclShapes = shaclParser.parseShacl(FileInputStream(shaclFile))
        val jsonLdContext = contextParser.parseContext(FileInputStream(contextFile))
        
        logger.info("Parsed ${shaclShapes.size} SHACL shapes")
        
        // Create output directories
        val outputDir = outputDirectory.get().asFile
        
        // Generate interfaces and wrappers for each shape
        shaclShapes.forEach { shape ->
            val className = jsonLdContext.typeMappings[shape.targetClass] 
                ?: shape.targetClass.substringAfterLast('#').substringAfterLast('/')
            
            logger.info("Generating code for class: $className")
            
            if (generateInterfaces) {
                val interfaceDir = File(outputDir, actualInterfacePackage.replace('.', '/'))
                interfaceDir.mkdirs()
                val interfaceFileSpec = interfaceGenerator.generateInterfaces(OntologyModel(listOf(shape), jsonLdContext), actualInterfacePackage)[className]
                val interfaceFile = File(interfaceDir, "$className.kt")
                interfaceFileSpec?.let {
                    interfaceFile.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                        it.writeTo(writer)
                    }
                }
                logger.info("Generated interface: ${interfaceFile.absolutePath}")
            }
            
            if (generateWrappers) {
                val wrapperDir = File(outputDir, wrapperPackage.replace('.', '/'))
                wrapperDir.mkdirs()
                val wrapperFileSpec = wrapperGenerator.generateWrappers(OntologyModel(listOf(shape), jsonLdContext), wrapperPackage)["${className}Wrapper"]
                val wrapperFile = File(wrapperDir, "${className}Wrapper.kt")
                wrapperFileSpec?.let {
                    wrapperFile.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                        it.writeTo(writer)
                    }
                }
                logger.info("Generated wrapper: ${wrapperFile.absolutePath}")
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
            logger.info("Generated DSL: ${dslFile.absolutePath}")
        }
        
        logger.info("Ontology generation completed successfully")
    }
    
    private fun resolveFile(path: String): File {
        val file = File(path)
        if (file.exists()) {
            return file
        }
        
        // Try relative to project directory
        val projectFile = File(project.projectDir, path)
        if (projectFile.exists()) {
            return projectFile
        }
        
        // Try in resources
        val resourceFile = File(project.projectDir, "src/main/resources/$path")
        if (resourceFile.exists()) {
            return resourceFile
        }
        
        throw IllegalArgumentException("File not found: $path (tried: $file, $projectFile, $resourceFile)")
    }
}












