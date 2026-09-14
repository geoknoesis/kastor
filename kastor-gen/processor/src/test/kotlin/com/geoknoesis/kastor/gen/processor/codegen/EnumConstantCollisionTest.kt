package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdProperty
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdType
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclInValue
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.EnumGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.ShaclEnumExtractor
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.prop
import com.geoknoesis.kastor.rdf.Iri
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** `sh:in` members whose Kotlin constant names collide get deterministic suffixes and keep their values. */
class EnumConstantCollisionTest {

    private val context = JsonLdContext(
        prefixes = emptyMap(),
        typeMappings = emptyMap(),
        propertyMappings = mapOf("status" to JsonLdProperty(Iri(EX + "status"), JsonLdType.Iri(Iri(EX + "Status")))),
    )

    private val probe = """
        package gen.enums

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        fun probe(): String {
            val g = MemoryGraph()
            val n = Iri("urn:task")
            g.addTriple(RdfTriple(n, Iri("https://example.test/status"), Literal("in_progress")))
            g.addTriple(RdfTriple(n, Iri("https://example.test/kind"), Iri("http://b.test/Open")))
            val task = OntoMapper.materialize(RdfRef(n, g), Task::class.java)
            return Status.Known.entries.joinToString(",") { it.name + "=" + it.code } + ";" +
                Kind.Known.entries.joinToString(",") { it.name + "=" + it.iri.value } + ";" +
                (task.status as Status.Known).name + ";" + (task.kind as Kind.Known).name
        }
    """.trimIndent()

    @Test
    fun `colliding enum constants are de-duplicated deterministically`() {
        val logger = RecordingLogger()
        val status = prop("status").copy(
            inValues = listOf("in-progress", "in_progress", "Draft", "draft"),
            inValuesTyped = listOf("in-progress", "in_progress", "Draft", "draft").map { ShaclInValue(it, isIri = false) },
        )
        val kind = prop("kind", targetClass = EX + "Kind").copy(
            inValues = listOf("http://a.test/x#Open", "http://b.test/Open"),
            inValuesTyped = listOf(ShaclInValue("http://a.test/x#Open", isIri = true), ShaclInValue("http://b.test/Open", isIri = true)),
        )
        val model = ShaclEnumExtractor(logger).enrich(OntologyModel(listOf(ShaclShape(EX + "TaskShape", EX + "Task", listOf(status, kind))), context))
        val files = EnumGenerator(logger).generateEnums(model, "gen.enums").values +
            InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(model, "gen.enums", fallbackUnshapedToIri = true).values +
            OntologyWrapperGenerator(logger).generateWrappers(model, "gen.enums", fallbackUnshapedToIri = true).values
        val result = KotlinSourceCompiler.compile(files, mapOf("gen/enums/Probe.kt" to probe))
        result.assertOk()
        assertEquals(
            "IN_PROGRESS=in-progress,IN_PROGRESS_2=in_progress,DRAFT=Draft,DRAFT_2=draft;" +
                "OPEN=http://a.test/x#Open,OPEN_2=http://b.test/Open;IN_PROGRESS_2;OPEN_2",
            result.classLoader().loadClass("gen.enums.ProbeKt").getMethod("probe").invoke(null),
        )
    }
}
