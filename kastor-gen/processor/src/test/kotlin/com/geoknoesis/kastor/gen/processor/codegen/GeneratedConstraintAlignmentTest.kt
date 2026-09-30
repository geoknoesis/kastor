package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.api.model.ClassBuilderModel
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.PropertyBuilderModel
import com.geoknoesis.kastor.gen.processor.api.model.PropertyConstraints
import com.geoknoesis.kastor.gen.processor.api.model.ShaclInValue
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.ValidationCodeGenerator
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.XSD_NS
import com.geoknoesis.kastor.gen.processor.testing.prop
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Compiles and runs generated validation code to check it follows SHACL for values of an unexpected term kind:
 * - embedded wrapper validation applies `sh:pattern` / `sh:minLength` / `sh:maxLength` to IRIs (their string) and
 *   reports them for blank nodes; literal `sh:in` compares every value node, so an IRI is never a member;
 * - DSL builder validation reports a value that cannot be compared with a numeric bound, as embedded validation does.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeneratedConstraintAlignmentTest {

    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())
    private lateinit var result: KotlinSourceCompiler.Result

    private fun shape() = ShaclShape(
        EX + "NoteShape", EX + "Note",
        listOf(
            prop("code", datatype = null).copy(pattern = "^u", minLength = 2, maxLength = 6),
            prop("nick", datatype = null).copy(minLength = 2, maxLength = 2),
            prop("status", datatype = null).copy(
                inValues = listOf("draft", "final"),
                inValuesTyped = listOf(ShaclInValue("draft", false, "${XSD_NS}string"), ShaclInValue("final", false, "${XSD_NS}string")),
            ),
        ),
    )

    private val probe = """
        package gen.align

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        private fun p(local: String) = Iri("https://example.test/" + local)

        private fun check(g: MemoryGraph, n: RdfResource): String {
            val r = (OntoMapper.materialize(RdfRef(n, g), Note::class.java) as NoteWrapper).validate()
            if (r !is ValidationResult.Violations) return "ok"
            return r.items.map { it.constraintIri.value.substringAfter('#') + ":" + it.path!!.value.substringAfterLast('/') }
                .sorted().joinToString(",")
        }

        fun wrapper(): String {
            val g = MemoryGraph()
            fun node(iri: String, pred: String, value: RdfTerm): Iri =
                Iri(iri).also { g.addTriple(RdfTriple(it, p(pred), value)) }
            val results = listOf(
                "literalOk=" + check(g, node("urn:a", "code", Literal("uuu"))),
                "iriOk=" + check(g, node("urn:b", "code", Iri("urn:x"))),
                "iriLong=" + check(g, node("urn:c", "code", Iri("urn:toolong"))),
                "iriPattern=" + check(g, node("urn:d", "code", Iri("a:bc"))),
                "blank=" + check(g, node("urn:e", "code", BlankNode("b1"))),
                "inOk=" + check(g, node("urn:f", "status", Literal("draft"))),
                "inIri=" + check(g, node("urn:g", "status", Iri("urn:draft"))),
                "inBlank=" + check(g, node("urn:h", "status", BlankNode("b2"))),
                // SHACL string lengths count characters (code points), not UTF-16 units.
                "emojiTwo=" + check(g, node("urn:i", "nick", Literal("\uD83D\uDE00\uD83D\uDE00"))),
                "emojiOne=" + check(g, node("urn:j", "nick", Literal("\uD83D\uDE00"))),
            )
            return results.joinToString("|")
        }

        fun dsl(): String {
            fun run(value: RdfTerm): String {
                val g = MemoryGraph()
                val n = Iri("urn:n")
                g.addTriple(RdfTriple(n, p("age"), value))
                return try { DslProbe(n, g).validate(); "ok" } catch (e: ValidationException) { "fails" }
            }
            return listOf(
                run(TypedLiteral("5", Iri("http://www.w3.org/2001/XMLSchema#integer"))),
                run(TypedLiteral("50", Iri("http://www.w3.org/2001/XMLSchema#integer"))),
                run(Literal("abc")),
                run(Iri("urn:five")),
            ).joinToString(",")
        }

        fun dslLength(): String {
            fun run(value: String): String {
                val g = MemoryGraph()
                val n = Iri("urn:n")
                g.addTriple(RdfTriple(n, p("nick"), Literal(value)))
                return try { DslProbe(n, g).validate(); "ok" } catch (e: ValidationException) { "fails" }
            }
            return listOf(run("\uD83D\uDE00\uD83D\uDE00"), run("\uD83D\uDE00"), run("ab"), run("abc")).joinToString(",")
        }
    """.trimIndent()

    @BeforeAll
    fun compile() {
        val logger = RecordingLogger()
        val model = OntologyModel(listOf(shape()), emptyContext)
        val files = mutableListOf<FileSpec>()
        files += InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(model, "gen.align").values
        files += OntologyWrapperGenerator(logger, ValidationMode.EMBEDDED).generateWrappers(model, "gen.align").values

        val builder = ClassBuilderModel(
            className = "Person",
            classIri = EX + "Person",
            builderName = "person",
            properties = listOf(
                PropertyBuilderModel(
                    propertyName = "age",
                    propertyIri = EX + "age",
                    kotlinType = STRING,
                    isRequired = false,
                    isList = false,
                    constraints = PropertyConstraints(minInclusive = BigDecimal.ZERO, maxInclusive = BigDecimal.TEN),
                ),
                PropertyBuilderModel(
                    propertyName = "nick",
                    propertyIri = EX + "nick",
                    kotlinType = STRING,
                    isRequired = false,
                    isList = false,
                    constraints = PropertyConstraints(minLength = 2, maxLength = 2),
                ),
            ),
            shapeIri = EX + "PersonShape",
        )
        val rdfResource = ClassName("com.geoknoesis.kastor.rdf", "RdfResource")
        val mutableGraph = ClassName("com.geoknoesis.kastor.rdf", "MutableRdfGraph")
        files += FileSpec.builder("gen.align", "DslProbe")
            .addImport("com.geoknoesis.kastor.rdf", "Iri")
            .addType(
                TypeSpec.classBuilder("DslProbe")
                    .primaryConstructor(
                        FunSpec.constructorBuilder().addParameter("resource", rdfResource).addParameter("graph", mutableGraph).build()
                    )
                    .addProperty(PropertySpec.builder("resource", rdfResource).initializer("resource").addModifiers(KModifier.PRIVATE).build())
                    .addProperty(PropertySpec.builder("graph", mutableGraph).initializer("graph").addModifiers(KModifier.PRIVATE).build())
                    .addFunction(ValidationCodeGenerator(logger).generateValidationMethod(builder))
                    .build()
            )
            .build()

        result = KotlinSourceCompiler.compile(files, mapOf("gen/align/Probe.kt" to probe))
        result.assertOk()
    }

    private val loader by lazy { result.classLoader() }
    private fun call(function: String): Any? = loader.loadClass("gen.align.ProbeKt").getMethod(function).invoke(null)

    @Test
    fun `embedded string and sh-in constraints apply to IRIs and blank nodes`() {
        assertEquals(
            "literalOk=ok|iriOk=ok|iriLong=maxLength:code|iriPattern=pattern:code|" +
                "blank=maxLength:code,minLength:code,pattern:code|inOk=ok|inIri=in:status|inBlank=in:status|" +
                "emojiTwo=ok|emojiOne=minLength:nick",
            call("wrapper"),
        )
    }

    @Test
    fun `DSL string lengths count code points`() {
        assertEquals("ok,fails,ok,fails", call("dslLength"))
    }

    @Test
    fun `DSL numeric bounds report values that are not numbers`() {
        assertEquals("ok,fails,fails,fails", call("dsl"))
    }
}
