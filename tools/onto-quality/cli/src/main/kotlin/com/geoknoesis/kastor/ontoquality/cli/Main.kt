package com.geoknoesis.kastor.ontoquality.cli

import com.geoknoesis.kastor.ontoquality.PitfallReference
import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.ontoquality.MarkdownReportOptions
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.ontoquality.explanation.ExplainedQualityReport
import com.geoknoesis.kastor.ontoquality.explanation.ExplanationOptions
import com.geoknoesis.kastor.ontoquality.explanation.FindingRef
import com.geoknoesis.kastor.ontoquality.explanation.QualityExplanationEnricher
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
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

fun main(args: Array<String>) {
    ontoQualityApp().main(args)
}

private val logger = LoggerFactory.getLogger("onto-qa")

/** Exit status when findings reach `--severity`. */
internal const val EXIT_FINDINGS = 1

/** Exit status when `--fail-on-explain-error` is set and LLM explanations failed (fully or partially). */
internal const val EXIT_EXPLAIN_ERROR = 3

/** Upper bound for `--explain-max` (LLM cost / rate-limit guard). */
internal const val MAX_EXPLAIN_FINDINGS = 500

private val CATALOG_NAMES =
    listOf(
        "owl-quality", "skos-validation", "data-quality", "embedding-quality", "modern-engineering",
        "rdf12-quality", "skos-vocabulary", "skos-vocabulary-embed", "all",
    )

/** Catalog values for [buildChecker]; documented on `--catalog` in check/pipeline. */
private val CATALOG_HELP = CATALOG_NAMES.joinToString(" | ")

/** Catalogs that include embedding shapes; log if the graph has no enrichment triples. */
private val CATALOGS_USING_EMBEDDING_SHAPES =
    setOf("all", "embedding-quality", "skos-vocabulary-embed")

/** When `true`, `onto-qa check --explain` / `onto-qa explain` may call an LLM (Koog). */
internal const val LLM_EXPLAIN_ENV = "KASTOR_ONTO_QUALITY_LLM"

private val FORMAT_CHOICES = arrayOf("text", "markdown", "json", "turtle")
private val SEVERITY_CHOICES = arrayOf("violation", "warning", "info")
private val REASONER_CHOICES = arrayOf("none", "off", "rdfs", "owl-micro", "owl_micro", "hermit")
private val PROVIDER_CHOICES = arrayOf("openai", "anthropic", "ollama")
private val PRESET_CHOICES =
    arrayOf("auto", "gpt4o-mini", "gpt-4o-mini", "gpt4o", "gpt-4o", "sonnet", "sonnet-4-5", "claude-sonnet-4-5", "haiku", "haiku-4-5", "llama3.2", "llama-3.2")
private const val INPUT_FORMAT_HELP =
    "turtle | rdfxml | ntriples | jsonld (default: from file extension — .ttl, .owl/.rdf/.xml, .nt, .jsonld/.json; otherwise Turtle)"

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
)

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
            val enricher = SemanticEnricher(model = model, threshold = options.threshold)
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

/** Injectable collaborators so commands can be exercised in tests without models, LLMs or process exits. */
internal class CliEnvironment(
    val enricherFactory: PipelineEnricherFactory = OnnxPipelineEnricherFactory,
    val explanationEnricherFactory: (LlmExplanationConfig) -> QualityExplanationEnricher = ::qualityExplanationEnricher,
    val env: (String) -> String? = System::getenv,
)

internal fun ontoQualityApp(environment: CliEnvironment = CliEnvironment()): CliktCommand =
    OntoQualityApp().subcommands(
        CheckCommand(environment),
        EnrichCommand(environment),
        PipelineCommand(environment),
        MetricsCommand(),
    )

private class OntoQualityApp : CliktCommand(name = "onto-qa") {
    override fun run() = Unit
}

