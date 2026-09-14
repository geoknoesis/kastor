package com.geoknoesis.kastor.ontoquality.embed

/**
 * Deterministic [EmbeddingModel] computing vectors with [vectorFor] (normalised to unit length), for tests and offline
 * fixtures that must not load an ONNX model. [EmbeddingModel] is sealed, so this in-module implementation is the only
 * way to exercise [SemanticEnricher] without native resources.
 */
internal class PrecomputedEmbeddingModel(
    override val name: String,
    override val dimension: Int,
    override val maxTokens: Int = 512,
    override val tokenizerDescription: String = name,
    private val vectorFor: (String) -> FloatArray,
) : EmbeddingModel {
    override fun embed(texts: List<String>): List<FloatArray> =
        texts.map { text ->
            val v = vectorFor(text)
            require(v.size == dimension) { "vector for '$text' has ${v.size} dimensions, expected $dimension" }
            val norm = kotlin.math.sqrt(v.sumOf { it.toDouble() * it })
            require(norm > 0.0) { "zero vector for '$text'" }
            FloatArray(v.size) { (v[it] / norm).toFloat() }
        }
}
