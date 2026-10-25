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
 *
 * The snapshot is copied into that repository once. Queries then run in place on it, with or without [attributed]
 * (no per-query copy), when it has a SPARQL engine; the graph-only `memory` provider is copied for every query, as
 * any [Dataset] over it is. With [attributed], the scope union is stored in a private named graph whose IRI is never
 * one of the dataset's named graphs.
 *
 * @throws HolonException with [HolonError.UNSUPPORTED_CAPABILITY] for the GLOBAL scope, a blank-node graph name
 *   with [attributed], or graph names or empty graphs the query provider cannot represent
 * @throws RdfException if [repositoryFactory] or the copy into its repository fails
 */
@JvmOverloads
@Throws(RdfException::class)
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
    // With named graphs, the scope union is stored in a private named graph rather than the store's default graph:
    // a dataset of the store's default graph and named graphs cannot be queried in place (the default graph has no
    // IRI for FROM), so every query would copy the whole repository. The private graph is named only by FROM.
    val defaultName = if (names.isEmpty()) null else privateDefaultGraphName(names)
    if ((repository as? DatasetClauseSupport)?.let { support -> (names + listOfNotNull(defaultName)).any { !support.supportsDatasetGraph(it) } } == true) {
      fail(HolonError.UNSUPPORTED_CAPABILITY, "The query provider cannot represent the selected graph names")
    }
    repository.transaction {
      if (defaultName == null) editDefaultGraph().addTriples(graph.getTriples()) else {
        createGraph(defaultName)
        editGraph(defaultName).addTriples(graph.getTriples())
      }
      names.forEach { name ->
        createGraph(name) // An attributed empty holon still names a graph.
        editGraph(name).addTriples(attributedGraphs.getValue(name).getTriples())
      }
    }
    if (names.any { attributedGraphs.getValue(it).size() == 0 && !repository.hasNamedGraph(it) }) {
      fail(HolonError.UNSUPPORTED_CAPABILITY, "The query provider cannot retain empty named graphs; use attributedGraphs")
    }
    val view = Dataset {
      if (defaultName == null) defaultGraph(repository)
      else defaultGraph(repository.getGraph(defaultName).asGraphRef(repository, defaultName))
      names.forEach { namedGraph(it, repository, it) }
    }
    return OwnedHolonDataset(view, repository)
  } catch (error: Throwable) {
    try { repository.close() } catch (closing: Throwable) { error.addSuppressed(closing) }
    throw error
  }
}

/** A graph name that is not one of [names], for the scope union of an attributed query dataset. */
private fun privateDefaultGraphName(names: List<Iri>): Iri =
  generateSequence { Iri("urn:uuid:${java.util.UUID.randomUUID()}") }.first { it !in names }

private class OwnedHolonDataset(
  private val view: Dataset,
  private val repository: RdfRepository,
) : Dataset by view {
  override fun close() {
    try { view.close() } finally { repository.close() }
  }
}
