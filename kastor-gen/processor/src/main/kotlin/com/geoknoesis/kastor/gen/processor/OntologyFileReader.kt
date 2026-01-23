package com.geoknoesis.kastor.gen.processor

import com.geoknoesis.kastor.gen.processor.exceptions.FileNotFoundException
import com.geoknoesis.kastor.gen.processor.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.model.OntologyClass
import com.geoknoesis.kastor.gen.processor.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.parsers.JsonLdContextParser
import com.geoknoesis.kastor.gen.processor.parsers.OntologyExtractor
import com.geoknoesis.kastor.gen.processor.parsers.ShaclParser
import com.geoknoesis.kastor.gen.processor.utils.VocabularyMapper
import com.google.devtools.ksp.processing.KSPLogger

/**
 * Reads and parses ontology files (SHACL, JSON-LD context, OWL/RDFS).
 */
class OntologyFileReader(private val logger: KSPLogger) {
    
    private val shaclParser = ShaclParser(logger)
    private val contextParser = JsonLdContextParser(logger)
    private val ontologyExtractor = OntologyExtractor(logger)
    
    /**
     * Loads ontology model from SHACL and context files.
     */
    fun loadOntologyModel(shaclPath: String, contextPath: String? = null): OntologyModel {
        logger.info("Processing SHACL file: $shaclPath")
        
        val shaclInputStream = javaClass.classLoader.getResourceAsStream(shaclPath)
            ?: throw FileNotFoundException(shaclPath)
        
        val shapes = shaclParser.parseShacl(shaclInputStream)
        logger.info("Parsed ${shapes.size} SHACL shapes")
        
        val context = if (contextPath != null && contextPath.isNotEmpty()) {
            logger.info("Processing context file: $contextPath")
            val contextInputStream = javaClass.classLoader.getResourceAsStream(contextPath)
                ?: throw FileNotFoundException(contextPath)
            val parsedContext = contextParser.parseContext(contextInputStream)
            logger.info("Parsed context with ${parsedContext.prefixes.size} prefixes and ${parsedContext.propertyMappings.size} properties")
            parsedContext
        } else {
            logger.info("No context file provided, using empty context")
            createEmptyContext()
        }
        
        return OntologyModel(shapes, context)
    }
    
    /**
     * Loads ontology classes from OWL/RDFS file.
     */
    fun loadOntologyClasses(ontologyPath: String?): List<OntologyClass> {
        if (ontologyPath == null || ontologyPath.isEmpty()) {
            return emptyList()
        }
        
        val ontologyInputStream = javaClass.classLoader.getResourceAsStream(ontologyPath)
            ?: throw FileNotFoundException(ontologyPath)
        
        val classes = ontologyExtractor.extractClasses(ontologyInputStream)
        logger.info("Parsed ${classes.size} ontology classes")
        
        return classes
    }
    
    /**
     * Creates empty context for cases where context is not provided.
     */
    fun createEmptyContext(): JsonLdContext {
        return JsonLdContext(
            prefixes = emptyMap(),
            propertyMappings = emptyMap(),
            typeMappings = emptyMap()
        )
    }
    
    /**
     * Creates ontology classes from SHACL shapes as fallback.
     */
    fun createClassesFromShapes(shapes: List<ShaclShape>): List<OntologyClass> {
        return shapes.map { shape ->
            OntologyClass(
                classIri = shape.targetClass,
                className = VocabularyMapper.extractLocalName(shape.targetClass)
            )
        }
    }
}

