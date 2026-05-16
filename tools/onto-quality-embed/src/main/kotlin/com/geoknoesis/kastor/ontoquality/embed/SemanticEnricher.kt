package com.geoknoesis.kastor.ontoquality.embed

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.decimal
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Reads labels from an ontology, computes embeddings, and materializes similarity triples.
 *
 * Pairwise similarity uses a [SimilarityIndex]; swap that for an HNSW-backed implementation in v0.2.x
 * without changing this class's orchestration logic.
 */
class SemanticEnricher(
    private val model: EmbeddingModel,
    private val threshold: Double = 0.85,
) {
    /**
     * Reads labels from [ontology], embeds them, and returns a NEW [RdfGraph] containing the
     * original triples plus `oqsh:semanticallyCloseTo` triples and enrichment provenance.
     */
    fun enrich(ontology: RdfGraph): RdfGraph {
        val out = JenaBridge.createEmptyModel()
        out.addTriples(ontology.getTriples())
        out.addTriples(enrichmentOnly(ontology).getTriples())
        return out
    }

    /**
     * Like [enrich] but contains only enrichment triples (similarity, drift scores, provenance).
     */
    fun enrichmentOnly(ontology: RdfGraph): RdfGraph {
        val labelMap = LabelExtractor.extractLabelTexts(ontology)
        if (labelMap.isEmpty()) {
            return provenanceGraph(
                modelPathForHash = null,
                entitiesProcessed = 0,
                pairsAboveThreshold = 0,
                labelDriftTriples = emptyList(),
                similarityTriples = emptyList(),
            )
        }

        val sortedEntries = labelMap.entries.sortedBy { it.key.toString() }
        val texts = sortedEntries.map { it.value }
        val vectors = model.embed(texts)
        val embeddings = sortedEntries.map { it.key }.zip(vectors).toMap()

        val index = SimilarityIndex(embeddings)
        val similarityTriples =
            index.pairsAboveThreshold(threshold).map { (a, b) ->
                RdfTriple(a, EnrichmentVocabulary.semanticallyCloseTo, b)
            }.toList()

        val labelDriftTriples = computeLabelDefinitionDrift(ontology)

        val modelPathForHash = (model as? OnnxEmbeddingModel)?.onnxModelPath

        return provenanceGraph(
            modelPathForHash = modelPathForHash,
            entitiesProcessed = labelMap.size,
            pairsAboveThreshold = similarityTriples.size,
            labelDriftTriples = labelDriftTriples,
            similarityTriples = similarityTriples,
        )
    }

    private fun computeLabelDefinitionDrift(ontology: RdfGraph): List<RdfTriple> {
        val pairs = LabelExtractor.extractLabelAndDefinitionTexts(ontology)
        if (pairs.isEmpty()) return emptyList()
        val labelTexts = pairs.map { it.second.first }
        val defTexts = pairs.map { it.second.second }
        val labelEmb = model.embed(labelTexts)
        val defEmb = model.embed(defTexts)
        return pairs.indices.map { i ->
            val cosine = dotProduct(labelEmb[i], defEmb[i]).coerceIn(-1.0, 1.0)
            val driftScore = (1.0 - cosine).coerceIn(0.0, 1.0)
            val bd = BigDecimal.valueOf(driftScore).setScale(6, RoundingMode.HALF_UP)
            RdfTriple(
                pairs[i].first,
                EnrichmentVocabulary.labelDefinitionDriftScore,
                decimal(bd),
            )
        }
    }

    private fun provenanceGraph(
        modelPathForHash: java.nio.file.Path?,
        entitiesProcessed: Int,
        pairsAboveThreshold: Int,
        labelDriftTriples: List<RdfTriple>,
        similarityTriples: List<RdfTriple>,
    ): RdfGraph {
        val g = JenaBridge.createEmptyModel()
        val root = Iri("urn:onto-quality:enrichment:${UUID.randomUUID()}")
        val hash =
            modelPathForHash?.let { path ->
                val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                digest.joinToString("") { b -> "%02x".format(b) }
            } ?: "unknown"

        fun stringLit(s: String) = Literal(s, XSD.string)

        g.addTriple(RdfTriple(root, RDF.type, EnrichmentVocabulary.Enrichment))
        g.addTriple(RdfTriple(root, EnrichmentVocabulary.model, stringLit(model.name)))
        g.addTriple(RdfTriple(root, EnrichmentVocabulary.modelHash, stringLit(hash)))
        g.addTriple(RdfTriple(root, EnrichmentVocabulary.threshold, decimal(BigDecimal.valueOf(threshold))))
        g.addTriple(RdfTriple(root, EnrichmentVocabulary.tokenizer, stringLit(model.tokenizerDescription)))
        g.addTriple(
            RdfTriple(
                root,
                EnrichmentVocabulary.timestamp,
                Literal(Instant.now().toString(), XSD.dateTime),
            ),
        )
        g.addTriple(
            RdfTriple(
                root,
                EnrichmentVocabulary.entitiesProcessed,
                Literal(entitiesProcessed.toString(), XSD.integer),
            ),
        )
        g.addTriple(
            RdfTriple(
                root,
                EnrichmentVocabulary.pairsAboveThreshold,
                Literal(pairsAboveThreshold.toString(), XSD.integer),
            ),
        )
        g.addTriples(similarityTriples)
        g.addTriples(labelDriftTriples)
        return g
    }

    companion object {
        fun default(): SemanticEnricher = SemanticEnricher(model = OnnxEmbeddingModel.fromMiniLm())

        private fun dotProduct(a: FloatArray, b: FloatArray): Double {
            var s = 0.0
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
