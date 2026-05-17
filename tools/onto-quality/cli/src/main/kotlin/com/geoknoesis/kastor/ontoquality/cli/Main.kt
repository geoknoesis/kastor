package com.geoknoesis.kastor.ontoquality.cli

import com.geoknoesis.kastor.ontoquality.PitfallReference
import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.ontoquality.MarkdownReportOptions
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.ontoquality.explanation.ExplainedQualityReport
import com.geoknoesis.kastor.ontoquality.explanation.ExplanationOptions
import com.geoknoesis.kastor.ontoquality.explanation.FindingRef
import com.geoknoesis.kastor.ontoquality.explanation.isAtLeast
import com.geoknoesis.kastor.ontoquality.llm.ExplanationModelPreset
import com.geoknoesis.kastor.ontoquality.llm.LlmExplanationConfig
import com.geoknoesis.kastor.ontoquality.llm.LlmProvider
import com.geoknoesis.kastor.ontoquality.llm.qualityExplanationEnricher
import com.geoknoesis.kastor.ontoquality.metrics.MetricsConfig
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetrics
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetricsReport
import com.geoknoesis.kastor.ontoquality.metrics.integration.KastorMetricsProvider
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile
import com.geoknoesis.kastor.ontoquality.embed.EnrichmentVocabulary
import com.geoknoesis.kastor.ontoquality.embed.OnnxEmbeddingModel
import com.geoknoesis.kastor.ontoquality.embed.SemanticEnricher
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import com.geoknoesis.kastor.rdf.serialize
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import com.geoknoesis.kastor.rdf.shacl.toShaclValidationReportRdf
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    OntoQualityApp().main(args)
}

private val logger = LoggerFactory.getLogger("onto-qa")

/** Catalog values for [buildChecker]; documented on `--catalog` in check/pipeline. */
private const val CATALOG_HELP =
    "owl-quality | skos-validation | data-quality | embedding-quality | " +
        "modern-engineering | rdf12-quality | skos-vocabulary | skos-vocabulary-embed | all"

/** Catalogs that include embedding shapes; log if the graph has no enrichment triples. */
private val CATALOGS_USING_EMBEDDING_SHAPES =
    setOf("all", "embedding-quality", "skos-vocabulary-embed")

/** When `true`, `onto-qa check --explain` / `onto-qa explain` may call an LLM (Koog). */
private const val LLM_EXPLAIN_ENV = "KASTOR_ONTO_QUALITY_LLM"

private class OntoQualityApp : CliktCommand(name = "onto-qa") {
    init {
        subcommands(CheckCommand(), EnrichCommand(), PipelineCommand(), MetricsCommand())
    }

    override fun run() = Unit
}

private class MetricsCommand : CliktCommand(name = "metrics") {
    private val ontologyArg by argument("ontology", help = "Path to the ontology (.ttl)").path(mustExist = true)
    private val formatOpt by option("--format", help = "text | markdown | json | turtle").default("text")
    private val outputOpt by option("--output", help = "Write output to this file instead of stdout").path()
    private val includeOpt by option("--include", help = "owl | skos | graph | all").default("all")
    private val topNOpt by option("--top-n").int().default(20)
    private val maxDepthOpt by option("--max-depth").int().default(50)
    private val noScoresOpt by option("--no-scores", help = "Disable OQuaRE 1–5 scores (raw metrics only)").flag(default = false)

    override fun run() {
        val graph = Rdf.parseFromFile(ontologyArg.toString(), "TURTLE")
        val cfg =
            MetricsConfig(
                emitOQuaREScores = !noScoresOpt,
                topNHotSpots = topNOpt,
                maxDepthCap = maxDepthOpt,
            )
        val report = VocabularyMetrics.compute(graph, cfg)
        val text =
            emitMetricsCliReport(
                report,
                formatOpt.lowercase(),
                includeOpt.lowercase(),
            )
        val sink = outputOpt
        if (sink != null) {
            sink.toFile().writeText(text)
        } else {
            println(text)
        }
    }
}

