package com.geoknoesis.kastor.ontoquality.cli

import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.ontoquality.MarkdownReportOptions
import com.geoknoesis.kastor.ontoquality.OutputSanitizer
import com.geoknoesis.kastor.ontoquality.SecretRedaction
import com.geoknoesis.kastor.ontoquality.explanation.QualityExplanationEnricher
import com.geoknoesis.kastor.ontoquality.llm.LlmExplanationConfig
import com.geoknoesis.kastor.ontoquality.llm.LlmProvider
import com.geoknoesis.kastor.ontoquality.llm.qualityExplanationEnricher
import com.geoknoesis.kastor.ontoquality.metrics.MetricsConfig
import com.geoknoesis.kastor.ontoquality.metrics.MetricsSection
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetrics
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetricsReport
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile
import com.geoknoesis.kastor.ontoquality.embed.EnrichmentVocabulary
import com.geoknoesis.kastor.ontoquality.embed.SimilaritySearchBudgetExceededException
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.serialize
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.clikt.parameters.types.restrictTo
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    installStandardStreams()
    val status = exitStatusAfterFlush(runOntoQa(args.toList()), System.out, System.err)
    exitProcess(status)
}

internal val logger = LoggerFactory.getLogger("onto-qa")

/** Injectable collaborators so commands can be exercised in tests without models, LLMs or process exits. */
internal class CliEnvironment(
    val enricherFactory: PipelineEnricherFactory = OnnxPipelineEnricherFactory,
    val explanationEnricherFactory: (LlmExplanationConfig) -> QualityExplanationEnricher = ::qualityExplanationEnricher,
    val env: (String) -> String? = System::getenv,
    /** File-system questions of the `--output` pre-check. */
    val outputFileSystem: OutputFileSystem = OutputFileSystem(),
    /** Whether a `--reasoner` profile can run here (HermiT needs its OWL API classes on the classpath). */
    val reasonerAvailable: (OntoQualityReasoningProfile) -> Boolean = ::reasonerAvailableOnClasspath,
)

internal fun ontoQualityApp(environment: CliEnvironment = CliEnvironment()): CliktCommand =
    OntoQualityApp().subcommands(
        CheckCommand(environment),
        EnrichCommand(environment),
        PipelineCommand(environment),
        MetricsCommand(environment),
    )

/**
 * Runs `onto-qa` and returns the process exit status (see [EXIT_STATUS_HELP]) instead of exiting.
 *
 * Every failure path is mapped explicitly: Clikt usage errors (unknown option, bad value, missing argument, …) →
 * [EXIT_USAGE]; errors raised by commands carry their own status; anything that escapes → [EXIT_RUNTIME_ERROR] with a
 * one-line message. Stack traces are printed only with `onto-qa --debug`.
 */
internal fun runOntoQa(
    argv: List<String>,
    environment: CliEnvironment = CliEnvironment(),
    err: (String) -> Unit = { System.err.println(it) },
): Int {
    val debug = argv.takeWhile { it != "--" }.contains("--debug")
    val app = ontoQualityApp(environment)
    return try {
        app.parse(argv)
        EXIT_OK
    } catch (e: CliktError) {
        val secrets = secretsOf(environment, llmProviderOf(argv), baseUrl = ollamaBaseOf(argv))
        app.getFormattedHelp(e)?.let { if (e.printError) err(redact(it, secrets)) else println(it) }
        if (debug) e.cause?.let { printStackTrace(it, secrets, err) }
        exitStatusFor(e)
    } catch (e: Throwable) {
        val secrets = secretsOf(environment, llmProviderOf(argv), baseUrl = ollamaBaseOf(argv))
        err(safeLine("onto-qa: internal error: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}", secrets))
        if (debug) printStackTrace(e, secrets, err)
        EXIT_RUNTIME_ERROR
    }
}

