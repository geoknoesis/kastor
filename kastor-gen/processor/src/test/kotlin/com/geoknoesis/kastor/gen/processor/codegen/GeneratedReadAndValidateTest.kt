package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassFactoryGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassWriterGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.XSD_NS
import com.geoknoesis.kastor.gen.processor.testing.prop
import com.squareup.kotlinpoet.FileSpec
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals

/**
 * Runs generated readers, embedded validation, writers and external validation:
 * - ill-typed literals in list properties follow `MaterializationPolicy` instead of disappearing;
 * - embedded `validate()` checks `sh:datatype`, `sh:nodeKind` and `sh:class` and compares numeric bounds exactly;
 * - `NestedMode.INTERFACE` writers reject nested values that are not RDF-backed instead of skipping them;
 * - an `EXTERNAL` validator is created once per wrapper class, not per `validate()` call.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeneratedReadAndValidateTest {

    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())
    private lateinit var result: KotlinSourceCompiler.Result

    private fun person() = ShaclShape(EX + "PersonShape", EX + "Person", listOf(prop("name"), prop("knows", targetClass = EX + "Person", maxCount = null)))

    private fun model() = OntologyModel(
        listOf(
            person(),
            ShaclShape(
                EX + "DocShape", EX + "Doc",
                listOf(
                    prop("scores", datatype = "${XSD_NS}integer", maxCount = null),
                    prop("age", datatype = "${XSD_NS}integer"),
                    prop("homepage", datatype = null).copy(nodeKind = "http://www.w3.org/ns/shacl#IRI"),
                    prop("author", targetClass = EX + "Person"),
                    prop("amount", datatype = "${XSD_NS}decimal").copy(maxInclusive = 0.1),
                ),
            ),
        ),
        emptyContext,
    )

    private val probe = """
        package gen.p3

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        private fun p(local: String) = Iri("https://example.test/" + local)
        private fun xsd(local: String) = Iri("http://www.w3.org/2001/XMLSchema#" + local)

        fun illTyped(): String {
            val g = MemoryGraph()
            val d = Iri("urn:d")
            g.addTriple(RdfTriple(d, p("scores"), TypedLiteral("1", xsd("integer"))))
            g.addTriple(RdfTriple(d, p("scores"), TypedLiteral("abc", xsd("integer"))))
            val wrapper = try { OntoMapper.materialize(RdfRef(d, g), Doc::class.java).scores.toString() } catch (e: MaterializationException) { "throws" }
            val record = try { OntoMapper.materialize(RdfRef(d, g), DocRecord::class.java).scores.toString() } catch (e: MaterializationException) { "throws" }
            val previous = MaterializationPolicy.illTypedValues
            MaterializationPolicy.illTypedValues = IllTypedValueHandling.SKIP
            try {
                return wrapper + "|" + record + "|" + OntoMapper.materialize(RdfRef(d, g), Doc::class.java).scores
            } finally {
                MaterializationPolicy.illTypedValues = previous
            }
        }

        fun violations(): String {
            val g = MemoryGraph()
            val bad = Iri("urn:bad")
            g.addTriple(RdfTriple(bad, p("age"), TypedLiteral("abc", xsd("integer"))))
            g.addTriple(RdfTriple(bad, p("homepage"), Literal("not-an-iri")))
            g.addTriple(RdfTriple(bad, p("author"), Iri("urn:bob")))
            g.addTriple(RdfTriple(bad, p("amount"), TypedLiteral("0.1000000000000000000001", xsd("decimal"))))
            val ok = Iri("urn:ok")
            g.addTriple(RdfTriple(ok, p("age"), TypedLiteral("42", xsd("integer"))))
            g.addTriple(RdfTriple(ok, p("homepage"), Iri("https://example.test/home")))
            g.addTriple(RdfTriple(ok, p("author"), Iri("urn:alice")))
            g.addTriple(RdfTriple(Iri("urn:alice"), Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), p("Person")))
            g.addTriple(RdfTriple(ok, p("amount"), TypedLiteral("0.1", xsd("decimal"))))
            fun check(node: Iri): String {
                val r = (OntoMapper.materialize(RdfRef(node, g), Doc::class.java) as DocWrapper).validate()
                return if (r is ValidationResult.Violations) {
                    r.items.map { it.constraintIri.value.substringAfter('#') + ":" + it.path!!.value.substringAfterLast('/') }.sorted().joinToString(",")
                } else "ok"
            }
            return check(bad) + "|" + check(ok)
        }

        fun writer(): String {
            val stranger = object : Person {
                override val name: String? = "S"
                override val knows: List<Person> = emptyList()
            }
            return try {
                DocRecordFactory.toTriples(DocRecord(author = stranger), Iri("urn:w"))
                "written"
            } catch (e: IllegalArgumentException) {
                "rejected:" + (e.message?.contains("RdfBacked") == true)
            }
        }
    """.trimIndent()

    private val externalProbe = """
        package gen.p3ext

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        class CountingValidator : ValidationContext {
            companion object { var instances = 0 }
            init { instances++ }
            override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult = ValidationResult.Ok
        }

        fun externalValidatorInstances(): String {
            val g = MemoryGraph()
            val n = Iri("urn:x")
            val a = OntoMapper.materialize(RdfRef(n, g), Person::class.java) as PersonWrapper
            val b = OntoMapper.materialize(RdfRef(n, g), Person::class.java) as PersonWrapper
            a.validate(); a.validate(); b.validate()
            return CountingValidator.instances.toString()
        }
    """.trimIndent()

    @BeforeAll
    fun compile() {
        val logger = RecordingLogger()
        val model = model()
        val files = mutableListOf<FileSpec>()
        files += InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(model, "gen.p3").values
        files += OntologyWrapperGenerator(logger, ValidationMode.EMBEDDED).generateWrappers(model, "gen.p3").values
        files += DataClassGenerator(logger, "Record", NestedMode.INTERFACE, false, ValidationAnnotations.NONE).generateDataClasses(model, "gen.p3").values
        files += DataClassFactoryGenerator(logger, "Record", NestedMode.INTERFACE, DataClassWriterGenerator(logger, "Record", NestedMode.INTERFACE))
            .generateFactories(model, "gen.p3").values
        val external = OntologyModel(listOf(person()), emptyContext)
        files += InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(external, "gen.p3ext").values
        files += OntologyWrapperGenerator(logger, ValidationMode.EXTERNAL, "gen.p3ext.CountingValidator").generateWrappers(external, "gen.p3ext").values
        result = KotlinSourceCompiler.compile(files, mapOf("gen/p3/Probe.kt" to probe, "gen/p3ext/Probe.kt" to externalProbe))
        result.assertOk()
    }

    private fun call(className: String, function: String): Any? = result.classLoader().loadClass(className).getMethod(function).invoke(null)

    @Test
    fun `ill typed list values throw by default and are skipped on request`() {
        assertEquals("throws|throws|[1]", call("gen.p3.ProbeKt", "illTyped"))
    }

    @Test
    fun `embedded validation checks datatype node kind class and exact numeric bounds`() {
        assertEquals("class:author,datatype:age,maxInclusive:amount,nodeKind:homepage|ok", call("gen.p3.ProbeKt", "violations"))
    }

    @Test
    fun `interface mode writer rejects nested values that are not RDF backed`() {
        assertEquals("rejected:true", call("gen.p3.ProbeKt", "writer"))
    }

    @Test
    fun `external validator is created once per wrapper class`() {
        assertEquals("1", call("gen.p3ext.ProbeKt", "externalValidatorInstances"))
    }
}