private fun emitMetricsCliReport(
    report: VocabularyMetricsReport,
    format: String,
    include: String,
): String {
    val inc = include.lowercase()
    return when (format) {
        "json" -> report.toJson()
        "turtle" -> report.toTurtle()
        "text" ->
            when (inc) {
                "all" -> report.describeText()
                "graph" -> sliceMetricsTextGraph(report)
                "owl" -> sliceMetricsTextOwl(report)
                "skos" -> sliceMetricsTextSkos(report)
                else -> throw UsageError("Unknown --include $include (expected owl|skos|graph|all)")
            }
        "markdown" ->
            when (inc) {
                "all" -> report.describeMarkdown()
                "graph" -> sliceMetricsMarkdownGraph(report)
                "owl" -> sliceMetricsMarkdownOwl(report)
                "skos" -> sliceMetricsMarkdownSkos(report)
                else -> throw UsageError("Unknown --include $include (expected owl|skos|graph|all)")
            }
        else -> throw UsageError("Unknown --format $format (expected text|markdown|json|turtle)")
    }
}

private fun sliceMetricsTextGraph(report: VocabularyMetricsReport): String {
    val full = report.describeText()
    val h = full.substring(0, full.indexOf("[Graph]")).trimEnd()
    val i0 = full.indexOf("[Graph]")
    val i1 = full.indexOf("[OQuaRE]")
    require(i0 >= 0 && i1 > i0) { "unexpected metrics text layout" }
    val body = full.substring(i0, i1).trimEnd()
    return "$h\n$body"
}

private fun sliceMetricsTextOwl(report: VocabularyMetricsReport): String {
    val full = report.describeText()
    val h = full.substring(0, full.indexOf("[OQuaRE]")).trimEnd()
    val i0 = full.indexOf("[OQuaRE]")
    val i1 = full.indexOf("[SKOS]")
    require(i0 >= 0 && i1 > i0) { "unexpected metrics text layout" }
    val body = full.substring(i0, i1).trimEnd()
    return "$h\n$body"
}

private fun sliceMetricsTextSkos(report: VocabularyMetricsReport): String {
    val full = report.describeText()
    val h = full.substring(0, full.indexOf("[SKOS]")).trimEnd()
    val i0 = full.indexOf("[SKOS]")
    require(i0 >= 0) { "unexpected metrics text layout" }
    val body = full.substring(i0).trimEnd()
    return "$h\n$body"
}

private fun sliceMetricsMarkdownGraph(report: VocabularyMetricsReport): String {
    val full = report.describeMarkdown()
    val head = full.substring(0, full.indexOf("## VoID-style graph counts")).trimEnd()
    val i0 = full.indexOf("## VoID-style graph counts")
    val i1 = full.indexOf("### Structural")
    require(i0 >= 0 && i1 > i0) { "unexpected metrics markdown layout" }
    val body = full.substring(i0, i1).trimEnd()
    return "$head\n\n$body"
}

private fun sliceMetricsMarkdownOwl(report: VocabularyMetricsReport): String {
    val full = report.describeMarkdown()
    val head = full.substring(0, full.indexOf("## VoID-style graph counts")).trimEnd()
    val i0 = full.indexOf("### Structural")
    val i1 = full.indexOf("## SKOS extensions")
    require(i0 >= 0 && i1 > i0) { "unexpected metrics markdown layout" }
    val body = full.substring(i0, i1).trimEnd()
    return "$head\n\n$body"
}

private fun sliceMetricsMarkdownSkos(report: VocabularyMetricsReport): String {
    val full = report.describeMarkdown()
    val head = full.substring(0, full.indexOf("## VoID-style graph counts")).trimEnd()
    val i0 = full.indexOf("## SKOS extensions")
    require(i0 >= 0) { "unexpected metrics markdown layout" }
    val body = full.substring(i0).trimEnd()
    return "$head\n\n$body"
}

