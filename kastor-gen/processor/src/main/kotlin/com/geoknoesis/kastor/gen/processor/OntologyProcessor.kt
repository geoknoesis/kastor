package com.geoknoesis.kastor.gen.processor

import com.geoknoesis.kastor.gen.processor.exceptions.ProcessingException
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate

/**
 * KSP processor for generating domain interfaces and wrappers from SHACL and JSON-LD context files.
 * Analyzes ontology files and generates Kotlin code.
 */
class OntologyProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    private val annotationParser = AnnotationParser(logger)
    private val fileReader = OntologyFileReader(logger)
    private val coordinator = GenerationCoordinator(logger, codeGenerator)

    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.info("Ontology processor starting...")
        
        val processedAnnotations = mutableListOf<KSAnnotated>()
        
        // Process ontology generation annotations
        val ontologyAnnotations = resolver.getSymbolsWithAnnotation("com.geoknoesis.kastor.gen.annotations.GenerateFromOntology")
        
        ontologyAnnotations
            .filterIsInstance<KSClassDeclaration>()
            .forEach { symbol ->
                symbol.annotations
                    .find { it.shortName.asString() == "GenerateFromOntology" }
                    ?.let { ann ->
                        annotationParser.parseOntologyAnnotation(ann, symbol.packageName.asString())
                    }
                    ?.let { request ->
                        try {
                            val model = fileReader.loadOntologyModel(request.shaclPath, request.contextPath)
                            coordinator.generateFromOntology(
                                model = model,
                                packageName = request.targetPackage,
                                generateInterfaces = request.generateInterfaces,
                                generateWrappers = request.generateWrappers,
                                validationMode = request.validationMode,
                                validationAnnotations = request.validationAnnotations,
                                externalValidatorClass = request.externalValidatorClass
                            )
                            processedAnnotations.add(symbol)
                        } catch (e: Exception) {
                            logger.error("Error processing ontology generation: ${e.message}", symbol)
                            logger.exception(e)
                            throw ProcessingException(
                                message = "Failed to process GenerateFromOntology annotation",
                                annotationName = "GenerateFromOntology",
                                cause = e
                            )
                        }
                    }
            }
        
        // Process instance DSL generation annotations
        val dslAnnotations = resolver.getSymbolsWithAnnotation("com.geoknoesis.kastor.gen.annotations.GenerateInstanceDsl")
        
        dslAnnotations
            .filterIsInstance<KSClassDeclaration>()
            .forEach { symbol ->
                symbol.annotations
                    .find { it.shortName.asString() == "GenerateInstanceDsl" }
                    ?.let { ann ->
                        annotationParser.parseInstanceDslAnnotation(ann, symbol.packageName.asString())
                    }
                    ?.let { request ->
                        try {
                            val model = fileReader.loadOntologyModel(request.shaclPath, request.contextPath)
                            coordinator.generateInstanceDsl(
                                model = model,
                                dslName = request.dslName,
                                packageName = request.targetPackage
                            )
                            processedAnnotations.add(symbol)
                        } catch (e: Exception) {
                            logger.error("Error processing instance DSL generation: ${e.message}", symbol)
                            logger.exception(e)
                            throw ProcessingException(
                                message = "Failed to process GenerateInstanceDsl annotation",
                                annotationName = "GenerateInstanceDsl",
                                cause = e
                            )
                        }
                    }
            }
        
        if (processedAnnotations.isEmpty()) {
            logger.info("No annotations found, returning empty list")
            return emptyList()
        }
        
        return processedAnnotations.filterNot { it.validate() }.toList()
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












