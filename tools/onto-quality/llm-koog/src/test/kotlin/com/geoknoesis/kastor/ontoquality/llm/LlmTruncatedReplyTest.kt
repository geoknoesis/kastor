package com.geoknoesis.kastor.ontoquality.llm

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
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
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A reply cut at the output-token limit is reported as such and never followed by a repair request. */
class LlmTruncatedReplyTest {
    private val refPattern = Regex("\"findingRef\":\"([0-9a-f]{64})\"")

    /** Fake provider: [reply] decides per call and says why the reply ended. */
    private class FakeSession(val reply: (call: Int, userMessage: String) -> LlmReply) : ExplanationLlmSession {
        val messages = ArrayList<String>()

        override suspend fun complete(systemPrompt: String, userMessage: String): String? = completeReply(systemPrompt, userMessage).text

        override suspend fun completeReply(systemPrompt: String, userMessage: String): LlmReply {
            messages += userMessage
            return reply(messages.size, userMessage)
        }

        override fun close() = Unit
    }

    private fun enricher(session: FakeSession, config: LlmExplanationConfig) =
        DefaultQualityExplanationEnricher(config, { session }, { }, System::nanoTime, { it })

    private fun validReply(userMessage: String): String =
        refPattern.findAll(userMessage).joinToString(",", prefix = "{\"schemaVersion\":1,\"items\":[", postfix = "]}") {
            "{\"findingRef\":\"${it.groupValues[1]}\",\"summary\":\"explained\"}"
        }

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
    fun `a reply cut at the token limit is reported and not repaired`() {
        runBlocking {
            for (finishReason in listOf("length", "max_tokens", "MAX_TOKENS")) {
                val session = FakeSession { _, message -> LlmReply(validReply(message).dropLast(9), finishReason, outputTokens = 512) }
                val config = LlmExplanationConfig(provider = LlmProvider.OPENAI, apiKey = "test-key-0123456789", maxRetries = 0, maxOutputTokens = 512)
                val explained = enricher(session, config).enrich(report("one", "two"), ExplanationOptions())

                assertTrue(explained.explanations.isEmpty())
                val failure = explained.failures.single()
                assertEquals(2, failure.findingRefs.size)
                assertTrue(failure.reason.startsWith("reply truncated at 512 tokens; raise --llm-max-output-tokens"), failure.reason)
                assertEquals(1, session.messages.size, "no paid repair request under the same limit ($finishReason)")
            }
        }
    }

    @Test
    fun `without a token count the configured limit is reported`() {
        runBlocking {
            val session = FakeSession { _, _ -> LlmReply("{\"schemaVersion\":1,\"items\":[{\"findingRef\":\"ab", "length") }
            val config = LlmExplanationConfig(provider = LlmProvider.ANTHROPIC, apiKey = "test-key-0123456789", maxRetries = 0, maxOutputTokens = 300)
            val reason = enricher(session, config).enrich(report("one"), ExplanationOptions()).failures.single().reason
            assertTrue(reason.startsWith("reply truncated at 300 tokens; raise --llm-max-output-tokens"), reason)
        }
    }

    @Test
    fun `an unparseable reply that was not cut is still repaired once`() {
        runBlocking {
            val session = FakeSession { call, message -> if (call == 1) LlmReply("not json", "stop") else LlmReply(validReply(message), "stop") }
            val config = LlmExplanationConfig(provider = LlmProvider.OPENAI, apiKey = "test-key-0123456789", maxRetries = 0)
            val explained = enricher(session, config).enrich(report("one"), ExplanationOptions())
            // The repair message does not carry the findings, so the repaired reply names no ref of the batch.
            assertEquals(2, session.messages.size)
            assertFalse(explained.failures.any { it.reason.contains("truncated") }, explained.failures.toString())
        }
    }

    @Test
    fun `a complete reply is used whatever its finish reason`() {
        runBlocking {
            val session = FakeSession { _, message -> LlmReply(validReply(message), "length", 512) }
            val config = LlmExplanationConfig(provider = LlmProvider.OPENAI, apiKey = "test-key-0123456789", maxRetries = 0)
            val explained = enricher(session, config).enrich(report("one"), ExplanationOptions())
            assertEquals(1, explained.explanations.size)
            assertTrue(explained.failures.isEmpty())
        }
    }

    @Test
    fun `consecutive truncated batches stop the run`() {
        runBlocking {
            val session = FakeSession { _, _ -> LlmReply("{\"schemaVersion\":1,\"items\":[", "length", 64) }
            val config = LlmExplanationConfig(provider = LlmProvider.OPENAI, apiKey = "test-key-0123456789", maxRetries = 0, circuitBreakerThreshold = 2, maxOutputTokens = 64)
            val explained = enricher(session, config).enrich(report("a", "b", "c", "d"), ExplanationOptions(batchSize = 1))
            assertEquals(2, session.messages.size, "the third and fourth batch are not sent")
            assertEquals(4, explained.failures.size)
            assertTrue(explained.failures[2].reason.contains("circuit breaker"), explained.failures[2].reason)
        }
    }

    @Test
    fun `the finish reason and token count of a Koog response are kept`() {
        val cut = replyOf(Message.Assistant("{\"items\":[", ResponseMetaInfo.Empty.copy(outputTokensCount = 8192), "length"))
        assertEquals(LlmReply("{\"items\":[", "length", 8192), cut)
        assertTrue(cut.truncatedAtLimit)
        assertFalse(replyOf(Message.Assistant("{}", ResponseMetaInfo.Empty, "stop")).truncatedAtLimit)
        // Ollama through Koog 0.8.0: no finish reason at all.
        assertFalse(replyOf(Message.Assistant("{}", ResponseMetaInfo.Empty)).truncatedAtLimit)
        assertNull(replyOf(null).text)
    }

    @Test
    fun `only Ollama cannot be given the output-token limit, and says so`() {
        assertTrue(LlmProvider.OPENAI.honoursMaxOutputTokens)
        assertTrue(LlmProvider.ANTHROPIC.honoursMaxOutputTokens)
        assertFalse(LlmProvider.OLLAMA.honoursMaxOutputTokens)
        assertNull(LlmExplanationConfig(provider = LlmProvider.OPENAI).maxOutputTokensNotice())
        assertNull(LlmExplanationConfig(provider = LlmProvider.ANTHROPIC).maxOutputTokensNotice())
        val notice = LlmExplanationConfig(provider = LlmProvider.OLLAMA, maxOutputTokens = 1234).maxOutputTokensNotice()!!
        assertTrue(notice.contains("1234 output tokens is not sent to ollama"), notice)
        assertTrue(notice.contains("500000 characters"), notice)
    }
}
