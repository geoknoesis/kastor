package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.GraphEditor
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import kotlin.reflect.KClass

/**
 * Live access wrapper for repository + graph, used by resource views.
 */
interface GraphAccess {
  val graph: RdfGraph
  val editor: GraphEditor
  val repository: RdfRepository

  fun <T> read(block: RdfRepository.() -> T): T = repository.block()
}

data class DefaultGraphAccess(
  override val repository: RdfRepository,
  override val graph: RdfGraph = repository.defaultGraph,
  override val editor: GraphEditor = repository.editDefaultGraph()
) : GraphAccess

data class ResourceContext(
  val uri: RdfResource,
  val access: GraphAccess
)

interface ResourceViewFactory {
  fun <T : Any> createView(
    iface: KClass<T>,
    context: ResourceContext
  ): T
}

object ResourceViews : ResourceViewFactory {
  override fun <T : Any> createView(
    iface: KClass<T>,
    context: ResourceContext
  ): T {
    return OntoMapper.materialize(RdfRef(context.uri, context.access.graph), iface.java)
  }

  fun <T : Any> createValidatedView(iface: KClass<T>, context: ResourceContext): T {
    return OntoMapper.materializeValidated(RdfRef(context.uri, context.access.graph), iface.java)
  }
}

/**
 * Runtime resource handle for creating typed views with live graph access.
 */
interface Resource {
  val uri: RdfResource
  val access: GraphAccess

  val graph: RdfGraph
    get() = access.graph
  val editor: GraphEditor
    get() = access.editor
  val repository: RdfRepository
    get() = access.repository

  fun <T : Any> `as`(iface: KClass<T>): T =
    ResourceViews.createView(iface, ResourceContext(uri, access))

  fun <T : Any> asValidated(iface: KClass<T>): T =
    ResourceViews.createValidatedView(iface, ResourceContext(uri, access))

  fun predicates(): Set<Iri> =
    graph.getTriples()
      .asSequence()
      .filter { it.subject == uri }
      .map { it.predicate }
      .toSet()

  fun values(pred: Iri): List<RdfTerm> =
    graph.getTriples()
      .asSequence()
      .filter { it.subject == uri && it.predicate == pred }
      .map { it.obj }
      .toList()

  fun literals(pred: Iri): List<Literal> =
    values(pred).mapNotNull { it as? Literal }

  fun strings(pred: Iri): List<String> =
    literals(pred).map { it.lexical }

  fun iris(pred: Iri): List<Iri> =
    values(pred).mapNotNull { it as? Iri }

  fun properties(): Map<Iri, List<RdfTerm>> =
    graph.getTriples()
      .asSequence()
      .filter { it.subject == uri }
      .groupBy({ it.predicate }, { it.obj })

  fun setLiteral(pred: Iri, value: Literal) = setValues(pred, listOf(value))
  fun setLiteral(pred: Iri, value: String) = setValues(pred, listOf(Literal(value)))
  fun setLiteral(pred: Iri, value: Int) = setValues(pred, listOf(Literal(value)))
  fun setLiteral(pred: Iri, value: Double) = setValues(pred, listOf(Literal(value)))
  fun setLiteral(pred: Iri, value: Boolean) = setValues(pred, listOf(Literal(value)))

  fun setResource(pred: Iri, value: RdfResource) = setValues(pred, listOf(value))

  fun setValues(pred: Iri, values: List<RdfTerm>) {
    clear(pred)
    values.forEach { addValue(pred, it) }
  }

  fun addValue(pred: Iri, value: RdfTerm) {
    editor.addTriple(RdfTriple(uri, pred, value))
  }

  fun removeValue(pred: Iri, value: RdfTerm): Boolean {
    return editor.removeTriple(RdfTriple(uri, pred, value))
  }

  fun clear(pred: Iri): Boolean {
    val toRemove = graph.getTriples()
      .asSequence()
      .filter { it.subject == uri && it.predicate == pred }
      .toList()
    if (toRemove.isEmpty()) return false
    editor.removeTriples(toRemove)
    return true
  }
}

data class ResourceRef(
  override val uri: RdfResource,
  override val access: GraphAccess
) : Resource

fun resource(uri: RdfResource, access: GraphAccess): Resource = ResourceRef(uri, access)

fun RdfRepository.resource(
  uri: RdfResource,
  graph: RdfGraph = defaultGraph,
  editor: GraphEditor = editDefaultGraph()
): Resource =
  ResourceRef(uri, DefaultGraphAccess(this, graph, editor))

inline fun <reified T : Any> Resource.asType(): T =
  `as`(T::class)

inline fun <reified T : Any> Resource.asValidatedType(): T =
  asValidated(T::class)













