package com.geoknoesis.kastor.ontoquality.cli

import com.geoknoesis.kastor.ontoquality.PitfallReference
import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.ontoquality.MarkdownReportOptions
import com.geoknoesis.kastor.ontoquality.OutputSanitizer
import com.geoknoesis.kastor.ontoquality.SecretRedaction
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.ontoquality.explanation.ExplainedQualityReport
import com.geoknoesis.kastor.ontoquality.explanation.ExplanationFailure
import com.geoknoesis.kastor.ontoquality.explanation.ExplanationOptions
import com.geoknoesis.kastor.ontoquality.explanation.FindingRef
import com.geoknoesis.kastor.ontoquality.explanation.QualityExplanationEnricher
import com.geoknoesis.kastor.ontoquality.explanation.isAtLeast
import com.geoknoesis.kastor.ontoquality.llm.ExplanationModelPreset
import com.geoknoesis.kastor.ontoquality.llm.LlmExplanationConfig
import com.geoknoesis.kastor.ontoquality.llm.LlmProvider
import com.geoknoesis.kastor.ontoquality.llm.qualityExplanationEnricher
import com.geoknoesis.kastor.ontoquality.metrics.MetricsConfig
import com.geoknoesis.kastor.ontoquality.metrics.MetricsSection
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetrics
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetricsReport
import com.geoknoesis.kastor.ontoquality.metrics.integration.KastorMetricsProvider
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoning
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile
import com.geoknoesis.kastor.ontoquality.embed.EnrichmentVocabulary
import com.geoknoesis.kastor.ontoquality.embed.OnnxEmbeddingModel
import com.geoknoesis.kastor.ontoquality.embed.SemanticEnricher
import com.geoknoesis.kastor.ontoquality.embed.SimilarityLimitsPolicy
import com.geoknoesis.kastor.ontoquality.embed.SimilaritySearchBudgetExceededException
import com.geoknoesis.kastor.ontoquality.embed.SimilaritySearchMode
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfProvider
import com.geoknoesis.kastor.rdf.RdfProviderRegistry
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
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
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
import com.github.ajalt.clikt.parameters.types.long
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
import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    installStandardStreams()
    val status = exitStatusAfterFlush(runOntoQa(args.toList()), System.out, System.err)
    exitProcess(status)
}

/**
 * [status], unless standard output could not be written: a [PrintStream] swallows [java.io.IOException]s (a closed
 * pipe, a full disk) and only [PrintStream.checkError] tells, so a report that never arrived must not exit 0 (or 1).
 */
internal fun exitStatusAfterFlush(status: Int, out: PrintStream, err: PrintStream): Int {
    val outputLost = out.checkError() // flushes first
    err.flush()
    if (!outputLost) return status
    try {
        err.println("onto-qa: error: could not write to standard output")
        err.flush()
    } catch (_: Throwable) {
        // Nothing more can be reported; the status still tells.
    }
    return EXIT_RUNTIME_ERROR
}

/**
 * Output redirected to a file or a pipe is UTF-8 whatever the platform charset: a Windows code page would turn
 * unmappable characters into `?` (`onto-qa check --format json > report.json`). An interactive console is written in
 * its own charset, or it would show UTF-8 bytes as garbage unless the user had switched it to UTF-8 (`chcp 65001`).
 *
 * The two streams are decided independently ([standardStreamCharsets]): `onto-qa check > report.json` writes the
 * report in UTF-8 and its messages, still on the console, in the console charset.
 */
internal fun installStandardStreams() {
    val charsets = standardStreamCharsets(StandardStreamFacts.ofThisJvm())
    System.setOut(PrintStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out)), true, charsets.out))
    System.setErr(PrintStream(BufferedOutputStream(FileOutputStream(FileDescriptor.err)), true, charsets.err))
}

/** The charsets of stdout and stderr. */
internal data class StandardStreamCharsets(val out: Charset, val err: Charset)

/**
 * What the JVM knows about its standard streams.
 *
 * @param stdoutTerminalEncoding `sun.stdout.encoding`: set by the JVM only when stdout is a terminal (its encoding).
 * @param stderrTerminalEncoding `sun.stderr.encoding`: the same for stderr.
 * @param consoleCharset the charset of the interactive console ([interactiveConsoleCharset]), null without one.
 */
internal class StandardStreamFacts(
    val stdoutTerminalEncoding: String?,
    val stderrTerminalEncoding: String?,
    val consoleCharset: Charset?,
) {
    companion object {
        fun ofThisJvm(): StandardStreamFacts =
            StandardStreamFacts(
                stdoutTerminalEncoding = property("sun.stdout.encoding"),
                stderrTerminalEncoding = property("sun.stderr.encoding"),
                consoleCharset = interactiveConsoleCharset(),
            )

        private fun property(name: String): String? =
            try {
                System.getProperty(name)?.takeIf { it.isNotBlank() }
            } catch (_: SecurityException) {
                null
            }
    }
}

/**
 * The charset of each standard stream, decided per stream: the console charset when **that** stream is a terminal,
 * UTF-8 when it is redirected.
 *
 * The JVM tells which stream is a terminal through `sun.stdout.encoding` / `sun.stderr.encoding` (it sets each one
 * only when the stream is attached to a terminal). When neither is set but there is an interactive console (a JVM
 * that does not provide the properties), the console only tells that stdin and stdout are terminals; stderr is then
 * written like stdout.
 */
