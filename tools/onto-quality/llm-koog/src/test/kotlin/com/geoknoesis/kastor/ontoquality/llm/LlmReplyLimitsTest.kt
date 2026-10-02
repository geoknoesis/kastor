package com.geoknoesis.kastor.ontoquality.llm

import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.ontoquality.explanation.ExplanationOptions
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ValidationReport
import com.geoknoesis.kastor.rdf.shacl.ValidationStatistics
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Limits on what an LLM may send back, and redaction of every configured secret. */
class LlmReplyLimitsTest {
    private val refPattern = Regex("\"findingRef\":\"([0-9a-f]{64})\"")

    private class FakeSession(val reply: suspend (String) -> String?) : ExplanationLlmSession {
        var calls = 0

        override suspend fun complete(systemPrompt: String, userMessage: String): String? {
            calls++
            return reply(userMessage)
        }

        override fun close() = Unit
    }

    private fun enricher(session: FakeSession, config: LlmExplanationConfig = LlmExplanationConfig(provider = LlmProvider.OLLAMA, maxRetries = 0)) =
        DefaultQualityExplanationEnricher(config, { session }, { }, System::nanoTime, { it })

    private fun report(vararg messages: String): QualityReport {
        val violations =
            messages.mapIndexed { i, message ->
                ValidationViolation(
                    severity = ViolationSeverity.WARNING,
                    constraint = ShaclConstraint(ConstraintType.MIN_COUNT, severity = ViolationSeverity.WARNING),
                    focusNode = Iri("http://example.org/vocab/C$i"),
                    message = message,
                    shapeUri = "http://example.org/shapes#S$i",
                )
            }
        val raw =
            ValidationReport(
                isValid = false,
                violations = violations,
                warnings = emptyList(),
                statistics =
                    ValidationStatistics(
                        totalResources = violations.size,
                        validatedResources = violations.size,
                        totalConstraints = 1,
                        validatedConstraints = 1,
                        shapesProcessed = 1,
                        constraintsByType = emptyMap(),
                        violationsByType = emptyMap(),
                        warningsByType = emptyMap(),
                        averageValidationTimePerResource = Duration.ZERO,
                    ),
                validationTime = Duration.ZERO,
                validatedResources = violations.size,
                validatedConstraints = 1,
            )
        return QualityReport.from(raw, emptyList())
    }

    @Test
    fun `every field of a reply is capped`() {
        runBlocking {
            val long = "x".repeat(3_000)
            val session =
                FakeSession { message ->
                    val ref = refPattern.find(message)!!.groupValues[1]
                    val actions = (1..30).joinToString(",") { JsonPrimitive("$it $long").toString() }
                    "{\"schemaVersion\":1,\"items\":[{\"findingRef\":\"$ref\",\"summary\":\"$long\",\"whyItMatters\":\"$long\"," +
                        "\"suggestedActions\":[$actions],\"confidenceNote\":\"$long\"}]}"
                }
            val explanation = enricher(session).enrich(report("one"), ExplanationOptions()).explanations.single()
            val limits = DefaultQualityExplanationEnricher
            assertEquals(limits.MAX_REPLY_FIELD_CHARS, explanation.summary.length)
            assertTrue(explanation.summary.endsWith(limits.TRUNCATED), explanation.summary.takeLast(20))
            assertEquals(limits.MAX_REPLY_FIELD_CHARS, explanation.whyItMatters!!.length)
            assertEquals(limits.MAX_REPLY_FIELD_CHARS, explanation.confidenceNote!!.length)
            assertEquals(limits.MAX_REPLY_ACTIONS, explanation.suggestedActions.size)
            assertTrue(explanation.suggestedActions.all { it.length == limits.MAX_REPLY_FIELD_CHARS }, explanation.suggestedActions.map { it.length }.toString())
        }
    }

    @Test
    fun `a reply longer than the limit is a failure and is not parsed or echoed in a repair request`() {
        runBlocking {
            val session = FakeSession { "{\"schemaVersion\":1,\"items\":[],\"padding\":\"" + "y".repeat(DefaultQualityExplanationEnricher.MAX_REPLY_CHARS) + "\"}" }
            val explained = enricher(session).enrich(report("one"), ExplanationOptions())
            assertTrue(explained.explanations.isEmpty())
            assertTrue(explained.failures.single().reason.contains("exceeds the limit"), explained.failures.single().reason)
            assertEquals(1, session.calls, "no repair request for an oversized reply")
        }
    }

    @Test
    fun `requests carry the configured maximum of output tokens`() {
        assertEquals(LlmExplanationConfig.DEFAULT_MAX_OUTPUT_TOKENS, LlmExplanationConfig(provider = LlmProvider.OPENAI).maxOutputTokens)
        val prompt = explanationPrompt("system", "user", 1234)
        assertEquals(1234, prompt.params.maxTokens)
        assertEquals(2, prompt.messages.size)
        assertFailsWith<IllegalArgumentException> { LlmExplanationConfig(provider = LlmProvider.OPENAI, maxOutputTokens = 0) }
    }

    @Test
    fun `a short API key is redacted like any other`() {
        runBlocking {
            val key = "k3y"
            val config = LlmExplanationConfig(provider = LlmProvider.OLLAMA, apiKey = key, maxRetries = 0)
            val session = FakeSession { throw IllegalStateException("401 Unauthorized: invalid api key k3y for request") }
            val reason = enricher(session, config).enrich(report("one"), ExplanationOptions()).failures.single().reason
            assertFalse(reason.contains(key), reason)
            assertTrue(reason.contains("invalid api key ***"), reason)
        }
    }

    @Test
    fun `credentials in URLs are redacted in failure reasons and in the config text`() {
        runBlocking {
            val base = "http://alice:s3cr3t-pw@ollama.internal:11434"
            val config = LlmExplanationConfig(provider = LlmProvider.OLLAMA, baseUrl = base, maxRetries = 0)
            assertFalse(config.toString().contains("s3cr3t-pw") || config.toString().contains("alice"), config.toString())
            assertTrue(config.toString().contains("http://***@ollama.internal:11434"), config.toString())
            assertEquals(base, config.baseUrl)

            val session = FakeSession { throw IllegalStateException("Connection refused: $base/api/chat (password s3cr3t-pw), also https://bob:pw@other.example/x") }
            val reason = enricher(session, config).enrich(report("one"), ExplanationOptions()).failures.single().reason
            assertFalse(reason.contains("s3cr3t-pw") || reason.contains("alice") || reason.contains("bob:pw"), reason)
            assertTrue(reason.contains("http://***@ollama.internal:11434/api/chat") && reason.contains("https://***@other.example/x"), reason)
        }
    }
}
