package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.api.model.*
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.EnumGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.ShaclEnumExtractor
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnumPipelineTest {
    private val logger = object : KSPLogger {
        override fun logging(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun info(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun warn(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun error(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun exception(e: Throwable) {}
    }

    @Test
    fun `extractor then generator yields an enum file for a sh-class sh-in property`() {
        val prop = ShaclProperty(
            path = "https://ex/#status", name = "status", description = "",
            datatype = null, targetClass = "https://ex/#DocumentStatus", minCount = 0, maxCount = 1,
            inValuesTyped = listOf(ShaclInValue("https://ex/#DRAFT", isIri = true)))
        val model = OntologyModel(
            listOf(ShaclShape("https://ex/#DocShape", "https://ex/#Doc", listOf(prop))),
            JsonLdContext(emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap()))
        val enriched = ShaclEnumExtractor(logger).enrich(model)
        val files = EnumGenerator(logger).generateEnums(enriched, "com.example")
        assertTrue(files.containsKey("DocumentStatus"))
    }
}
