package com.geoknoesis.kastor.ontoquality.embed

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import kotlin.math.sqrt

@DisabledIfEnvironmentVariable(named = "KASTOR_SKIP_EMBEDDING_TESTS", matches = "1")
class OnnxEmbeddingModelManualTest {

    @Test
    fun `car and automobile have high cosine similarity`() {
        OnnxEmbeddingModel.fromMiniLm().use { model ->
            val v = model.embed(listOf("Car", "Automobile"))
            require(v.size == 2)
            var dot = 0.0
            for (i in v[0].indices) dot += v[0][i] * v[1][i]
            assertTrue(dot > 0.5, "expected cosine > 0.5, got $dot")
        }
    }

    @Test
    fun `normalized embeddings are unit length`() {
        OnnxEmbeddingModel.fromMiniLm().use { model ->
            val v = model.embed(listOf("test"))[0]
            var n = 0.0
            for (x in v) n += x * x
            assertTrue(kotlin.math.abs(sqrt(n) - 1.0) < 1e-3)
        }
    }
}