/** The value of `--ollama-base` in [argv], for redaction where the parsed options are not at hand. */
private fun ollamaBaseOf(argv: List<String>): String? {
    val index = argv.indexOf("--ollama-base")
    return if (index >= 0) argv.getOrNull(index + 1) else argv.firstOrNull { it.startsWith("--ollama-base=") }?.substringAfter('=')
}

/** The value of `--llm-provider` in [argv] (default `openai`), for redaction where the parsed options are not at hand. */
private fun llmProviderOf(argv: List<String>): LlmProvider? {
    val index = argv.indexOf("--llm-provider")
    val value = if (index >= 0) argv.getOrNull(index + 1) else argv.firstOrNull { it.startsWith("--llm-provider=") }?.substringAfter('=')
    return when (value?.lowercase()) {
        null, "openai" -> LlmProvider.OPENAI
        "anthropic" -> LlmProvider.ANTHROPIC
        "ollama" -> LlmProvider.OLLAMA
        else -> null
    }
}

/**
 * Secrets of this run: the API key of the **active** [provider] (a key of another provider is not in use and is not
 * redacted) and the credentials of the `--ollama-base` URL ([baseUrl]). They are replaced by `***` in everything the
 * CLI prints or writes ([SecretRedaction]): a secret of 8 characters or more wherever it occurs, a shorter one only
 * where it stands as a credential (a header, a `key=` value), so a key such as `1` cannot turn "HTTP 401" into
 * "HTTP 40***". Longest first, so a secret that contains another is replaced as a whole.
 */
internal fun secretsOf(environment: CliEnvironment, provider: LlmProvider?, extra: String? = null, baseUrl: String? = null): List<String> =
    (
        listOfNotNull(extra, provider?.apiKeyVariable?.let { environment.env(it) })
            // Also trimmed: that is the key sent (a CRLF .env leaves a carriage return the echoed key no longer has).
            .flatMap { listOf(it, it.trim()) } + SecretRedaction.urlCredentials(baseUrl)
    )
        .filter { it.isNotBlank() }
        .distinct()
        .sortedByDescending { it.length }

/** [text] without [secrets] and without the credentials of any URL (the part between `://` and `@`). */
internal fun redact(text: String, secrets: List<String>): String = SecretRedaction.redact(text, secrets)

/** Most lines of one `--debug` stack trace; the rest is counted. */
internal const val MAX_DEBUG_TRACE_LINES = 120

/** Longest line the CLI prints for a failure (message or stack trace line), in characters. */
internal const val MAX_DEBUG_LINE_CHARS = 2_000

private const val CUT = " ...[truncated]"

/** One line for stderr: [secrets] redacted, control and bidi characters made visible, at most [MAX_DEBUG_LINE_CHARS] characters. */
internal fun safeLine(text: String, secrets: List<String>): String {
    // Bound the work on huge provider bodies; the margin keeps a secret at the cut whole, so it is still redacted.
    val bounded = if (text.length > MAX_DEBUG_LINE_CHARS * 4) text.take(MAX_DEBUG_LINE_CHARS * 4) else text
    val line = sanitize(redact(bounded, secrets))
    return if (line.length <= MAX_DEBUG_LINE_CHARS) line else line.take(MAX_DEBUG_LINE_CHARS - CUT.length) + CUT
}

/**
 * `--debug` stack trace of [failure], line by line. Exception messages carry untrusted text (ontology content in parse
 * errors, provider error bodies), so every line is sanitised like any other message, [secrets] and URL credentials
 * are redacted, a line is cut to [MAX_DEBUG_LINE_CHARS] characters and a trace to [MAX_DEBUG_TRACE_LINES] lines.
 */
internal fun printStackTrace(failure: Throwable, secrets: List<String>, err: (String) -> Unit) {
    val trace =
        try {
            failure.stackTraceToString()
        } catch (_: Throwable) {
            "${failure.javaClass.name} (stack trace unavailable)"
        }
    val lines = trace.trimEnd().lineSequence()
    var printed = 0
    var hidden = 0
    for (line in lines) {
        if (printed < MAX_DEBUG_TRACE_LINES) {
            err(safeLine(line, secrets))
            printed++
        } else {
            hidden++
        }
    }
    if (hidden > 0) err("... ($hidden more stack trace line(s) not shown)")
}

