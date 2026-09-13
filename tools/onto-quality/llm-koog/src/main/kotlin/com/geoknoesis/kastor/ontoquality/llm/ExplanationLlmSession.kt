package com.geoknoesis.kastor.ontoquality.llm

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient

/**
 * Minimal seam over the LLM transport: one system + user message in, the first reply's text out.
 * Keeps batching, retries, parsing and failure isolation testable without a live provider.
 */
internal interface ExplanationLlmSession : AutoCloseable {
    suspend fun complete(systemPrompt: String, userMessage: String): String?
}

/** Koog-backed session (OpenAI, Anthropic or Ollama per [LlmExplanationConfig.provider]). */
internal class KoogExplanationLlmSession(config: LlmExplanationConfig) : ExplanationLlmSession {
    private val model = config.resolvedModel()
    private val executor = MultiLLMPromptExecutor(createLlmClient(config))

    override suspend fun complete(systemPrompt: String, userMessage: String): String? {
        val prompt =
            Prompt
                .builder("onto-quality-explain")
                .system(systemPrompt)
                .user(userMessage)
                .build()
        return executor.execute(prompt, model, emptyList()).firstOrNull()?.content
    }

    override fun close() {
        executor.close()
    }

    private companion object {
        fun createLlmClient(config: LlmExplanationConfig): LLMClient =
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
    }
}
