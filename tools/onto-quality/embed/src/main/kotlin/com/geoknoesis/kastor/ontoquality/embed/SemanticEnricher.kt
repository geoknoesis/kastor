package com.geoknoesis.kastor.ontoquality.embed

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.decimal
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads labels from an ontology, computes embeddings, and materializes similarity triples.
 *
 * Pairwise similarity uses a [SimilarityIndex] with explicit resource limits. By default the search is exact and
 * its limits scale with the number of labelled entities ([SimilarityLimitsPolicy.scaled]); a
 * [SimilaritySearchMode.ApproximateLsh] mode is available for large vocabularies. The mode used is recorded on the
 * enrichment node. Exhausted limits raise [SimilaritySearchBudgetExceededException].
 * Constructor-supplied models remain caller-owned. [default] creates an owned model;
 * close that enricher with `use` to release its native resources.
 */
class SemanticEnricher(
    private val model: EmbeddingModel,
    private val threshold: Double = 0.85,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private var ownedModel: AutoCloseable? = null
    private var limitsPolicy: SimilarityLimitsPolicy = SimilarityLimitsPolicy.scaled()
    private var searchMode: SimilaritySearchMode = SimilaritySearchMode.Exact
    init { require(threshold.isFinite() && threshold in -1.0..1.0) }

    /** Exact search with the same fixed [limits] for every ontology. */
    constructor(model: EmbeddingModel, threshold: Double, limits: SimilaritySearchLimits) : this(model, threshold) {
        limitsPolicy = SimilarityLimitsPolicy.fixed(limits)
    }

    constructor(
        model: EmbeddingModel,
        threshold: Double,
        limitsPolicy: SimilarityLimitsPolicy,
        mode: SimilaritySearchMode,
    ) : this(model, threshold) {
        this.limitsPolicy = limitsPolicy
        searchMode = mode
    }
    /**
     * Reads labels from [ontology], embeds them, and returns a NEW [RdfGraph] containing the
     * original triples plus `oqsh:semanticallyCloseTo` triples and enrichment provenance.
     */
    fun enrich(ontology: RdfGraph): RdfGraph {
        check(!closed.get()) { "Semantic enricher is closed" }
        val out = JenaBridge.createEmptyModel()
        out.addTriples(ontology.getTriples())
        out.addTriples(enrichmentOnly(ontology).getTriples())
        return out
    }

    /**
     * Like [enrich] but contains only enrichment triples (similarity, drift scores, provenance).
     */
    fun enrichmentOnly(ontology: RdfGraph): RdfGraph {
        check(!closed.get()) { "Semantic enricher is closed" }
        val labelMap = LabelExtractor.extractLabelTexts(ontology)
        if (labelMap.isEmpty()) {
            return provenanceGraph(
                modelHash = modelHash(),
                entitiesProcessed = 0,
                pairsAboveThreshold = 0,
                labelDriftTriples = emptyList(),
                similarityTriples = emptyList(),
            )
        }

        val sortedEntries = labelMap.entries.sortedBy { it.key.toString() }
        val texts = sortedEntries.map { it.value }
        val vectors = model.embed(texts)
        check(vectors.size == texts.size) { "Embedding model returned ${vectors.size} vectors for ${texts.size} inputs" }
        val embeddings = sortedEntries.map { it.key }.zip(vectors).toMap()

        val index = SimilarityIndex(embeddings)
        val similarityTriples =
            index.pairsAboveThreshold(threshold, limitsPolicy.limitsFor(labelMap.size), searchMode).map { (a, b) ->
                RdfTriple(a, EnrichmentVocabulary.semanticallyCloseTo, b)
            }.toList()

        val labelDriftTriples = computeLabelDefinitionDrift(ontology, embeddings)

        return provenanceGraph(
            modelHash = modelHash(),
            entitiesProcessed = labelMap.size,
            pairsAboveThreshold = similarityTriples.size,
            labelDriftTriples = labelDriftTriples,
            similarityTriples = similarityTriples,
        )
    }

    /**
     * Label vectors already computed for the similarity index are reused (label strings are built by the same
     * [LabelExtractor] join); only definitions, and labels not present in [labelVectors], are embedded here.
     */
    private fun computeLabelDefinitionDrift(
        ontology: RdfGraph,
        labelVectors: Map<out RdfResource, FloatArray>,
    ): List<RdfTriple> {
        val pairs = LabelExtractor.extractLabelAndDefinitionTexts(ontology)
        if (pairs.isEmpty()) return emptyList()
        val missingLabels = pairs.filter { (iri, _) -> iri !in labelVectors }
        val freshLabels =
            if (missingLabels.isEmpty()) emptyMap()
            else missingLabels.map { it.first }.zip(model.embed(missingLabels.map { it.second.first })).toMap()
        val labelEmb = pairs.map { (iri, _) -> labelVectors[iri] ?: freshLabels.getValue(iri) }
        val defEmb = model.embed(pairs.map { it.second.second })
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

    /** Hash of the ONNX model; computed once per model instance by [OnnxEmbeddingModel.modelSha256]. */
    private fun modelHash(): String = (model as? OnnxEmbeddingModel)?.modelSha256 ?: "unknown"

    private fun provenanceGraph(
        modelHash: String,
        entitiesProcessed: Int,
        pairsAboveThreshold: Int,
        labelDriftTriples: List<RdfTriple>,
        similarityTriples: List<RdfTriple>,
    ): RdfGraph {
        val g = JenaBridge.createEmptyModel()
        val root = Iri("urn:onto-quality:enrichment:${UUID.randomUUID()}")
        val hash = modelHash

        fun stringLit(s: String) = Literal(s, XSD.string)

        g.addTriple(RdfTriple(root, RDF.type, EnrichmentVocabulary.Enrichment))
        g.addTriple(RdfTriple(root, EnrichmentVocabulary.model, stringLit(model.name)))
        g.addTriple(RdfTriple(root, EnrichmentVocabulary.modelHash, stringLit(hash)))
        g.addTriple(RdfTriple(root, EnrichmentVocabulary.threshold, decimal(BigDecimal.valueOf(threshold))))
        g.addTriple(RdfTriple(root, EnrichmentVocabulary.tokenizer, stringLit(model.tokenizerDescription)))
        g.addTriple(RdfTriple(root, EnrichmentVocabulary.similaritySearchMode, stringLit(searchMode.label)))
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

    /** Closes this enricher and any model it owns; caller-supplied models remain open. */
    override fun close() {
        if (closed.compareAndSet(false, true)) ownedModel?.close()
    }

    companion object {
        /** Creates an enricher that owns its native model. Close the result with `use`. */
        fun default(): SemanticEnricher {
            val model = OnnxEmbeddingModel.fromMiniLm()
            return try {
                SemanticEnricher(model).apply { ownedModel = model }
            } catch (failure: Throwable) {
                model.close()
                throw failure
            }
        }

        private fun dotProduct(a: FloatArray, b: FloatArray): Double {
            var s = 0.0
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
