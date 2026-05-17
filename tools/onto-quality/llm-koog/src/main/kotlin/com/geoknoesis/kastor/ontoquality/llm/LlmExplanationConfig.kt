package com.geoknoesis.kastor.ontoquality.llm

import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.ollama.client.OllamaModels

/** Backing LLM vendor (Koog client selection). */
enum class LlmProvider {
    OPENAI,
    ANTHROPIC,
    OLLAMA,
}

/**
 * Named model presets bundled with Koog catalogs. Used when [LlmExplanationConfig.modelId] is null.
 * [AUTO] picks a small, cost-effective default per [LlmProvider].
 */
enum class ExplanationModelPreset {
    AUTO,
    OPENAI_GPT4O_MINI,
    OPENAI_GPT4O,
    ANTHROPIC_SONNET_4_5,
    ANTHROPIC_HAIKU_4_5,
    OLLAMA_LLAMA_3_2,
}

/**
 * Configuration for the default LLM explanation enricher (Koog-backed).
 *
 * Model selection: when [modelId] is non-null, it is sent to the provider as-is and overrides [modelPreset].
 * Otherwise [modelPreset] selects a catalog model; [ExplanationModelPreset.AUTO] uses a pragmatic default per provider.
 *
 * API keys: when [apiKey] is null, [OPENAI_API_KEY] / [ANTHROPIC_API_KEY] are read from the environment. Ollama ignores [apiKey].
 */
data class LlmExplanationConfig(
    val provider: LlmProvider,
    val apiKey: String? = null,
    val baseUrl: String? = null,
    /**
     * Raw provider model id (e.g. `gpt-4o`, `claude-sonnet-4-5-20250929`).
     * When set, overrides [modelPreset].
     */
    val modelId: String? = null,
    val modelPreset: ExplanationModelPreset = ExplanationModelPreset.AUTO,
) {
    companion object {
        const val OPENAI_API_KEY = "OPENAI_API_KEY"
        const val ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY"
    }

    internal fun resolvedModel(): LLModel {
        val explicit = modelId?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) {
            return when (provider) {
                LlmProvider.OPENAI -> normalizeOpenAiExplicit(explicit)
                LlmProvider.ANTHROPIC -> normalizeAnthropicExplicit(explicit)
                LlmProvider.OLLAMA -> normalizeOllamaExplicit(explicit)
            }
        }
        return when (provider) {
            LlmProvider.OPENAI ->
                when (modelPreset) {
                    ExplanationModelPreset.OPENAI_GPT4O -> OpenAIModels.Chat.GPT4o
                    ExplanationModelPreset.OPENAI_GPT4O_MINI -> OpenAIModels.Chat.GPT4oMini
                    else -> OpenAIModels.Chat.GPT4oMini
                }
            LlmProvider.ANTHROPIC ->
                when (modelPreset) {
                    ExplanationModelPreset.ANTHROPIC_SONNET_4_5 -> AnthropicModels.Sonnet_4_5
                    ExplanationModelPreset.ANTHROPIC_HAIKU_4_5 -> AnthropicModels.Haiku_4_5
                    else -> AnthropicModels.Haiku_4_5
                }
            LlmProvider.OLLAMA ->
                when (modelPreset) {
                    ExplanationModelPreset.OLLAMA_LLAMA_3_2 -> OllamaModels.Meta.LLAMA_3_2
                    else -> OllamaModels.Meta.LLAMA_3_2
                }
        }
    }

    private fun normalizeOpenAiExplicit(mid: String): LLModel =
        when (mid.lowercase()) {
            "gpt-4o-mini",
            "gpt4o-mini",
            -> OpenAIModels.Chat.GPT4oMini
            "gpt-4o",
            "gpt4o",
            -> OpenAIModels.Chat.GPT4o
            else -> LLModel(LLMProvider.OpenAI, mid)
        }

    private fun normalizeAnthropicExplicit(mid: String): LLModel =
        when (mid.lowercase()) {
            "sonnet",
            "claude-sonnet-4-5",
            -> AnthropicModels.Sonnet_4_5
            "haiku",
            -> AnthropicModels.Haiku_4_5
            else -> LLModel(LLMProvider.Anthropic, mid)
        }

    private fun normalizeOllamaExplicit(mid: String): LLModel =
        when (mid.lowercase()) {
            "llama3.2",
            "llama-3.2",
            -> OllamaModels.Meta.LLAMA_3_2
            else -> LLModel(LLMProvider.Ollama, mid)
        }

    /** Stable string for cache keys and tracing. */
    internal fun modelKey(): String =
        modelId?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${provider.name}:${modelPreset.name}"
}