private class EnrichCommand : CliktCommand(name = "enrich") {
    private val ontologyArg by argument("ontology", help = "Path to the ontology (.ttl)").path(mustExist = true)
    private val modelOpt by
        option(
            "--model",
            help = "${OnnxEmbeddingModel.MODEL_ID_MINILM} (bundled) | ${OnnxEmbeddingModel.MODEL_ID_CUSTOM} (local ONNX + tokenizer)",
        ).default(OnnxEmbeddingModel.MODEL_ID_MINILM)
    private val thresholdOpt by option("--threshold").double().default(0.85)
    private val cacheDirOpt by option("--cache-dir", help = "Model cache root (MiniLM only; default: ~/.kastor/onto-quality/models)").path()
    private val onnxOpt by option("--onnx", help = "Path to .onnx (${OnnxEmbeddingModel.MODEL_ID_CUSTOM} only)").path(mustExist = true)
    private val tokenizerOpt by
        option("--tokenizer", help = "Path to tokenizer.json (${OnnxEmbeddingModel.MODEL_ID_CUSTOM} only)").path(mustExist = true)
    private val embeddingDimOpt by
        option("--embedding-dim", help = "Hidden size from the ONNX graph (${OnnxEmbeddingModel.MODEL_ID_CUSTOM} only)").int()
    private val maxTokensOpt by option("--max-tokens", help = "Max sequence length (default: 512)").int().default(512)
    private val modelDisplayNameOpt by
        option("--model-display-name", help = "Provenance id for the model (default: ONNX file stem)")
    private val tokenizerNoteOpt by
        option("--tokenizer-note", help = "Provenance id for tokenizer, e.g. HuggingFace model id (default: display name)")
    private val outputOpt by option("--output", help = "Output Turtle path").path()

    override fun run() {
        val graph = Rdf.parseFromFile(ontologyArg.toString(), "TURTLE")
        loadOnnxEmbeddingModel(
            modelOpt = modelOpt,
            cacheDirOpt = cacheDirOpt,
            onnxOpt = onnxOpt,
            tokenizerOpt = tokenizerOpt,
            embeddingDimOpt = embeddingDimOpt,
            maxTokensOpt = maxTokensOpt,
            displayNameOpt = modelDisplayNameOpt,
            tokenizerNoteOpt = tokenizerNoteOpt,
        ).use { model ->
            val enricher = SemanticEnricher(model = model, threshold = thresholdOpt)
            System.err.println("Embedding and building similarity index (threshold=$thresholdOpt)…")
            val enriched = enricher.enrich(graph)

            val out =
                outputOpt ?: run {
                    val name = ontologyArg.fileName.toString().removeSuffix(".ttl")
                    ontologyArg.parent?.resolve("$name.enriched.ttl")
                        ?: Path.of("$name.enriched.ttl")
                }
            val turtle = enriched.serialize(RdfFormat.TURTLE)
            out.toAbsolutePath().parent?.toFile()?.mkdirs()
            out.toFile().writeText(turtle)
            System.err.println("Wrote enriched ontology to $out")
        }
    }
}