/** Clikt raises usage problems as [UsageError] (or help-on-error) with status 1; they map to [EXIT_USAGE]. */
internal fun exitStatusFor(e: CliktError): Int =
    when {
        e is ProgramResult -> e.statusCode
        e is UsageError -> EXIT_USAGE
        e.statusCode == 1 -> EXIT_USAGE
        else -> e.statusCode
    }

internal fun describeFailure(e: Throwable): String = "${e.javaClass.simpleName}: ${sanitize(e.message ?: "(no message)")}"

/** Untrusted text (exception messages, paths, ontology or LLM text) made safe for stderr: controls and bidi shown as \uXXXX. */
internal fun sanitize(text: String?): String = OutputSanitizer.terminal(text ?: "")

private class OntoQualityApp : CliktCommand(name = "onto-qa") {
    val debug by option("--debug", help = "Print stack traces for runtime errors and LLM explanation failures").flag(default = false)

    override fun help(context: Context): String = "Ontology quality checks, OQuaRE metrics and semantic enrichment."

    override fun helpEpilog(context: Context): String = EXIT_STATUS_HELP

    override fun run() {
        if (debug) logger.debug("onto-qa debug output enabled")
    }
}

/**
 * Base for subcommands: [execute] runs under a guard that turns unexpected exceptions into [EXIT_RUNTIME_ERROR]
 * with a concise message (the cause is kept for `--debug`), so no failure falls back to the JVM's status 1.
 */
private abstract class OntoQaCommand(name: String, private val outputFileSystem: OutputFileSystem) : CliktCommand(name = name) {
    /** Also accepted after the command name; [runOntoQa] reads it from argv in either position. */
    private val debugOpt by option("--debug", help = "Print stack traces for runtime errors and LLM explanation failures").flag(default = false)

    /** `--debug` given before or after the command name. */
    protected val debugEnabled: Boolean
        get() = debugOpt || (currentContext.parent?.command as? OntoQualityApp)?.debug == true

    /** `--base-iri`; resolve with [validateBaseIri] before loading anything. */
    protected val baseIriOpt by option("--base-iri", help = BASE_IRI_HELP)

    private val overwriteInputOpt by option("--overwrite-input", help = OVERWRITE_INPUT_HELP).flag(default = false)

    /**
     * Checks [output] before any work (parsing, model loading, paid LLM calls): a usage error when it is the [input]
     * file and `--overwrite-input` was not given, a runtime (I/O) error when it cannot be written ([outputProblem]).
     */
    protected fun requireUsableOutput(input: Path, output: Path?) {
        if (output == null) return
        if (!overwriteInputOpt && isSameFile(input, output)) {
            throw usageError("--output is the input file (${sanitize(input.toString())}); choose another path or pass --overwrite-input")
        }
        outputProblem(output, outputFileSystem)?.let { throw CliktError("Cannot write --output ${sanitize(output.toString())}: ${sanitize(it)}", null, EXIT_RUNTIME_ERROR) }
    }

    override fun helpEpilog(context: Context): String = EXIT_STATUS_HELP

    abstract fun execute()

    final override fun run() {
        if (debugOpt) logger.debug("onto-qa debug output enabled")
        try {
            execute()
        } catch (e: CliktError) {
            throw e
        } catch (e: ShaclValidationException) {
            throw CliktError("SHACL validation failed: ${sanitize(e.message)}. $VALIDATION_FAILURE_HINT", e, EXIT_RUNTIME_ERROR)
        } catch (e: SimilaritySearchBudgetExceededException) {
            throw CliktError("Semantic enrichment failed: ${sanitize(e.message)}. ${similarityBudgetHint(e)}", e, EXIT_RUNTIME_ERROR)
        } catch (e: Exception) {
            throw CliktError("onto-qa $commandName failed: ${describeFailure(e)}", e, EXIT_RUNTIME_ERROR)
        }
    }
}

