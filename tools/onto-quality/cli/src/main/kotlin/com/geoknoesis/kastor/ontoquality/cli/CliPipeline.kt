package com.geoknoesis.kastor.ontoquality.cli

import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.ontoquality.metrics.integration.KastorMetricsProvider
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoning
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile
import com.geoknoesis.kastor.ontoquality.embed.OnnxEmbeddingModel
import com.geoknoesis.kastor.ontoquality.embed.SemanticEnricher
import com.geoknoesis.kastor.ontoquality.embed.SimilarityLimitsPolicy
import com.geoknoesis.kastor.ontoquality.embed.SimilaritySearchMode
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Path
import java.time.Duration

/** Loads the ONNX model; options must already be validated with [OnnxEmbeddingModel.validateCliOptions]. */
internal fun loadOnnxEmbeddingModel(options: EmbeddingCliOptions): OnnxEmbeddingModel =
    OnnxEmbeddingModel.fromCliOptions(
        modelId = options.modelId,
        cacheRoot = options.cacheDir?.toAbsolutePath(),
        onnxPath = options.onnx?.toAbsolutePath(),
        tokenizerPath = options.tokenizer?.toAbsolutePath(),
        embeddingDim = options.embeddingDim,
        maxTokens = options.maxTokens,
        displayName = options.displayName,
        tokenizerNote = options.tokenizerNote,
    )

internal fun buildChecker(
    catalogOpt: String,
    validator: com.geoknoesis.kastor.rdf.shacl.ShaclValidator,
    withMetricsProvider: Boolean,
): QualityChecker {
    fun maybeMetrics(builder: QualityChecker.Builder): QualityChecker.Builder =
        if (withMetricsProvider) {
            builder.withMetricsProvider(KastorMetricsProvider())
        } else {
            builder
        }

    val builder =
        when (catalogOpt.lowercase()) {
            "all" -> maybeMetrics(QualityChecker.builder(validator).withAllBundledCatalogs())
            "owl-quality" ->
                maybeMetrics(QualityChecker.builder(validator).addCatalog(BundledCatalogs.OWL_QUALITY))
            "skos-validation" ->
                maybeMetrics(QualityChecker.builder(validator).addCatalog(BundledCatalogs.SKOS_VALIDATION))
            "data-quality" ->
                maybeMetrics(QualityChecker.builder(validator).addCatalog(BundledCatalogs.DATA_QUALITY))
            "embedding-quality" ->
                maybeMetrics(QualityChecker.builder(validator).addCatalog(BundledCatalogs.EMBEDDING_QUALITY))
            "modern-engineering" ->
                maybeMetrics(QualityChecker.builder(validator).addCatalog(BundledCatalogs.MODERN_ENGINEERING))
            "rdf12-quality" ->
                maybeMetrics(QualityChecker.builder(validator).addCatalog(BundledCatalogs.RDF12_QUALITY))
            "skos-vocabulary" ->
                maybeMetrics(QualityChecker.builder(validator).addCatalogs(BundledCatalogs.SKOS_VOCABULARY_QC))
            "skos-vocabulary-embed" ->
                maybeMetrics(
                    QualityChecker.builder(validator).addCatalogs(BundledCatalogs.SKOS_VOCABULARY_QC_WITH_EMBEDDING),
                )
            else ->
                throw usageError(
                    "Unknown --catalog $catalogOpt (expected $CATALOG_HELP)",
                )
        }
    return builder.build()
}


/** Options for loading an embedding model (enrich / pipeline). */
internal data class EmbeddingCliOptions(
    val modelId: String,
    val cacheDir: Path?,
    val onnx: Path?,
    val tokenizer: Path?,
    val embeddingDim: Int?,
    val maxTokens: Int,
    val displayName: String?,
    val tokenizerNote: String?,
    val threshold: Double,
    /** `--similarity-max-work`; null scales the budget with the number of labelled entities. */
    val similarityMaxWork: Long? = null,
    /** `--similarity-timeout`; null scales the deadline with the number of labelled entities. */
    val similarityTimeout: Duration? = null,
    /** `--similarity-max-pairs`; null keeps the default result-pair limit. */
    val similarityMaxPairs: Int? = null,
    val similarityMode: SimilaritySearchMode = SimilaritySearchMode.Exact,
    /** `--label-language`; null embeds the ontology's most common label language. */
    val labelLanguage: String? = null,
)

/** Scaled similarity limits with the CLI overrides applied. */
internal fun EmbeddingCliOptions.similarityLimitsPolicy(): SimilarityLimitsPolicy =
    SimilarityLimitsPolicy.scaled(similarityMaxWork, similarityTimeout, similarityMaxPairs)

/** Semantic enrichment step, owning any native resources until [close]. */
internal interface PipelineEnricher : AutoCloseable {
    fun enrich(ontology: RdfGraph): RdfGraph
}