private class PipelineCommand : CliktCommand(name = "pipeline") {
    private val ontologyArg by argument("ontology", help = "Path to the ontology (.ttl)").path(mustExist = true)
    private val modelOpt by
        option(
            "--model",
            help = "${OnnxEmbeddingModel.MODEL_ID_MINILM} (bundled) | ${OnnxEmbeddingModel.MODEL_ID_CUSTOM} (local ONNX + tokenizer)",
        ).default(OnnxEmbeddingModel.MODEL_ID_MINILM)
    private val thresholdOpt by option("--threshold").double().default(0.85)
    private val cacheDirOpt by option("--cache-dir", help = "Model cache root (MiniLM only; default: ~/.kastor/onto-quality/models)").path()
    private val onnxOpt by option("--onnx", help = "Path to .onnx (${OnnxEmbeddingModel.MODEL_ID_CUSTOM} only)").path(mustExist = true)
    private val tokenizerOpt by
        option("--tokenizer", help = "Path to tokenizer.json (${OnnxEmbeddingModel.MODEL_ID_CUSTOM} only)").path(mustExist = true)
    private val embeddingDimOpt by
        option("--embedding-dim", help = "Hidden size from the ONNX graph (${OnnxEmbeddingModel.MODEL_ID_CUSTOM} only)").int()
    private val maxTokensOpt by option("--max-tokens", help = "Max sequence length (default: 512)").int().default(512)
    private val modelDisplayNameOpt by
        option("--model-display-name", help = "Provenance id for the model (default: ONNX file stem)")
    private val tokenizerNoteOpt by
        option("--tokenizer-note", help = "Provenance id for tokenizer, e.g. HuggingFace model id (default: display name)")
    private val catalogOpt by
        option("--catalog", help = CATALOG_HELP)
            .default("all")
    private val formatOpt by
        option("--format", help = "text | markdown | json | turtle").default("text")
    private val severityOpt by
        option("--severity", help = "violation | warning | info").default("violation")
    private val outputOpt by
        option("--output", help = "Write output to this file instead of stdout")
            .path()
    private val keepIntermediate by
        option("--keep-intermediate", help = "Keep the temporary enriched Turtle file")
            .flag(default = false)
    private val explainOpt by
        option(
            "--explain",
            help = "Add LLM explanations via Koog (requires $LLM_EXPLAIN_ENV=true and provider credentials).",
        ).flag(default = false)
    private val explainDryRun by
        option("--explain-dry-run", help = "Print how many findings would be explained; no API call.").flag(default = false)
    private val llmProviderOpt by
        option("--llm-provider", help = "openai | anthropic | ollama").default("openai")
    private val llmModelOpt by option("--llm-model", help = "Model id override (provider-specific; overrides --llm-model-preset)")
    private val llmModelPresetOpt by
        option(
            "--llm-model-preset",
            help =
                "When --llm-model is omitted: auto | gpt4o-mini | gpt4o | sonnet-4-5 | haiku-4-5 | llama3.2",
        ).default("auto")
    private val ollamaBaseOpt by
        option("--ollama-base", help = "Ollama base URL (default: http://localhost:11434)")
    private val explainMaxOpt by option("--explain-max", help = "Max findings to explain").int().default(50)
    private val explainBatchOpt by option("--explain-batch", help = "Findings per LLM request").int().default(12)
    private val explainMinSeverityOpt by
        option("--explain-min-severity", help = "violation | warning | info").default("warning")
    private val reasonerOpt by
        option(
            "--reasoner",
            help = "none | rdfs | owl-micro | hermit — materialize inferences before SHACL (v0.4; hermit = OWL DL via HermiT)",
        ).default("none")
    private val markdownAsciiOpt by
        option(
            "--markdown-ascii",
            help = "With --format markdown: use [VIOLATION]/[WARNING] markers instead of emoji.",
        ).flag(default = false)

    override fun run() {
        loadOnnxEmbeddingModel(
            modelOpt = modelOpt,
            cacheDirOpt = cacheDirOpt,
            onnxOpt = onnxOpt,
            tokenizerOpt = tokenizerOpt,
            embeddingDimOpt = embeddingDimOpt,
            maxTokensOpt = maxTokensOpt,
            displayNameOpt = modelDisplayNameOpt,
            tokenizerNoteOpt = tokenizerNoteOpt,
        ).use { model ->
            val enricher = SemanticEnricher(model = model, threshold = thresholdOpt)
            System.err.println("Pipeline: enriching…")
            val ontology = Rdf.parseFromFile(ontologyArg.toString(), "TURTLE")
            val enriched = enricher.enrich(ontology)

            val tmp = Files.createTempFile("onto-qa-", ".enriched.ttl")
            try {
                Files.writeString(tmp, enriched.serialize(RdfFormat.TURTLE))
                if (keepIntermediate) {
                    System.err.println("Kept intermediate enriched graph at $tmp")
                }

                val validator = ShaclValidation.validator()
                val checker = buildChecker(catalogOpt, validator, withMetricsProvider = false)
                if (catalogOpt.lowercase() in CATALOGS_USING_EMBEDDING_SHAPES) {
                    val hasClose =
                        enriched.getTriplesSequence().any {
                            it.predicate == EnrichmentVocabulary.semanticallyCloseTo
                        }
                    if (!hasClose) {
                        logger.info(
                            "Embedding-quality catalog selected but no {} enrichment triples were materialized; semantic similarity shapes will not fire.",
                            EnrichmentVocabulary.semanticallyCloseTo,
                        )
                    }
                }

                val reasoningProfile = parseReasonerProfile(reasonerOpt)
                if (reasoningProfile != OntoQualityReasoningProfile.NONE) {
                    logger.info("Onto-quality reasoning profile: {}", reasoningProfile.name.lowercase())
                }
                val report = checker.check(enriched, reasoningProfile)
                val explained =
                    maybeExplainReport(
                        report,
                        explainCliFromFlags(
                            explainOpt,
                            explainDryRun,
                            llmProviderOpt,
                            llmModelOpt,
                            llmModelPresetOpt,
                            ollamaBaseOpt,
                            explainMaxOpt,
                            explainBatchOpt,
                            explainMinSeverityOpt,
                        ),
                    )
                emitReport(
                    report,
                    explained,
                    formatOpt,
                    outputOpt,
                    MarkdownReportOptions(useAsciiSeverityMarkers = markdownAsciiOpt),
                )
                System.err.println(
                    "Pipeline complete (catalog=$catalogOpt). Use --catalog skos-vocabulary-embed for SKOS + embedding shapes after enrichment.",
                )

                if (shouldFail(report, severityThreshold(severityOpt))) {
                    exitProcess(1)
                }
            } finally {
                if (!keepIntermediate) {
                    Files.deleteIfExists(tmp)
                }
            }
        }
    }
}