internal fun usageError(message: String): UsageError = UsageError(message, statusCode = EXIT_USAGE)

private class MetricsCommand(environment: CliEnvironment) : OntoQaCommand(name = "metrics", environment.outputFileSystem) {
    private val ontologyArg by argument("ontology", help = "Path to the ontology file").path(mustExist = true, canBeDir = false)
    private val inputFormatOpt by option("--input-format", help = INPUT_FORMAT_HELP)
    private val formatOpt by option("--format", help = "text | markdown | json | turtle").choice(*FORMAT_CHOICES, ignoreCase = true).default("text")
    private val outputOpt by option("--output", help = OUTPUT_HELP).path()
    private val includeOpt by
        option("--include", help = "owl | skos | graph | all (text and markdown only; json and turtle always include all)")
            .choice("owl", "skos", "graph", "all", ignoreCase = true)
            .default("all")
    private val topNOpt by option("--top-n").int().restrictTo(min = 0).default(20)
    private val maxDepthOpt by option("--max-depth").int().restrictTo(min = 1).default(50)
    private val noScoresOpt by option("--no-scores", help = "Disable OQuaRE 1–5 scores (raw metrics only)").flag(default = false)

    override fun execute() {
        val format = formatOpt.lowercase()
        val include = includeOpt.lowercase()
        if (format in setOf("json", "turtle") && include != "all") {
            throw usageError("--include $includeOpt is only supported with --format text or markdown ($format output always contains every section)")
        }
        requireUsableOutput(ontologyArg, outputOpt)
        val graph = parseOntology(ontologyArg, resolveInputFormat(ontologyArg, inputFormatOpt), validateBaseIri(baseIriOpt))
        val cfg =
            MetricsConfig(
                emitOQuaREScores = !noScoresOpt,
                topNHotSpots = topNOpt,
                maxDepthCap = maxDepthOpt,
            )
        val report = VocabularyMetrics.compute(graph, cfg)
        val text = emitMetricsCliReport(report, format, include)
        writeOutput(text, outputOpt) { echo(it) }
    }
}

private fun emitMetricsCliReport(
    report: VocabularyMetricsReport,
    format: String,
    include: String,
): String =
    when (format) {
        "json" -> report.toJson()
        "turtle" -> report.toTurtle()
        "text" -> report.describeText(metricsSectionsOf(include))
        "markdown" -> report.describeMarkdown(metricsSectionsOf(include))
        else -> throw usageError("Unknown --format $format (expected text|markdown|json|turtle)")
    }

/** The sections `--include` selects, sliced from the structured report (not from its rendered text). */
private fun metricsSectionsOf(include: String): Set<MetricsSection> =
    when (include) {
        "all" -> MetricsSection.ALL
        "graph" -> setOf(MetricsSection.GRAPH)
        "owl" -> setOf(MetricsSection.OWL)
        "skos" -> setOf(MetricsSection.SKOS)
        else -> throw usageError("Unknown --include $include (expected owl|skos|graph|all)")
    }

private class EnrichCommand(private val environment: CliEnvironment) : OntoQaCommand(name = "enrich", environment.outputFileSystem) {
    private val ontologyArg by argument("ontology", help = "Path to the ontology file").path(mustExist = true, canBeDir = false)
    private val inputFormatOpt by option("--input-format", help = INPUT_FORMAT_HELP)
    private val embedding by EmbeddingOptionGroup()
    private val outputOpt by
        option(
            "--output",
            help = "Output Turtle path (default: <ontology name>.enriched.ttl next to the input; parent directories are created; " +
                "an existing file is replaced atomically)",
        ).path()

