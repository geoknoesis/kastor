package com.geoknoesis.kastor.gen.processor.parsers

import com.geoknoesis.kastor.gen.processor.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.model.ShaclProperty
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
        private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
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
        val minLengthProp = model.createProperty("${SHACL_NS}minLength")
        val maxLengthProp = model.createProperty("${SHACL_NS}maxLength")
        val patternProp = model.createProperty("${SHACL_NS}pattern")
        val minInclusiveProp = model.createProperty("${SHACL_NS}minInclusive")
        val maxInclusiveProp = model.createProperty("${SHACL_NS}maxInclusive")
        val minExclusiveProp = model.createProperty("${SHACL_NS}minExclusive")
        val maxExclusiveProp = model.createProperty("${SHACL_NS}maxExclusive")
        val inProp = model.createProperty("${SHACL_NS}in")
        val hasValueProp = model.createProperty("${SHACL_NS}hasValue")
        val qualifiedValueShapeProp = model.createProperty("${SHACL_NS}qualifiedValueShape")
        val qualifiedMinCountProp = model.createProperty("${SHACL_NS}qualifiedMinCount")
        val qualifiedMaxCountProp = model.createProperty("${SHACL_NS}qualifiedMaxCount")
        
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
            val nodeKind = propertyShape.getProperty(nodeKindProp)?.resource?.uri
            val minLength = propertyShape.getProperty(minLengthProp)?.int
            val maxLength = propertyShape.getProperty(maxLengthProp)?.int
            val pattern = propertyShape.getProperty(patternProp)?.string
            val minInclusive = propertyShape.getProperty(minInclusiveProp)?.double
            val maxInclusive = propertyShape.getProperty(maxInclusiveProp)?.double
            val minExclusive = propertyShape.getProperty(minExclusiveProp)?.double
            val maxExclusive = propertyShape.getProperty(maxExclusiveProp)?.double
            val hasValue = propertyShape.getProperty(hasValueProp)?.let { 
                it.string ?: it.resource?.uri 
            }
            val qualifiedValueShape = propertyShape.getProperty(qualifiedValueShapeProp)?.resource?.uri
            val qualifiedMinCount = propertyShape.getProperty(qualifiedMinCountProp)?.int
            val qualifiedMaxCount = propertyShape.getProperty(qualifiedMaxCountProp)?.int
            
            // Extract sh:in values (RDF list)
            val inValues = propertyShape.getProperty(inProp)?.resource?.let { listResource ->
                val listValues = mutableListOf<String>()
                var current = listResource
                while (current != null && !current.hasProperty(model.createProperty("${RDF_NS}nil"))) {
                    val first = current.getProperty(model.createProperty("${RDF_NS}first"))
                    first?.let {
                        val value = it.string ?: it.resource?.uri
                        if (value != null) listValues.add(value)
                    }
                    current = current.getProperty(model.createProperty("${RDF_NS}rest"))?.resource
                }
                listValues.takeIf { it.isNotEmpty() }
            }
            
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
                    maxCount = maxCount,
                    minLength = minLength,
                    maxLength = maxLength,
                    pattern = pattern,
                    minInclusive = minInclusive,
                    maxInclusive = maxInclusive,
                    minExclusive = minExclusive,
                    maxExclusive = maxExclusive,
                    inValues = inValues,
                    hasValue = hasValue,
                    nodeKind = nodeKind,
                    qualifiedValueShape = qualifiedValueShape,
                    qualifiedMinCount = qualifiedMinCount,
                    qualifiedMaxCount = qualifiedMaxCount
                ))
            }
        }
        
        return properties
    }
}












