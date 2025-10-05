package com.geoknoesis.ontomapper.gradle.tasks

import com.example.ontomapper.processor.codegen.InterfaceGenerator
import com.example.ontomapper.processor.codegen.OntologyWrapperGenerator
import com.example.ontomapper.processor.model.ShaclShape
import com.example.ontomapper.processor.model.JsonLdContext
import com.example.ontomapper.processor.model.OntologyModel
import com.example.ontomapper.processor.parsers.ShaclParser
import com.example.ontomapper.processor.parsers.JsonLdContextParser
import com.geoknoesis.ontomapper.gradle.VocabularyGenerator
import com.google.devtools.ksp.processing.KSPLogger
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import java.io.File
import java.io.FileInputStream

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
    abstract val targetPackage: Property<String>
    
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
    
    @TaskAction
    fun generateOntology() {
        logger.info("Starting ontology generation...")
        
        val shaclFile = resolveFile(shaclPath.get())
        val contextFile = resolveFile(contextPath.get())
        val basePackage = targetPackage.getOrElse("com.example.generated")
        val interfacePackage = interfacePackage.getOrElse(basePackage)
        val wrapperPackage = wrapperPackage.getOrElse(basePackage)
        val vocabularyPackage = vocabularyPackage.getOrElse(basePackage)
        val generateInterfaces = generateInterfaces.getOrElse(true)
        val generateWrappers = generateWrappers.getOrElse(true)
        val generateVocabulary = generateVocabulary.getOrElse(false)
        val vocabularyName = vocabularyName.getOrElse("Vocabulary")
        val vocabularyNamespace = vocabularyNamespace.getOrElse("http://example.org/vocab#")
        val vocabularyPrefix = vocabularyPrefix.getOrElse("vocab")
        
        logger.info("SHACL file: ${shaclFile.absolutePath}")
        logger.info("Context file: ${contextFile.absolutePath}")
        logger.info("Base package: $basePackage")
        logger.info("Interface package: $interfacePackage")
        logger.info("Wrapper package: $wrapperPackage")
        logger.info("Vocabulary package: $vocabularyPackage")
        logger.info("Generate interfaces: $generateInterfaces")
        logger.info("Generate wrappers: $generateWrappers")
        logger.info("Generate vocabulary: $generateVocabulary")
        if (generateVocabulary) {
            logger.info("Vocabulary name: $vocabularyName")
            logger.info("Vocabulary namespace: $vocabularyNamespace")
            logger.info("Vocabulary prefix: $vocabularyPrefix")
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
                val interfaceDir = File(outputDir, interfacePackage.replace('.', '/'))
                interfaceDir.mkdirs()
                val interfaceCode = interfaceGenerator.generateInterfaces(OntologyModel(listOf(shape), jsonLdContext), interfacePackage)[className] ?: ""
                val interfaceFile = File(interfaceDir, "$className.kt")
                interfaceFile.writeText(interfaceCode)
                logger.info("Generated interface: ${interfaceFile.absolutePath}")
            }
            
            if (generateWrappers) {
                val wrapperDir = File(outputDir, wrapperPackage.replace('.', '/'))
                wrapperDir.mkdirs()
                val wrapperCode = wrapperGenerator.generateWrappers(OntologyModel(listOf(shape), jsonLdContext), wrapperPackage)["${className}Wrapper"] ?: ""
                val wrapperFile = File(wrapperDir, "${className}Wrapper.kt")
                wrapperFile.writeText(wrapperCode)
                logger.info("Generated wrapper: ${wrapperFile.absolutePath}")
            }
        }
        
        // Generate vocabulary file if requested
        if (generateVocabulary) {
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
