package com.geoknoesis.kastor.ontoquality.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A short secret is redacted where it is a credential, never as a substring of other text. */
class LlmSecretRedactionTest {
    private fun reason(text: String, config: LlmExplanationConfig): String =
        DefaultQualityExplanationEnricher.failureReason(text, DefaultQualityExplanationEnricher.secretsToRedact(config))

    @Test
    fun `a one-character key does not mangle status codes and counts`() {
        val config = LlmExplanationConfig(provider = LlmProvider.OPENAI, apiKey = "1")
        val text = "LLM request failed after 1 attempt(s): HTTP 401 Unauthorized for 12 findings (11 retried)"
        assertEquals(text, reason(text, config))
    }

    @Test
    fun `a short key is redacted in credential positions`() {
        val config = LlmExplanationConfig(provider = LlmProvider.OPENAI, apiKey = "k3y")
        assertEquals("HTTP 401: Incorrect API key provided: ***.", reason("HTTP 401: Incorrect API key provided: k3y.", config))
        assertEquals("header Authorization: Bearer *** rejected", reason("header Authorization: Bearer k3y rejected", config))
        assertEquals("x-api-key: *** rejected", reason("x-api-key: k3y rejected", config))
        assertEquals("GET /v1/models?key=***&limit=3", reason("GET /v1/models?key=k3y&limit=3", config))
        assertEquals("body {\"api_key\":\"***\"}", reason("body {\"api_key\":\"k3y\"}", config))
        // Not a credential position, and not a whole token.
        assertEquals("monk3ys and k3ys are not keys", reason("monk3ys and k3ys are not keys", config))
    }

    @Test
    fun `short URL credentials are redacted in the URL only`() {
        val config = LlmExplanationConfig(provider = LlmProvider.OLLAMA, baseUrl = "http://u:a@ollama.internal:11434")
        val out = reason("HTTP 401 at http://u:a@ollama.internal:11434/api/chat: unauthorized, a user and a password are needed", config)
        assertEquals("HTTP 401 at http://***@ollama.internal:11434/api/chat: unauthorized, a user and a password are needed", out)
    }

    @Test
    fun `a long key is redacted wherever it occurs`() {
        val key = "sk-test-0123456789abcdef"
        val config = LlmExplanationConfig(provider = LlmProvider.OPENAI, apiKey = key)
        val out = reason("request $key failed; echoed prefix$key-suffix", config)
        assertFalse(out.contains(key), out)
        assertEquals("request *** failed; echoed prefix***-suffix", out)
    }

    @Test
    fun `only the configured key is a secret of an Ollama run`() {
        // Whatever OPENAI_API_KEY / ANTHROPIC_API_KEY hold in this environment, they are not secrets of this run.
        val secrets = DefaultQualityExplanationEnricher.secretsToRedact(LlmExplanationConfig(provider = LlmProvider.OLLAMA))
        assertTrue(secrets.isEmpty(), "secrets of an Ollama run without credentials: ${secrets.size}")
        val withUrl = DefaultQualityExplanationEnricher.secretsToRedact(LlmExplanationConfig(provider = LlmProvider.OLLAMA, baseUrl = "http://user:password1234@host"))
        assertEquals(listOf("user:password1234", "password1234"), withUrl)
    }
}
