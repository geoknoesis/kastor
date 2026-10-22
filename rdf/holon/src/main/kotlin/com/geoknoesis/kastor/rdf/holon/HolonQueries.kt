package com.geoknoesis.kastor.rdf.holon

import com.geoknoesis.kastor.rdf.*

/**
 * Build a read-only standard-SPARQL dataset over the resolved snapshot. The default graph is the
 * deduplicated scope union; named graphs are exposed only when [attributed] is requested.
 *
 * Close the returned dataset. The private query repository is owned by this result, never the source.
 * Kastor's Dataset API names graphs by IRI: a requested blank-node graph name reports unsupported-capability;
 * the full RDF-H result remains available through [HolonResolution.attributedGraphs].
 * Providers that cannot retain empty named graphs also report unsupported-capability for those datasets.
 * [repositoryFactory] must return a fresh, empty repository whose ownership transfers to this method.
 */
fun HolonResolution.asDataset(
  attributed: Boolean = false,
  repositoryFactory: () -> RdfRepository = { Rdf.memory() },
): Dataset {
  if (scope == HolonScope.GLOBAL) fail(HolonError.UNSUPPORTED_CAPABILITY, "GLOBAL is not a holon query scope")
  if (attributed && attributedGraphs.keys.any { it !is Iri }) {
    fail(HolonError.UNSUPPORTED_CAPABILITY, "Kastor query datasets require IRI graph names; use attributedGraphs for blank-node holons")
  }
  val repository = repositoryFactory()
  try {
    val names = if (attributed) attributedGraphs.keys.filterIsInstance<Iri>() else emptyList()
    if ((repository as? DatasetClauseSupport)?.let { support -> names.any { !support.supportsDatasetGraph(it) } } == true) {
      fail(HolonError.UNSUPPORTED_CAPABILITY, "The query provider cannot represent the selected graph names")
    }
    repository.transaction {
      editDefaultGraph().addTriples(graph.getTriples())
      names.forEach { name ->
        createGraph(name) // An attributed empty holon still names a graph.
        editGraph(name).addTriples(attributedGraphs.getValue(name).getTriples())
      }
    }
    if (names.any { attributedGraphs.getValue(it).size() == 0 && !repository.hasNamedGraph(it) }) {
      fail(HolonError.UNSUPPORTED_CAPABILITY, "The query provider cannot retain empty named graphs; use attributedGraphs")
    }
    val view = Dataset {
      defaultGraph(repository)
      names.forEach { namedGraph(it, repository, it) }
    }
    return OwnedHolonDataset(view, repository)
  } catch (error: Throwable) {
    try { repository.close() } catch (closing: Throwable) { error.addSuppressed(closing) }
    throw error
  }
}

private class OwnedHolonDataset(
  private val view: Dataset,
  private val repository: RdfRepository,
) : Dataset by view {
  override fun close() {
    try { view.close() } finally { repository.close() }
  }
}