internal fun standardStreamCharsets(facts: StandardStreamFacts): StandardStreamCharsets {
    val perStream = facts.stdoutTerminalEncoding != null || facts.stderrTerminalEncoding != null

    fun charset(terminalEncoding: String?): Charset =
        when {
            terminalEncoding != null -> charsetOrNull(terminalEncoding) ?: facts.consoleCharset ?: Charsets.UTF_8
            perStream -> Charsets.UTF_8
            else -> standardStreamCharset(facts.consoleCharset)
        }
    return StandardStreamCharsets(out = charset(facts.stdoutTerminalEncoding), err = charset(facts.stderrTerminalEncoding))
}

private fun charsetOrNull(name: String): Charset? =
    try {
        Charset.forName(name)
    } catch (_: IllegalArgumentException) {
        null
    }

/** The charset of a stream when only the console is known: the charset of the console when there is one, else UTF-8. */
internal fun standardStreamCharset(consoleCharset: Charset?): Charset = consoleCharset ?: Charsets.UTF_8

/**
 * The charset of the interactive console this JVM is attached to, or null when there is none (or the charset cannot
 * be determined). `System.console()` is null when the standard streams are redirected; on JDK 22 and later it can be
 * non-null even then, and `Console.isTerminal()` (called reflectively, it does not exist before) tells.
 */
internal fun interactiveConsoleCharset(): Charset? {
    val console = System.console() ?: return null
    val terminal =
        try {
            java.io.Console::class.java.getMethod("isTerminal").invoke(console) as? Boolean ?: true
        } catch (_: ReflectiveOperationException) {
            true
        }
    if (!terminal) return null
    return try {
        console.charset()
    } catch (_: RuntimeException) {
        null
    }
}

private val logger = LoggerFactory.getLogger("onto-qa")

/** Exit status: success, no findings at or above `--severity`. */
internal const val EXIT_OK = 0

/** Exit status when findings reach `--severity`. */
internal const val EXIT_FINDINGS = 1

/** Exit status when the input ontology cannot be parsed in the selected RDF syntax (reading it failing is [EXIT_RUNTIME_ERROR]). */
internal const val EXIT_INPUT_ERROR = 2

/** Exit status when `--fail-on-explain-error` is set and LLM explanations failed (fully or partially). */
internal const val EXIT_EXPLAIN_ERROR = 3

/** Exit status for invalid command-line usage or inconsistent configuration (nothing was run). */
internal const val EXIT_USAGE = 4

/** Exit status for runtime failures: network / model download, model loading, similarity or LLM budgets, I/O, internal errors. */
internal const val EXIT_RUNTIME_ERROR = 5

private const val EXIT_STATUS_HELP =
    "Exit status: 0 success; 1 findings at or above --severity; 2 the ontology could not be parsed; " +
        "3 LLM explanations failed (with --fail-on-explain-error); 4 usage or configuration error; " +
        "5 runtime error (model download or loading, similarity or LLM budget, I/O, internal). " +
        "Use --debug (before or after the command) for stack traces.\n\n" +
        "kastor-rdf uses a different convention: 0 success; 1 usage or input error; 2 diff found the inputs not isomorphic; " +
        "3 runtime error.\n\n" +
        "Output redirected to a file or a pipe is UTF-8, whatever the platform charset; an interactive console is written " +
        "in its own charset (stdout and stderr are decided separately). --output files are written atomically (temporary file, then move); parent directories are " +
        "created and an existing file is replaced. An --output that cannot be written is refused before anything is run."

private const val OUTPUT_HELP =
    "Write output to this file instead of stdout (parent directories are created; an existing file is replaced atomically)"

private const val OVERWRITE_INPUT_HELP = "Allow --output to be the input ontology file (refused by default)"

private const val SIMILARITY_BUDGET_HINT =
    "Re-run with a larger --similarity-max-work (distance evaluations) or --similarity-timeout (seconds), " +
        "or use --similarity-mode approximate for large vocabularies."

/** Advice for an exhausted similarity budget, targeted at the limit that was hit. */
internal fun similarityBudgetHint(e: SimilaritySearchBudgetExceededException): String =
    when (e.limit) {
        SimilaritySearchBudgetExceededException.Limit.RESULT_PAIRS ->
            "Too many similar pairs: re-run with a higher --threshold or a larger --similarity-max-pairs."
        SimilaritySearchBudgetExceededException.Limit.DISTANCE_EVALUATIONS ->
            "Re-run with a larger --similarity-max-work (distance evaluations), or use --similarity-mode approximate for large vocabularies."
        SimilaritySearchBudgetExceededException.Limit.DEADLINE ->
            "Re-run with a larger --similarity-timeout (seconds), or use --similarity-mode approximate for large vocabularies."
        null -> SIMILARITY_BUDGET_HINT
    }

/**
 * Default base IRI prefix for relative references in the input. It is independent of the directory the file is in, so
 * finding IRIs and `findingRef`s are identical on every machine and no absolute path (user names included) reaches
 * reports or LLM prompts. The `/` makes relative paths resolve inside the prefix (`<Sub>` → `urn:onto-qa:input/Sub`).
 */
internal const val DEFAULT_BASE_IRI_PREFIX = "urn:onto-qa:input/"

private const val BASE_IRI_HELP =
    "Base IRI for relative references such as <#Foo> (default: $DEFAULT_BASE_IRI_PREFIX<file name>, independent of the directory)"

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
private val REASONER_CHOICES = arrayOf("none", "off", "rdfs", "owl-rl", "owl_rl", "owl-micro", "owl_micro", "hermit")
private val PROVIDER_CHOICES = arrayOf("openai", "anthropic", "ollama")
private val PRESET_CHOICES =
    arrayOf("auto", "gpt4o-mini", "gpt-4o-mini", "gpt4o", "gpt-4o", "sonnet", "sonnet-4-5", "claude-sonnet-4-5", "haiku", "haiku-4-5", "llama3.2", "llama-3.2")
