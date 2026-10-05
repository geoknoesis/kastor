package com.geoknoesis.kastor.ontoquality.llm

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams

/**
 * Minimal seam over the LLM transport: one system + user message in, the first reply's text out.
 * Keeps batching, retries, parsing and failure isolation testable without a live provider.
 */
internal interface ExplanationLlmSession : AutoCloseable {
    suspend fun complete(systemPrompt: String, userMessage: String): String?

    /** [complete] with what the provider says about the reply; the default knows the text only. */
    suspend fun completeReply(systemPrompt: String, userMessage: String): LlmReply = LlmReply(complete(systemPrompt, userMessage))
}

/**
 * A reply and why it ended.
 *
 * @param finishReason the finish / stop reason given by the provider (`stop`, `length`, `max_tokens`, …); null when
 *   the client does not expose one (Ollama through Koog 0.8.0).
 * @param outputTokens tokens generated, when the provider reports them.
 */
internal data class LlmReply(val text: String?, val finishReason: String? = null, val outputTokens: Int? = null) {
    /** The provider stopped generating because the output-token limit was reached. */
    val truncatedAtLimit: Boolean get() = finishReason?.trim()?.lowercase() in LENGTH_FINISH_REASONS

    private companion object {
        /** OpenAI `length`, Anthropic `max_tokens`, and the spellings of compatible gateways. */
        val LENGTH_FINISH_REASONS = setOf("length", "max_tokens", "max_output_tokens", "model_length", "max_tokens_exceeded")
    }
}

/** The request for one batch; the reply is limited to [maxOutputTokens] tokens by the provider. */
internal fun explanationPrompt(systemPrompt: String, userMessage: String, maxOutputTokens: Int): Prompt =
    Prompt
        .builder("onto-quality-explain")
        .system(systemPrompt)
        .user(userMessage)
        .build()
        .withParams(LLMParams(maxTokens = maxOutputTokens))

/** The text of a Koog response with its finish reason and output-token count, where the provider gave them. */
internal fun replyOf(response: Message.Response?): LlmReply =
    LlmReply(
        text = response?.content,
        finishReason = (response as? Message.Assistant)?.finishReason,
        outputTokens = response?.metaInfo?.outputTokensCount,
    )

/** Koog-backed session (OpenAI, Anthropic or Ollama per [LlmExplanationConfig.provider]). */
internal class KoogExplanationLlmSession(config: LlmExplanationConfig) : ExplanationLlmSession {
    private val model = config.resolvedModel()
    private val executor = MultiLLMPromptExecutor(createLlmClient(config))
    private val maxOutputTokens = config.maxOutputTokens

    override suspend fun complete(systemPrompt: String, userMessage: String): String? = completeReply(systemPrompt, userMessage).text

    override suspend fun completeReply(systemPrompt: String, userMessage: String): LlmReply =
        replyOf(executor.execute(explanationPrompt(systemPrompt, userMessage, maxOutputTokens), model, emptyList()).firstOrNull())

    override fun close() {
        executor.close()
    }

    private companion object {
        fun createLlmClient(config: LlmExplanationConfig): LLMClient =
            when (config.provider) {
                LlmProvider.OPENAI -> OpenAILLMClient(resolveApiKey(config))
                LlmProvider.ANTHROPIC -> AnthropicLLMClient(resolveApiKey(config))
                LlmProvider.OLLAMA -> {
                    val base = config.baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: "http://localhost:11434"
                    OllamaClient(base)
                }
            }
    }
}

/**
 * The API key of [config]'s provider, trimmed: [LlmExplanationConfig.apiKey], else the provider's environment variable
 * (read through [env]). A blank value (an exported but empty variable) is a missing key, not a key to send.
 */
internal fun resolveApiKey(config: LlmExplanationConfig, env: (String) -> String? = System::getenv): String {
    val variable = config.provider.apiKeyVariable ?: error("${config.provider.name.lowercase()} needs no API key")
    val name = if (config.provider == LlmProvider.OPENAI) "OpenAI" else "Anthropic"
    return config.apiKey?.trim()?.takeIf { it.isNotEmpty() }
        ?: env(variable)?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("$name API key missing: set $variable (it is unset or blank) or pass apiKey")
}