/** Picks the RDF syntax from `--input-format` or the file extension. */
internal fun resolveInputFormat(path: Path, override: String?): RdfFormat {
    if (override != null) {
        val normalized =
            when (override.trim().lowercase()) {
                "rdfxml", "rdf-xml", "rdf/xml", "xml", "owl" -> RdfFormat.RDF_XML
                "ntriples", "n-triples", "nt" -> RdfFormat.N_TRIPLES
                "jsonld", "json-ld" -> RdfFormat.JSON_LD
                "turtle", "ttl" -> RdfFormat.TURTLE
                else -> null
            }
        return normalized ?: throw UsageError("Unsupported --input-format $override (expected $INPUT_FORMAT_HELP)")
    }
    val name = path.fileName?.toString()?.lowercase().orEmpty()
    return when {
        name.endsWith(".owl") || name.endsWith(".rdf") || name.endsWith(".xml") -> RdfFormat.RDF_XML
        name.endsWith(".nt") -> RdfFormat.N_TRIPLES
        name.endsWith(".jsonld") || name.endsWith(".json") -> RdfFormat.JSON_LD
        else -> RdfFormat.TURTLE
    }
}

private fun parseOntology(path: Path, format: RdfFormat): RdfGraph =
    try {
        Rdf.parseFromFile(path.toString(), format)
    } catch (e: Exception) {
        throw CliktError("Failed to parse $path as ${format.formatName}: ${e.message}", cause = e, statusCode = 2)
    }

private class MetricsCommand : CliktCommand(name = "metrics") {
    private val ontologyArg by argument("ontology", help = "Path to the ontology").path(mustExist = true)
    private val inputFormatOpt by option("--input-format", help = INPUT_FORMAT_HELP)
    private val formatOpt by option("--format", help = "text | markdown | json | turtle").choice(*FORMAT_CHOICES, ignoreCase = true).default("text")
    private val outputOpt by option("--output", help = "Write output to this file instead of stdout").path()
    private val includeOpt by option("--include", help = "owl | skos | graph | all").choice("owl", "skos", "graph", "all", ignoreCase = true).default("all")
    private val topNOpt by option("--top-n").int().restrictTo(min = 0).default(20)
    private val maxDepthOpt by option("--max-depth").int().restrictTo(min = 1).default(50)
    private val noScoresOpt by option("--no-scores", help = "Disable OQuaRE 1–5 scores (raw metrics only)").flag(default = false)

    override fun run() {
        val graph = parseOntology(ontologyArg, resolveInputFormat(ontologyArg, inputFormatOpt))
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
        writeOutput(text, outputOpt) { echo(it) }
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
                "graph" -> sliceReport(report.describeText(), "[Graph]", "[Graph]", "[OQuaRE]", "\n")
                "owl" -> sliceReport(report.describeText(), "[Graph]", "[OQuaRE]", "[SKOS]", "\n")
                "skos" -> sliceReport(report.describeText(), "[Graph]", "[SKOS]", null, "\n")
                else -> throw UsageError("Unknown --include $include (expected owl|skos|graph|all)")
            }
        "markdown" ->
            when (inc) {
                "all" -> report.describeMarkdown()
                "graph" -> sliceReport(report.describeMarkdown(), MD_GRAPH, MD_GRAPH, "### Structural", "\n\n")
                "owl" -> sliceReport(report.describeMarkdown(), MD_GRAPH, "### Structural", "## SKOS extensions", "\n\n")
                "skos" -> sliceReport(report.describeMarkdown(), MD_GRAPH, "## SKOS extensions", null, "\n\n")
                else -> throw UsageError("Unknown --include $include (expected owl|skos|graph|all)")
            }
        else -> throw UsageError("Unknown --format $format (expected text|markdown|json|turtle)")
    }
}

private const val MD_GRAPH = "## VoID-style graph counts"

/** Report header (text before [headerEnd]) followed by the section from [start] up to [end] (or the end). */
internal fun sliceReport(full: String, headerEnd: String, start: String, end: String?, separator: String): String {
    val headerIndex = full.indexOf(headerEnd)
    val i0 = full.indexOf(start)
    val i1 = if (end == null) full.length else full.indexOf(end)
    check(headerIndex >= 0 && i0 >= 0 && i1 > i0) { "unexpected metrics report layout (missing '$start' section)" }
    return full.substring(0, headerIndex).trimEnd() + separator + full.substring(i0, i1).trimEnd()
}

