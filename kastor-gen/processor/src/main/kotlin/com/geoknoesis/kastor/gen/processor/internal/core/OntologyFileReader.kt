package com.geoknoesis.kastor.gen.processor.internal.core

import com.geoknoesis.kastor.gen.processor.api.exceptions.FileNotFoundException
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyClass
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.parsers.JsonLdContextParser
import com.geoknoesis.kastor.gen.processor.internal.parsers.OntologyExtractor
import com.geoknoesis.kastor.gen.processor.internal.parsers.ShaclParser
import com.geoknoesis.kastor.gen.processor.internal.utils.VocabularyMapper
import com.google.devtools.ksp.processing.KSPLogger
import java.io.File
import java.io.InputStream

/**
 * Reads and parses ontology files (SHACL, JSON-LD context, OWL/RDFS).
 *
 * A relative path such as `@Rdf(shacl = "shapes.ttl")` is resolved, in order, against:
 * 1. the resource directories of the source set containing the annotated file
 *    (`src/<set>/kotlin/…/File.kt` → `src/<set>/resources`, then `src/main/resources`, then the project directory),
 * 2. [searchRoots] (the KSP option `kastor.gen.resources`, a path-separator separated list),
 * 3. the processor class path (legacy behaviour).
 * Absolute paths are used as-is.
 *
 * @param searchRoots additional directories to resolve relative ontology paths against
 */
public class OntologyFileReader(
    private val logger: KSPLogger,
    private val searchRoots: List<File> = emptyList(),
) {

    private val shaclParser = ShaclParser(logger)
    private val contextParser = JsonLdContextParser(logger)
    private val ontologyExtractor = OntologyExtractor(logger)

    /**
     * Loads ontology model from SHACL and context files.
     *
     * @param near the annotated source file, used to locate its source set's resources
     */
    public fun loadOntologyModel(shaclPath: String, contextPath: String? = null, near: File? = null): OntologyModel {
        logger.info("Processing SHACL file: $shaclPath")

        val shapes = open(shaclPath, near).use { shaclParser.parseShacl(it) }
        logger.info("Parsed ${shapes.size} SHACL shapes")

        val context = if (contextPath != null && contextPath.isNotEmpty()) {
            logger.info("Processing context file: $contextPath")
            val parsedContext = open(contextPath, near).use { contextParser.parseContext(it) }
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
    public fun loadOntologyClasses(ontologyPath: String?, near: File? = null): List<OntologyClass> {
        if (ontologyPath == null || ontologyPath.isEmpty()) {
            return emptyList()
        }

        val classes = open(ontologyPath, near).use { ontologyExtractor.extractClasses(it) }
        logger.info("Parsed ${classes.size} ontology classes")

        return classes
    }

    /** Candidate files for [path], in resolution order. */
    public fun candidates(path: String, near: File? = null): List<File> {
        val requested = File(path)
        if (requested.isAbsolute) return listOf(requested)
        val dirs = LinkedHashSet<File>()
        near?.absoluteFile?.let { source ->
            var dir: File? = source.parentFile
            while (dir != null) {
                val parent = dir.parentFile
                if (parent != null && parent.name == "src") {
                    dirs += File(dir, "resources")
                    dirs += File(parent, "main/resources")
                    parent.parentFile?.let { dirs += it }
                    break
                }
                dir = parent
            }
        }
        dirs += searchRoots
        return dirs.map { File(it, path) }
    }

    private fun open(path: String, near: File?): InputStream {
        val tried = candidates(path, near)
        tried.firstOrNull { it.isFile }?.let {
            logger.info("Resolved $path to ${it.path}")
            return it.inputStream()
        }
        javaClass.classLoader.getResourceAsStream(path)?.let { return it }
        logger.error("Ontology file '$path' not found; tried ${tried.joinToString { it.path }} and the processor class path")
        throw FileNotFoundException(path)
    }

    /**
     * Creates empty context for cases where context is not provided.
     */
    public fun createEmptyContext(): JsonLdContext {
        return JsonLdContext(
            prefixes = emptyMap(),
            propertyMappings = emptyMap(),
            typeMappings = emptyMap()
        )
    }

    /**
     * Creates ontology classes from SHACL shapes as fallback.
     */
    public fun createClassesFromShapes(shapes: List<ShaclShape>): List<OntologyClass> {
        return shapes.map { shape ->
            OntologyClass(
                classIri = shape.targetClass,
                className = VocabularyMapper.extractLocalName(shape.targetClass)
            )
        }
    }
}