    override fun execute() {
        val options = embedding.toOptions()
        val out =
            outputOpt ?: run {
                val name = ontologyArg.fileName.toString().substringBeforeLast('.')
                ontologyArg.parent?.resolve("$name.enriched.ttl")
                    ?: Path.of("$name.enriched.ttl")
            }
        requireUsableOutput(ontologyArg, out)
        val graph = parseOntology(ontologyArg, resolveInputFormat(ontologyArg, inputFormatOpt), validateBaseIri(baseIriOpt))
        openEnricher(environment, options).use { enricher ->
            echo("Embedding and building similarity index (threshold=${embedding.threshold}, mode=${options.similarityMode.label})…", err = true)
            val enriched = enricher.enrich(graph)
            writeFileAtomically(out, enriched.serialize(RdfFormat.TURTLE))
            echo("Wrote enriched ontology to ${sanitize(out.toString())}", err = true)
        }
    }
}

/** `--ollama-base` as an `http(s)` URL with a host; the value is not echoed (it may carry credentials). */
internal fun validatedOllamaBase(value: String?): String? {
    if (value == null) return null
    val base = value.trim()
    val uri = try { java.net.URI(base) } catch (_: java.net.URISyntaxException) { null }
    val host = uri?.host?.takeIf { it.isNotEmpty() } ?: uri?.rawAuthority?.let(::registryBasedHost)
    if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || host.isNullOrEmpty()) {
        throw usageError("--ollama-base must be an http or https URL with a host, for example http://localhost:11434")
    }
    return base
}

/** The host name of an authority that [java.net.URI] does not parse as a server (`user@ollama_box:11434`), or null. */
private fun registryBasedHost(authority: String): String? {
    val hostAndPort = authority.substringAfterLast('@')
    val port = hostAndPort.substringAfterLast(':', "")
    val host = if (port.isNotEmpty() && port.all { it in '0'..'9' }) hostAndPort.substringBeforeLast(':') else hostAndPort
    return host.takeIf { REGISTRY_HOST.matches(it) }
}

private val REGISTRY_HOST = Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*\\.?")

private class PipelineCommand(private val environment: CliEnvironment) : OntoQaCommand(name = "pipeline", environment.outputFileSystem) {
    private val ontologyArg by argument("ontology", help = "Path to the ontology file").path(mustExist = true, canBeDir = false)
    private val inputFormatOpt by option("--input-format", help = INPUT_FORMAT_HELP)
    private val embedding by EmbeddingOptionGroup()
    private val reportOptions by ReportOptionGroup()
    private val keepIntermediate by
        option("--keep-intermediate", help = "Write the enriched graph to a temporary Turtle file and keep it")
            .flag(default = false)

    override fun execute() {
        // Resolve and validate every input before loading a model.
        val format = resolveInputFormat(ontologyArg, inputFormatOpt)
        val reasoningProfile = parseReasonerProfile(reportOptions.reasoner)
        requireReasonerAvailable(environment, reasoningProfile, reportOptions.reasoner)
        val baseIri = validateBaseIri(baseIriOpt)
        requireUsableOutput(ontologyArg, reportOptions.output)
        val embeddingOptions = embedding.toOptions()
        // LLM prerequisites (opt-in, API key) are checked before the ontology is parsed or a model is loaded.
        val llm = preflightLlm(reportOptions.explainCli(), environment) { echo(it, err = true) }
        // Held only until it is enriched: the enriched graph is a copy, and the check runs on it alone.
        var ontology: RdfGraph? = parseOntology(ontologyArg, format, baseIri)
        val checker = buildChecker(reportOptions.catalog, ShaclValidation.validator(reportOptions.validationConfig()), withMetricsProvider = false)

        val exitCode =
            openEnricher(environment, embeddingOptions).use { enricher ->
                echo("Pipeline: enriching…", err = true)
                val enriched = enricher.enrich(checkNotNull(ontology))
                ontology = null

                if (keepIntermediate) {
                    val tmp = Files.createTempFile("onto-qa-", ".enriched.ttl")
                    Files.writeString(tmp, enriched.serialize(RdfFormat.TURTLE))
                    echo("Kept intermediate enriched graph at $tmp", err = true)
                }

                if (reportOptions.catalog.lowercase() in CATALOGS_USING_EMBEDDING_SHAPES) {
                    val hasClose = enriched.getTriplesSequence().any { it.predicate == EnrichmentVocabulary.semanticallyCloseTo }
                    if (!hasClose) {
                        echo(
                            "onto-qa: warning: --catalog ${reportOptions.catalog.lowercase()} includes embedding-quality shapes, but enrichment " +
                                "produced no oqsh:semanticallyCloseTo triples (no labelled pair reached --threshold ${embedding.threshold}); " +
                                "those shapes produce no findings.",
                            err = true,
                        )
                    }
                }

                val report = runCheck(checker, enriched, reasoningProfile, reportOptions)
                warnIfTruncated(report, reportOptions.maxFindings) { echo(it, err = true) }
                val outcome = maybeExplainReport(report, llm, environment, debugEnabled) { echo(it, err = true) }
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
        if (exitCode != EXIT_OK) throw ProgramResult(exitCode)
    }
}

private class CheckCommand(private val environment: CliEnvironment) : OntoQaCommand(name = "check", environment.outputFileSystem) {
    private val ontologyArg by argument("ontology", help = "Path to the ontology file").path(mustExist = true, canBeDir = false)
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

