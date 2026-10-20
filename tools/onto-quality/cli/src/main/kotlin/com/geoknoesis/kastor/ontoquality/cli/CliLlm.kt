package com.geoknoesis.kastor.ontoquality.cli

import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.ontoquality.SecretRedaction
import com.geoknoesis.kastor.ontoquality.explanation.ExplainedQualityReport
import com.geoknoesis.kastor.ontoquality.explanation.ExplanationFailure
import com.geoknoesis.kastor.ontoquality.explanation.ExplanationOptions
import com.geoknoesis.kastor.ontoquality.explanation.isAtLeast
import com.geoknoesis.kastor.ontoquality.llm.ExplanationModelPreset
import com.geoknoesis.kastor.ontoquality.llm.LlmExplanationConfig
import com.geoknoesis.kastor.ontoquality.llm.LlmProvider
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import com.github.ajalt.clikt.core.ProgramResult
import kotlinx.coroutines.runBlocking
import java.time.Duration

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
internal fun apiKeyEnv(provider: LlmProvider): String? = provider.apiKeyVariable

/**
 * Checks the LLM prerequisites ([LLM_EXPLAIN_ENV] opt-in, provider API key) before any expensive work. A dry run needs
 * neither. When one is missing: with `--fail-on-explain-error` the command stops with [EXIT_EXPLAIN_ERROR]; otherwise a
 * warning is printed and the returned options are marked [LlmExplainCli.skipped].
 */
internal fun preflightLlm(llm: LlmExplainCli?, environment: CliEnvironment, err: (String) -> Unit): LlmExplainCli? {
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

internal fun parseLlmProvider(s: String): LlmProvider =
    when (s.lowercase()) {
        "openai" -> LlmProvider.OPENAI
        "anthropic" -> LlmProvider.ANTHROPIC
        "ollama" -> LlmProvider.OLLAMA
        else -> throw usageError("Unknown --llm-provider $s (expected openai|anthropic|ollama)")
    }

internal fun parseExplanationModelPreset(s: String): ExplanationModelPreset =
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

internal fun explainMinViolationSeverity(s: String): ViolationSeverity =
    when (s.lowercase()) {
        "violation" -> ViolationSeverity.VIOLATION
        "warning" -> ViolationSeverity.WARNING
        "info" -> ViolationSeverity.INFO
        else -> throw usageError("Unknown --explain-min-severity $s (expected violation|warning|info)")
    }

/** @param failed true when explanations were requested and the call threw or left findings unexplained. */
internal data class ExplainOutcome(val explained: ExplainedQualityReport?, val failed: Boolean)

internal fun maybeExplainReport(
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