private const val INPUT_FORMAT_HELP =
    "turtle | rdfxml | ntriples | jsonld (default: from file extension — .ttl, .owl/.rdf/.xml, .nt, .jsonld/.json; other extensions are read as Turtle, except .trig/.nq/.n3 and compressed files, which are refused)"

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
            val enricher = SemanticEnricher(model, options.threshold, options.similarityLimitsPolicy(), options.similarityMode)
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
    /** File-system questions of the `--output` pre-check. */
    val outputFileSystem: OutputFileSystem = OutputFileSystem(),
    /** Whether a `--reasoner` profile can run here (HermiT needs its OWL API classes on the classpath). */
    val reasonerAvailable: (OntoQualityReasoningProfile) -> Boolean = ::reasonerAvailableOnClasspath,
)

/** [OntoQualityReasoning.supports], treating missing reasoner classes (a [LinkageError]) as unavailable. */
internal fun reasonerAvailableOnClasspath(profile: OntoQualityReasoningProfile): Boolean =
    try {
        OntoQualityReasoning.supports(profile)
    } catch (_: LinkageError) {
        false
    } catch (_: Exception) {
        false
    }

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
    (listOfNotNull(extra, provider?.apiKeyVariable?.let { environment.env(it) }) + SecretRedaction.urlCredentials(baseUrl))
        .filter { it.isNotBlank() }
        .distinct()
        .sortedByDescending { it.length }

/** [text] without [secrets] and without the credentials of any URL (the part between `://` and `@`). */
private fun redact(text: String, secrets: List<String>): String = SecretRedaction.redact(text, secrets)

/** Most lines of one `--debug` stack trace; the rest is counted. */
internal const val MAX_DEBUG_TRACE_LINES = 120

/** Longest line the CLI prints for a failure (message or stack trace line), in characters. */
internal const val MAX_DEBUG_LINE_CHARS = 2_000

private const val CUT = " ...[truncated]"

/** One line for stderr: [secrets] redacted, control and bidi characters made visible, at most [MAX_DEBUG_LINE_CHARS] characters. */
private fun safeLine(text: String, secrets: List<String>): String {
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
private fun printStackTrace(failure: Throwable, secrets: List<String>, err: (String) -> Unit) {
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

private fun describeFailure(e: Throwable): String = "${e.javaClass.simpleName}: ${sanitize(e.message ?: "(no message)")}"

/** Untrusted text (exception messages, paths, ontology or LLM text) made safe for stderr: controls and bidi shown as \uXXXX. */
private fun sanitize(text: String?): String = OutputSanitizer.terminal(text ?: "")

/** `urn:onto-qa:input/` plus the percent-encoded file name of [path]. */
internal fun defaultBaseIri(path: Path): String =
    buildString {
        append(DEFAULT_BASE_IRI_PREFIX)
        for (byte in path.fileName?.toString().orEmpty().toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xff
            val ch = code.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                append(ch)
            } else {
                append('%').append("%02X".format(code))
            }
        }
    }

/** Returns [value] when it is an absolute IRI; a usage error otherwise. */
internal fun validateBaseIri(value: String?): String? {
    if (value == null) return null
    val absolute =
        try {
            URI(value).isAbsolute
        } catch (_: URISyntaxException) {
            false
        }
    if (!absolute || value.any { it.isWhitespace() || it.isISOControl() }) {
        throw usageError("--base-iri must be an absolute IRI such as https://example.org/onto (got '${sanitize(value)}')")
    }
    return value
}

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
        } catch (e: SimilaritySearchBudgetExceededException) {
            throw CliktError("Semantic enrichment failed: ${sanitize(e.message)}. ${similarityBudgetHint(e)}", e, EXIT_RUNTIME_ERROR)
        } catch (e: Exception) {
            throw CliktError("onto-qa $commandName failed: ${describeFailure(e)}", e, EXIT_RUNTIME_ERROR)
        }
    }
}

private fun usageError(message: String): UsageError = UsageError(message, statusCode = EXIT_USAGE)

/** Extensions of compressed or quad / N3 files, which [resolveInputFormat] must not take for Turtle. */
private val UNSUPPORTED_INPUT_EXTENSIONS = listOf(".trig", ".nq", ".nquads", ".n3", ".trix", ".gz", ".bz2", ".xz", ".zip", ".zst")

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
        return normalized ?: throw usageError("Unsupported --input-format $override (expected $INPUT_FORMAT_HELP)")
    }
    val name = path.fileName?.toString()?.lowercase().orEmpty()
    return when {
        name.endsWith(".owl") || name.endsWith(".rdf") || name.endsWith(".xml") -> RdfFormat.RDF_XML
        name.endsWith(".nt") -> RdfFormat.N_TRIPLES
        name.endsWith(".jsonld") || name.endsWith(".json") -> RdfFormat.JSON_LD
        name.endsWith(".ttl") || name.endsWith(".turtle") -> RdfFormat.TURTLE
        // Known formats this tool cannot read as an ontology graph: Turtle would fail with a parse error that blames
        // the content instead of the extension.
        UNSUPPORTED_INPUT_EXTENSIONS.any { name.endsWith(it) } ->
            throw usageError(
                "Unsupported input extension '${sanitize(name.substringAfterLast('.', ""))}' of ${sanitize(name)}: " +
                    "onto-qa reads $INPUT_FORMAT_HELP; decompress or convert the file, or force a syntax with --input-format",
            )
        else -> RdfFormat.TURTLE
    }
}