    override fun execute() {
        val format = resolveInputFormat(ontologyArg, inputFormatOpt)
        val reasoningProfile = parseReasonerProfile(reportOptions.reasoner)
        requireReasonerAvailable(environment, reasoningProfile, reportOptions.reasoner)
        val baseIri = validateBaseIri(baseIriOpt)
        requireUsableOutput(ontologyArg, reportOptions.output)
        val llm = preflightLlm(reportOptions.explainCli(), environment) { echo(it, err = true) }
        val useMetrics = withMetricsOpt && !noMetricsOpt
        val checker = buildChecker(reportOptions.catalog, ShaclValidation.validator(reportOptions.validationConfig()), useMetrics)

        val graph = parseOntology(ontologyArg, format, baseIri)

        if (reportOptions.catalog.lowercase() in CATALOGS_USING_EMBEDDING_SHAPES) {
            val hasClose =
                graph.getTriplesSequence().any { it.predicate == EnrichmentVocabulary.semanticallyCloseTo }
            if (!hasClose) {
                echo(
                    "onto-qa: warning: --catalog ${reportOptions.catalog.lowercase()} includes embedding-quality shapes, but the ontology " +
                        "has no oqsh:semanticallyCloseTo triples; run onto-qa enrich or onto-qa pipeline first, or those shapes " +
                        "produce no findings.",
                    err = true,
                )
            }
        }

        val report = runCheck(checker, graph, reasoningProfile, reportOptions)
        warnIfTruncated(report, reportOptions.maxFindings) { echo(it, err = true) }
        val outcome = maybeExplainReport(report, llm, environment, debugEnabled) { echo(it, err = true) }
        emitReport(
            report,
            outcome.explained,
            reportOptions.format,
            reportOptions.output,
            MarkdownReportOptions(useAsciiSeverityMarkers = reportOptions.markdownAscii),
        ) { echo(it) }
        val exitCode = exitStatus(report, reportOptions, outcome)
        if (exitCode != EXIT_OK) throw ProgramResult(exitCode)
    }
}

/**
 * [EXIT_FINDINGS] takes precedence over [EXIT_EXPLAIN_ERROR]. A [QualityReport.truncated] report is [EXIT_FINDINGS]
 * whatever the findings it lists: the findings it dropped may reach `--severity`.
 */
private fun exitStatus(report: QualityReport, options: ReportOptionGroup, outcome: ExplainOutcome): Int =
    when {
        shouldFail(report, severityThreshold(options.severity)) -> EXIT_FINDINGS
        report.truncated -> EXIT_FINDINGS
        options.failOnExplainError && outcome.failed -> EXIT_EXPLAIN_ERROR
        else -> EXIT_OK
    }
