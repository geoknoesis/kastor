package com.geoknoesis.kastor.ontoquality.embed

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDFS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.sqrt

/**
 * Manual test: downloads the all-MiniLM-L6-v2 ONNX model from HuggingFace and runs
 * real inference, so it is opt-in (skipped by default, including CI, where the model
 * download is unavailable). Run with `KASTOR_RUN_EMBEDDING_TESTS=1`. Mirrors the
 * opt-in gating used by the OOPS benchmark tests.
 */
@EnabledIfEnvironmentVariable(named = "KASTOR_RUN_EMBEDDING_TESTS", matches = "1")
class OnnxEmbeddingModelManualTest {
    @Test
    fun `enrichment hashes the real model and preserves its source graph`() {
        val value = RdfTriple(Iri("https://example.org/car"), RDFS.label, string("Car"))
        val source = MemoryGraph(listOf(value))
        OnnxEmbeddingModel.fromMiniLm().use { model ->
            val enricher = SemanticEnricher(model)
            val enriched = enricher.use { it.enrich(source) }
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) { enricher.enrich(source) }
            assertEquals(1, model.embed(listOf("Caller still owns this model")).size)
            assertEquals(setOf(value), source.getTriples().toSet())
            val hashes = enriched.find(predicate = EnrichmentVocabulary.modelHash)
                .map { (it.obj as Literal).lexical }
            assertEquals(listOf(ModelDownloader.EXPECTED_MODEL_SHA256), hashes)
        }
    }

    @Test
    fun `repeated inference and concurrent close release native resources safely`() {
        repeat(3) {
            val model = OnnxEmbeddingModel.fromMiniLm()
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val inference = pool.submit<Boolean> {
                    try { model.embed(List(32) { "resource lifecycle test" }); true }
                    catch (_: IllegalStateException) { true } // close may acquire the lock first
                }
                val close = pool.submit { model.close() }
                assertTrue(inference.get(30, java.util.concurrent.TimeUnit.SECONDS))
                close.get(30, java.util.concurrent.TimeUnit.SECONDS)
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) { model.embed(listOf("closed")) }
                model.close()
            } finally { model.close(); pool.shutdownNow() }
        }
    }

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
