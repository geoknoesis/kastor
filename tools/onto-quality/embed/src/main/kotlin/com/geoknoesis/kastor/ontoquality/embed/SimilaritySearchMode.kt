package com.geoknoesis.kastor.ontoquality.embed

/** How [SimilarityIndex] enumerates pairs above a cosine threshold. */
sealed class SimilaritySearchMode {
    /** Provenance label recorded on the enrichment node (`oqsh:similaritySearchMode`). */
    abstract val label: String

    /** Exact metric-tree search: every pair above the threshold is found (default). */
    object Exact : SimilaritySearchMode() {
        override val label: String = "exact"

        override fun toString(): String = label
    }

    /**
     * **Approximate** search with random-projection locality-sensitive hashing (SimHash), for large vocabularies
     * where exact search is too slow. Each of [tables] hash tables buckets entities by the signs of [bitsPerTable]
     * random hyperplane projections; only entities sharing a bucket in some table are compared, and every
     * reported pair is verified against the threshold. Results are therefore a subset of the exact results: pairs
     * are never invented but some may be missed. For a pair at cosine c the chance of being compared is
     * 1 − (1 − (1 − θ/π)^bitsPerTable)^tables with θ = arccos(c) — about 95 % at c = 0.85 with the defaults,
     * and higher for closer pairs. Hyperplanes are drawn from [seed], so runs are reproducible.
     */
    data class ApproximateLsh(
        val tables: Int = 20,
        val bitsPerTable: Int = 10,
        val seed: Long = 42L,
    ) : SimilaritySearchMode() {
        init {
            require(tables in 1..256) { "tables must be between 1 and 256" }
            require(bitsPerTable in 1..62) { "bitsPerTable must be between 1 and 62" }
        }

        override val label: String
            get() = "approximate-lsh(tables=$tables,bitsPerTable=$bitsPerTable,seed=$seed)"
    }
}
