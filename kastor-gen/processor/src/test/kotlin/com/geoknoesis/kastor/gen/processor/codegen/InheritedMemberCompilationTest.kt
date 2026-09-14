package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.processor.api.exceptions.InvalidConfigurationException
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
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.XSD_NS
import com.geoknoesis.kastor.gen.processor.testing.prop
import com.squareup.kotlinpoet.FileSpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Inherited and restated property shapes must produce one Kotlin member per inherited member with a signature
 * that is a valid override, and every generator (interfaces, wrappers, data classes, factories, writers, DSL)
 * must agree on it. The generated sources are compiled and executed.
 */
class InheritedMemberCompilationTest {

    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())

    private fun model(): OntologyModel = OntologyModel(
        listOf(
            // Single inheritance: restated path with looser declared cardinality, List parent vs single child,
            // different sh:name, optional parent narrowed to required.
            ShaclShape(
                EX + "AgentShape", EX + "Agent",
                listOf(
                    prop("name", minCount = 1),
                    prop("tags", maxCount = null),
                    prop("title"),
                    prop("nick"),
                ),
            ),
            ShaclShape(
                EX + "PersonShape", EX + "Person",
                listOf(
                    prop("name"),
                    prop("tags", maxCount = 1),
                    prop("title", name = "fullName"),
                    prop("nick", minCount = 1),
                    prop("age", datatype = "${XSD_NS}int"),
                ),
                parentClasses = listOf(EX + "Agent"),
            ),
            // Multiple inheritance, same path and name, different cardinality.
            ShaclShape(EX + "NamedShape", EX + "Named", listOf(prop("label"))),
            ShaclShape(EX + "TitledShape", EX + "Titled", listOf(prop("label", minCount = 1))),
            ShaclShape(EX + "DocShape", EX + "Doc", emptyList(), parentClasses = listOf(EX + "Named", EX + "Titled")),
            // Multiple inheritance, same path, different member names.
            ShaclShape(EX + "CaptionedShape", EX + "Captioned", listOf(prop("label", name = "caption"))),
            ShaclShape(EX + "FigureShape", EX + "Figure", emptyList(), parentClasses = listOf(EX + "Named", EX + "Captioned")),
            // Two property shapes on the same path inside one node shape.
            ShaclShape(
                EX + "ThingShape", EX + "Thing",
                listOf(prop("code", name = "beta"), prop("code", name = "alpha", minCount = 1)),
            ),
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
            InstanceDslRequest(dslName = "inherit", ontologyModel = model, packageName = "$pkg.dsl", options = DslGenerationOptions())
        )
        return files
    }

    private val probe = """
        package gen.members

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        fun probe(): String {
            fun p(local: String) = Iri("https://example.test/" + local)
            val g = MemoryGraph()
            val pat = Iri("urn:pat")
            g.addTriple(RdfTriple(pat, p("name"), Literal("Pat")))
            g.addTriple(RdfTriple(pat, p("tags"), Literal("x")))
            g.addTriple(RdfTriple(pat, p("title"), Literal("Dr")))
            g.addTriple(RdfTriple(pat, p("nick"), Literal("P")))
            g.addTriple(RdfTriple(pat, p("age"), Literal("41", Iri("http://www.w3.org/2001/XMLSchema#int"))))
            val doc = Iri("urn:doc")
            g.addTriple(RdfTriple(doc, p("label"), Literal("L")))
            val thing = Iri("urn:thing")
            g.addTriple(RdfTriple(thing, p("code"), Literal("c")))

            val person: Person = OntoMapper.materialize(RdfRef(pat, g), Person::class.java)
            val agent: Agent = person
            val nick: String = person.nick
            val name: String = person.name
            val tags: List<String> = person.tags
            val d: Doc = OntoMapper.materialize(RdfRef(doc, g), Doc::class.java)
            val label: String = d.label
            val f: Figure = OntoMapper.materialize(RdfRef(doc, g), Figure::class.java)
            val t: Thing = OntoMapper.materialize(RdfRef(thing, g), Thing::class.java)
            val alpha: String = t.alpha
            val record: Agent = PersonRecord(name = "Pat", tags = listOf("x"), title = "Dr", nick = "P", age = 41)
            val triples = PersonRecordFactory.toTriples(PersonRecord(name = "Pat", tags = listOf("x"), title = "Dr", nick = "P", age = 41), pat)
            return listOf(
                agent.name, name, tags.joinToString(), agent.title, nick, person.age, label, f.label, f.caption, alpha,
                record.title, triples.count { it.predicate == p("title") },
            ).joinToString("|")
        }
    """.trimIndent()

    @Test
    fun `inherited restated and duplicated property shapes compile and run through every generator`() {
        val logger = RecordingLogger()
        val files = generateAll(model(), "gen.members", logger)
        val result = KotlinSourceCompiler.compile(files, mapOf("gen/members/Probe.kt" to probe))
        result.assertOk()
        val output = result.classLoader().loadClass("gen.members.ProbeKt").getMethod("probe").invoke(null)
        assertEquals("Pat|Pat|x|Dr|P|41|L|L|L|c|Dr|1", output)
        assertTrue(logger.warnings.any { "fullName" in it && "title" in it }, logger.warnings.toString())
        assertTrue(logger.warnings.any { "alpha" in it && "beta" in it }, logger.warnings.toString())
    }

    @Test
    fun `interface for a restated path keeps the parent member name and a compatible type`() {
        val files = InterfaceGenerator(RecordingLogger(), ValidationAnnotations.NONE).generateInterfaces(model(), "gen.members")
        val person = files.getValue("Person").toString()
        assertTrue(person.contains("override val nick: String\n"), person)
        assertTrue(person.contains("override val tags: List<String>"), person)
        assertTrue(!person.contains("fullName"), person)
        val thing = files.getValue("Thing").toString()
        assertEquals(1, Regex("val (alpha|beta)").findAll(thing).count(), thing)
    }

    @Test
    fun `parents that declare incompatible signatures for one path fail at generation time`() {
        val model = OntologyModel(
            listOf(
                ShaclShape(EX + "ManyShape", EX + "Many", listOf(prop("tags", maxCount = null))),
                ShaclShape(EX + "OneShape", EX + "One", listOf(prop("tags"))),
                ShaclShape(EX + "BothShape", EX + "Both", emptyList(), parentClasses = listOf(EX + "Many", EX + "One")),
            ),
            emptyContext,
        )
        val e = assertFailsWith<InvalidConfigurationException> {
            InterfaceGenerator(RecordingLogger(), ValidationAnnotations.NONE).generateInterfaces(model, "x")
        }
        assertTrue(e.message!!.contains("${EX}tags") && e.message!!.contains("${EX}BothShape"), e.message)
    }
}