private class CheckCommand : CliktCommand(name = "check") {
    private val ontologyArg by argument("ontology", help = "Path to the ontology (.ttl)").path(mustExist = true)
    private val catalogOpt by
        option(
            "--catalog",
            help = CATALOG_HELP,
        ).default("all")
    private val formatOpt by
        option("--format", help = "text | markdown | json | turtle").default("text")
    private val severityOpt by
        option("--severity", help = "violation | warning | info").default("violation")
    private val outputOpt by
        option("--output", help = "Write output to this file instead of stdout")
            .path()
    private val explainOpt by
        option(
            "--explain",
            help = "Add LLM explanations via Koog (requires $LLM_EXPLAIN_ENV=true and provider credentials).",
        ).flag(default = false)
    private val explainDryRun by
        option("--explain-dry-run", help = "Print how many findings would be explained; no API call.").flag(default = false)
    private val llmProviderOpt by
        option("--llm-provider", help = "openai | anthropic | ollama").default("openai")
    private val llmModelOpt by option("--llm-model", help = "Model id override (provider-specific; overrides --llm-model-preset)")
    private val llmModelPresetOpt by
        option(
            "--llm-model-preset",
            help =
                "When --llm-model is omitted: auto | gpt4o-mini | gpt4o | sonnet-4-5 | haiku-4-5 | llama3.2",
        ).default("auto")
    private val ollamaBaseOpt by
        option("--ollama-base", help = "Ollama base URL (default: http://localhost:11434)")
    private val explainMaxOpt by option("--explain-max", help = "Max findings to explain").int().default(50)
    private val explainBatchOpt by option("--explain-batch", help = "Findings per LLM request").int().default(12)
    private val explainMinSeverityOpt by
        option("--explain-min-severity", help = "violation | warning | info").default("warning")
    private val reasonerOpt by
        option(
            "--reasoner",
            help = "none | rdfs | owl-micro | hermit — materialize inferences before SHACL (v0.4; hermit = OWL DL via HermiT)",
        ).default("none")
    private val withMetricsOpt by
        option(
            "--with-metrics",
            help = "Include OQuaRE metrics and prioritize findings (Markdown adds Top findings + Metrics summary).",
        ).flag(default = false)
    private val noMetricsOpt by
        option("--no-metrics", help = "Disable metrics integration even if --with-metrics was passed.")
            .flag(default = false)
    private val markdownAsciiOpt by
        option(
            "--markdown-ascii",
            help = "With --format markdown: use [VIOLATION]/[WARNING] markers instead of emoji in quality sections.",
        ).flag(default = false)

