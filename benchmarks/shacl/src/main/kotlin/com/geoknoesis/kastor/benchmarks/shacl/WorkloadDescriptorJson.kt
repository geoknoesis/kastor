package com.geoknoesis.kastor.benchmarks.shacl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WorkloadDescriptorJson(
    val id: String,
    val tier: String? = null,
    val validationProfile: String? = null,
    val comparisonMode: String? = null,
    @SerialName("pathsRelativeTo") val pathsRelativeTo: String? = null,
    @SerialName("dataTurtle") val dataTurtle: String,
    @SerialName("shapesTurtle") val shapesTurtle: String,
    val expectedConforms: Boolean? = null,
    val notes: String? = null,
)