private class EnrichCommand(private val environment: CliEnvironment) : CliktCommand(name = "enrich") {
    private val ontologyArg by argument("ontology", help = "Path to the ontology").path(mustExist = true)
    private val inputFormatOpt by option("--input-format", help = INPUT_FORMAT_HELP)
    private val embedding by EmbeddingOptionGroup()
    private val outputOpt by option("--output", help = "Output Turtle path").path()

    override fun run() {
        val graph = parseOntology(ontologyArg, resolveInputFormat(ontologyArg, inputFormatOpt))
        environment.enricherFactory.open(embedding.toOptions()).use { enricher ->
            echo("Embedding and building similarity index (threshold=${embedding.threshold})…", err = true)
            val enriched = enricher.enrich(graph)

            val out =
                outputOpt ?: run {
                    val name = ontologyArg.fileName.toString().substringBeforeLast('.')
                    ontologyArg.parent?.resolve("$name.enriched.ttl")
                        ?: Path.of("$name.enriched.ttl")
                }
            val turtle = enriched.serialize(RdfFormat.TURTLE)
            out.toAbsolutePath().parent?.toFile()?.mkdirs()
            out.toFile().writeText(turtle)
            echo("Wrote enriched ontology to $out", err = true)
        }
    }
}

/** Embedding options shared by `enrich` and `pipeline`; validated by Clikt before any model is loaded. */
private class EmbeddingOptionGroup : com.github.ajalt.clikt.parameters.groups.OptionGroup() {
    val model by
        option(
            "--model",
            help = "${OnnxEmbeddingModel.MODEL_ID_MINILM} (bundled) | ${OnnxEmbeddingModel.MODEL_ID_CUSTOM} (local ONNX + tokenizer)",
        ).choice(OnnxEmbeddingModel.MODEL_ID_MINILM, OnnxEmbeddingModel.MODEL_ID_CUSTOM, ignoreCase = true)
            .default(OnnxEmbeddingModel.MODEL_ID_MINILM)
    val threshold by option("--threshold", help = "Cosine similarity threshold in [-1, 1] (default 0.85)")
        .double()
        .default(0.85)
        .check("must be between -1.0 and 1.0") { it in -1.0..1.0 }
    val cacheDir by option("--cache-dir", help = "Model cache root (MiniLM only; default: ~/.kastor/onto-quality/models)").path()
    val onnx by option("--onnx", help = "Path to .onnx (${OnnxEmbeddingModel.MODEL_ID_CUSTOM} only)").path(mustExist = true)
    val tokenizer by option("--tokenizer", help = "Path to tokenizer.json (${OnnxEmbeddingModel.MODEL_ID_CUSTOM} only)").path(mustExist = true)
    val embeddingDim by
        option("--embedding-dim", help = "Hidden size from the ONNX graph (${OnnxEmbeddingModel.MODEL_ID_CUSTOM} only)").int().restrictTo(min = 1)
    val maxTokens by option("--max-tokens", help = "Max sequence length / truncation (default: 512)").int().restrictTo(1..8192).default(512)
    val displayName by option("--model-display-name", help = "Provenance id for the model (default: ONNX file stem)")
    val tokenizerNote by
        option("--tokenizer-note", help = "Provenance id for tokenizer, e.g. HuggingFace model id (default: display name)")

    fun toOptions() =
        EmbeddingCliOptions(
            modelId = model,
            cacheDir = cacheDir,
            onnx = onnx,
            tokenizer = tokenizer,
            embeddingDim = embeddingDim,
            maxTokens = maxTokens,
            displayName = displayName,
            tokenizerNote = tokenizerNote,
            threshold = threshold,
        )
}

