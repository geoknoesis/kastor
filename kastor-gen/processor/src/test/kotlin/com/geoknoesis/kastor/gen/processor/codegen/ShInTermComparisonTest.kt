package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.api.model.ClassBuilderModel
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.PropertyBuilderModel
import com.geoknoesis.kastor.gen.processor.api.model.PropertyConstraints
import com.geoknoesis.kastor.gen.processor.api.model.ShaclInValue
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.ValidationCodeGenerator
import com.geoknoesis.kastor.gen.processor.internal.parsers.ShaclParser
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.XSD_NS
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
import kotlin.test.assertEquals

/**
 * `sh:in` members are RDF terms: a language-tagged member only equals a literal with the same lexical form and
 * language tag, and a typed member only one with the same lexical form and datatype. The parser keeps the language
 * tag and the datatype, and both the embedded wrapper validation and the DSL builder `validate()` compare terms.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShInTermComparisonTest {

    private val shacl = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix ex: <https://example.test/> .
        ex:NoteShape a sh:NodeShape ; sh:targetClass ex:Note ;
            sh:property [ sh:path ex:label ; sh:nodeKind sh:Literal ; sh:in ( "chat"@en "plain" 5 ) ] .
    """.trimIndent()

    private val rdfLangString = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"
    private val members = listOf(
        ShaclInValue("chat", isIri = false, datatype = rdfLangString, language = "en"),
        ShaclInValue("plain", isIri = false, datatype = "${XSD_NS}string"),
        ShaclInValue("5", isIri = false, datatype = "${XSD_NS}integer"),
    )

    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())
    private lateinit var result: KotlinSourceCompiler.Result

    private val probe = """
        package gen.shin

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        private val label = Iri("https://example.test/label")
        private val code = Iri("https://example.test/code")
        private fun xsd(local: String) = Iri("http://www.w3.org/2001/XMLSchema#" + local)

        private val values: List<Pair<String, RdfTerm>> = listOf(
            "chat@en" to LangString("chat", "en"),
            "chat@EN" to LangString("chat", "EN"),
            "chat@fr" to LangString("chat", "fr"),
            "chat" to Literal("chat"),
            "plain" to Literal("plain"),
            "plain@en" to LangString("plain", "en"),
            "5" to TypedLiteral("5", xsd("integer")),
            "5str" to Literal("5"),
            "5int" to TypedLiteral("5", xsd("int")),
        )

        fun wrapper(): String = values.joinToString("|") { (name, value) ->
            val g = MemoryGraph()
            val n = Iri("urn:n")
            g.addTriple(RdfTriple(n, label, value))
            val r = (OntoMapper.materialize(RdfRef(n, g), Note::class.java) as NoteWrapper).validate()
            name + "=" + (if (r is ValidationResult.Violations) "in" else "ok")
        }

        private fun dsl(predicate: Iri, entries: List<Pair<String, RdfTerm>>): String = entries.joinToString("|") { (name, value) ->
            val g = MemoryGraph()
            val n = Iri("urn:n")
            g.addTriple(RdfTriple(n, predicate, value))
            name + "=" + (try { DslProbe(n, g).validate(); "ok" } catch (e: ValidationException) { "in" })
        }

        fun dslTyped(): String = dsl(label, values)

        fun dslPlain(): String = dsl(code, listOf(
            "5" to TypedLiteral("5", xsd("integer")),
            "5str" to Literal("5"),
            "5@en" to LangString("5", "en"),
            "7" to TypedLiteral("7", xsd("integer")),
        ))
    """.trimIndent()

    @BeforeAll
    fun compile() {
        val logger = RecordingLogger()
        val model = OntologyModel(ShaclParser(logger).parseShaclContent(shacl), emptyContext)
        val files = mutableListOf<FileSpec>()
        files += InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(model, "gen.shin").values
        files += OntologyWrapperGenerator(logger, ValidationMode.EMBEDDED).generateWrappers(model, "gen.shin").values

        val builder = ClassBuilderModel(
            className = "Note",
            classIri = "https://example.test/Note",
            builderName = "note",
            properties = listOf(
                PropertyBuilderModel(
                    propertyName = "label",
                    propertyIri = "https://example.test/label",
                    kotlinType = STRING,
                    isRequired = false,
                    isList = false,
                    constraints = PropertyConstraints(inValues = members.map { it.value }, inValuesTyped = members),
                ),
                // Members without their own type are literals of the property's sh:datatype.
                PropertyBuilderModel(
                    propertyName = "code",
                    propertyIri = "https://example.test/code",
                    kotlinType = STRING,
                    isRequired = false,
                    isList = false,
                    constraints = PropertyConstraints(inValues = listOf("5", "6")),
                    datatype = "${XSD_NS}integer",
                ),
            ),
            shapeIri = "https://example.test/NoteShape",
        )
        val rdfResource = ClassName("com.geoknoesis.kastor.rdf", "RdfResource")
        val mutableGraph = ClassName("com.geoknoesis.kastor.rdf", "MutableRdfGraph")
        files += FileSpec.builder("gen.shin", "DslProbe")
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

        result = KotlinSourceCompiler.compile(files, mapOf("gen/shin/Probe.kt" to probe))
        result.assertOk()
    }

    private val loader by lazy { result.classLoader() }
    private fun call(function: String): Any? = loader.loadClass("gen.shin.ProbeKt").getMethod(function).invoke(null)

    private val expected = "chat@en=ok|chat@EN=ok|chat@fr=in|chat=in|plain=ok|plain@en=in|5=ok|5str=in|5int=in"

    @Test
    fun `the parser keeps the language tag and datatype of sh-in members`() {
        val property = ShaclParser(RecordingLogger()).parseShaclContent(shacl).single().properties.single()
        assertEquals(members, property.inValuesTyped)
    }

    @Test
    fun `embedded validation compares sh-in members as RDF terms`() {
        assertEquals(expected, call("wrapper"))
    }

    @Test
    fun `DSL validate compares typed sh-in members as RDF terms`() {
        assertEquals(expected, call("dslTyped"))
    }

    @Test
    fun `DSL validate compares untyped sh-in members as literals of the property datatype`() {
        assertEquals("5=ok|5str=in|5@en=in|7=in", call("dslPlain"))
    }
}
