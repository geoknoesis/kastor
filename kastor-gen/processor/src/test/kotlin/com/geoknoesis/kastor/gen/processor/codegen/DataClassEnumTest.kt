package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.processor.api.model.*
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassFactoryGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassWriterGenerator
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DataClassEnumTest {
    private val logger = object : KSPLogger {
        override fun logging(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun info(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun warn(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun error(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun exception(e: Throwable) {}
    }
    private fun model(): OntologyModel {
        val prop = ShaclProperty(
            path = "https://ex/#status", name = "status", description = "",
            datatype = null, targetClass = "https://ex/#DocumentStatus", minCount = 0, maxCount = 1,
            enumName = "DocumentStatus")
        val enum = EnumModel("DocumentStatus", "https://ex/#DocumentStatus", EnumMemberKind.IRI,
            listOf(EnumMember("DRAFT", iri = "https://ex/#DRAFT")))
        return OntologyModel(
            listOf(ShaclShape("https://ex/#DocShape", "https://ex/#Doc", listOf(prop))),
            JsonLdContext(emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap()),
            enums = listOf(enum))
    }
    private fun render(fs: com.squareup.kotlinpoet.FileSpec) = java.io.StringWriter().also { fs.writeTo(it) }.toString()

    @Test
    fun `factory converts enum read with from`() {
        val gen = DataClassFactoryGenerator(logger, suffix = "Record", nestedMode = NestedMode.DATA_CLASS, writerGenerator = null)
        val code = render(gen.generateFactories(model(), "com.example").getValue("DocRecordFactory"))
        assertTrue(code.contains("DocumentStatus.from("))
    }

    @Test
    fun `writer emits enum iri`() {
        val gen = DataClassWriterGenerator(logger, suffix = "Record", nestedMode = NestedMode.DATA_CLASS)
        // The writer is exercised through the factory with writeSupport; assert via the factory output.
        val factory = DataClassFactoryGenerator(logger, suffix = "Record", nestedMode = NestedMode.DATA_CLASS, writerGenerator = gen)
        val code = render(factory.generateFactories(model(), "com.example").getValue("DocRecordFactory"))
        assertTrue(code.contains(".iri"))
    }
}
