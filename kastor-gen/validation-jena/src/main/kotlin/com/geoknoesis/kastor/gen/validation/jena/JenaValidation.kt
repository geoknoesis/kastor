package com.geoknoesis.kastor.gen.validation.jena

import com.geoknoesis.kastor.gen.runtime.ShaclSeverity
import com.geoknoesis.kastor.gen.runtime.ShaclValidation
import com.geoknoesis.kastor.gen.runtime.ShaclValidator
import com.geoknoesis.kastor.gen.runtime.ShaclViolation
import com.geoknoesis.kastor.gen.runtime.ValidationResult
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
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.shacl.ShaclValidator
import org.apache.jena.sparql.graph.GraphFactory
import org.apache.jena.vocabulary.RDF

/**
 * Jena-based SHACL validation adapter.
 * Bridges Kastor RdfGraph to Jena Model for SHACL validation.
 */
class JenaValidation : ShaclValidator {
  
  companion object {
    private const val SHACL_NS = "http://www.w3.org/ns/shacl#"
    private const val ERROR_CONVERT_RESOURCE = "Cannot convert %s to Jena Resource"
    private const val ERROR_CONVERT_NODE = "Cannot convert %s to Jena RDFNode"
    private const val ERROR_NO_FOCUS_RESULTS = "SHACL validation failed for focus node"
    private val NODE_SHAPE = ResourceFactory.createResource("${SHACL_NS}NodeShape")
    private val FOCUS_NODE = ResourceFactory.createProperty("${SHACL_NS}focusNode")
    private val RESULT_MESSAGE = ResourceFactory.createProperty("${SHACL_NS}resultMessage")
    private val RESULT_PATH = ResourceFactory.createProperty("${SHACL_NS}resultPath")
    private val RESULT_SEVERITY = ResourceFactory.createProperty("${SHACL_NS}resultSeverity")
  }
  
  init {
    ShaclValidation.register(this)
  }
  
  /**
   * Validates the focus node against SHACL shapes.
   *
   * This implementation performs basic SHACL validation using Jena's capabilities.
   * For production use, configure proper SHACL shapes and validation rules.
   */
  override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
    return try {
      val jenaModel = convertToJenaModel(data)

      if (!jenaModel.contains(null, RDF.type, NODE_SHAPE)) {
        return ValidationResult.Ok
      }

      val report = ShaclValidator.get().validate(jenaModel, jenaModel)
      if (report.conforms()) {
        return ValidationResult.Ok
      }

      val focusResource = when (focus) {
        is Iri -> jenaModel.createResource(focus.value)
        is BlankNode -> jenaModel.createResource(AnonId(focus.id))
        else -> null
      } ?: return ValidationResult.Violations(listOf(ShaclViolation(null, ERROR_NO_FOCUS_RESULTS)))

      val reportModel = report.model
      val focusResults = reportModel.listResourcesWithProperty(FOCUS_NODE, focusResource).toList()
      if (focusResults.isEmpty()) {
        return ValidationResult.Violations(listOf(ShaclViolation(null, ERROR_NO_FOCUS_RESULTS)))
      }

      val violations = focusResults.flatMap { result ->
        val path = result.getProperty(RESULT_PATH)?.`object`?.asResource()?.uri?.let { Iri(it) }
        val severity = result.getProperty(RESULT_SEVERITY)?.`object`?.asResource()?.uri
          ?.let { mapSeverity(it) } ?: ShaclSeverity.Violation
        val messages = result.listProperties(RESULT_MESSAGE)
          .toList()
          .mapNotNull { it.`object`?.toString() }
          .ifEmpty { listOf(ERROR_NO_FOCUS_RESULTS) }
        messages.map { message -> ShaclViolation(path, message, severity) }
      }.ifEmpty { listOf(ShaclViolation(null, ERROR_NO_FOCUS_RESULTS)) }

      ValidationResult.Violations(violations)
    } catch (e: Exception) {
      ValidationResult.Violations(listOf(ShaclViolation(null, "SHACL validation failed: ${e.message}")))
    }
  }

  private fun mapSeverity(uri: String): ShaclSeverity {
    return when (uri) {
      "${SHACL_NS}Violation" -> ShaclSeverity.Violation
      "${SHACL_NS}Warning" -> ShaclSeverity.Warning
      "${SHACL_NS}Info" -> ShaclSeverity.Info
      else -> ShaclSeverity.Violation
    }
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









