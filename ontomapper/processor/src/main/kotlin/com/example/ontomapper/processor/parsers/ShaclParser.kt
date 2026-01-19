package com.example.ontomapper.processor.parsers

import com.example.ontomapper.processor.model.ShaclShape
import com.example.ontomapper.processor.model.ShaclProperty
import com.google.devtools.ksp.processing.KSPLogger
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.RDF
import java.io.InputStream
import java.io.StringReader

/**
 * Parser for SHACL (Shapes Constraint Language) files.
 * Extracts NodeShapes and their property constraints for code generation.
 * Uses Apache Jena for proper RDF parsing.
 */
class ShaclParser(private val logger: KSPLogger) {

    companion object {
        private const val SHACL_NS = "http://www.w3.org/ns/shacl#"
        private const val RDFS_NS = "http://www.w3.org/2000/01/rdf-schema#"
        private const val XSD_NS = "http://www.w3.org/2001/XMLSchema#"
    }

    /**
     * Parses a SHACL file and extracts NodeShapes.
     * 
     * @param inputStream The SHACL file input stream
     * @return List of extracted SHACL shapes
     */
    fun parseShacl(inputStream: InputStream): List<ShaclShape> {
        val content = inputStream.bufferedReader().use { it.readText() }
        return parseShaclContent(content)
    }

    /**
     * Parses SHACL content from a string using Apache Jena.
     * 
     * @param content The SHACL content as string
     * @return List of extracted SHACL shapes
     */
    fun parseShaclContent(content: String): List<ShaclShape> {
        val shapes = mutableListOf<ShaclShape>()
        
        try {
            // Create Jena model and parse Turtle content
            val model = ModelFactory.createDefaultModel()
            model.read(StringReader(content), null, "TURTLE")
            
            logger.info("Successfully parsed SHACL file with ${model.size()} triples")
            
            // Find all NodeShapes
            val nodeShapeClass = model.createResource("${SHACL_NS}NodeShape")
            val targetClassProp = model.createProperty("${SHACL_NS}targetClass")
            val propertyProp = model.createProperty("${SHACL_NS}property")
            
            val nodeShapes = model.listSubjectsWithProperty(RDF.type, nodeShapeClass).toList()
            logger.info("Found ${nodeShapes.size} NodeShapes")
            
            nodeShapes.forEach { shapeResource ->
                val targetClass = shapeResource.getProperty(targetClassProp)?.resource?.uri
                
                if (targetClass != null) {
                    val properties = extractPropertiesFromShape(shapeResource, model)
                    
                    shapes.add(ShaclShape(
                        shapeIri = shapeResource.uri,
                        targetClass = targetClass,
                        properties = properties
                    ))
                    
                    logger.info("Extracted shape: ${shapeResource.uri} -> $targetClass with ${properties.size} properties")
                }
            }
            
        } catch (e: Exception) {
            logger.error("Error parsing SHACL content: ${e.message}")
            logger.exception(e)
        }
        
        return shapes
    }

    private fun extractPropertiesFromShape(shapeResource: Resource, model: org.apache.jena.rdf.model.Model): List<ShaclProperty> {
        val properties = mutableListOf<ShaclProperty>()
        
        val propertyProp = model.createProperty("${SHACL_NS}property")
        val pathProp = model.createProperty("${SHACL_NS}path")
        val nameProp = model.createProperty("${SHACL_NS}name")
        val descriptionProp = model.createProperty("${SHACL_NS}description")
        val datatypeProp = model.createProperty("${SHACL_NS}datatype")
        val classProp = model.createProperty("${SHACL_NS}class")
        val minCountProp = model.createProperty("${SHACL_NS}minCount")
        val maxCountProp = model.createProperty("${SHACL_NS}maxCount")
        val nodeKindProp = model.createProperty("${SHACL_NS}nodeKind")
        
        // Get all property constraints
        val propertyConstraints = shapeResource.listProperties(propertyProp).toList()
        
        propertyConstraints.forEach { propertyStmt ->
            val propertyShape = propertyStmt.resource
            
            val path = propertyShape.getProperty(pathProp)?.resource?.uri
            val name = propertyShape.getProperty(nameProp)?.string
            val description = propertyShape.getProperty(descriptionProp)?.string ?: ""
            val datatype = propertyShape.getProperty(datatypeProp)?.resource?.uri
            val targetClass = propertyShape.getProperty(classProp)?.resource?.uri
            val minCount = propertyShape.getProperty(minCountProp)?.int
            val maxCount = propertyShape.getProperty(maxCountProp)?.int
            
            // Extract property name from path if name is not provided
            val propertyName = name ?: path?.substringAfterLast('#')?.substringAfterLast('/')
            
            // Only add properties that have a path and either datatype or targetClass
            if (path != null && propertyName != null && (datatype != null || targetClass != null)) {
                properties.add(ShaclProperty(
                    path = path,
                    name = propertyName,
                    description = description,
                    datatype = datatype,
                    targetClass = targetClass,
                    minCount = minCount,
                    maxCount = maxCount
                ))
            }
        }
        
        return properties
    }
}












