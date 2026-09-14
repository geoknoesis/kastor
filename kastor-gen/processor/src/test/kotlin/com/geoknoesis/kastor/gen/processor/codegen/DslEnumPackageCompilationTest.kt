package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdProperty
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdType
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclInValue
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.InstanceDslGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.EnumGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.ShaclEnumExtractor
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.prop
import com.geoknoesis.kastor.rdf.Iri
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** DSL setters for `sh:in` enums reference the enum type in the package it is generated into. */
class DslEnumPackageCompilationTest {

    private val probe = """
        package gen.dslenum.app

        import gen.dslenum.dsl.tasks
        import gen.dslenum.model.Status

        fun probe(): String {
            val graph = tasks { task("urn:t") { status(Status.Known.DONE) } }.build()
            return graph.getTriples().single { it.predicate.value == "https://example.test/status" }.obj.toString()
        }
    """.trimIndent()

    @Test
    fun `enum setters in a different package than the enums compile and write the member value`() {
        val logger = RecordingLogger()
        val context = JsonLdContext(
            prefixes = emptyMap(), typeMappings = emptyMap(),
            propertyMappings = mapOf("status" to JsonLdProperty(Iri(EX + "status"), JsonLdType.Iri(Iri(EX + "Status")))),
        )
        val status = prop("status").copy(
            inValues = listOf("open", "done"),
            inValuesTyped = listOf(ShaclInValue("open", isIri = false), ShaclInValue("done", isIri = false)),
        )
        val model = ShaclEnumExtractor(logger).enrich(OntologyModel(listOf(ShaclShape(EX + "TaskShape", EX + "Task", listOf(status))), context))
        val files = EnumGenerator(logger).generateEnums(model, "gen.dslenum.model").values +
            InstanceDslGenerator(logger).generate(
                InstanceDslRequest("tasks", model, "gen.dslenum.dsl", DslGenerationOptions(), enumPackage = "gen.dslenum.model")
            )
        val result = KotlinSourceCompiler.compile(files, mapOf("gen/dslenum/app/Probe.kt" to probe))
        result.assertOk()
        val written = result.classLoader().loadClass("gen.dslenum.app.ProbeKt").getMethod("probe").invoke(null) as String
        assertEquals(true, written.contains("done"), written)
    }
}
