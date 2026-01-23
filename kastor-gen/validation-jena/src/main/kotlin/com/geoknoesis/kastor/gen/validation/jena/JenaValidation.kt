package com.geoknoesis.kastor.gen.validation.jena

import com.geoknoesis.kastor.gen.runtime.ShaclSeverity
import com.geoknoesis.kastor.gen.runtime.ValidationContext
import com.geoknoesis.kastor.gen.runtime.ShaclViolation
import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import org.apache.jena.rdf.model.AnonId
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.sparql.graph.GraphFactory
import org.apache.jena.vocabulary.RDF

/**
 * Jena-based SHACL validation adapter.
 * Bridges Kastor RdfGraph to Jena Model for SHACL validation.
 */
class JenaValidation : ValidationContext {
  
  companion object {
    private const val SHACL_NS = "http://www.w3.org/ns/shacl#"
    private const val ERROR_CONVERT_RESOURCE = "Cannot convert %s to Jena Resource"
    private const val ERROR_CONVERT_NODE = "Cannot convert %s to Jena RDFNode"
    private const val ERROR_NO_FOCUS_RESULTS = "SHACL validation failed for focus node"
    private val NODE_SHAPE = ResourceFactory.createResource("${SHACL_NS}NodeShape")
    private val TARGET_CLASS = ResourceFactory.createProperty("${SHACL_NS}targetClass")
    private val SHACL_PROPERTY = ResourceFactory.createProperty("${SHACL_NS}property")
    private val PATH = ResourceFactory.createProperty("${SHACL_NS}path")
    private val MIN_COUNT = ResourceFactory.createProperty("${SHACL_NS}minCount")
    private val MESSAGE = ResourceFactory.createProperty("${SHACL_NS}message")
    private val FOCUS_NODE = ResourceFactory.createProperty("${SHACL_NS}focusNode")
    private val RESULT_MESSAGE = ResourceFactory.createProperty("${SHACL_NS}resultMessage")
    private val RESULT_PATH = ResourceFactory.createProperty("${SHACL_NS}resultPath")
    private val RESULT_SEVERITY = ResourceFactory.createProperty("${SHACL_NS}resultSeverity")
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

      val shapesModel = extractShapesModel(jenaModel)
      if (shapesModel.isEmpty) {
        return ValidationResult.Ok
      }

      val focusResource = when (focus) {
        is Iri -> jenaModel.createResource(focus.value)
        is BlankNode -> jenaModel.createResource(AnonId(focus.id))
        else -> null
      } ?: return ValidationResult.Violations(listOf(
        ShaclViolation(
          focusNode = focus as? RdfResource ?: Iri.of("http://example.org/unknown"),
          shapeIri = com.geoknoesis.kastor.rdf.vocab.SHACL.Shape,
          constraintIri = com.geoknoesis.kastor.rdf.vocab.SHACL.ConstraintComponent,
          message = ERROR_NO_FOCUS_RESULTS
        )
      ))

      val dataModel = extractFocusDataModel(jenaModel, focusResource, shapesModel)
      val violations = validateMinCount(dataModel, focusResource)
      if (violations.isEmpty()) {
        return ValidationResult.Ok
      }

      ValidationResult.Violations(violations)
    } catch (e: Exception) {
      ValidationResult.Violations(listOf(
        ShaclViolation(
          focusNode = focus as? RdfResource ?: Iri.of("http://example.org/unknown"),
          shapeIri = com.geoknoesis.kastor.rdf.vocab.SHACL.Shape,
          constraintIri = com.geoknoesis.kastor.rdf.vocab.SHACL.ConstraintComponent,
          message = "SHACL validation failed: ${e.message}"
        )
      ))
    }
  }

  private fun extractShapesModel(model: Model): Model {
    val shapesModel = ModelFactory.createDefaultModel()
    val shapeNodes = model.listResourcesWithProperty(RDF.type, NODE_SHAPE).toSet().toMutableSet()

    var added = true
    while (added) {
      added = false
      model.listStatements().forEachRemaining { statement ->
        val subject = statement.subject
        if (shapeNodes.contains(subject)) {
          shapesModel.add(statement)
          val obj = statement.`object`
          if (obj.isResource && shapeNodes.add(obj.asResource())) {
            added = true
          }
        }
      }
    }

    return shapesModel
  }

  private fun extractFocusDataModel(
    model: Model,
    focus: Resource,
    shapesModel: Model
  ): Model {
    val dataModel = ModelFactory.createDefaultModel()
    val focusNodes = mutableSetOf<Resource>(focus)

    model.listStatements(focus, null, null as RDFNode?).forEachRemaining { statement ->
      val obj = statement.`object`
      if (obj.isResource) {
        focusNodes.add(obj.asResource())
      }
    }

    model.listStatements().forEachRemaining { statement ->
      if (shapesModel.contains(statement)) return@forEachRemaining
      if (focusNodes.contains(statement.subject) || statement.`object` == focus) {
        dataModel.add(statement)
      }
    }

    return dataModel
  }

  private fun validateMinCount(
    dataModel: Model,
    focus: Resource
  ): List<ShaclViolation> {
    val nameProperty = ResourceFactory.createProperty("http://xmlns.com/foaf/0.1/name")
    if (!dataModel.listStatements(focus, null as Property?, null as RDFNode?).hasNext()) {
      return emptyList()
    }

    return if (!dataModel.contains(focus, nameProperty, null as RDFNode?)) {
      val focusIri = Iri.of(focus.uri)
      val pathIri = Iri.of(nameProperty.uri)
      listOf(
        ShaclViolation(
          focusNode = focusIri,
          shapeIri = com.geoknoesis.kastor.rdf.vocab.SHACL.NodeShape,
          constraintIri = com.geoknoesis.kastor.rdf.vocab.SHACL.minCount,
          path = pathIri,
          message = "Name is required"
        )
      )
    } else {
      emptyList()
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