/** Report / LLM options shared by `check` and `pipeline`. */
private class ReportOptionGroup : com.github.ajalt.clikt.parameters.groups.OptionGroup() {
    val catalog by option("--catalog", help = CATALOG_HELP).choice(*CATALOG_NAMES.toTypedArray(), ignoreCase = true).default("all")
    val format by option("--format", help = "text | markdown | json | turtle").choice(*FORMAT_CHOICES, ignoreCase = true).default("text")
    val severity by
        option("--severity", help = "violation | warning | info — exit $EXIT_FINDINGS when findings reach this level")
            .choice(*SEVERITY_CHOICES, ignoreCase = true)
            .default("violation")
    val output by option("--output", help = "Write output to this file instead of stdout").path()
    val explain by
        option(
            "--explain",
            help = "Add LLM explanations via Koog (requires $LLM_EXPLAIN_ENV=true and provider credentials).",
        ).flag(default = false)
    val explainDryRun by
        option("--explain-dry-run", help = "Print how many findings would be explained; no API call.").flag(default = false)
    val failOnExplainError by
        option(
            "--fail-on-explain-error",
            help = "Exit $EXIT_EXPLAIN_ERROR when LLM explanations fail or are incomplete (default: warn and continue).",
        ).flag(default = false)
    val llmProvider by option("--llm-provider", help = "openai | anthropic | ollama").choice(*PROVIDER_CHOICES, ignoreCase = true).default("openai")
    val llmModel by option("--llm-model", help = "Model id override (provider-specific; overrides --llm-model-preset)")
    val llmModelPreset by
        option(
            "--llm-model-preset",
            help = "When --llm-model is omitted: auto | gpt4o-mini | gpt4o | sonnet-4-5 | haiku-4-5 | llama3.2",
        ).choice(*PRESET_CHOICES, ignoreCase = true).default("auto")
    val ollamaBase by option("--ollama-base", help = "Ollama base URL (default: http://localhost:11434)")
    val explainMax by
        option("--explain-max", help = "Max findings to explain (1–$MAX_EXPLAIN_FINDINGS)").int().restrictTo(1..MAX_EXPLAIN_FINDINGS).default(50)
    val explainBatch by option("--explain-batch", help = "Findings per LLM request (1–100)").int().restrictTo(1..100).default(12)
    val explainMinSeverity by
        option("--explain-min-severity", help = "violation | warning | info").choice(*SEVERITY_CHOICES, ignoreCase = true).default("warning")
    val llmTimeoutSeconds by
        option("--llm-timeout", help = "Per-request LLM timeout in seconds (default 60)").int().restrictTo(1..3600).default(60)
    val llmRetries by option("--llm-retries", help = "Retries per failed LLM request (0–10, default 2)").int().restrictTo(0..10).default(2)
    val reasoner by
        option(
            "--reasoner",
            help = "none | rdfs | owl-micro | hermit — materialize inferences before SHACL (v0.4; hermit = OWL DL via HermiT)",
        ).choice(*REASONER_CHOICES, ignoreCase = true).default("none")
    val markdownAscii by
        option(
            "--markdown-ascii",
            help = "With --format markdown: use [VIOLATION]/[WARNING] markers instead of emoji.",
        ).flag(default = false)

    fun explainCli(): LlmExplainCli? =
        if (!explain) {
            null
        } else {
            LlmExplainCli(
                dryRun = explainDryRun,
                provider = parseLlmProvider(llmProvider),
                modelId = llmModel?.trim()?.takeIf { it.isNotEmpty() },
                modelPreset = parseExplanationModelPreset(llmModelPreset),
                ollamaBase = ollamaBase?.trim()?.takeIf { it.isNotEmpty() },
                maxFindings = explainMax,
                batchSize = explainBatch,
                minSeverity = explainMinViolationSeverity(explainMinSeverity),
                requestTimeout = Duration.ofSeconds(llmTimeoutSeconds.toLong()),
                retries = llmRetries,
            )
        }
}

