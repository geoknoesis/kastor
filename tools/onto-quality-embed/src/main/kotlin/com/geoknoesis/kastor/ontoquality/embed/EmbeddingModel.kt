package com.geoknoesis.kastor.ontoquality.embed

sealed interface EmbeddingModel {
    val name: String
    val dimension: Int
    val maxTokens: Int

    /** Short label for enrichment provenance (`oqsh:tokenizer`), e.g. HuggingFace model id. */
    val tokenizerDescription: String

    /**
     * Embeds a batch of strings. Implementations must be thread-safe
     * or document that they are not.
     */
    fun embed(texts: List<String>): List<FloatArray>
}
