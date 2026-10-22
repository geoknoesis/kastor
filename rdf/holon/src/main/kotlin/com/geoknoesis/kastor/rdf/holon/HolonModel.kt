package com.geoknoesis.kastor.rdf.holon

import com.geoknoesis.kastor.rdf.*
import java.util.Collections

/** Storage interpretation is explicit. Dataset input defaults to [DATASET], graph input to [REIFIER]. */
enum class HolonProfile { DATASET, REIFIER, MIXED }

/** GLOBAL is for validation/reasoning, rather than a SPARQL-H holon query scope. */
enum class HolonScope { DIRECT, AGGREGATED, VISIBLE, GLOBAL }

/** Error identifiers from RDF-H 0.8, plus its operation-specific conversion failure. */
enum class HolonError(val id: String) {
  UNKNOWN_HOLON("unknown-holon"),
  CONFLICTING_MAPPING("conflicting-mapping"),
  INVALID_GRAPH_NAME("invalid-graph-name"),
  UNMAPPED_BLANK_NODE_HOLON("unmapped-blank-node-holon"),
  UNREGISTERED_MEMBERSHIP_HOLON("unregistered-membership-holon"),
  MISSING_REIFICATION("missing-reification"),
  NON_TRIPLE_TERM_REIFICATION("non-triple-term-reification"),
  MISSING_BASE_ASSERTION("missing-base-assertion"),
  UNREGISTERED_PARENT_ENDPOINT("unregistered-parent-endpoint"),
  MULTIPLE_PARENTS("multiple-parents"),
  PARENT_CYCLE("parent-cycle"),
  UNRESOLVED_IMPORT("unresolved-import"),
  UNSUPPORTED_CAPABILITY("unsupported-capability"),
  MISSING_SHAPES_GRAPH("missing-shapes-graph"),
  ILL_FORMED_SHAPES_GRAPH("ill-formed-shapes-graph"),
  UNREPRESENTABLE_CONVERSION("unrepresentable-conversion"),
}

class HolonException(
  val condition: HolonError,
  detail: String = condition.id,
  cause: Throwable? = null,
) : RuntimeException("${condition.id}: $detail", cause)

/** Immutable RDF graph snapshot. Blank-node identity, including nested triple terms, is retained. */
internal class HolonGraph(triples: Iterable<RdfTriple>) : RdfGraph {
  private val triples = Collections.unmodifiableSet(LinkedHashSet(triples.toList()))
  override fun hasTriple(triple: RdfTriple): Boolean = triple in triples
  override fun getTriples(): List<RdfTriple> = triples.toList()
  override fun getTriplesSequence(): Sequence<RdfTriple> = triples.asSequence()
  override fun size(): Int = triples.size
}

internal fun RdfGraph.snapshot(): RdfGraph = HolonGraph(getTriples())
internal fun union(graphs: Iterable<RdfGraph>): RdfGraph = HolonGraph(graphs.flatMap { it.getTriples() })
internal fun emptyGraph(): RdfGraph = HolonGraph(emptyList())
internal fun <K, V> frozenMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
internal fun <T> frozenSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
internal fun fail(condition: HolonError, detail: String): Nothing = throw HolonException(condition, detail)

/** A resolved read-only scope, including enough context to identify what was selected. */
class HolonResolution internal constructor(
  val sourceId: String,
  val snapshotId: String,
  val metadata: RdfGraph,
  val profile: HolonProfile,
  val holon: RdfResource,
  val scope: HolonScope,
  val filtered: Boolean,
  val graph: RdfGraph,
  selected: Map<RdfResource, RdfGraph>,
) {
  /** One named graph per selected holon, including empty and blank-node holons. */
  val attributedGraphs: Map<RdfResource, RdfGraph> = frozenMap(selected)
}