/**
 * Parses [path] with [baseIri] (default [defaultBaseIri], `urn:onto-qa:input/<file name>`), so relative references
 * (`<>`, `<#Foo>`, `rdf:about="#Foo"`) resolve instead of being rejected, independently of where the file lives. Uses
 * the provider-level `RdfProvider.parseGraph(stream, format, baseIri)` overload, trying providers in registry priority
 * order.
 */
internal fun parseOntology(
    path: Path,
    format: RdfFormat,
    baseIri: String? = null,
    providers: List<RdfProvider> = RdfProviderRegistry.discoverProviders().filter { it.supportsInputFormat(format.formatName) },
): RdfGraph {
    val base = baseIri ?: defaultBaseIri(path)
    fun readError(e: IOException) = CliktError("Failed to read ${sanitize(path.toString())}: ${describeFailure(e)}", e, EXIT_RUNTIME_ERROR)
    for (provider in providers) {
        val stream =
            try {
                IoTrackingInputStream(Files.newInputStream(path))
            } catch (e: IOException) {
                throw readError(e)
            }
        try {
            return stream.use { provider.parseGraph(it, format.formatName, base) }
        } catch (_: UnsupportedOperationException) {
            continue
        } catch (e: Exception) {
            // The file could not be read (status 5), as opposed to read but not understood (status 2).
            stream.failure?.let { throw readError(it) }
            throw CliktError("Failed to parse ${sanitize(path.toString())} as ${format.formatName}: ${sanitize(e.message)}", e, EXIT_INPUT_ERROR)
        }
    }
    throw CliktError(
        "Failed to parse ${sanitize(path.toString())} as ${format.formatName}: no RDF provider can parse ${format.formatName} " +
            "(is an RDF provider such as rdf-jena on the class path?)",
        null,
        EXIT_RUNTIME_ERROR,
    )
}

/** Remembers an [IOException] raised while reading, so a read failure is not reported as a syntax error. */
private class IoTrackingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
    var failure: IOException? = null
        private set

    override fun read(): Int =
        try {
            super.read()
        } catch (e: IOException) {
            failure = e
            throw e
        }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        try {
            super.read(b, off, len)
        } catch (e: IOException) {
            failure = e
            throw e
        }
}

/**
 * File-system questions of the `--output` pre-check. Tests answer them for situations a test cannot set up on every
 * platform (symbolic links, a file owned by another user).
 */
internal open class OutputFileSystem {
    open fun isSymbolicLink(path: Path): Boolean = Files.isSymbolicLink(path)

    /** The file a symbolic link leads to; fails when a link of the chain is dangling or the chain loops. */
    @Throws(IOException::class)
    open fun realPath(path: Path): Path = path.toRealPath()

    /**
     * Why the existing file [target] cannot be replaced by a new file that keeps its POSIX permissions and owner, or
     * null when it can (or the file system has no POSIX permissions). A probe file is created next to [target], given
     * the permissions of [target], compared by owner and removed: exactly what the atomic write does later.
     */
    open fun replacementProblem(target: Path): String? {
        val permissions =
            try {
                Files.getPosixFilePermissions(target)
            } catch (_: UnsupportedOperationException) {
                return null
            } catch (e: IOException) {
                return "its permissions cannot be read (${e.javaClass.simpleName})"
            }
        val parent = target.parent ?: return null
        val probe = parent.resolve(".${target.fileName}.${java.util.UUID.randomUUID()}.probe")
        return try {
            Files.newOutputStream(probe, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close()
            val owner = Files.getOwner(target)
            if (owner != Files.getOwner(probe)) {
                return "the file is owned by another user (${owner.name}): replacing it would change its owner; choose another path"
            }
            Files.setPosixFilePermissions(probe, permissions)
            null
        } catch (_: UnsupportedOperationException) {
            null
        } catch (e: IOException) {
            "a replacement file with the same permissions cannot be created in $parent (${e.javaClass.simpleName}: ${e.message})"
        } finally {
            try {
                Files.deleteIfExists(probe)
            } catch (_: IOException) {
                // Best effort: the probe is empty.
            }
        }
    }
}

/**
 * Why [output] cannot be written, or null when it can: it is a symbolic link that cannot be resolved (dangling, or a
 * loop), a directory, an existing file that is not writable or that cannot be replaced by a file with the same owner
 * and permissions ([OutputFileSystem.replacementProblem]), or its nearest existing parent is not a writable directory
 * (missing parent directories are created when the file is written). Everything [writeFileAtomically] can stumble
 * on is found here, before any work. Nothing is changed (a probe file next to an existing target is removed again).
 */
internal fun outputProblem(output: Path, fileSystem: OutputFileSystem = OutputFileSystem()): String? {
    var target = output.toAbsolutePath().normalize()
    if (fileSystem.isSymbolicLink(target)) {
        target =
            try {
                fileSystem.realPath(target)
            } catch (e: IOException) {
                return "it is a symbolic link whose target does not exist or cannot be resolved (${e.javaClass.simpleName}); " +
                    "create the target or choose another path"
            }
    }
    if (Files.isDirectory(target)) return "it is a directory"
    if (Files.exists(target)) return if (Files.isWritable(target)) fileSystem.replacementProblem(target) else "the file is not writable"
    var ancestor = target.parent ?: return "it has no parent directory"
    while (!Files.exists(ancestor)) ancestor = ancestor.parent ?: return "none of its parent directories exists"
    if (!Files.isDirectory(ancestor)) return "$ancestor is not a directory"
    if (!Files.isWritable(ancestor)) return "directory $ancestor is not writable"
    return null
}

/** True when [a] and [b] name the same file (through links when both exist, else by normalised absolute path). */
internal fun isSameFile(a: Path, b: Path): Boolean {
    val normalised = a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()
    if (normalised) return true
    return try {
        Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b)
    } catch (_: IOException) {
        false
    }
}

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

