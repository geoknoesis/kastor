package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclInValue
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.InstanceDslGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.ShaclInCode
import com.geoknoesis.kastor.gen.processor.internal.parsers.ShaclParser
import com.geoknoesis.kastor.gen.processor.internal.utils.GenerationNames
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.prop
import com.squareup.kotlinpoet.FileSpec
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A subtype's `sh:in` is conjoined with the inherited one: a value must be a member of both lists. Language tags
 * are compared ignoring case (BCP 47), and lists without a common member reject every value instead of emitting no
 * check at all.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShInIntersectionTest {

    private val shacl = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix ex: <https://example.test/> .
        ex:AgentShape a sh:NodeShape ; sh:targetClass ex:Agent ;
            sh:property [ sh:path ex:label ; sh:nodeKind sh:Literal ; sh:in ( "x"@en "y" 5 ) ] .
        ex:Person rdfs:subClassOf ex:Agent .
        ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;
            sh:property [ sh:path ex:label ; sh:nodeKind sh:Literal ; sh:in ( "x"@EN "z" 5 ) ] .
        ex:Robot rdfs:subClassOf ex:Agent .
        ex:RobotShape a sh:NodeShape ; sh:targetClass ex:Robot ;
            sh:property [ sh:path ex:label ; sh:nodeKind sh:Literal ; sh:in ( "q" 7 ) ] .
    """.trimIndent()

    private val probe = """
        package gen.shinx

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        private val label = Iri("https://example.test/label")
        private val integer = Iri("http://www.w3.org/2001/XMLSchema#integer")

        private val values: List<Pair<String, RdfTerm>> = listOf(
            "x@en" to LangString("x", "en"),
            "x@EN" to LangString("x", "EN"),
            "y" to Literal("y"),
            "z" to Literal("z"),
            "q" to Literal("q"),
            "5" to TypedLiteral("5", integer),
            "7" to TypedLiteral("7", integer),
        )

        private fun <T : Any> check(type: Class<T>, validate: (T) -> ValidationResult): String =
            values.joinToString("|") { (name, value) ->
                val g = MemoryGraph()
                val n = Iri("urn:n")
                g.addTriple(RdfTriple(n, label, value))
                name + "=" + (if (validate(OntoMapper.materialize(RdfRef(n, g), type)) is ValidationResult.Violations) "in" else "ok")
            }

        fun agent(): String = check(Agent::class.java) { (it as AgentWrapper).validate() }
        fun person(): String = check(Person::class.java) { (it as PersonWrapper).validate() }
        fun robot(): String = check(Robot::class.java) { (it as RobotWrapper).validate() }
        fun robotWithoutValue(): String {
            val robot = OntoMapper.materialize(RdfRef(Iri("urn:n"), MemoryGraph()), Robot::class.java)
            return if ((robot as RobotWrapper).validate() is ValidationResult.Violations) "in" else "ok"
        }
    """.trimIndent()

    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())
    private val logger = RecordingLogger()
    private lateinit var model: OntologyModel
    private lateinit var result: KotlinSourceCompiler.Result

    @BeforeAll
    fun compile() {
        model = OntologyModel(ShaclParser(logger).parseShaclContent(shacl), emptyContext)
        val files = mutableListOf<FileSpec>()
        files += InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(model, "gen.shinx").values
        files += OntologyWrapperGenerator(logger, ValidationMode.EMBEDDED).generateWrappers(model, "gen.shinx").values
        // The instance DSL of the same model (its setters and validate() check sh:in too) must compile as well.
        files += InstanceDslGenerator(logger).generate(
            InstanceDslRequest(dslName = "shinx", ontologyModel = model, packageName = "gen.shinx.dsl", options = DslGenerationOptions())
        )
        result = KotlinSourceCompiler.compile(files, mapOf("gen/shinx/Probe.kt" to probe))
        result.assertOk()
    }

    private val loader by lazy { result.classLoader() }
    private fun call(function: String): Any? = loader.loadClass("gen.shinx.ProbeKt").getMethod(function).invoke(null)

    @Test
    fun `the parent accepts its own members`() {
        assertEquals("x@en=ok|x@EN=ok|y=ok|z=in|q=in|5=ok|7=in", call("agent"))
    }

    @Test
    fun `language tags that differ only in case denote the same member of both lists`() {
        // "x"@en (parent) and "x"@EN (child) are one member; "y" and "z" are each in one list only.
        assertEquals("x@en=ok|x@EN=ok|y=in|z=in|q=in|5=ok|7=in", call("person"))
    }

    @Test
    fun `lists without a common member reject every value`() {
        assertEquals("x@en=in|x@EN=in|y=in|z=in|q=in|5=in|7=in", call("robot"))
        assertEquals("ok", call("robotWithoutValue"), "sh:in constrains value nodes: no value, no violation")
    }

    @Test
    fun `an empty intersection is reported when the code is generated`() {
        val warning = logger.warnings.filter { "RobotShape" in it && "sh:in" in it }
        assertTrue(warning.isNotEmpty(), logger.warnings.toString())
        assertTrue(warning.all { "https://example.test/label" in it && "every value" in it }, warning.toString())
        assertTrue(logger.warnings.none { "PersonShape" in it && "every value" in it }, logger.warnings.toString())

        val members = GenerationNames.effectiveMembers(model, GenerationNames.superTypes(model))
        assertEquals(emptyList(), members.getValue("https://example.test/Robot").single().constraints.inValuesTyped)
        assertEquals(
            listOf("x", "5"),
            members.getValue("https://example.test/Person").single().constraints.inValuesTyped?.map { it.value },
        )
    }

    @Test
    fun `members of hand-built models are intersected ignoring the case of language tags`() {
        val langString = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"
        fun member(value: String, language: String) = ShaclInValue(value, isIri = false, datatype = langString, language = language)
        fun shape(local: String, parent: String?, vararg members: ShaclInValue) = ShaclShape(
            EX + local + "Shape", EX + local,
            listOf(prop("label", datatype = null).copy(inValues = members.map { it.value }, inValuesTyped = members.toList())),
            parentClasses = listOfNotNull(parent?.let { EX + it }),
        )
        val handBuilt = OntologyModel(
            listOf(
                shape("Agent", null, member("chat", "en-GB"), member("chat", "fr"), member("dog", "en")),
                shape("Person", "Agent", member("chat", "EN-gb"), member("dog", "de")),
            ),
            emptyContext,
        )
        val label = GenerationNames.effectiveMembers(handBuilt, GenerationNames.superTypes(handBuilt)).getValue(EX + "Person").single()
        assertEquals(listOf(member("chat", "EN-gb")), label.constraints.inValuesTyped, "\"chat\"@en-GB and \"chat\"@EN-gb are one member")
        assertEquals(listOf("chat"), label.constraints.inValues)
    }

    @Test
    fun `no sh-in emits no check and an empty sh-in a check that nothing passes`() {
        assertNull(ShaclInCode.members(null, null, iriValued = false))
        assertEquals(emptyList(), ShaclInCode.members(emptyList(), emptyList(), iriValued = false))
        assertEquals(emptyList(), ShaclInCode.members(null, emptyList(), iriValued = true))
        assertEquals("false", ShaclInCode.isMember("value", emptyList(), null).toString())
        assertEquals(
            listOf(ShaclInValue("a", isIri = true)),
            ShaclInCode.members(null, listOf("a"), iriValued = true),
        )
    }
}
