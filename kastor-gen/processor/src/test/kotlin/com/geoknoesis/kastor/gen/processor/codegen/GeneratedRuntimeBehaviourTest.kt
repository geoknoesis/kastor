package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.api.exceptions.InvalidConfigurationException
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassFactoryGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassWriterGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.core.GenerationCoordinator
import com.geoknoesis.kastor.gen.processor.internal.utils.GenerationNames
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.XSD_NS
import com.geoknoesis.kastor.gen.processor.testing.prop
import com.google.devtools.ksp.processing.CodeGenerator
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Compiles generated code and *runs* it: datatype fidelity when reading and writing, eager snapshot cycles,
 * and the generation-time guards against ambiguous names.
 */
class GeneratedRuntimeBehaviourTest {

    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())

    private fun personModel(): OntologyModel = OntologyModel(
        listOf(
            ShaclShape(
                shapeIri = EX + "PersonShape",
                targetClass = EX + "Person",
                properties = listOf(
                    prop("name", minCount = 1),
                    prop("age", datatype = "${XSD_NS}integer"),
                    prop("active", datatype = "${XSD_NS}boolean"),
                    prop("score", datatype = "${XSD_NS}float"),
                    prop("price", datatype = "${XSD_NS}decimal"),
                    prop("birth", datatype = "${XSD_NS}date"),
                    prop("label", datatype = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString", maxCount = null),
                    prop("duration", datatype = "${XSD_NS}duration"),
                    prop("knows", targetClass = EX + "Person", maxCount = null),
                ),
            )
        ),
        emptyContext,
    )

    private val probe = """
        package gen.rt

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph
        import com.geoknoesis.kastor.rdf.vocab.XSD
        import java.math.BigDecimal
        import java.math.BigInteger
        import java.time.LocalDate

        fun probe(): String {
            fun p(local: String) = Iri("https://example.test/" + local)
            val g = MemoryGraph()
            val a = Iri("urn:a")
            val b = Iri("urn:b")
            g.addTriple(RdfTriple(a, p("name"), Literal("Ann")))
            g.addTriple(RdfTriple(a, p("age"), TypedLiteral("12345678901234567890", XSD.integer)))
            g.addTriple(RdfTriple(a, p("active"), TypedLiteral("1", XSD.boolean)))
            g.addTriple(RdfTriple(a, p("score"), TypedLiteral("1.5", XSD.float)))
            g.addTriple(RdfTriple(a, p("price"), TypedLiteral("10.25", XSD.decimal)))
            g.addTriple(RdfTriple(a, p("birth"), TypedLiteral("2000-01-02", XSD.date)))
            g.addTriple(RdfTriple(a, p("label"), LangString("hi", "en")))
            g.addTriple(RdfTriple(a, p("duration"), TypedLiteral("P1D", Iri("http://www.w3.org/2001/XMLSchema#duration"))))
            g.addTriple(RdfTriple(a, p("knows"), b))
            g.addTriple(RdfTriple(b, p("knows"), a))
            g.addTriple(RdfTriple(b, p("name"), Literal("Bob")))

            val out = StringBuilder()
            val person = OntoMapper.materialize(RdfRef(a, g), Person::class.java)
            out.append("age=" + person.age + ";active=" + person.active + ";score=" + person.score)
            out.append(";price=" + person.price + ";birth=" + person.birth + ";label=" + person.label.single().lang)
            out.append(";duration=" + person.duration + ";friend=" + person.knows.single().name)
            out.append(";friendOfFriend=" + person.knows.single().knows.single().name)

            try {
                OntoMapper.materialize(RdfRef(a, g), PersonRecord::class.java)
                out.append(";cycle=none")
            } catch (e: MaterializationException) {
                out.append(";cycle=detected")
            }

            val record = PersonRecord(
                name = "Ann", age = BigInteger("12345678901234567890"), active = true, score = 1.5f,
                price = BigDecimal("10.25"), birth = LocalDate.of(2000, 1, 2),
                label = listOf(LangString("hi", "en")), duration = "P1D",
            )
            val triples = PersonRecordFactory.toTriples(record, a)
            fun dt(local: String) = (triples.single { it.predicate == p(local) }.obj as Literal).datatype.value.substringAfter('#')
            out.append(";w.age=" + dt("age") + ";w.active=" + dt("active") + ";w.score=" + dt("score"))
            out.append(";w.price=" + dt("price") + ";w.birth=" + dt("birth") + ";w.duration=" + dt("duration"))
            out.append(";w.label=" + dt("label"))
            return out.toString()
        }
    """.trimIndent()

    @Test
    fun `generated wrappers, factories and writers preserve datatypes and detect snapshot cycles`() {
        val logger = RecordingLogger()
        val model = personModel()
        val pkg = "gen.rt"
        val files = InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(model, pkg).values +
            OntologyWrapperGenerator(logger, ValidationMode.EMBEDDED).generateWrappers(model, pkg).values +
            DataClassGenerator(logger, "Record", NestedMode.DATA_CLASS, false, ValidationAnnotations.NONE).generateDataClasses(model, pkg).values +
            DataClassFactoryGenerator(logger, "Record", NestedMode.DATA_CLASS, DataClassWriterGenerator(logger, "Record", NestedMode.DATA_CLASS))
                .generateFactories(model, pkg).values
        val result = KotlinSourceCompiler.compile(files, mapOf("gen/rt/Probe.kt" to probe))
        result.assertOk()

        val output = result.classLoader().loadClass("gen.rt.ProbeKt").getMethod("probe").invoke(null) as String
        listOf(
            "age=12345678901234567890", "active=true", "score=1.5", "price=10.25", "birth=2000-01-02", "label=en",
            "duration=P1D", "friend=Bob", "friendOfFriend=Ann", "cycle=detected",
            "w.age=integer", "w.active=boolean", "w.score=float", "w.price=decimal", "w.birth=date",
            "w.duration=duration", "w.label=langString",
        ).forEach { assertTrue(output.split(';').contains(it), "missing '$it' in $output") }
    }

    @Test
    fun `property names that collapse to the same identifier fail with the colliding IRIs`() {
        val model = OntologyModel(
            listOf(
                ShaclShape(
                    EX + "DocShape", EX + "Doc",
                    listOf(prop("issued1", name = "date-issued"), prop("issued2", name = "Date issued")),
                )
            ),
            emptyContext,
        )
        val e = assertFailsWith<InvalidConfigurationException> {
            InterfaceGenerator(RecordingLogger()).generateInterfaces(model, "x")
        }
        assertTrue(e.message!!.contains("${EX}issued1") && e.message!!.contains("${EX}issued2"), e.message)
    }

    @Test
    fun `type names differing only in case fail instead of overwriting files`() {
        val model = OntologyModel(
            listOf(ShaclShape(EX + "S1", EX + "FOO", emptyList()), ShaclShape(EX + "S2", EX + "Foo", emptyList())),
            emptyContext,
        )
        val e = assertFailsWith<InvalidConfigurationException> { GenerationNames.checkCollisions(model) }
        assertTrue(e.message!!.contains("${EX}FOO") && e.message!!.contains("${EX}Foo"), e.message)
    }

    @Test
    fun `empty data class suffix is rejected when interfaces are generated in the same package`() {
        val unusedCodeGenerator = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CodeGenerator::class.java)) { _, method, _ ->
            error("CodeGenerator.${method.name} must not be called")
        } as CodeGenerator
        val coordinator = GenerationCoordinator(RecordingLogger(), unusedCodeGenerator)
        assertFailsWith<InvalidConfigurationException> {
            coordinator.generateFromOntology(
                model = personModel(), packageName = "x", generateInterfaces = true, generateWrappers = true,
                validationMode = ValidationMode.NONE, validationAnnotations = ValidationAnnotations.NONE,
                externalValidatorClass = null, generateDataClass = true, dataClassSuffix = "",
            )
        }
    }
}