/** Opens the enrichment step; model download or loading failures are runtime errors, not usage errors. */
private fun openEnricher(environment: CliEnvironment, options: EmbeddingCliOptions): PipelineEnricher =
    try {
        environment.enricherFactory.open(options)
    } catch (e: CliktError) {
        throw e
    } catch (e: Exception) {
        throw CliktError("Failed to load embedding model ${options.modelId}: ${describeFailure(e)}", e, EXIT_RUNTIME_ERROR)
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

/** Embedding options shared by `enrich` and `pipeline`; validated before any model is loaded. */
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
    val similarityMaxWork by
        option(
            "--similarity-max-work",
            help = "Max distance evaluations for the similarity search (default: scaled to the number of labelled entities, at least 50000000)",
        ).long().restrictTo(min = 1L)
    val similarityTimeout by
        option("--similarity-timeout", help = "Similarity search deadline in seconds (default: scaled, at least 30)")
            .int()
            .restrictTo(1..86_400)
    val similarityMaxPairs by
        option(
            "--similarity-max-pairs",
            help = "Max similar pairs the search may return before failing (default: 1000000); raise --threshold instead when this is hit",
        ).int().restrictTo(min = 1)
    val similarityMode by
        option(
            "--similarity-mode",
            help = "exact | approximate — approximate uses random-projection LSH: much faster on large vocabularies, " +
                "but may miss some similar pairs (recorded in enrichment provenance)",
        ).choice("exact", "approximate", ignoreCase = true).default("exact")

    fun toOptions(): EmbeddingCliOptions {
        try {
            OnnxEmbeddingModel.validateCliOptions(model, onnx, tokenizer, embeddingDim, maxTokens)
        } catch (e: IllegalArgumentException) {
            throw usageError(e.message ?: "Invalid embedding options")
        }
        return EmbeddingCliOptions(
            modelId = model,
            cacheDir = cacheDir,
            onnx = onnx,
            tokenizer = tokenizer,
            embeddingDim = embeddingDim,
            maxTokens = maxTokens,
            displayName = displayName,
            tokenizerNote = tokenizerNote,
            threshold = threshold,
            similarityMaxWork = similarityMaxWork,
            similarityTimeout = similarityTimeout?.let { Duration.ofSeconds(it.toLong()) },
            similarityMaxPairs = similarityMaxPairs,
            similarityMode =
                if (similarityMode.equals("approximate", ignoreCase = true)) SimilaritySearchMode.ApproximateLsh() else SimilaritySearchMode.Exact,
        )
    }
}

/** Report / LLM options shared by `check` and `pipeline`. */
private class ReportOptionGroup : com.github.ajalt.clikt.parameters.groups.OptionGroup() {
    val catalog by option("--catalog", help = CATALOG_HELP).choice(*CATALOG_NAMES.toTypedArray(), ignoreCase = true).default("all")
    val format by option("--format", help = "text | markdown | json | turtle").choice(*FORMAT_CHOICES, ignoreCase = true).default("text")
    val severity by
        option("--severity", help = "violation | warning | info — exit $EXIT_FINDINGS when any finding is at or above this level")
            .choice(*SEVERITY_CHOICES, ignoreCase = true)
            .default("violation")
    val output by option("--output", help = OUTPUT_HELP).path()
    val explain by
        option(
            "--explain",
            help = "Add LLM explanations via Koog (requires $LLM_EXPLAIN_ENV=true and provider credentials). " +
                "Not available with --format turtle.",
        ).flag(default = false)
    val explainDryRun by
        option(
            "--explain-dry-run",
            help = "With --explain: print how many findings would be explained; no API call, so neither $LLM_EXPLAIN_ENV nor an " +
                "API key is needed. A usage error without --explain.",
        ).flag(default = false)
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
    val llmRetries by
        option(
            "--llm-retries",
            help = "Retries per LLM request for timeouts, HTTP 408/429/5xx and connection errors (0–10, default 2); other errors are not retried",
        ).int().restrictTo(0..10).default(2)
    val llmMaxOutputTokens by
        option(
            "--llm-max-output-tokens",
            help = "Most tokens the model may generate per LLM request (default ${LlmExplanationConfig.DEFAULT_MAX_OUTPUT_TOKENS}); raise it with a large " +
                "--explain-batch, or when a reply is reported as truncated. Sent to OpenAI and Anthropic; not to Ollama (a note says so)",
        ).int().restrictTo(1..LlmExplanationConfig.MAX_OUTPUT_TOKENS_LIMIT).default(LlmExplanationConfig.DEFAULT_MAX_OUTPUT_TOKENS)
    val llmMaxDurationSeconds by
        option(
            "--llm-max-duration",
            help = "Upper bound in seconds for all LLM explanation requests, retries and waits (default 600); unfinished batches are reported as failures",
        ).int().restrictTo(1..86_400).default(600)
    val reasoner by
        option(
            "--reasoner",
            help = "none | rdfs | owl-micro | owl-rl | hermit — materialize inferences before SHACL (owl-micro = Jena OWL Micro " +
                "rule reasoner, fast and incomplete; owl-rl = Jena OWL rule reasoner; hermit = OWL DL via HermiT)",
        ).choice(*REASONER_CHOICES, ignoreCase = true).default("none")
    val markdownAscii by
        option(
            "--markdown-ascii",
            help = "With --format markdown: use [VIOLATION]/[WARNING] markers instead of emoji.",
        ).flag(default = false)

