package com.geoknoesis.kastor.ontoquality.llm

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class LlmFailureClassifierTest {
    private class HttpFailure(val statusCode: Int, message: String, cause: Throwable? = null) : Exception(message, cause)

    @Test
    fun `a structured status deeper in the cause chain wins over a number scraped from an outer message`() {
        val outer = IllegalStateException("Batch failed: Status code: 503 reported by a proxy line", HttpFailure(400, "bad request"))
        // Scraping the outer message would call this retryable.
        assertEquals(LlmFailureKind(false, "HTTP 400"), LlmFailureClassifier.classify(outer))
    }

    @Test
    fun `a status quoted inside the error body is not the status of the failure`() {
        val quoting = IllegalStateException("Error from client: X\nError body: {\"detail\":\"upstream status 503, HTTP/1.1 502\"}")
        assertEquals(LlmFailureKind(false, "IllegalStateException: Error from client: X"), LlmFailureClassifier.classify(quoting))
        // The status line before the body still decides.
        val real = IllegalStateException("Error from client: X\nStatus code: 400\nError body: upstream status 503")
        assertEquals(LlmFailureKind(false, "HTTP 400"), LlmFailureClassifier.classify(real))
        // A status mentioned mid-sentence is not a status line.
        assertEquals(
            LlmFailureKind(false, "IllegalStateException: reply mentions status 503 in prose"),
            LlmFailureClassifier.classify(IllegalStateException("reply mentions status 503 in prose")),
        )
    }

    @Test
    fun `retry-after needs a header-like separator`() {
        val prose = HttpFailure(429, "Status code: 429\nError body: retry-after 30 batches of items")
        assertEquals(LlmFailureKind(true, "HTTP 429", null), LlmFailureClassifier.classify(prose))
        val header = HttpFailure(429, "Status code: 429\nError body: Retry-After: 30")
        assertEquals(LlmFailureKind(true, "HTTP 429", Duration.ofSeconds(30)), LlmFailureClassifier.classify(header))
    }
}

class ApiKeyResolutionTest {
    @Test
    fun `a blank environment variable is a missing key and keys are trimmed`() {
        val openai = LlmExplanationConfig(LlmProvider.OPENAI)
        val missing = kotlin.test.assertFailsWith<IllegalStateException> { resolveApiKey(openai) { "   " } }
        assertEquals(true, missing.message!!.contains("OpenAI API key missing"), missing.message)
        kotlin.test.assertFailsWith<IllegalStateException> { resolveApiKey(openai) { null } }
        assertEquals("sk-env", resolveApiKey(openai) { " sk-env\n" })
        assertEquals("sk-cfg", resolveApiKey(openai.copy(apiKey = " sk-cfg ")) { "sk-env" })
        // A blank configured key falls back to the environment.
        assertEquals("sk-env", resolveApiKey(openai.copy(apiKey = "  ")) { "sk-env" })
        val anthropic = LlmExplanationConfig(LlmProvider.ANTHROPIC)
        kotlin.test.assertFailsWith<IllegalStateException> { resolveApiKey(anthropic) { "" } }
        assertEquals("ak", resolveApiKey(anthropic) { name -> if (name == "ANTHROPIC_API_KEY") "ak" else null })
    }
}
