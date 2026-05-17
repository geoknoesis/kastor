package com.geoknoesis.kastor.ontoquality.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ExplanationPayloadTest {
    @Test
    fun parsesLlmJson() {
        val json =
            """
            {"schemaVersion":1,"items":[{"findingRef":"abc","summary":"s","whyItMatters":null,"suggestedActions":["x"],"confidenceNote":null}]}
            """.trimIndent()
        val p = explanationJson.decodeFromString(LlmExplanationPayload.serializer(), json)
        assertEquals(1, p.schemaVersion)
        assertEquals(1, p.items.size)
        assertEquals("abc", p.items[0].findingRef)
        assertEquals("s", p.items[0].summary)
    }

    @Test
    fun roundTripPayloadShape() {
        val payload =
            LlmExplanationPayload(
                items =
                    listOf(
                        LlmExplanationItemPayload(
                            findingRef = "deadbeef",
                            summary = "line\ntwo",
                            suggestedActions = listOf("a\"b"),
                        ),
                    ),
            )
        val s = explanationJson.encodeToString(LlmExplanationPayload.serializer(), payload)
        val back = explanationJson.decodeFromString(LlmExplanationPayload.serializer(), s)
        assertNotNull(back)
        assertEquals("deadbeef", back.items[0].findingRef)
    }
}
