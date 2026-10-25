package com.geoknoesis.kastor.ontoquality.cli

import com.geoknoesis.kastor.ontoquality.llm.LlmExplanationConfig
import com.geoknoesis.kastor.ontoquality.embed.OnnxEmbeddingModel
import com.geoknoesis.kastor.ontoquality.embed.SimilaritySearchBudgetExceededException
import com.geoknoesis.kastor.ontoquality.embed.SimilaritySearchMode
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
import java.nio.file.Path
import java.time.Duration

/** Embedding options shared by `enrich` and `pipeline`; validated before any model is loaded. */
internal class EmbeddingOptionGroup : com.github.ajalt.clikt.parameters.groups.OptionGroup() {
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
        option("--similarity-timeout", help = "Similarity search deadline in seconds (default: scaled, 30 to 600)")
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
internal class ReportOptionGroup : com.github.ajalt.clikt.parameters.groups.OptionGroup() {
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

internal const val EXIT_STATUS_HELP =
    "Exit status: 0 success; 1 findings at or above --severity; 2 the ontology could not be parsed; " +
        "3 LLM explanations failed (with --fail-on-explain-error); 4 usage or configuration error; " +
        "5 runtime error (model download or loading, similarity or LLM budget, I/O, internal). " +
        "Use --debug (before or after the command) for stack traces.\n\n" +
        "kastor-rdf uses a different convention: 0 success; 1 usage or input error; 2 diff found the inputs not isomorphic; " +
        "3 runtime error.\n\n" +
        "Output redirected to a file or a pipe is UTF-8, whatever the platform charset; an interactive console is written " +
        "in its own charset (stdout and stderr are decided separately). --output files are written atomically (temporary file, then move); parent directories are " +
        "created and an existing file is replaced. An --output that cannot be written is refused before anything is run."

internal const val OUTPUT_HELP =
    "Write output to this file instead of stdout (parent directories are created; an existing file is replaced atomically)"

internal const val OVERWRITE_INPUT_HELP = "Allow --output to be the input ontology file (refused by default)"

internal const val SIMILARITY_BUDGET_HINT =
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

internal const val BASE_IRI_HELP =
    "Base IRI for relative references such as <#Foo> (default: $DEFAULT_BASE_IRI_PREFIX<file name>, independent of the directory)"

/** Upper bound for `--explain-max` (LLM cost / rate-limit guard). */
internal const val MAX_EXPLAIN_FINDINGS = 500

internal val CATALOG_NAMES =
    listOf(
        "owl-quality", "skos-validation", "data-quality", "embedding-quality", "modern-engineering",
        "rdf12-quality", "skos-vocabulary", "skos-vocabulary-embed", "all",
    )

/** Catalog values for [buildChecker]; documented on `--catalog` in check/pipeline. */
internal val CATALOG_HELP = CATALOG_NAMES.joinToString(" | ")

/** Catalogs that include embedding shapes; log if the graph has no enrichment triples. */
internal val CATALOGS_USING_EMBEDDING_SHAPES =
    setOf("all", "embedding-quality", "skos-vocabulary-embed")

/** When `true`, `onto-qa check --explain` / `onto-qa explain` may call an LLM (Koog). */
internal const val LLM_EXPLAIN_ENV = "KASTOR_ONTO_QUALITY_LLM"

internal val FORMAT_CHOICES = arrayOf("text", "markdown", "json", "turtle")
internal val SEVERITY_CHOICES = arrayOf("violation", "warning", "info")
internal val REASONER_CHOICES = arrayOf("none", "off", "rdfs", "owl-rl", "owl_rl", "owl-micro", "owl_micro", "hermit")
internal val PROVIDER_CHOICES = arrayOf("openai", "anthropic", "ollama")
internal val PRESET_CHOICES =
    arrayOf("auto", "gpt4o-mini", "gpt-4o-mini", "gpt4o", "gpt-4o", "sonnet", "sonnet-4-5", "claude-sonnet-4-5", "haiku", "haiku-4-5", "llama3.2", "llama-3.2")
internal const val INPUT_FORMAT_HELP =
    "turtle | rdfxml | ntriples | jsonld (default: from file extension — .ttl, .owl/.rdf/.xml, .nt, .jsonld/.json; other extensions are read as Turtle, except .trig/.nq/.n3 and compressed files, which are refused)"
