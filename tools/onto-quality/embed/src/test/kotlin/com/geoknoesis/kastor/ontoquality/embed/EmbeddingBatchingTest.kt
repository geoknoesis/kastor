package com.geoknoesis.kastor.ontoquality.embed

import org.junit.jupiter.api.Test
import java.nio.FloatBuffer
import kotlin.math.sqrt
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmbeddingBatchingTest {
    @Test
    fun `batches group similar lengths and cover every index once`() {
        val plan = EmbeddingBatching.plan(listOf(5, 1, 9, 3, 1), batchSize = 2)
        assertEquals(listOf(listOf(1, 4), listOf(3, 0), listOf(2)), plan)
        assertEquals((0..4).toSet(), plan.flatten().toSet())
    }

    @Test
    fun `manual truncation keeps the closing special token`() {
        val ids = longArrayOf(101, 7, 8, 9, 102)
        assertContentEquals(longArrayOf(101, 7, 102), EmbeddingBatching.fit(ids, 3, keepLastToken = true))
        assertContentEquals(longArrayOf(101, 7, 8), EmbeddingBatching.fit(ids, 3, keepLastToken = false))
        assertContentEquals(longArrayOf(101, 7, 8, 9, 102, 0, 0), EmbeddingBatching.fit(ids, 7, keepLastToken = true))
    }

    @Test
    fun `output is selected by name before falling back to the only output`() {
        assertEquals("last_hidden_state", EmbeddingBatching.selectOutput(listOf("sentence_embedding", "last_hidden_state")))
        assertEquals("sentence_embedding", EmbeddingBatching.selectOutput(listOf("pooler_output", "sentence_embedding")))
        assertEquals("output_0", EmbeddingBatching.selectOutput(listOf("output_0")))
        assertFailsWith<IllegalArgumentException> { EmbeddingBatching.selectOutput(listOf("a", "b")) }
    }

    @Test
    fun `rank-3 output is mean pooled over attended tokens only`() {
        // batch 1, seq 3, hidden 2; third token is padding.
        val data = FloatBuffer.wrap(floatArrayOf(1f, 0f, 3f, 0f, 100f, 100f))
        val vectors = EmbeddingBatching.pool(longArrayOf(1, 3, 2), data, arrayOf(longArrayOf(1, 1, 0)), 1, 3, 2)
        assertContentEquals(floatArrayOf(1f, 0f), vectors.single())
    }

    @Test
    fun `rank-2 sentence embeddings are used without pooling`() {
        val data = FloatBuffer.wrap(floatArrayOf(3f, 4f, 0f, 2f))
        val vectors = EmbeddingBatching.pool(longArrayOf(2, 2), data, arrayOf(longArrayOf(1), longArrayOf(1)), 2, 1, 2)
        assertEquals(0.6f, vectors[0][0], 1e-6f)
        assertEquals(0.8f, vectors[0][1], 1e-6f)
        assertEquals(1f, sqrt(vectors[1].sumOf { it.toDouble() * it }).toFloat(), 1e-6f)
    }

    @Test
    fun `full output shape is validated`() {
        val data = FloatBuffer.wrap(FloatArray(12))
        val mask = arrayOf(LongArray(3) { 1 }, LongArray(3) { 1 })
        // Wrong sequence length (same element count per row would read at wrong offsets).
        assertFailsWith<IllegalArgumentException> { EmbeddingBatching.pool(longArrayOf(2, 2, 3), data, mask, 2, 3, 2) }
        assertFailsWith<IllegalArgumentException> { EmbeddingBatching.pool(longArrayOf(1, 3, 2), data, mask, 2, 3, 2) }
        assertFailsWith<IllegalArgumentException> { EmbeddingBatching.pool(longArrayOf(2, 3), data, mask, 2, 3, 2) }
        assertFailsWith<IllegalArgumentException> { EmbeddingBatching.pool(longArrayOf(12), data, mask, 2, 3, 2) }
    }
}
