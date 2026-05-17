package com.geoknoesis.kastor.ontoquality.llm

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import com.geoknoesis.kastor.ontoquality.PitfallReference
import com.geoknoesis.kastor.ontoquality.QualityFinding
import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.ontoquality.explanation.ExplainedQualityReport
import com.geoknoesis.kastor.ontoquality.explanation.ExplanationOptions
import com.geoknoesis.kastor.ontoquality.explanation.FindingExplanation
import com.geoknoesis.kastor.ontoquality.explanation.FindingRef
import com.geoknoesis.kastor.ontoquality.explanation.QualityExplanationEnricher
import com.geoknoesis.kastor.ontoquality.explanation.isAtLeast
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Default [QualityExplanationEnricher] for onto-quality v0.3 (Koog runtime).
 */
class DefaultQualityExplanationEnricher(
    private val config: LlmExplanationConfig,
) : QualityExplanationEnricher {
    override suspend fun enrich(
        report: QualityReport,
        options: ExplanationOptions,
    ): ExplainedQualityReport =
        withContext(Dispatchers.Default) {
            val indexed =
                report.findings
                    .mapIndexed { i, f -> i to f }
                    .filter { (_, f) -> f.violation.severity.isAtLeast(options.minSeverity) }
                    .take(options.maxFindings)

            if (indexed.isEmpty()) {
                return@withContext ExplainedQualityReport(report, emptyList())
            }

            val model = config.resolvedModel()
            val client = createLlmClient(config)
            val all = mutableListOf<FindingExplanation>()

            MultiLLMPromptExecutor(client).use { executor ->
                for (chunk in indexed.chunked(options.batchSize)) {
                    val userMessage = buildUserMessage(report, chunk)
                    val prompt =
                        Prompt
                            .builder("onto-quality-explain")
                            .system(SYSTEM_PROMPT)
                            .user(userMessage)
                            .build()
                    val responses =
                        executor.execute(
                            prompt,
                            model,
                            emptyList(),
                        )
                    val raw =
                        responses
                            .firstOrNull()
                            ?.content
                            ?: continue
                    val parsed =
                        parseLlmJson(stripMarkdownFence(raw))
                            ?: run {
                                val fixPrompt =
                                    Prompt
                                        .builder("onto-quality-explain-fix")
                                        .system(SYSTEM_PROMPT)
                                        .user("$FIX_JSON_PREFIX\n\n$raw")
                                        .build()
                                val second =
                                    executor.execute(
                                        fixPrompt,
                                        model,
                                        emptyList(),
                                    )
                                parseLlmJson(
                                    stripMarkdownFence(
                                        second.firstOrNull()?.content ?: "",
                                    ),
                                )
                            }
                    if (parsed == null) continue
                    val runId = promptRunId(chunk.map { it.second }, userMessage, config.modelKey())
                    val allowedRefs = chunk.map { FindingRef.from(it.second) }.toSet()
                    for (item in parsed.items) {
                        val ref = FindingRef(item.findingRef)
                        if (ref !in allowedRefs) continue
                        all.add(
                            FindingExplanation(
                                findingRef = ref,
                                summary = item.summary,
                                whyItMatters = item.whyItMatters,
                                suggestedActions = item.suggestedActions,
                                confidenceNote = item.confidenceNote,
                                modelId = model.id,
                                providerKind = config.provider.name.lowercase(),
                                promptRunId = runId,
                            ),
                        )
                    }
                }
            }

            ExplainedQualityReport(report, all)
        }

    private fun createLlmClient(config: LlmExplanationConfig): LLMClient =
        when (config.provider) {
            LlmProvider.OPENAI -> {
                val key =
                    config.apiKey?.takeIf { it.isNotBlank() }
                        ?: System.getenv(LlmExplanationConfig.OPENAI_API_KEY)
                        ?: error(
                            "OpenAI API key missing: set ${LlmExplanationConfig.OPENAI_API_KEY} or pass apiKey",
                        )
                OpenAILLMClient(key)
            }
            LlmProvider.ANTHROPIC -> {
                val key =
                    config.apiKey?.takeIf { it.isNotBlank() }
                        ?: System.getenv(LlmExplanationConfig.ANTHROPIC_API_KEY)
                        ?: error(
                            "Anthropic API key missing: set ${LlmExplanationConfig.ANTHROPIC_API_KEY} or pass apiKey",
                        )
                AnthropicLLMClient(key)
            }
            LlmProvider.OLLAMA -> {
                val base = config.baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: "http://localhost:11434"
                OllamaClient(base)
            }
        }

    private companion object {
        const val SYSTEM_PROMPT =
            "You help ontology engineers understand SHACL validation findings. " +
                "Respond with ONE JSON object ONLY (no markdown fences), shape: " +
                "{\"schemaVersion\":1,\"items\":[{\"findingRef\":\"<hex>\",\"summary\":\"...\",\"whyItMatters\":\"...\",\"suggestedActions\":[\"...\"],\"confidenceNote\":\"...\"}]} " +
                "Rules: findingRef MUST match each input exactly (content-stable hex; row order does not matter). Do not invent IRIs. " +
                "Explanations are advisory only and are not logical entailments."

        const val FIX_JSON_PREFIX =
            "The previous reply was not valid JSON. Return ONLY a single JSON object with schemaVersion 1 and items[], no markdown."

        fun focusString(t: RdfTerm): String =
            when (t) {
                is Iri -> t.value
                is BlankNode -> "_:${t.id}"
                is Literal -> t.lexical
                else -> t.toString()
            }

        fun pitfallJson(f: QualityFinding): String =
            when (val p = f.pitfall) {
                null -> "null"
                is PitfallReference.Oops -> "\"OOPS:${p.number}\""
                is PitfallReference.Skos -> "\"SKOS:${p.number}\""
                is PitfallReference.OntoQuality -> "\"OntoQuality:${p.number}\""
                is PitfallReference.KastorExtension -> "\"Kastor:${p.code}\""
                PitfallReference.Convention -> "\"convention\""
            }

        fun buildUserMessage(
            report: QualityReport,
            chunk: List<Pair<Int, QualityFinding>>,
        ): String {
            val conforms = report.conforms
            val sb = StringBuilder()
            sb.append("Ontology quality check: conforms=").append(conforms).append('\n')
            sb.append("Findings batch:\n")
            for ((_, f) in chunk) {
                val ref = FindingRef.from(f)
                sb.append("- findingRef: ").append(ref.hexSha256).append('\n')
                sb.append("  severity: ").append(f.violation.severity.name).append('\n')
                sb.append("  message: ").append(f.violation.message).append('\n')
                sb.append("  shapeUri: ").append(f.violation.shapeUri ?: "").append('\n')
                sb.append("  category: ").append(f.category.name).append('\n')
                sb.append("  tier: ").append(f.tier.name).append('\n')
                sb.append("  pitfall: ").append(pitfallJson(f)).append('\n')
                sb.append("  focusNode: ").append(focusString(f.violation.focusNode)).append('\n')
                val path = f.violation.path
                if (path != null) {
                    sb.append("  path: ").append(path.joinToString(" / ") { focusString(it) }).append('\n')
                }
                sb.append('\n')
            }
            sb.append("Produce JSON with one item per findingRef above.")
            return sb.toString()
        }

        fun stripMarkdownFence(s: String): String {
            var t = s.trim()
            if (t.startsWith("```")) {
                t = t.removePrefix("```json").removePrefix("```").trim()
                if (t.endsWith("```")) {
                    t = t.substring(0, t.length - 3).trim()
                }
            }
            return t
        }

        fun parseLlmJson(s: String): LlmExplanationPayload? {
            if (s.isBlank()) return null
            return try {
                explanationJson.decodeFromString(LlmExplanationPayload.serializer(), s)
            } catch (_: Exception) {
                null
            }
        }

        fun promptRunId(
            findings: List<QualityFinding>,
            userMessage: String,
            modelKey: String,
        ): String {
            val canon =
                buildString {
                    append(modelKey).append('\u001f')
                    for (f in findings) {
                        append(f.violation.message).append('\u001f')
                        append(f.violation.shapeUri ?: "").append('\u001e')
                    }
                    append(userMessage.length)
                }
            val digest = MessageDigest.getInstance("SHA-256").digest(canon.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { b -> "%02x".format(b) }
        }
    }
}

/** Convenience factory for library consumers. */
fun qualityExplanationEnricher(config: LlmExplanationConfig): QualityExplanationEnricher =
    DefaultQualityExplanationEnricher(config)