private class PipelineCommand(private val environment: CliEnvironment) : CliktCommand(name = "pipeline") {
    private val ontologyArg by argument("ontology", help = "Path to the ontology").path(mustExist = true)
    private val inputFormatOpt by option("--input-format", help = INPUT_FORMAT_HELP)
    private val embedding by EmbeddingOptionGroup()
    private val reportOptions by ReportOptionGroup()
    private val keepIntermediate by
        option("--keep-intermediate", help = "Write the enriched graph to a temporary Turtle file and keep it")
            .flag(default = false)

    override fun run() {
        // Every option is validated by Clikt at parse time; resolve the remaining inputs before loading a model.
        val format = resolveInputFormat(ontologyArg, inputFormatOpt)
        val reasoningProfile = parseReasonerProfile(reportOptions.reasoner)
        val llm = reportOptions.explainCli()
        val ontology = parseOntology(ontologyArg, format)
        val checker = buildChecker(reportOptions.catalog, ShaclValidation.validator(), withMetricsProvider = false)

        val exitCode =
            environment.enricherFactory.open(embedding.toOptions()).use { enricher ->
                echo("Pipeline: enriching…", err = true)
                val enriched = enricher.enrich(ontology)

                if (keepIntermediate) {
                    val tmp = Files.createTempFile("onto-qa-", ".enriched.ttl")
                    Files.writeString(tmp, enriched.serialize(RdfFormat.TURTLE))
                    echo("Kept intermediate enriched graph at $tmp", err = true)
                }

                if (reportOptions.catalog.lowercase() in CATALOGS_USING_EMBEDDING_SHAPES) {
                    val hasClose = enriched.getTriplesSequence().any { it.predicate == EnrichmentVocabulary.semanticallyCloseTo }
                    if (!hasClose) {
                        logger.info(
                            "Embedding-quality catalog selected but no {} enrichment triples were materialized; semantic similarity shapes will not fire.",
                            EnrichmentVocabulary.semanticallyCloseTo,
                        )
                    }
                }

                if (reasoningProfile != OntoQualityReasoningProfile.NONE) {
                    logger.info("Onto-quality reasoning profile: {}", reasoningProfile.name.lowercase())
                }
                val report = checker.check(enriched, reasoningProfile)
                val outcome = maybeExplainReport(report, llm, environment) { echo(it, err = true) }
                emitReport(
                    report,
                    outcome.explained,
                    reportOptions.format,
                    reportOptions.output,
                    MarkdownReportOptions(useAsciiSeverityMarkers = reportOptions.markdownAscii),
                ) { echo(it) }
                echo(
                    "Pipeline complete (catalog=${reportOptions.catalog}). Use --catalog skos-vocabulary-embed for SKOS + embedding shapes after enrichment.",
                    err = true,
                )
                exitStatus(report, reportOptions, outcome)
            }
        // Resources are closed before the process status is reported.
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}

private class CheckCommand(private val environment: CliEnvironment) : CliktCommand(name = "check") {
    private val ontologyArg by argument("ontology", help = "Path to the ontology").path(mustExist = true)
    private val inputFormatOpt by option("--input-format", help = INPUT_FORMAT_HELP)
    private val reportOptions by ReportOptionGroup()
    private val withMetricsOpt by
        option(
            "--with-metrics",
            help = "Include OQuaRE metrics and prioritize findings (Markdown adds Top findings + Metrics summary).",
        ).flag(default = false)
    private val noMetricsOpt by
        option("--no-metrics", help = "Disable metrics integration even if --with-metrics was passed.")
            .flag(default = false)