    /**
     * `--explain` options, validated before anything is parsed, loaded or sent. The SHACL validation report
     * vocabulary has no place for explanations, so `--format turtle --explain` is refused rather than paying for LLM
     * calls whose result would be dropped.
     */
    fun explainCli(): LlmExplainCli? =
        if (explain && format.equals("turtle", ignoreCase = true)) {
            throw usageError(
                "--explain cannot be combined with --format turtle: the Turtle output is a SHACL validation report, which has no " +
                    "place for LLM explanations. Use --format text, markdown or json, or drop --explain.",
            )
        } else if (!explain) {
            if (explainDryRun) throw usageError("--explain-dry-run requires --explain")
            null
        } else {
            // Checked for a dry run too: it exists to catch a bad configuration before a paid run.
            val provider = parseLlmProvider(llmProvider)
            if (llmModel != null && llmModel!!.isBlank()) throw usageError("--llm-model must not be blank")
            LlmExplainCli(
                dryRun = explainDryRun,
                provider = provider,
                modelId = llmModel?.trim()?.takeIf { it.isNotEmpty() },
                modelPreset = parseExplanationModelPreset(llmModelPreset),
                ollamaBase = validatedOllamaBase(ollamaBase),
                maxFindings = explainMax,
                batchSize = explainBatch,
                minSeverity = explainMinViolationSeverity(explainMinSeverity),
                requestTimeout = Duration.ofSeconds(llmTimeoutSeconds.toLong()),
                retries = llmRetries,
                maxTotalDuration = Duration.ofSeconds(llmMaxDurationSeconds.toLong()),
                failOnError = failOnExplainError,
                maxOutputTokens = llmMaxOutputTokens,
            )
        }
}

/** `--ollama-base` as an `http(s)` URL with a host; the value is not echoed (it may carry credentials). */
internal fun validatedOllamaBase(value: String?): String? {
    if (value == null) return null
    val base = value.trim()
    val uri = try { java.net.URI(base) } catch (_: java.net.URISyntaxException) { null }
    if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) {
        throw usageError("--ollama-base must be an http or https URL with a host, for example http://localhost:11434")
    }
    return base
}

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
        val ontology = parseOntology(ontologyArg, format, baseIri)
        val checker = buildChecker(reportOptions.catalog, ShaclValidation.validator(), withMetricsProvider = false)

        val exitCode =
            openEnricher(environment, embeddingOptions).use { enricher ->
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
                        echo(
                            "onto-qa: warning: --catalog ${reportOptions.catalog.lowercase()} includes embedding-quality shapes, but enrichment " +
                                "produced no oqsh:semanticallyCloseTo triples (no labelled pair reached --threshold ${embedding.threshold}); " +
                                "those shapes produce no findings.",
                            err = true,
                        )
                    }
                }

                if (reasoningProfile != OntoQualityReasoningProfile.NONE) {
                    logger.info("Onto-quality reasoning profile: {}", reasoningProfile.name.lowercase())
                }
                val report = checker.check(enriched, reasoningProfile)
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
        val checker = buildChecker(reportOptions.catalog, ShaclValidation.validator(), useMetrics)

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

        if (reasoningProfile != OntoQualityReasoningProfile.NONE) {
            logger.info("Onto-quality reasoning profile: {}", reasoningProfile.name.lowercase())
        }
        val report = checker.check(graph, reasoningProfile)
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

/** [EXIT_FINDINGS] takes precedence over [EXIT_EXPLAIN_ERROR]. */
private fun exitStatus(report: QualityReport, options: ReportOptionGroup, outcome: ExplainOutcome): Int =
    when {
        shouldFail(report, severityThreshold(options.severity)) -> EXIT_FINDINGS
        options.failOnExplainError && outcome.failed -> EXIT_EXPLAIN_ERROR
        else -> EXIT_OK
    }

/** Usage error with guidance when the selected reasoner cannot run (e.g. HermiT missing from the classpath). */
private fun requireReasonerAvailable(environment: CliEnvironment, profile: OntoQualityReasoningProfile, option: String) {
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
private fun parseReasonerProfile(s: String): OntoQualityReasoningProfile =
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
    val maxTotalDuration: Duration,
    /** `--fail-on-explain-error`. */
    val failOnError: Boolean = false,
    /** Provider API key read from the environment by [preflightLlm]. */
    val apiKey: String? = null,
    /** Set by [preflightLlm] when a prerequisite is missing: no LLM is called and the run counts as an explanation failure. */
    val skipped: Boolean = false,
    /** `--llm-max-output-tokens`. */
    val maxOutputTokens: Int = LlmExplanationConfig.DEFAULT_MAX_OUTPUT_TOKENS,
) {
    override fun toString(): String =
        "LlmExplainCli(provider=$provider, dryRun=$dryRun, skipped=$skipped, apiKey=${if (apiKey == null) "null" else "***"})"
}

/** Environment variable holding the API key for [provider]; null when none is needed (Ollama). */
private fun apiKeyEnv(provider: LlmProvider): String? = provider.apiKeyVariable

/**
 * Checks the LLM prerequisites ([LLM_EXPLAIN_ENV] opt-in, provider API key) before any expensive work. A dry run needs
 * neither. When one is missing: with `--fail-on-explain-error` the command stops with [EXIT_EXPLAIN_ERROR]; otherwise a
 * warning is printed and the returned options are marked [LlmExplainCli.skipped].
 */
