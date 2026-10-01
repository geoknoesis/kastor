package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassFactoryGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassWriterGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InstanceDslGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.utils.GenerationNames
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.prop
import com.squareup.kotlinpoet.FileSpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A `sh:deactivated` parent shape still declares its members with their Kotlin types; only its validation
 * constraints are dropped. A subtype that restates a path with looser (or omitted) cardinality must keep a signature
 * that is a valid override of the inherited declaration.
 */
class DeactivatedParentCompilationTest {

    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())

    private fun model(): OntologyModel = OntologyModel(
        listOf(
            ShaclShape(
                EX + "AgentShape", EX + "Agent",
                listOf(
                    prop("name", minCount = 1), // required single: `val name: String`
                    prop("nick"),               // optional single: `val nick: String?`
                    prop("tags", maxCount = null), // list
                    prop("code", minCount = 1),
                ),
                deactivated = true,
            ),
            ShaclShape(
                EX + "PersonShape", EX + "Person",
                listOf(
                    prop("name"),                  // restated without sh:minCount
                    prop("nick", maxCount = null), // restated without sh:maxCount
                    prop("tags", maxCount = 1),
                ),
                parentClasses = listOf(EX + "Agent"),
            ),
            // Not restated at all, two levels down.
            ShaclShape(EX + "StudentShape", EX + "Student", listOf(prop("name", maxCount = null)), parentClasses = listOf(EX + "Person")),
        ),
        emptyContext,
    )

    private fun generateAll(model: OntologyModel, pkg: String, logger: RecordingLogger): List<FileSpec> {
        val files = mutableListOf<FileSpec>()
        files += InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(model, pkg).values
        files += OntologyWrapperGenerator(logger).generateWrappers(model, pkg).values
        files += DataClassGenerator(logger, "Record", NestedMode.INTERFACE, true, ValidationAnnotations.NONE)
            .generateDataClasses(model, pkg).values
        files += DataClassFactoryGenerator(logger, "Record", NestedMode.INTERFACE, DataClassWriterGenerator(logger, "Record", NestedMode.INTERFACE))
            .generateFactories(model, pkg).values
        files += InstanceDslGenerator(logger).generate(
            InstanceDslRequest(dslName = "deact", ontologyModel = model, packageName = "$pkg.dsl", options = DslGenerationOptions())
        )
        return files
    }

    private val probe = """
        package gen.deact

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        fun probe(): String {
            fun p(local: String) = Iri("https://example.test/" + local)
            val g = MemoryGraph()
            val pat = Iri("urn:pat")
            g.addTriple(RdfTriple(pat, p("name"), Literal("Pat")))
            g.addTriple(RdfTriple(pat, p("tags"), Literal("x")))
            g.addTriple(RdfTriple(pat, p("code"), Literal("c")))
            val person: Person = OntoMapper.materialize(RdfRef(pat, g), Person::class.java)
            val agent: Agent = person
            val name: String = person.name
            val nick: String? = person.nick
            val tags: List<String> = person.tags
            val student: Student = OntoMapper.materialize(RdfRef(pat, g), Student::class.java)
            val studentName: String = student.name
            return listOf(agent.name, name, nick, tags.joinToString(), person.code, studentName).joinToString("|")
        }
    """.trimIndent()

    @Test
    fun `a hierarchy below a deactivated parent compiles and runs through every generator`() {
        val logger = RecordingLogger()
        val files = generateAll(model(), "gen.deact", logger)
        val result = KotlinSourceCompiler.compile(files, mapOf("gen/deact/Probe.kt" to probe))
        result.assertOk()
        val output = result.classLoader().loadClass("gen.deact.ProbeKt").getMethod("probe").invoke(null)
        assertEquals("Pat|Pat|null|x|c|Pat", output)
    }

    @Test
    fun `the signature stays the inherited one while the deactivated constraints are dropped`() {
        val model = model()
        val members = GenerationNames.effectiveMembers(model, GenerationNames.superTypes(model)).getValue(EX + "Person")
        val name = members.single { it.path == EX + "name" }
        assertEquals(1, name.typing.minCount, "the member overrides a required `String`")
        assertEquals(1, name.typing.maxCount)
        assertNull(name.constraints.minCount, "the deactivated parent's sh:minCount is not validated")
        val nick = members.single { it.path == EX + "nick" }
        assertEquals(1, nick.typing.maxCount, "the member overrides a single value")
        assertNull(nick.constraints.maxCount)

        val person = InterfaceGenerator(RecordingLogger(), ValidationAnnotations.NONE).generateInterfaces(model, "gen.deact")
            .getValue("Person").toString()
        assertTrue(person.contains("override val name: String\n"), person)
        assertTrue(person.contains("override val nick: String?"), person)
    }
}
