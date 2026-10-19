package com.geoknoesis.kastor.rdf

import java.io.Closeable

/** Temporary query repositories, owned here until a compatible candidate is handed to the dataset. */
internal object DatasetRepositories {
    fun supports(repo: RdfRepository, names: Collection<Iri>): Boolean =
        (repo as? DatasetClauseSupport)?.let { support -> names.all(support::supportsDatasetGraph) } ?: true

    fun retainCompatible(repo: RdfRepository, names: Collection<Iri>): RdfRepository? =
        Candidate(repo).use { candidate ->
            if (supports(repo, names)) candidate.retain() else null
        }

    fun create(names: Collection<Iri>): RdfRepository {
        retainCompatible(Rdf.memory(), names)?.let { return it }
        // A GRAPH pattern can interpret a provider's sentinel as the default graph even without FROM clauses.
        for (provider in RdfProviderRegistry.discoverProviders()) {
            if (!provider.supportsVariant("memory") ||
                !runCatching { provider.getCapabilities("memory").declaresSparqlSupport() }.getOrDefault(false)
            ) continue
            val repo = Rdf.repository { providerId = provider.id; variantId = "memory" }
            retainCompatible(repo, names)?.let { return it }
        }
        throw UnsupportedOperationException(
            "No installed in-memory SPARQL provider supports the selected named graph IRIs. " +
                "Add a compatible provider (for example rdf-jena) to query this dataset.",
        )
    }

    private class Candidate(private val repo: RdfRepository) : Closeable {
        private var retained = false
        fun retain(): RdfRepository { retained = true; return repo }
        override fun close() { if (!retained) repo.close() }
    }
}