private fun preflightLlm(llm: LlmExplainCli?, environment: CliEnvironment, err: (String) -> Unit): LlmExplainCli? {
    if (llm == null) return null
    if (llm.ollamaBase != null && llm.provider != LlmProvider.OLLAMA) {
        err("onto-qa: warning: --ollama-base is ignored: it only applies to --llm-provider ollama (the provider is ${llm.provider.name.lowercase()}).")
    }
    if (llm.dryRun) return llm
    val keyEnv = apiKeyEnv(llm.provider)
    val key = keyEnv?.let { environment.env(it) }?.trim()?.takeIf { it.isNotEmpty() }
    val problem =
        when {
            !environment.env(LLM_EXPLAIN_ENV).equals("true", ignoreCase = true) ->
                "$LLM_EXPLAIN_ENV is not set to true (required by --explain)"
            keyEnv != null && key == null ->
                "$keyEnv is not set (required by --explain --llm-provider ${llm.provider.name.lowercase()})"
            else -> {
                if (keyEnv != null && key != null && SecretRedaction.isShort(key)) {
                    err(
                        "onto-qa: warning: $keyEnv has only ${key.length} character${if (key.length == 1) "" else "s"}, which is unusual for an API key; " +
                            "it is redacted only where it stands as a credential (a header, a key= value), not inside other text.",
                    )
                }
                return llm.copy(apiKey = key)
            }
        }
    if (llm.failOnError) {
        err("onto-qa: error: LLM explanations skipped: $problem; failing because of --fail-on-explain-error.")
        throw ProgramResult(EXIT_EXPLAIN_ERROR)
    }
    err("onto-qa: warning: LLM explanations skipped: $problem.")
    return llm.copy(skipped = true)
}

private fun parseLlmProvider(s: String): LlmProvider =
    when (s.lowercase()) {
        "openai" -> LlmProvider.OPENAI
        "anthropic" -> LlmProvider.ANTHROPIC
        "ollama" -> LlmProvider.OLLAMA
        else -> throw usageError("Unknown --llm-provider $s (expected openai|anthropic|ollama)")
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
            throw usageError(
                "Unknown --llm-model-preset $s (expected auto|gpt4o-mini|gpt4o|sonnet-4-5|haiku-4-5|llama3.2)",
            )
    }

private fun explainMinViolationSeverity(s: String): ViolationSeverity =
    when (s.lowercase()) {
        "violation" -> ViolationSeverity.VIOLATION
        "warning" -> ViolationSeverity.WARNING
        "info" -> ViolationSeverity.INFO
        else -> throw usageError("Unknown --explain-min-severity $s (expected violation|warning|info)")
    }

/** @param failed true when explanations were requested and the call threw or left findings unexplained. */
internal data class ExplainOutcome(val explained: ExplainedQualityReport?, val failed: Boolean)

private fun maybeExplainReport(
    report: QualityReport,
    llm: LlmExplainCli?,
    environment: CliEnvironment,
    debug: Boolean,
    err: (String) -> Unit,
): ExplainOutcome {
    if (llm == null) return ExplainOutcome(null, failed = false)
    // Missing prerequisites were reported by preflightLlm; no explanations is a failure under --fail-on-explain-error.
    if (llm.skipped) return ExplainOutcome(null, failed = true)
    if (llm.dryRun) {
        val n =
            report.findings
                .count { it.violation.severity.isAtLeast(llm.minSeverity) }
                .coerceAtMost(llm.maxFindings)
        val modelDesc =
            llm.modelId
                ?: "${llm.modelPreset.name.lowercase()} (preset)"
        err("LLM explain dry-run: would send up to $n findings (provider=${llm.provider}, model=$modelDesc, max output tokens=${llm.maxOutputTokens})")
        LlmExplanationConfig(provider = llm.provider, requestTimeout = llm.requestTimeout, maxOutputTokens = llm.maxOutputTokens)
            .maxOutputTokensNotice()
            ?.let { err("onto-qa: note: $it") }
        return ExplainOutcome(null, failed = false)
    }
    val secrets = secretsOf(environment, llm.provider, llm.apiKey, llm.ollamaBase)
    return try {
        val cfg =
            LlmExplanationConfig(
                provider = llm.provider,
                apiKey = llm.apiKey,
                modelId = llm.modelId,
                modelPreset = llm.modelPreset,
                baseUrl = llm.ollamaBase,
                requestTimeout = llm.requestTimeout,
                maxRetries = llm.retries,
                maxTotalDuration = llm.maxTotalDuration,
                maxOutputTokens = llm.maxOutputTokens,
            )
        // Said once, before the first request: the provider cannot be given the limit (Ollama).
        cfg.maxOutputTokensNotice()?.let { err("onto-qa: note: $it") }
        val enricher = environment.explanationEnricherFactory(cfg)
        val opts =
            ExplanationOptions(
                maxFindings = llm.maxFindings,
                batchSize = llm.batchSize,
                minSeverity = llm.minSeverity,
            )
        val raw = runBlocking { enricher.enrich(report, opts) }
        // No API key or URL credential reaches a report, even when a provider echoes it in an error.
        val explained =
            if (raw.failures.all { redact(it.reason, secrets) == it.reason }) {
                raw
            } else {
                raw.copy(failures = raw.failures.map { ExplanationFailure(it.findingRefs, redact(it.reason, secrets), it.cause) })
            }
        if (explained.hasExplanationFailures) {
            reportUnexplained(explained.failures, llm.failOnError, secrets, err)
            if (debug) printFailureCauses(explained.failures, secrets, err)
        }
        ExplainOutcome(explained, failed = explained.hasExplanationFailures)
    } catch (e: Exception) {
        err(safeLine("LLM explanations failed: ${e::class.simpleName}: ${e.message ?: ""}", secrets))
        if (debug) printStackTrace(e, secrets, err)
        ExplainOutcome(null, failed = true)
    }
}

/** Distinct failure reasons listed on stderr; findings with further reasons are counted together. */
private const val MAX_FAILURE_REASONS = 5