    override fun run() {
        val format = resolveInputFormat(ontologyArg, inputFormatOpt)
        val reasoningProfile = parseReasonerProfile(reportOptions.reasoner)
        val llm = reportOptions.explainCli()
        val useMetrics = withMetricsOpt && !noMetricsOpt
        val checker = buildChecker(reportOptions.catalog, ShaclValidation.validator(), useMetrics)

        val graph = parseOntology(ontologyArg, format)

        if (reportOptions.catalog.lowercase() in CATALOGS_USING_EMBEDDING_SHAPES) {
            val hasClose =
                graph.getTriplesSequence().any { it.predicate == EnrichmentVocabulary.semanticallyCloseTo }
            if (!hasClose) {
                logger.info(
                    "Embedding-quality catalog selected but no {} triples found in the ontology; semantic similarity shapes will not produce findings.",
                    EnrichmentVocabulary.semanticallyCloseTo,
                )
            }
        }

        if (reasoningProfile != OntoQualityReasoningProfile.NONE) {
            logger.info("Onto-quality reasoning profile: {}", reasoningProfile.name.lowercase())
        }
        val report = checker.check(graph, reasoningProfile)
        val outcome = maybeExplainReport(report, llm, environment) { echo(it, err = true) }
        emitReport(
            report,
            outcome.explained,
            reportOptions.format,
            reportOptions.output,
            MarkdownReportOptions(useAsciiSeverityMarkers = reportOptions.markdownAscii),
        ) { echo(it) }
        val exitCode = exitStatus(report, reportOptions, outcome)
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}

/** [EXIT_FINDINGS] takes precedence over [EXIT_EXPLAIN_ERROR]. */
private fun exitStatus(report: QualityReport, options: ReportOptionGroup, outcome: ExplainOutcome): Int =
    when {
        shouldFail(report, severityThreshold(options.severity)) -> EXIT_FINDINGS
        options.failOnExplainError && outcome.failed -> EXIT_EXPLAIN_ERROR
        else -> 0
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

internal data class LlmExplainCli(
    val dryRun: Boolean,
    val provider: LlmProvider,
    val modelId: String?,
    val modelPreset: ExplanationModelPreset,
    val ollamaBase: String?,
    val maxFindings: Int,
    val batchSize: Int,
    val minSeverity: ViolationSeverity,
    val requestTimeout: Duration,
    val retries: Int,
)

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

/** @param failed true when explanations were requested and the call threw or left findings unexplained. */
internal data class ExplainOutcome(val explained: ExplainedQualityReport?, val failed: Boolean)

private fun maybeExplainReport(
    report: QualityReport,
    llm: LlmExplainCli?,
    environment: CliEnvironment,
    err: (String) -> Unit,
): ExplainOutcome {
    if (llm == null) return ExplainOutcome(null, failed = false)
    if (!environment.env(LLM_EXPLAIN_ENV).equals("true", ignoreCase = true)) {
        logger.warn(
            "{} is not set to true; skipping LLM explanations (Koog).",
            LLM_EXPLAIN_ENV,
        )
        return ExplainOutcome(null, failed = false)
    }
    if (llm.dryRun) {
        val n =
            report.findings
                .count { it.violation.severity.isAtLeast(llm.minSeverity) }
                .coerceAtMost(llm.maxFindings)
        val modelDesc =
            llm.modelId
                ?: "${llm.modelPreset.name.lowercase()} (preset)"
        err("LLM explain dry-run: would send up to $n findings (provider=${llm.provider}, model=$modelDesc)")
        return ExplainOutcome(null, failed = false)
    }
    return try {
        val cfg =
            LlmExplanationConfig(
                provider = llm.provider,
                modelId = llm.modelId,
                modelPreset = llm.modelPreset,
                baseUrl = llm.ollamaBase,
                requestTimeout = llm.requestTimeout,
                maxRetries = llm.retries,
            )
        val enricher = environment.explanationEnricherFactory(cfg)
        val opts =
            ExplanationOptions(
                maxFindings = llm.maxFindings,
                batchSize = llm.batchSize,
                minSeverity = llm.minSeverity,
            )
        val explained = runBlocking { enricher.enrich(report, opts) }
        if (explained.hasExplanationFailures) {
            val missing = explained.failures.sumOf { it.findingRefs.size }
            err("LLM explanations incomplete: $missing finding(s) not explained (${explained.failures.first().reason})")
        }
        ExplainOutcome(explained, failed = explained.hasExplanationFailures)
    } catch (e: Exception) {
        logger.warn("LLM explanations failed", e)
        err("LLM explanations failed: ${e::class.simpleName}: ${e.message}")
        ExplainOutcome(null, failed = true)
    }
}

internal fun loadOnnxEmbeddingModel(options: EmbeddingCliOptions): OnnxEmbeddingModel =
    try {
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

private fun writeOutput(text: String, output: Path?, stdout: (String) -> Unit) {
    if (output != null) {
        output.toFile().writeText(text)
    } else {
        stdout(text)
    }
}

private fun emitReport(
    report: QualityReport,
    explained: ExplainedQualityReport?,
    formatOpt: String,
    outputOpt: Path?,
    markdownOptions: MarkdownReportOptions = MarkdownReportOptions(),
    stdout: (String) -> Unit,
) {
    val text =
        when (formatOpt.lowercase()) {
            "text" -> explained?.describeText() ?: report.describeText()
            "markdown" -> explained?.describeMarkdown(markdownOptions) ?: report.describeMarkdown(markdownOptions)
            "json" -> findingsToJson(report, explained)
            "turtle" -> JenaBridge.toString(report.underlying.toShaclValidationReportRdf(), "TURTLE")
            else -> throw UsageError("Unknown --format $formatOpt (expected text|markdown|json|turtle)")
        }
    writeOutput(text, outputOpt, stdout)
}

private fun severityThreshold(severityOpt: String): SeverityThreshold =
    when (severityOpt.lowercase()) {
        "violation" -> SeverityThreshold.VIOLATION
        "warning" -> SeverityThreshold.WARNING
        "info" -> SeverityThreshold.INFO
        else -> throw UsageError("Unknown --severity $severityOpt (expected violation|warning|info)")
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

private val reportJson = Json { prettyPrint = true }

private fun pitfallLabel(p: PitfallReference?): String? =
    when (p) {
        null -> null
        PitfallReference.Convention -> "convention"
        is PitfallReference.Oops -> "OOPS:${p.number}"
        is PitfallReference.Skos -> "SKOS:${p.number}"
        is PitfallReference.OntoQuality -> "OntoQuality:${p.number}"
        is PitfallReference.KastorExtension -> "Kastor:${p.code}"
    }

/** JSON report built with kotlinx.serialization, so every string (including control characters) is escaped. */
internal fun findingsToJson(
    report: QualityReport,
    explained: ExplainedQualityReport?,
): String {
    val root =
        buildJsonObject {
            putJsonArray("findings") {
                for (f in report.findings) {
                    addJsonObject {
                        put("findingRef", FindingRef.from(f).hexSha256)
                        put("severity", f.violation.severity.name)
                        put("message", f.violation.message)
                        put("shapeUri", f.violation.shapeUri)
                        put("category", f.category.name)
                        put("pitfall", pitfallLabel(f.pitfall))
                        put("tier", f.tier.name)
                        put("focusNode", focusNodeToString(f.violation.focusNode))
                    }
                }
            }
            putJsonArray("llmExplanations") {
                for (e in explained?.explanations.orEmpty()) {
                    addJsonObject {
                        put("findingRef", e.findingRef.hexSha256)
                        put("summary", e.summary)
                        put("whyItMatters", e.whyItMatters)
                        putJsonArray("suggestedActions") { e.suggestedActions.forEach { add(it) } }
                        put("confidenceNote", e.confidenceNote)
                        put("modelId", e.modelId)
                        put("providerKind", e.providerKind)
                        put("promptRunId", e.promptRunId)
                    }
                }
            }
            putJsonArray("llmExplanationFailures") {
                for (failure in explained?.failures.orEmpty()) {
                    addJsonObject {
                        putJsonArray("findingRefs") { failure.findingRefs.forEach { add(it.hexSha256) } }
                        put("reason", failure.reason)
                    }
                }
            }
        }
    return reportJson.encodeToString(JsonObject.serializer(), root)
}

private fun focusNodeToString(term: RdfTerm): String =
    when (term) {
        is Iri -> term.value
        is BlankNode -> "_:${term.id}"
        is Literal -> term.lexical
        else -> term.toString()
    }
