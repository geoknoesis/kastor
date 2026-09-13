package com.geoknoesis.kastor.ontoquality.embed

import java.nio.FloatBuffer
import kotlin.math.sqrt

/** Pure helpers behind [OnnxEmbeddingModel] batching, truncation and pooling (testable without a model). */
internal object EmbeddingBatching {
    /** Output names carrying per-token states (mean-pooled with the attention mask). */
    val TOKEN_OUTPUT_NAMES = listOf("last_hidden_state", "token_embeddings")

    /** Output names carrying one already-pooled vector per input (rank 2, used as-is). */
    val POOLED_OUTPUT_NAMES = listOf("sentence_embedding", "sentence_embeddings", "embeddings")

    /**
     * Groups input indices into batches of at most [batchSize], sorted by token length so each batch pads only
     * to its own longest member. Callers write results back by index, restoring the original order.
     */
    fun plan(tokenLengths: List<Int>, batchSize: Int): List<List<Int>> {
        require(batchSize > 0) { "batchSize must be positive" }
        return tokenLengths.indices.sortedWith(compareBy({ tokenLengths[it] }, { it })).chunked(batchSize)
    }

    /**
     * Copies [src] into a zero-padded array of [seqLen]. When [src] is longer and [keepLastToken] is set
     * (the final token is a special token such as `[SEP]`), the head is cut instead of the closing token.
     */
    fun fit(src: LongArray, seqLen: Int, keepLastToken: Boolean): LongArray {
        require(seqLen > 0) { "seqLen must be positive" }
        val out = LongArray(seqLen)
        if (src.size <= seqLen) {
            src.copyInto(out)
        } else if (keepLastToken) {
            src.copyInto(out, destinationOffset = 0, startIndex = 0, endIndex = seqLen - 1)
            out[seqLen - 1] = src[src.size - 1]
        } else {
            src.copyInto(out, destinationOffset = 0, startIndex = 0, endIndex = seqLen)
        }
        return out
    }

    /** Picks the model output to read: a token-state output by name, else a pooled output, else the only output. */
    fun selectOutput(outputNames: Collection<String>): String {
        val byLower = outputNames.associateBy { it.lowercase() }
        TOKEN_OUTPUT_NAMES.firstNotNullOfOrNull { byLower[it] }?.let { return it }
        POOLED_OUTPUT_NAMES.firstNotNullOfOrNull { byLower[it] }?.let { return it }
        require(outputNames.size == 1) {
            "Cannot choose an embedding output among $outputNames; expected one of ${TOKEN_OUTPUT_NAMES + POOLED_OUTPUT_NAMES}"
        }
        return outputNames.single()
    }

    /**
     * Converts a model output into L2-normalised sentence vectors after validating its full shape:
     * rank 3 must be `[batch, seqLen, dimension]` (masked mean pooling), rank 2 must be `[batch, dimension]`.
     */
    fun pool(
        shape: LongArray,
        data: FloatBuffer,
        attentionMask: Array<LongArray>,
        batch: Int,
        seqLen: Int,
        dimension: Int,
    ): List<FloatArray> {
        val vectors =
            when (shape.size) {
                3 -> {
                    require(shape[0] == batch.toLong() && shape[1] == seqLen.toLong() && shape[2] == dimension.toLong()) {
                        "Expected token output shape [$batch, $seqLen, $dimension], got ${shape.contentToString()}"
                    }
                    List(batch) { b ->
                        val pooled = FloatArray(dimension)
                        var count = 0
                        for (s in 0 until seqLen) {
                            if (attentionMask[b][s] == 0L) continue
                            count++
                            val base = (b * seqLen + s) * dimension
                            for (h in 0 until dimension) pooled[h] += data.get(base + h)
                        }
                        if (count > 0) for (h in 0 until dimension) pooled[h] /= count.toFloat()
                        pooled
                    }
                }
                2 -> {
                    require(shape[0] == batch.toLong() && shape[1] == dimension.toLong()) {
                        "Expected pooled output shape [$batch, $dimension], got ${shape.contentToString()}"
                    }
                    List(batch) { b -> FloatArray(dimension) { h -> data.get(b * dimension + h) } }
                }
                else -> throw IllegalArgumentException(
                    "Expected rank-3 token states or rank-2 sentence embeddings, got ${shape.contentToString()}",
                )
            }
        vectors.forEach(::l2Normalize)
        return vectors
    }

    private fun l2Normalize(v: FloatArray) {
        var sum = 0.0
        for (x in v) sum += x * x
        val norm = sqrt(sum).toFloat().coerceAtLeast(1e-12f)
        for (i in v.indices) v[i] /= norm
    }
}
