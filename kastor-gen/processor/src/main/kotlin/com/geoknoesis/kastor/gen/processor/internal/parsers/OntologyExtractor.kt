package com.geoknoesis.kastor.gen.processor.internal.parsers

import com.geoknoesis.kastor.gen.processor.api.model.OntologyClass
import com.google.devtools.ksp.processing.KSPLogger
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.RDFS
import org.apache.jena.vocabulary.OWL
import java.io.InputStream
import java.io.StringReader

/**
 * Extracts classes from OWL/RDFS ontology files.
 * Uses Apache Jena for proper RDF parsing.
 *
 * Only **named** classes are generation targets. Anonymous class expressions (`[ a owl:Class ; owl:unionOf (...) ]`,
 * `[ a owl:Restriction ; ... ]`, `owl:complementOf`, ...) have no IRI to name a Kotlin type after: they are skipped,
 * both as classes and as superclasses, and what was skipped is reported at info level. The model never holds a class
 * or a superclass without an IRI.
 */
internal class OntologyExtractor(private val logger: KSPLogger) {

    companion object {
        private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        private const val RDFS_NS = "http://www.w3.org/2000/01/rdf-schema#"
        private const val OWL_NS = "http://www.w3.org/2002/07/owl#"
    }

    /**
     * Extracts classes from an OWL/RDFS ontology file.
     * 
     * @param inputStream The ontology file input stream
     * @return List of extracted ontology classes
     */
    fun extractClasses(inputStream: InputStream): List<OntologyClass> {
        val content = inputStream.bufferedReader().use { it.readText() }
        return extractClassesFromContent(content)
    }

    /**
     * Extracts classes from ontology content using Apache Jena.
     * 
     * @param content The ontology content as string
     * @return List of extracted ontology classes
     */
    fun extractClassesFromContent(content: String): List<OntologyClass> {
        val classes = mutableListOf<OntologyClass>()
        
        try {
            // Create Jena model and parse Turtle content
            val model = ModelFactory.createDefaultModel()
            model.read(StringReader(content), null, "TURTLE")
            
            logger.info("Successfully parsed ontology file with ${model.size()} triples")
            
            // Find all classes (OWL:Class or RDFS:Class)
            val owlClass = model.createResource("${OWL_NS}Class")
            val rdfsClass = model.createResource("${RDFS_NS}Class")
            
            val owlClasses = model.listSubjectsWithProperty(RDF.type, owlClass).toList()
            val rdfsClasses = model.listSubjectsWithProperty(RDF.type, rdfsClass).toList()
            
            val declared = (owlClasses + rdfsClasses).distinct()
            val allClassResources = declared.filter { it.isURIResource }.distinctBy { it.uri }
            logger.info("Found ${allClassResources.size} classes")
            val anonymous = declared.size - declared.count { it.isURIResource }
            if (anonymous > 0) {
                logger.info(
                    "Skipped $anonymous anonymous class expression(s) (owl:unionOf, owl:Restriction, ...): " +
                        "only named classes are generation targets"
                )
            }
            
            // Extract subClassOf relationships
            val subClassOfProp = model.createProperty("${RDFS_NS}subClassOf")
            
            allClassResources.forEach { classResource ->
                val classIri = classResource.uri
                val className = extractLocalName(classIri)
                
                // Extract super classes
                val superClasses = mutableListOf<String>()
                var skippedSuperClasses = 0
                classResource.listProperties(subClassOfProp).forEach { stmt ->
                    val superClass = stmt.`object`
                    // A restriction or another class expression (or a literal, in a broken ontology) is not a type.
                    if (superClass.isURIResource) superClasses.add(superClass.asResource().uri) else skippedSuperClasses++
                }
                if (skippedSuperClasses > 0) {
                    logger.info("Skipped $skippedSuperClasses anonymous superclass expression(s) of $classIri")
                }
                
                classes.add(OntologyClass(
                    classIri = classIri,
                    className = className,
                    superClasses = superClasses
                ))
                
                logger.info("Extracted class: $classIri ($className) with ${superClasses.size} super classes")
            }
            
        } catch (e: Exception) {
            logger.error("Error parsing ontology content: ${e.message}")
            logger.exception(e)
        }
        
        return classes
    }

    /**
     * Extracts the local name from an IRI.
     */
    private fun extractLocalName(iri: String): String {
        return iri.substringAfterLast('#').substringAfterLast('/')
    }

    /**
     * Matches ontology classes with SHACL shapes by IRI.
     * 
     * @param classes List of ontology classes
     * @param shapeTargetClasses List of target class IRIs from SHACL shapes
     * @return Map of class IRI to matching shape target class IRI
     */
    fun matchClassesWithShapes(
        classes: List<OntologyClass>,
        shapeTargetClasses: List<String>
    ): Map<String, String> {
        val matches = mutableMapOf<String, String>()
        
        classes.forEach { ontologyClass ->
            // Direct match
            if (ontologyClass.classIri in shapeTargetClasses) {
                matches[ontologyClass.classIri] = ontologyClass.classIri
            }
        }
        
        logger.info("Matched ${matches.size} classes with SHACL shapes")
        return matches
    }
}


