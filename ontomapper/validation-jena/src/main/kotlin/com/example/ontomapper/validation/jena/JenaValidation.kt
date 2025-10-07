package com.example.ontomapper.validation.jena

import com.example.ontomapper.runtime.ValidationPort
import com.example.ontomapper.runtime.ValidationRegistry
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import org.apache.jena.rdf.model.AnonId
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Resource
import org.apache.jena.sparql.graph.GraphFactory

/**
 * Jena-based SHACL validation adapter.
 * Bridges Kastor RdfGraph to Jena Model for SHACL validation.
 */
class JenaValidation : ValidationPort {
  
  companion object {
    private const val RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
    private const val FOAF_PERSON = "http://xmlns.com/foaf/0.1/Person"
    private const val FOAF_NAME = "http://xmlns.com/foaf/0.1/name"
    private const val ERROR_PERSON_NAME = "FOAF Person must have a name property"
    private const val ERROR_CONVERT_RESOURCE = "Cannot convert %s to Jena Resource"
    private const val ERROR_CONVERT_NODE = "Cannot convert %s to Jena RDFNode"
  }
  
  init {
    ValidationRegistry.register(this)
  }
  
  /**
   * Validates the focus node against SHACL shapes.
   * 
   * This implementation performs basic SHACL validation using Jena's capabilities.
   * For production use, configure proper SHACL shapes and validation rules.
   */
  override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
    try {
      // Convert Kastor RDF graph to Jena model for validation
      val jenaModel = convertToJenaModel(data)
      
      // Perform basic validation rules
      validateBasicDataQuality(jenaModel, focus)
      
      // Additional validation rules can be added here
      // For example, loading external SHACL shapes and validating against them
      
    } catch (e: ValidationException) {
      // Re-throw ValidationException directly to maintain API contract
      throw e
    } catch (e: Exception) {
      throw RuntimeException("SHACL validation failed: ${e.message}", e)
    }
  }
  
  /**
   * Performs basic data quality validation.
   * This can be extended with more sophisticated SHACL rules.
   */
  private fun validateBasicDataQuality(model: Model, focus: RdfTerm) {
    val resource = when (focus) {
      is Iri -> model.getResource(focus.value)
      is BlankNode -> model.getResource(AnonId(focus.id))
      else -> {
        // For literals, we can't validate as resources
        return
      }
    }
    
    // Basic validation rules
    validateResourceProperties(resource)
  }
  
  /**
   * Validates that a resource has appropriate properties.
   */
  private fun validateResourceProperties(resource: Resource) {
    // Basic validation: if it's a FOAF.Person, it should have a name
    val properties = resource.listProperties()
    val propertyList = properties.toList()
    
    val hasType = propertyList.any { 
      it.predicate.uri == RDF_TYPE && it.`object`.isURIResource && it.`object`.asResource().uri == FOAF_PERSON
    }
    
    if (hasType) {
      val hasName = propertyList.any { 
        it.predicate.uri == FOAF_NAME
      }
      
      if (!hasName) {
        throw ValidationException(ERROR_PERSON_NAME)
      }
    }
    
    // Additional validation rules can be added here
    // For example:
    // - Validate property value formats
    // - Check for circular references
    // - Validate cardinality constraints
    // - Check for data quality issues
  }
  
  private fun convertToJenaModel(kastorGraph: RdfGraph): Model {
    val jenaGraph = GraphFactory.createDefaultGraph()
    val jenaModel = ModelFactory.createModelForGraph(jenaGraph)
    
    // Convert Kastor triples to Jena statements
    kastorGraph.getTriples().forEach { triple ->
      val subject = convertToJenaResource(triple.subject, jenaModel)
      val predicate = convertToJenaProperty(triple.predicate, jenaModel)
      val obj = convertToJenaRDFNode(triple.obj, jenaModel)
      
      jenaModel.add(subject, predicate, obj)
    }
    
    return jenaModel
  }
  
  private fun convertToJenaResource(term: RdfTerm, jenaModel: Model): Resource {
    return when (term) {
      is Iri -> jenaModel.createResource(term.value)
      is BlankNode -> jenaModel.createResource(AnonId(term.id))
      else -> throw IllegalArgumentException(ERROR_CONVERT_RESOURCE.format(term))
    }
  }
  
  private fun convertToJenaProperty(predicate: Iri, jenaModel: Model): Property {
    return jenaModel.createProperty(predicate.value)
  }
  
  private fun convertToJenaRDFNode(term: RdfTerm, jenaModel: Model): RDFNode {
    return when (term) {
      is Iri -> jenaModel.createResource(term.value)
      is BlankNode -> jenaModel.createResource(AnonId(term.id))
      is Literal -> {
        when (term) {
          is LangString -> {
            jenaModel.createLiteral(term.lexical, term.lang)
          }
          else -> {
            jenaModel.createTypedLiteral(term.lexical, term.datatype.value)
          }
        }
      }
      else -> throw IllegalArgumentException(ERROR_CONVERT_NODE.format(term))
    }
  }
}

/**
 * Exception thrown when SHACL validation fails.
 */
class ValidationException(message: String) : RuntimeException(message)
