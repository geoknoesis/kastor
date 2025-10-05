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
   * @deprecated This is a placeholder implementation. Real SHACL validation coming soon.
   */
  @Deprecated("Placeholder implementation - real SHACL validation not yet implemented")
  override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
    // For now, implement a simple validation that checks for basic properties
    // In a real implementation, this would load SHACL shapes and validate against them
    
    // Simple validation: if it's a FOAF.Person, it should have a name
    val triples = data.getTriples()
    val focusTriples = triples.filter { it.subject == focus }
    
    val hasType = focusTriples.any { 
      it.predicate.value == RDF_TYPE &&
      it.obj is Iri && (it.obj as Iri).value == FOAF_PERSON
    }
    
    if (hasType) {
      val hasName = focusTriples.any { 
        it.predicate.value == FOAF_NAME
      }
      
      if (!hasName) {
        throw ValidationException(ERROR_PERSON_NAME)
      }
    }
    
    // If no specific validation rules apply, pass
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
            if (term.datatype != null) {
              jenaModel.createTypedLiteral(term.lexical, term.datatype.value)
            } else {
              jenaModel.createLiteral(term.lexical)
            }
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