/**
 * Says on stderr how many findings got no explanation, and why: one line when every failure has the same reason, else
 * one line per reason with its number of findings (a batch that is too large to send is skipped without an LLM call,
 * so its findings would otherwise go unnoticed). Without `--fail-on-explain-error` the exit status does not tell, so
 * a hint says so.
 */
private fun reportUnexplained(failures: List<ExplanationFailure>, failOnError: Boolean, secrets: List<String>, err: (String) -> Unit) {
    val missing = failures.sumOf { it.findingRefs.size }
    val byReason = LinkedHashMap<String, Int>()
    for (failure in failures) byReason.merge(failure.reason, failure.findingRefs.size, Int::plus)
    if (byReason.size == 1) {
        err(safeLine("LLM explanations incomplete: $missing finding(s) not explained (${failures.first().reason})", secrets))
        return
    }
    err("LLM explanations incomplete: $missing finding(s) not explained, for ${byReason.size} reasons:")
    val reasons = byReason.entries.sortedByDescending { it.value }
    for ((reason, count) in reasons.take(MAX_FAILURE_REASONS)) err(safeLine("  - $count finding(s): $reason", secrets))
    if (reasons.size > MAX_FAILURE_REASONS) {
        val rest = reasons.drop(MAX_FAILURE_REASONS)
        err("  - ${rest.sumOf { it.value }} finding(s): ${rest.size} other reason(s)")
    }
    if (!failOnError) err("  The exit status does not reflect this; pass --fail-on-explain-error to exit with status $EXIT_EXPLAIN_ERROR.")
}

/** Stack traces printed for LLM request failures under `--debug`; further distinct causes are only counted. */
private const val MAX_DEBUG_FAILURE_TRACES = 3

/** `--debug`: the exceptions behind failed LLM requests (HTTP 401, timeouts, 5xx, …), each distinct one once. */
private fun printFailureCauses(failures: List<ExplanationFailure>, secrets: List<String>, err: (String) -> Unit) {
    val causes = failures.mapNotNull { it.cause }.distinctBy { it.javaClass.name to it.message }
    for (cause in causes.take(MAX_DEBUG_FAILURE_TRACES)) {
        err("LLM explanation failure cause:")
        printStackTrace(cause, secrets, err)
    }
    if (causes.size > MAX_DEBUG_FAILURE_TRACES) {
        err("(${causes.size - MAX_DEBUG_FAILURE_TRACES} more distinct LLM failure cause(s) not shown)")
    }
}

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
                throw usageError(
                    "Unknown --catalog $catalogOpt (expected $CATALOG_HELP)",
                )
        }
    return builder.build()
}

/**
 * Writes [text] (UTF-8) to [output] through a temporary file in the same directory that is then moved over the
 * target, so a reader never sees a half-written file and a failed write leaves the previous content in place.
 * Parent directories are created; an existing file is replaced (keeping its POSIX permissions); a directory is not.
 */
internal fun writeFileAtomically(output: Path, text: String) {
    var target = output.toAbsolutePath().normalize()
    if (Files.isSymbolicLink(target)) {
        target =
            try {
                target.toRealPath()
            } catch (e: IOException) {
                throw IOException("$output is a symbolic link whose target does not exist or cannot be resolved (${e.javaClass.simpleName})", e)
            }
    }
    if (Files.isDirectory(target)) throw IOException("$output is a directory")
    val parent = target.parent ?: throw IOException("$output has no parent directory")
    Files.createDirectories(parent)
    val temp = parent.resolve(".${target.fileName}.${java.util.UUID.randomUUID()}.tmp")
    try {
        // CREATE_NEW with the default permissions of new files (a createTempFile would restrict them to the owner).
        Files.newBufferedWriter(temp, Charsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { it.write(text) }
        if (Files.exists(target)) {
            try {
                Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target))
            } catch (_: UnsupportedOperationException) {
                // Not a POSIX file system.
            } catch (e: IOException) {
                // Normally found by outputProblem before any work; a replacement with other permissions is not written.
                throw IOException("the permissions of $output cannot be kept on its replacement (${e.javaClass.simpleName}: ${e.message})", e)
            }
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temp)
    }
}

private fun writeOutput(text: String, output: Path?, stdout: (String) -> Unit) {
    if (output != null) {
        writeFileAtomically(output, text)
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
            else -> throw usageError("Unknown --format $formatOpt (expected text|markdown|json|turtle)")
        }
    writeOutput(text, outputOpt, stdout)
}

private fun severityThreshold(severityOpt: String): SeverityThreshold =
    when (severityOpt.lowercase()) {
        "violation" -> SeverityThreshold.VIOLATION
        "warning" -> SeverityThreshold.WARNING
        "info" -> SeverityThreshold.INFO
        else -> throw usageError("Unknown --severity $severityOpt (expected violation|warning|info)")
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

/** True when any finding is at or above [threshold]; with INFO every finding (including debug / trace rows) counts. */
private fun shouldFail(report: QualityReport, threshold: SeverityThreshold): Boolean =
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
                        put("message", f.stableMessage)
                        put("shapeUri", f.violation.shapeUri)
                        put("category", f.category.name)
                        put("pitfall", pitfallLabel(f.pitfall))
                        put("tier", f.tier.name)
                        put("focusNode", focusNodeToString(f.violation.focusNode, f.blankNodeKeys))
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

/** Blank nodes are shown by their parse-independent key when known (see [QualityFinding.blankNodeKeys]). */
private fun focusNodeToString(term: RdfTerm, blankNodeKeys: Map<BlankNode, String>): String =
    when (term) {
        is Iri -> term.value
        is BlankNode -> blankNodeKeys[term] ?: term.toString()
        is Literal -> term.lexical
        else -> term.toString()
    }
