package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.api.model.*
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.EnumGenerator
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnumGeneratorTest {
    private val logger = object : KSPLogger {
        override fun logging(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun info(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun warn(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun error(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun exception(e: Throwable) {}
    }
    private fun model(enum: EnumModel) = OntologyModel(
        shapes = emptyList(),
        context = JsonLdContext(emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap()),
        enums = listOf(enum),
    )
    private fun render(fs: com.squareup.kotlinpoet.FileSpec) =
        java.io.StringWriter().also { fs.writeTo(it) }.toString()

    @Test
    fun `iri enum emits sealed interface with Known Unknown and from`() {
        val e = EnumModel("DocumentStatus", "https://ex/#DocumentStatus", EnumMemberKind.IRI,
            listOf(EnumMember("DRAFT", iri = "https://ex/#DRAFT"), EnumMember("ACTIVE", iri = "https://ex/#ACTIVE")))
        val code = render(EnumGenerator(logger).generateEnums(model(e), "com.example")["DocumentStatus"]!!)
        assertTrue(code.contains("sealed interface DocumentStatus"))
        assertTrue(code.contains("val iri: Iri"))
        assertTrue(code.contains("enum class Known"))
        assertTrue(code.contains("DRAFT(Iri(\"https://ex/#DRAFT\"))"))
        assertTrue(code.contains("data class Unknown"))
        assertTrue(code.contains("fun from(iri: Iri): DocumentStatus"))
    }

    @Test
    fun `literal enum emits code-based sealed interface`() {
        val e = EnumModel("Priority", null, EnumMemberKind.LITERAL,
            listOf(EnumMember("LOW", code = "LOW"), EnumMember("HIGH", code = "HIGH")))
        val code = render(EnumGenerator(logger).generateEnums(model(e), "com.example")["Priority"]!!)
        assertTrue(code.contains("sealed interface Priority"))
        assertTrue(code.contains("val code: String"))
        assertTrue(code.contains("LOW(\"LOW\")"))
        assertTrue(code.contains("fun from(code: String): Priority"))
    }
}
