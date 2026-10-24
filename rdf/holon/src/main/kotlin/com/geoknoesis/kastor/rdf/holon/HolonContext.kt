package com.geoknoesis.kastor.rdf.holon

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFH
import java.util.UUID

/**
 * Fixed RDF-H processing context. Source and M are snapshotted together at construction.
 *
 * Use [fromDataset] for an existing Kastor dataset, [fromGraph] for reifiers, or [of] for
 * an already assembled RDF dataset with a shared blank-node scope (including blank-node graph names).
 * Independently loaded source data is standardized apart from M unless it is explicitly a source view.
 * Snapshot a live repository inside its read transaction; no IRI is ever dereferenced.
 */
class HolonContext private constructor(
  val profile: HolonProfile,
  val defaultGraph: RdfGraph,
  named: Map<RdfResource, RdfGraph>,
  val metadata: RdfGraph,
  val sourceId: String,
  val filtered: Boolean,
) {
  val snapshotId: String = UUID.randomUUID().toString()
  val namedGraphs: Map<RdfResource, RdfGraph> = frozenMap(named)
  val assertedGraph: RdfGraph by lazy { union(listOf(defaultGraph, metadata) + namedGraphs.values) }
  val registry: Set<RdfResource> = frozenSet(metadata.getTriples().filter {
    it.predicate == RDFH.contentGraph ||
      (it.predicate == RDF.type && (it.obj == RDFH.Holon || it.obj == RDFH.CompleteHolon))
  }.map { it.subject })
  private val sourceAssertions: RdfGraph by lazy { union(listOf(defaultGraph) + namedGraphs.values) }
  private val contentCache: Map<RdfResource, RdfGraph> by lazy { extractContents() }
  // A failed initializer is retried, so invalid hierarchies are rejected on every composed resolve.
  private val parentCache: Map<RdfResource, RdfResource> by lazy { extractParents() }

  internal fun requireKnown(holon: RdfResource) {
    if (holon !in registry) fail(HolonError.UNKNOWN_HOLON, "$holon is not explicitly registered in M")
  }

  internal fun graphMappings(): Map<RdfResource, RdfResource> {
    val mappings = metadata.find(predicate = RDFH.contentGraph).groupBy { it.subject }
    return registry.associateWith { holon ->
      val values = mappings[holon].orEmpty().map { it.obj }.toSet()
      if (values.size > 1) fail(HolonError.CONFLICTING_MAPPING, "$holon has multiple graph mappings")
      val value = values.singleOrNull()
      if (value != null && value !is RdfResource) fail(HolonError.INVALID_GRAPH_NAME, "$holon maps to $value")
      value ?: (holon as? Iri)
        ?: fail(HolonError.UNMAPPED_BLANK_NODE_HOLON, "$holon needs an explicit graph mapping")
    }
  }

  /** Validates every applicable mapping and membership, rather than only the selected holon. */
  internal fun contents(): Map<RdfResource, RdfGraph> = contentCache

  private fun extractContents(): Map<RdfResource, RdfGraph> {
    val result = registry.associateWith { linkedSetOf<RdfTriple>() }.toMutableMap()
    if (profile != HolonProfile.REIFIER) {
      graphMappings().forEach { (holon, graph) -> result.getValue(holon).addAll(namedGraphs[graph]?.getTriples().orEmpty()) }
    }
    if (profile != HolonProfile.DATASET) {
      val members = sourceAssertions.find(predicate = RDFH.inHolon).groupBy { it.subject }
      val reificationsBySubject = sourceAssertions.find(predicate = RDF.reifies).groupBy { it.subject }
      members.forEach { (reifier, memberships) ->
        memberships.forEach {
          if (it.obj !in registry) fail(HolonError.UNREGISTERED_MEMBERSHIP_HOLON, "$reifier files into ${it.obj}")
        }
        val reifications = reificationsBySubject[reifier].orEmpty()
        if (reifications.isEmpty()) fail(HolonError.MISSING_REIFICATION, "$reifier has no rdf:reifies value")
        reifications.forEach { reification ->
          val triple = (reification.obj as? TripleTerm)?.triple
            ?: fail(HolonError.NON_TRIPLE_TERM_REIFICATION, "$reifier reifies ${reification.obj}")
          if (!assertedGraph.hasTriple(triple)) fail(HolonError.MISSING_BASE_ASSERTION, "$triple is not asserted in G")
          memberships.forEach { result.getValue(it.obj as RdfResource).add(triple) }
        }
      }
    }
    return result.mapValues { HolonGraph(it.value) }
  }

  private fun parents(): Map<RdfResource, RdfResource> = parentCache

  private fun extractParents(): Map<RdfResource, RdfResource> {
    val parents = linkedMapOf<RdfResource, RdfResource>()
    metadata.find(predicate = RDFH.parentHolon).forEach { edge ->
      if (edge.subject !in registry || edge.obj !in registry) {
        fail(HolonError.UNREGISTERED_PARENT_ENDPOINT, "$edge has an unregistered endpoint")
      }
      val parent = edge.obj as RdfResource
      if (parents.put(edge.subject, parent)?.let { it != parent } == true) {
        fail(HolonError.MULTIPLE_PARENTS, "${edge.subject} has more than one parent")
      }
    }
    val checked = hashSetOf<RdfResource>()
    parents.keys.forEach { start ->
      val path = hashSetOf<RdfResource>()
      var node: RdfResource? = start
      while (node != null && node !in checked) {
        if (!path.add(node)) fail(HolonError.PARENT_CYCLE, "Cycle through $node")
        node = parents[node]
      }
      checked.addAll(path)
    }
    return parents
  }

  /** Resolve DIRECT by default; GLOBAL is available for validation and reasoning callers. */
  fun resolve(holon: RdfResource, scope: HolonScope = HolonScope.DIRECT): HolonResolution {
    requireKnown(holon)
    val content = contents()
    val chosen = linkedSetOf(holon)
    if (scope == HolonScope.AGGREGATED || scope == HolonScope.VISIBLE) {
      val children = parents().entries.groupBy({ it.value }, { it.key })
      val pending = ArrayDeque<RdfResource>()
      pending.add(holon)
      while (pending.isNotEmpty()) {
        children[pending.removeFirst()].orEmpty().forEach { if (chosen.add(it)) pending.add(it) }
      }
    }
    if (scope == HolonScope.VISIBLE) chosen.addAll(imports(holon))
    val selected = chosen.associateWith { content.getValue(it) }
    val graph = if (scope == HolonScope.GLOBAL) assertedGraph else union(selected.values)
    return HolonResolution(sourceId, snapshotId, metadata, profile, holon, scope, filtered, graph, selected)
  }

  private fun imports(holon: RdfResource?): List<RdfResource> = metadata.find(holon, RDFH.imports).map {
    if (it.obj !in registry) fail(HolonError.UNRESOLVED_IMPORT, "${it.subject} imports unregistered ${it.obj}")
    it.obj as RdfResource
  }

  /** Check every mapping, membership, parent edge and import, not only those reachable from one holon. */
  internal fun validateDeclarations() {
    contents()
    parents()
    imports(null)
  }

  /** Select only H's explicit shape imports from a separately supplied validation dataset. */
  fun effectiveShapes(holon: RdfResource, shapes: Map<Iri, RdfGraph>): RdfGraph {
    requireKnown(holon)
    return union(shapeGraphNames(holon).map { name ->
      shapes[name] ?: fail(HolonError.MISSING_SHAPES_GRAPH, "No validation graph named $name")
    })
  }

  internal fun shapeGraphNames(holon: RdfResource): Set<Iri> = metadata.find(holon, RDFH.importsShapes).map {
    it.obj as? Iri ?: fail(HolonError.MISSING_SHAPES_GRAPH, "A shapes graph must have an IRI name: ${it.obj}")
  }.toSet()

  companion object {
    /**
     * Assemble one already scoped RDF input. Every supplied graph and graph name belongs to the same
     * blank-node scope. Snapshot independently loaded graphs with [BlankNodeScopes] before calling this.
     * The source graph contains reifier data; named graphs require DATASET or explicitly declared MIXED.
     */
    fun of(
      defaultGraph: RdfGraph,
      namedGraphs: Map<out RdfResource, RdfGraph> = emptyMap(),
      metadata: RdfGraph? = null,
      profile: HolonProfile = HolonProfile.DATASET,
      sourceId: String = UUID.randomUUID().toString(),
      filtered: Boolean = false,
    ): HolonContext {
      if (profile == HolonProfile.REIFIER && namedGraphs.isNotEmpty()) {
        fail(HolonError.UNSUPPORTED_CAPABILITY, "Reifier input is a graph, not a dataset; declare MIXED for named content")
      }
      val selectedMetadata = metadata ?: if (profile == HolonProfile.REIFIER) {
        fail(HolonError.UNSUPPORTED_CAPABILITY, "Reifier input requires an explicitly supplied metadata context M")
      } else defaultGraph
      val named = namedGraphs.entries.associate { (name, graph) -> name to graph.snapshot() }
      return HolonContext(profile, defaultGraph.snapshot(), named, selectedMetadata.snapshot(), sourceId, filtered)
    }

    /** Default M is the dataset's default graph; reifier-looking assertions are inactive unless [mixed]. */
    fun fromDataset(
      source: Dataset,
      metadata: RdfGraph? = null,
      mixed: Boolean = false,
      metadataSharesSourceScope: Boolean = false,
      sourceId: String = UUID.randomUUID().toString(),
      filtered: Boolean = false,
    ): HolonContext {
      var snapshot: HolonContext? = null
      val capture = {
        val default = source.defaultGraph
        val selectedMetadata = metadata ?: default
        val named = source.namedGraphs
        val shared = metadata == null || metadataSharesSourceScope || sameScope(metadata, default, source as? RdfRepository)
        val scopes = if (shared) null else BlankNodeScopes(selectedMetadata)
        // One namespace for the whole dataset, so blank nodes shared across its graphs stay shared.
        val key = emptyGraph()
        fun scoped(graph: RdfGraph): RdfGraph = if (scopes == null) graph else HolonGraph(graph.getTriplesSequence().map {
          RdfTriple(scopes.mapTerm(key, it.subject) as RdfResource, it.predicate, scopes.mapTerm(key, it.obj))
        }.asIterable())
        snapshot = of(scoped(default), named.mapValues { scoped(it.value) }, selectedMetadata,
          if (mixed) HolonProfile.MIXED else HolonProfile.DATASET, sourceId, filtered)
      }
      if (source is RdfRepository) source.readTransaction { capture() } else capture()
      return checkNotNull(snapshot)
    }

    /** Graphs of one repository share its scope, whatever wrapper a provider returns for each access. */
    private fun sameScope(metadata: RdfGraph, source: RdfGraph, owner: RdfRepository? = null): Boolean {
      if (metadata === source) return true
      val repository = source.asGraphRef().sourceRepository ?: owner ?: return false
      return metadata.asGraphRef().sourceRepository === repository
    }

    /** M is mandatory. A separately loaded M never shares blank-node labels with the source by default. */
    fun fromGraph(
      source: RdfGraph,
      metadata: RdfGraph,
      metadataSharesSourceScope: Boolean = false,
      sourceId: String = UUID.randomUUID().toString(),
      filtered: Boolean = false,
    ): HolonContext {
      val graph = if (metadataSharesSourceScope || sameScope(metadata, source)) source else BlankNodeScopes(metadata).graph(source)
      return of(graph, metadata = metadata, profile = HolonProfile.REIFIER, sourceId = sourceId, filtered = filtered)
    }
  }
}
