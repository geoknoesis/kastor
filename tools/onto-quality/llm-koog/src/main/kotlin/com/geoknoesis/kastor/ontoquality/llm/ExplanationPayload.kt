package com.geoknoesis.kastor.ontoquality.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class LlmExplanationPayload(
    @SerialName("schemaVersion")
    val schemaVersion: Int = 1,
    val items: List<LlmExplanationItemPayload>,
)

@Serializable
internal data class LlmExplanationItemPayload(
    val findingRef: String,
    val summary: String,
    val whyItMatters: String? = null,
    val suggestedActions: List<String> = emptyList(),
    val confidenceNote: String? = null,
)

internal val explanationJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
