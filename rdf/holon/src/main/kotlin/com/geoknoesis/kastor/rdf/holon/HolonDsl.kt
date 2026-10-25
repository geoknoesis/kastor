package com.geoknoesis.kastor.rdf.holon

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.dsl.GraphDsl
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFH
import java.util.UUID

@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class HolonDslMarker

/**
 * Build a provider-independent DATASET context with explicit registrations and mappings in M.
 * Every identifier and content graph shares one blank-node scope. Use [BlankNodeScopes] first
 * for independently loaded graphs. Use [HolonContext.convert] for a reifier representation.
 *
 * @throws HolonException if a declaration is invalid (a second parent, a cycle, an import of an undeclared holon)
 */
@JvmOverloads
@Throws(HolonException::class)
fun holons(
  sourceId: String = UUID.randomUUID().toString(),
  filtered: Boolean = false,
  configure: HolonsDsl.() -> Unit = {},
): HolonContext = HolonsDsl().apply(configure).build(sourceId, filtered)

/** Administrative declarations are separate from the RDF triples inside content blocks. */
@HolonDslMarker
class HolonsDsl internal constructor() {
  private val definitions = linkedMapOf<RdfResource, HolonDsl>()

  /**
   * Declare a holon, including an empty one. Forward references in parent/import declarations
   * are allowed. Repeating an identifier is rejected; use multiple content blocks to append.
   * Holons with the same [graphName] share the union of content written to that graph.
   * Blank-node holons receive an explicit mapping, also when the graph name is the same node.
   */
  fun holon(id: RdfResource, graphName: RdfResource = id, configure: HolonDsl.() -> Unit = {}) {
    require(id !in definitions) { "Holon $id is already declared" }
    definitions[id] = HolonDsl(id, graphName).apply(configure)
  }

  internal fun build(sourceId: String, filtered: Boolean): HolonContext {
    val metadata = HolonGraph(definitions.values.flatMap { it.declarations })
    val graphs = definitions.values.groupBy { it.graphName }.mapValues { (_, entries) ->
      union(entries.map { HolonGraph(it.contentRows) })
    }
    val context = HolonContext.of(metadata, graphs, sourceId = sourceId, filtered = filtered)
    // The authoring DSL rejects invalid declarations globally, including forward references.
    context.validateDeclarations()
    return context
  }
}

@HolonDslMarker
class HolonDsl internal constructor(val id: RdfResource, val graphName: RdfResource) {
  internal val declarations = mutableListOf(
    RdfTriple(id, RDF.type, RDFH.Holon),
    RdfTriple(id, RDFH.contentGraph, graphName),
  )
  internal val contentRows = mutableListOf<RdfTriple>()

  /** Add asserted content using Kastor's existing RDF triple DSL. Repeated blocks append. */
  fun content(configure: @HolonDslMarker GraphDsl.() -> Unit) {
    content(GraphDsl().apply(configure).build())
  }

  /** Copy content already in this DSL's shared blank-node scope at the time of this call. */
  fun content(graph: RdfGraph) {
    contentRows.addAll(graph.getTriples())
  }

  /** Declare administrative parentage; a second distinct parent or a cycle fails at build time. */
  fun parent(holon: RdfResource) {
    declarations.add(RdfTriple(id, RDFH.parentHolon, holon))
  }

  /** Import only each target's direct content; targets must be declared in this context. */
  fun imports(vararg holons: RdfResource) {
    holons.forEach { declarations.add(RdfTriple(id, RDFH.imports, it)) }
  }

  /** Select named graphs from the separate shapes dataset supplied when validating. */
  fun importsShapes(graph: Iri) {
    declarations.add(RdfTriple(id, RDFH.importsShapes, graph))
  }

  fun importsShapes(graphs: Iterable<Iri>) {
    graphs.forEach { importsShapes(it) }
  }
}
