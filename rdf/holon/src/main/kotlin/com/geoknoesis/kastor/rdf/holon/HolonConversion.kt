package com.geoknoesis.kastor.rdf.holon

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFH
import java.util.UUID

enum class HolonPreservation { CONTENT, ASSERTION }

/** Conversion report: exact discarded assertions and every mapping added to M. */
class HolonConversion internal constructor(
  val context: HolonContext,
  val preservation: HolonPreservation,
  val discardedAssertions: RdfGraph,
  derivedMappings: Map<RdfResource, RdfResource>,
) {
  val derivedMappings: Map<RdfResource, RdfResource> = frozenMap(derivedMappings)
}

/**
 * Convert content before entailment. M and all CG(H) are preserved or conversion fails.
 * Assertion preservation also retains unfiled records, without activating them in M.
 * Original graph placement, reifier identity and occurrence multiplicity are not additional guarantees.
 */
fun HolonContext.convert(
  target: HolonProfile,
  preservation: HolonPreservation = HolonPreservation.CONTENT,
): HolonConversion {
  if (target == HolonProfile.MIXED) fail(HolonError.UNSUPPORTED_CAPABILITY, "Conversion targets must be DATASET or REIFIER")
  val original = contents() // Invalid sources fail with their resolution condition.
  val reserved = hashSetOf<RdfResource>()
  fun reserve(term: RdfTerm) {
    when (term) {
      is RdfResource -> reserved.add(term)
      is TripleTerm -> { reserve(term.triple.subject); reserve(term.triple.predicate); reserve(term.triple.obj) }
      else -> Unit
    }
  }
  assertedGraph.getTriples().forEach { reserve(it.subject); reserve(it.predicate); reserve(it.obj) }
  reserved.addAll(namedGraphs.keys)
  fun freshIri(): Iri {
    var value: Iri
    do { value = Iri("urn:uuid:${UUID.randomUUID()}") } while (!reserved.add(value))
    return value
  }
  fun freshReifier(): BlankNode {
    var value: BlankNode
    do { value = BlankNode("rdfh_${UUID.randomUUID()}") } while (!reserved.add(value))
    return value
  }
  val derived = linkedMapOf<RdfResource, RdfResource>()
  val output = try {
    if (target == HolonProfile.REIFIER) {
      val assertions = linkedSetOf<RdfTriple>()
      if (preservation == HolonPreservation.ASSERTION) {
        assertions.addAll(defaultGraph.getTriples())
        namedGraphs.values.forEach { assertions.addAll(it.getTriples()) }
      }
      original.forEach { (holon, graph) ->
        graph.getTriples().forEach { triple ->
          val reifier = freshReifier()
          assertions.add(triple)
          assertions.add(RdfTriple(reifier, RDF.reifies, TripleTerm(triple)))
          assertions.add(RdfTriple(reifier, RDFH.inHolon, holon))
        }
      }
      HolonContext.of(HolonGraph(assertions), metadata = metadata, profile = target, sourceId = sourceId, filtered = filtered)
    } else {
      val metadataRows = metadata.getTriples().toMutableSet()
      registry.filterIsInstance<BlankNode>().forEach { holon ->
        if (metadata.find(holon, RDFH.contentGraph).isEmpty()) {
          val graphName = freshIri()
          derived[holon] = graphName
          metadataRows.add(RdfTriple(holon, RDFH.contentGraph, graphName))
        }
      }
      val m = HolonGraph(metadataRows)
      val mappings = HolonContext.of(emptyGraph(), metadata = m).graphMappings()
      val graphs = linkedMapOf<RdfResource, RdfGraph>()
      original.forEach { (holon, graph) ->
        val name = mappings.getValue(holon)
        val previous = graphs[name]
        if (previous != null && !sameTriples(previous, graph)) {
          fail(HolonError.UNREPRESENTABLE_CONVERSION, "Different contents select the same target graph $name")
        }
        graphs[name] = graph
      }
      if (preservation == HolonPreservation.ASSERTION) {
        val retained = (m.getTriples() + graphs.values.flatMap { it.getTriples() }).toSet()
        val unfiled = assertedGraph.getTriples().filter { it !in retained }
        if (unfiled.isNotEmpty()) graphs[freshIri()] = HolonGraph(unfiled)
      }
      // Keep M explicit and separate: an existing mapping can name any source graph.
      HolonContext.of(emptyGraph(), graphs, m, target, sourceId, filtered)
    }
  } catch (error: HolonException) {
    if (error.condition == HolonError.UNREPRESENTABLE_CONVERSION) throw error
    throw HolonException(HolonError.UNREPRESENTABLE_CONVERSION, "Target representation is invalid", error)
  }
  try {
    val converted = output.contents()
    if (registry != output.registry || original.any { (h, graph) -> converted[h]?.let { sameTriples(graph, it) } != true }) {
      fail(HolonError.UNREPRESENTABLE_CONVERSION, "The target changes the registry or a holon's direct content")
    }
  } catch (error: HolonException) {
    if (error.condition == HolonError.UNREPRESENTABLE_CONVERSION) throw error
    throw HolonException(HolonError.UNREPRESENTABLE_CONVERSION, "Retained assertions activate invalid target memberships", error)
  }
  val discarded = HolonGraph(assertedGraph.getTriples().filterNot(output.assertedGraph::hasTriple))
  if (preservation == HolonPreservation.ASSERTION && discarded.size() != 0) {
    fail(HolonError.UNREPRESENTABLE_CONVERSION, "The target loses asserted source statements")
  }
  return HolonConversion(output, preservation, discarded, derived)
}