    override fun run() {
        val validator = ShaclValidation.validator()
        val useMetrics = withMetricsOpt && !noMetricsOpt
        val checker = buildChecker(catalogOpt, validator, useMetrics)

        val graph = Rdf.parseFromFile(ontologyArg.toString(), "TURTLE")

        if (catalogOpt.lowercase() in CATALOGS_USING_EMBEDDING_SHAPES) {
            val hasClose =
                graph.getTriplesSequence().any { it.predicate == EnrichmentVocabulary.semanticallyCloseTo }
            if (!hasClose) {
                logger.info(
                    "Embedding-quality catalog selected but no {} triples found in the ontology; semantic similarity shapes will not produce findings.",
                    EnrichmentVocabulary.semanticallyCloseTo,
                )
            }
        }

        val reasoningProfile = parseReasonerProfile(reasonerOpt)
        if (reasoningProfile != OntoQualityReasoningProfile.NONE) {
            logger.info("Onto-quality reasoning profile: {}", reasoningProfile.name.lowercase())
        }
        val report = checker.check(graph, reasoningProfile)
        val explained =
            maybeExplainReport(
                report,
                explainCliFromFlags(
                    explainOpt,
                    explainDryRun,
                    llmProviderOpt,
                    llmModelOpt,
                    llmModelPresetOpt,
                    ollamaBaseOpt,
                    explainMaxOpt,
                    explainBatchOpt,
                    explainMinSeverityOpt,
                ),
            )
        emitReport(
            report,
            explained,
            formatOpt,
            outputOpt,
            MarkdownReportOptions(useAsciiSeverityMarkers = markdownAsciiOpt),
        )
        if (shouldFail(report, severityThreshold(severityOpt))) {
            exitProcess(1)
        }
    }
}

private fun parseReasonerProfile(s: String): OntoQualityReasoningProfile =
    when (s.lowercase()) {
        "none",
        "off",
        -> OntoQualityReasoningProfile.NONE
        "rdfs" -> OntoQualityReasoningProfile.RDFS
        "owl-micro",
        "owl_micro",
        -> OntoQualityReasoningProfile.OWL_MICRO
        "hermit" -> OntoQualityReasoningProfile.HERMIT
        else ->
            throw UsageError("Unknown --reasoner $s (expected none|rdfs|owl-micro|hermit)")
    }

private data class LlmExplainCli(
    val requested: Boolean,
    val dryRun: Boolean,
    val provider: LlmProvider,
    val modelId: String?,
    val modelPreset: ExplanationModelPreset,
    val ollamaBase: String?,
    val maxFindings: Int,
    val batchSize: Int,
    val minSeverity: ViolationSeverity,
)

private fun explainCliFromFlags(
    explainOpt: Boolean,
    explainDryRun: Boolean,
    llmProviderOpt: String,
    llmModelOpt: String?,
    llmModelPresetOpt: String,
    ollamaBaseOpt: String?,
    explainMaxOpt: Int,
    explainBatchOpt: Int,
    explainMinSeverityOpt: String,
): LlmExplainCli? =
    if (!explainOpt) {
        null
    } else {
        LlmExplainCli(
            requested = true,
            dryRun = explainDryRun,
            provider = parseLlmProvider(llmProviderOpt),
            modelId = llmModelOpt?.trim()?.takeIf { it.isNotEmpty() },
            modelPreset = parseExplanationModelPreset(llmModelPresetOpt),
            ollamaBase = ollamaBaseOpt?.trim()?.takeIf { it.isNotEmpty() },
            maxFindings = explainMaxOpt,
            batchSize = explainBatchOpt,
            minSeverity = explainMinViolationSeverity(explainMinSeverityOpt),
        )
    }

private fun parseLlmProvider(s: String): LlmProvider =
    when (s.lowercase()) {
        "openai" -> LlmProvider.OPENAI
        "anthropic" -> LlmProvider.ANTHROPIC
        "ollama" -> LlmProvider.OLLAMA
        else -> throw UsageError("Unknown --llm-provider $s (expected openai|anthropic|ollama)")
    }

private fun parseExplanationModelPreset(s: String): ExplanationModelPreset =
    when (s.lowercase()) {
        "auto" -> ExplanationModelPreset.AUTO
        "gpt4o-mini",
        "gpt-4o-mini",
        -> ExplanationModelPreset.OPENAI_GPT4O_MINI
        "gpt4o",
        "gpt-4o",
        -> ExplanationModelPreset.OPENAI_GPT4O
        "sonnet",
        "sonnet-4-5",
        "claude-sonnet-4-5",
        -> ExplanationModelPreset.ANTHROPIC_SONNET_4_5
        "haiku",
        "haiku-4-5",
        -> ExplanationModelPreset.ANTHROPIC_HAIKU_4_5
        "llama3.2",
        "llama-3.2",
        -> ExplanationModelPreset.OLLAMA_LLAMA_3_2
        else ->
            throw UsageError(
                "Unknown --llm-model-preset $s (expected auto|gpt4o-mini|gpt4o|sonnet-4-5|haiku-4-5|llama3.2)",
            )
    }