internal fun interface PipelineEnricherFactory {
    fun open(options: EmbeddingCliOptions): PipelineEnricher
}

/** Default factory: ONNX model + [SemanticEnricher]; the model is closed with the enricher. */
internal object OnnxPipelineEnricherFactory : PipelineEnricherFactory {
    override fun open(options: EmbeddingCliOptions): PipelineEnricher {
        val model = loadOnnxEmbeddingModel(options)
        return try {
            val enricher = SemanticEnricher(model, options.threshold, options.similarityLimitsPolicy(), options.similarityMode, options.labelLanguage)
            object : PipelineEnricher {
                override fun enrich(ontology: RdfGraph): RdfGraph = enricher.enrich(ontology)

                override fun close() {
                    try { enricher.close() } finally { model.close() }
                }
            }
        } catch (failure: Throwable) {
            model.close()
            throw failure
        }
    }
}


/** [OntoQualityReasoning.supports], treating missing reasoner classes (a [LinkageError]) as unavailable. */
internal fun reasonerAvailableOnClasspath(profile: OntoQualityReasoningProfile): Boolean =
    try {
        OntoQualityReasoning.supports(profile)
    } catch (_: LinkageError) {
        false
    } catch (_: Exception) {
        false
    }


/** Opens the enrichment step; model download or loading failures are runtime errors, not usage errors. */
internal fun openEnricher(environment: CliEnvironment, options: EmbeddingCliOptions): PipelineEnricher =
    try {
        environment.enricherFactory.open(options)
    } catch (e: CliktError) {
        throw e
    } catch (e: Exception) {
        throw CliktError("Failed to load embedding model ${options.modelId}: ${describeFailure(e)}", e, EXIT_RUNTIME_ERROR)
    }


/** Usage error with guidance when the selected reasoner cannot run (e.g. HermiT missing from the classpath). */
internal fun requireReasonerAvailable(environment: CliEnvironment, profile: OntoQualityReasoningProfile, option: String) {
    if (profile == OntoQualityReasoningProfile.NONE || environment.reasonerAvailable(profile)) return
    val guidance =
        if (profile == OntoQualityReasoningProfile.HERMIT) {
            "HermiT (:rdf:reasoning-hermit with the OWL API) is not on the classpath. Add it to the classpath, or use --reasoner owl-rl, owl-micro or rdfs."
        } else {
            "use another --reasoner (none, rdfs, owl-micro, owl-rl)."
        }
    throw usageError("--reasoner ${option.lowercase()} is not available: $guidance")
}

/** `owl-rl` runs Jena's OWL rule reasoner; `owl-micro` runs Jena's faster, less complete OWL Micro rule reasoner. */
internal fun parseReasonerProfile(s: String): OntoQualityReasoningProfile =
    when (s.lowercase()) {
        "none",
        "off",
        -> OntoQualityReasoningProfile.NONE
        "rdfs" -> OntoQualityReasoningProfile.RDFS
        "owl-rl",
        "owl_rl",
        -> OntoQualityReasoningProfile.OWL_RL
        "owl-micro",
        "owl_micro",
        -> OntoQualityReasoningProfile.OWL_MICRO
        "hermit" -> OntoQualityReasoningProfile.HERMIT
        else ->
            throw usageError("Unknown --reasoner $s (expected none|rdfs|owl-micro|owl-rl|hermit)")
    }

internal fun severityThreshold(severityOpt: String): SeverityThreshold =
    when (severityOpt.lowercase()) {
        "violation" -> SeverityThreshold.VIOLATION
        "warning" -> SeverityThreshold.WARNING
        "info" -> SeverityThreshold.INFO
        else -> throw usageError("Unknown --severity $severityOpt (expected violation|warning|info)")
    }

internal enum class SeverityThreshold {
    INFO,
    WARNING,
    VIOLATION,
}

private fun ViolationSeverity.rank(): Int =
    when (this) {
        ViolationSeverity.TRACE,
        ViolationSeverity.DEBUG,
        ViolationSeverity.INFO,
        -> 0
        ViolationSeverity.WARNING -> 1
        ViolationSeverity.VIOLATION,
        ViolationSeverity.ERROR,
        -> 2
    }

/** True when any finding is at or above [threshold]; with INFO every finding (including debug / trace rows) counts. */
internal fun shouldFail(report: QualityReport, threshold: SeverityThreshold): Boolean =
    when (threshold) {
        SeverityThreshold.INFO ->
            report.underlying.violations.isNotEmpty() || report.underlying.warnings.isNotEmpty()
        SeverityThreshold.WARNING ->
            report.underlying.violations.any { it.severity.rank() >= ViolationSeverity.WARNING.rank() } ||
                report.underlying.warnings.isNotEmpty()
        SeverityThreshold.VIOLATION ->
            report.underlying.violations.any {
                it.severity.rank() >= ViolationSeverity.VIOLATION.rank()
            }
    }
