package com.example.ontomapper.processor

import com.example.ontomapper.processor.codegen.InterfaceGenerator
import com.example.ontomapper.processor.codegen.OntologyWrapperGenerator
import com.example.ontomapper.processor.model.OntologyModel
import com.example.ontomapper.processor.parsers.JsonLdContextParser
import com.example.ontomapper.processor.parsers.ShaclParser
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import java.io.InputStream

/**
 * KSP processor for generating domain interfaces and wrappers from SHACL and JSON-LD context files.
 * Analyzes ontology files and generates Kotlin code.
 */
class OntologyProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    private val shaclParser = ShaclParser(logger)
    private val contextParser = JsonLdContextParser(logger)
    private val interfaceGenerator = InterfaceGenerator(logger)
    private val wrapperGenerator = OntologyWrapperGenerator(logger)

    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.info("Ontology processor starting...")
        
        // Look for GenerateFromOntology annotations
        val ontologyAnnotations = resolver.getSymbolsWithAnnotation("com.example.ontomapper.annotations.GenerateFromOntology")
        
        val allSymbols = ontologyAnnotations.toList()
        
        logger.info("Found ${allSymbols.size} ontology generation annotations")
        
        if (allSymbols.isEmpty()) {
            logger.info("No ontology annotations found, returning empty list")
            return emptyList()
        }

        val processedAnnotations = mutableListOf<KSAnnotated>()
        
        allSymbols.forEach { symbol ->
            if (symbol is KSClassDeclaration) {
                val annotation = symbol.annotations.find { 
                    it.shortName.asString() == "GenerateFromOntology" 
                }
                if (annotation != null) {
                    processOntologyAnnotation(annotation, symbol.packageName.asString())
                    processedAnnotations.add(symbol)
                }
            } else {
                logger.error("GenerateFromOntology annotation can only be applied to classes", symbol)
            }
        }
        
        return processedAnnotations.filterNot { it.validate() }.toList()
    }
    
    private fun processOntologyAnnotation(annotation: KSAnnotation, packageName: String) {
        val shaclPath = getAnnotationValue(annotation, "shaclPath") as? String
        val contextPath = getAnnotationValue(annotation, "contextPath") as? String
        val targetPackage = getAnnotationValue(annotation, "packageName") as? String ?: packageName
        val generateInterfaces = getAnnotationValue(annotation, "generateInterfaces") as? Boolean ?: true
        val generateWrappers = getAnnotationValue(annotation, "generateWrappers") as? Boolean ?: true
        
        if (shaclPath == null || contextPath == null) {
            logger.error("Both shaclPath and contextPath must be specified")
            return
        }
        
        processOntologyFiles(shaclPath, contextPath, targetPackage, generateInterfaces, generateWrappers)
    }
    
    
    private fun processOntologyFiles(
        shaclPath: String, 
        contextPath: String, 
        packageName: String,
        generateInterfaces: Boolean,
        generateWrappers: Boolean
    ) {
        try {
            logger.info("Processing SHACL file: $shaclPath")
            logger.info("Processing context file: $contextPath")
            logger.info("Target package: $packageName")
            
            // Parse SHACL file
            val shaclInputStream = javaClass.classLoader.getResourceAsStream(shaclPath)
                ?: throw IllegalArgumentException("SHACL file not found: $shaclPath")
            
            val shapes = shaclParser.parseShacl(shaclInputStream)
            logger.info("Parsed ${shapes.size} SHACL shapes")
            
            // Parse JSON-LD context file
            val contextInputStream = javaClass.classLoader.getResourceAsStream(contextPath)
                ?: throw IllegalArgumentException("Context file not found: $contextPath")
            
            val context = contextParser.parseContext(contextInputStream)
            logger.info("Parsed context with ${context.prefixes.size} prefixes and ${context.propertyMappings.size} properties")
            
            // Create ontology model
            val ontologyModel = OntologyModel(shapes, context)
            
            // Generate interfaces
            if (generateInterfaces) {
                val interfaces = interfaceGenerator.generateInterfaces(ontologyModel, packageName)
                interfaces.forEach { (name, code) ->
                    generateFile("$name.kt", packageName, code)
                }
            }
            
            // Generate wrappers
            if (generateWrappers) {
                val wrappers = wrapperGenerator.generateWrappers(ontologyModel, packageName)
                wrappers.forEach { (name, code) ->
                    generateFile("$name.kt", packageName, code)
                }
            }
            
        } catch (e: Exception) {
            logger.error("Error processing ontology files: ${e.message}", null)
        }
    }
    
    private fun generateFile(fileName: String, packageName: String, code: String) {
        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(false),
            packageName = packageName,
            fileName = fileName
        )
        
        file.write(code.toByteArray())
        file.close()
        
        logger.info("Generated file: $fileName in package $packageName")
    }
    
    private fun getAnnotationValue(annotation: KSAnnotation, name: String): Any? {
        return annotation.arguments.find { it.name?.asString() == name }?.value
    }
}

/**
 * Processor provider for ontology generation.
 */
class OntologyProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return OntologyProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
            options = environment.options
        )
    }
}