private fun explainMinViolationSeverity(s: String): ViolationSeverity =
    when (s.lowercase()) {
        "violation" -> ViolationSeverity.VIOLATION
        "warning" -> ViolationSeverity.WARNING
        "info" -> ViolationSeverity.INFO
        else -> throw UsageError("Unknown --explain-min-severity $s (expected violation|warning|info)")
    }

private fun maybeExplainReport(
    report: QualityReport,
    llm: LlmExplainCli?,
): ExplainedQualityReport? {
    if (llm == null || !llm.requested) return null
    if (!System.getenv(LLM_EXPLAIN_ENV).equals("true", ignoreCase = true)) {
        logger.warn(
            "{} is not set to true; skipping LLM explanations (Koog).",
            LLM_EXPLAIN_ENV,
        )
        return null
    }
    if (llm.dryRun) {
        val n =
            report.findings
                .count { it.violation.severity.isAtLeast(llm.minSeverity) }
                .coerceAtMost(llm.maxFindings)
        val modelDesc =
            llm.modelId
                ?: "${llm.modelPreset.name.lowercase()} (preset)"
        System.err.println(
            "LLM explain dry-run: would send up to $n findings (provider=${llm.provider}, model=$modelDesc)",
        )
        return null
    }
    return try {
        val cfg =
            LlmExplanationConfig(
                provider = llm.provider,
                modelId = llm.modelId,
                modelPreset = llm.modelPreset,
                baseUrl = llm.ollamaBase,
            )
        val enricher = qualityExplanationEnricher(cfg)
        val opts =
            ExplanationOptions(
                maxFindings = llm.maxFindings,
                batchSize = llm.batchSize,
                minSeverity = llm.minSeverity,
            )
        runBlocking { enricher.enrich(report, opts) }
    } catch (e: Exception) {
        logger.warn("LLM explanations failed: {}", e.message)
        null
    }
}

private fun loadOnnxEmbeddingModel(
    modelOpt: String,
    cacheDirOpt: Path?,
    onnxOpt: Path?,
    tokenizerOpt: Path?,
    embeddingDimOpt: Int?,
    maxTokensOpt: Int,
    displayNameOpt: String?,
    tokenizerNoteOpt: String?,
): OnnxEmbeddingModel =
    try {
        OnnxEmbeddingModel.fromCliOptions(
            modelId = modelOpt,
            cacheRoot = cacheDirOpt?.toAbsolutePath(),
            onnxPath = onnxOpt?.toAbsolutePath(),
            tokenizerPath = tokenizerOpt?.toAbsolutePath(),
            embeddingDim = embeddingDimOpt,
            maxTokens = maxTokensOpt,
            displayName = displayNameOpt,
            tokenizerNote = tokenizerNoteOpt,
        )
    } catch (e: IllegalArgumentException) {
        throw UsageError(e.message ?: "Invalid embedding options")
    }

private fun buildChecker(
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
                throw UsageError(
                    "Unknown --catalog $catalogOpt (expected $CATALOG_HELP)",
                )
        }
    return builder.build()
}

private fun emitReport(
    report: QualityReport,
    explained: ExplainedQualityReport?,
    formatOpt: String,
    outputOpt: Path?,
    markdownOptions: MarkdownReportOptions = MarkdownReportOptions(),
) {
    val format =
        when (formatOpt.lowercase()) {
            "text" -> OutputFormat.TEXT
            "markdown" -> OutputFormat.MARKDOWN
            "json" -> OutputFormat.JSON
            "turtle" -> OutputFormat.TURTLE
            else -> throw UsageError("Unknown --format $formatOpt (expected text|markdown|json|turtle)")
        }

    val text =
        when (format) {
            OutputFormat.TEXT ->
                if (explained != null) {
                    explained.describeText()
                } else {
                    report.describeText()
                }
            OutputFormat.MARKDOWN ->
                if (explained != null) {
                    explained.describeMarkdown(markdownOptions)
                } else {
                    report.describeMarkdown(markdownOptions)
                }
            OutputFormat.JSON -> findingsToJson(report, explained)
            OutputFormat.TURTLE ->
                JenaBridge.toString(report.underlying.toShaclValidationReportRdf(), "TURTLE")
        }

    val sink = outputOpt
    if (sink != null) {
        sink.toFile().writeText(text)
    } else {
        println(text)
    }
}

private fun severityThreshold(severityOpt: String): SeverityThreshold =
    when (severityOpt.lowercase()) {
        "violation" -> SeverityThreshold.VIOLATION
        "warning" -> SeverityThreshold.WARNING
        "info" -> SeverityThreshold.INFO
        else -> throw UsageError("Unknown --severity $severityOpt (expected violation|warning|info)")
    }

private enum class OutputFormat {
    TEXT,
    MARKDOWN,
    JSON,
    TURTLE,
}

private enum class SeverityThreshold {
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

private fun shouldFail(report: QualityReport, threshold: SeverityThreshold): Boolean =
    when (threshold) {
        SeverityThreshold.INFO -> false
        SeverityThreshold.WARNING ->
            report.underlying.violations.any { it.severity.rank() >= ViolationSeverity.WARNING.rank() } ||
                report.underlying.warnings.isNotEmpty()
        SeverityThreshold.VIOLATION ->
            report.underlying.violations.any {
                it.severity.rank() >= ViolationSeverity.VIOLATION.rank()
            }
    }

private fun findingsToJson(
    report: QualityReport,
    explained: ExplainedQualityReport?,
): String {
    val rows =
        report.findings.map { f ->
            val pit =
                when (val p = f.pitfall) {
                    null -> "null"
                    PitfallReference.Convention -> "\"convention\""
                    is PitfallReference.Oops -> "\"OOPS:${escapeJson(p.number)}\""
                    is PitfallReference.Skos -> "\"SKOS:${escapeJson(p.number)}\""
                    is PitfallReference.OntoQuality -> "\"OntoQuality:${escapeJson(p.number)}\""
                    is PitfallReference.KastorExtension -> "\"Kastor:${escapeJson(p.code)}\""
                }
            val ref = FindingRef.from(f).hexSha256
            """
            {
              "findingRef": "${escapeJson(ref)}",
              "severity": "${f.violation.severity.name}",
              "message": "${escapeJson(f.violation.message)}",
              "shapeUri": ${f.violation.shapeUri?.let { "\"${escapeJson(it)}\"" } ?: "null"},
              "category": "${f.category.name}",
              "pitfall": $pit,
              "tier": "${f.tier.name}",
              "focusNode": "${escapeJson(focusNodeToString(f.violation.focusNode))}"
            }
            """.trimIndent()
        }
    val explRows =
        explained?.explanations?.map { e ->
            val actions = e.suggestedActions.joinToString(",") { "\"${escapeJson(it)}\"" }
            """
            {
              "findingRef": "${escapeJson(e.findingRef.hexSha256)}",
              "summary": "${escapeJson(e.summary)}",
              "whyItMatters": ${e.whyItMatters?.let { "\"${escapeJson(it)}\"" } ?: "null"},
              "suggestedActions": [$actions],
              "confidenceNote": ${e.confidenceNote?.let { "\"${escapeJson(it)}\"" } ?: "null"},
              "modelId": "${escapeJson(e.modelId)}",
              "providerKind": "${escapeJson(e.providerKind)}",
              "promptRunId": "${escapeJson(e.promptRunId)}"
            }
            """.trimIndent()
        }.orEmpty()
    return """
        {
          "findings": [${rows.joinToString(",\n")}],
          "llmExplanations": [${explRows.joinToString(",\n")}]
        }
        """.trimIndent()
}

private fun focusNodeToString(term: RdfTerm): String =
    when (term) {
        is Iri -> term.value
        is BlankNode -> "_:${term.id}"
        is Literal -> term.lexical
        else -> term.toString()
    }

private fun escapeJson(s: String): String =
    buildString(s.length + 8) {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
