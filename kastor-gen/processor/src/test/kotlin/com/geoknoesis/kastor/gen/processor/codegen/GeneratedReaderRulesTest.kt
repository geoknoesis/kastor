package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclInValue
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassFactoryGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassWriterGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.EnumGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.ShaclEnumExtractor
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.XSD_NS
import com.geoknoesis.kastor.gen.processor.testing.prop
import com.squareup.kotlinpoet.FileSpec
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Compiles and runs generated wrappers, factories, writers and validators:
 * - cardinality counts every value of the path, whatever its term kind (literal `sh:in` enums, literal + IRI values);
 * - `sh:BlankNodeOrIRI` members are `RdfResource` and keep blank nodes; values of an unexpected term kind follow
 *   `MaterializationPolicy` (throw by default) in wrappers and factories;
 * - numeric bounds are exact beyond the `Double` range;
 * - `sh:severity`, `sh:message` and `sh:deactivated` are honoured by embedded validation;
 * - wrapper types naming the same external validator share one instance, which `SharedValidators` can close.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeneratedReaderRulesTest {

    private val sh = "http://www.w3.org/ns/shacl#"
    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())
    private lateinit var result: KotlinSourceCompiler.Result

    private fun docShape() = ShaclShape(
        EX + "DocShape", EX + "Doc",
        listOf(
            prop("status", minCount = 1).copy(
                inValues = listOf("draft", "final"),
                inValuesTyped = listOf(ShaclInValue("draft", false, "${XSD_NS}string"), ShaclInValue("final", false, "${XSD_NS}string")),
            ),
            prop("title"),
            prop("ref", datatype = null).copy(nodeKind = "${sh}BlankNodeOrIRI"),
            prop("homepage", datatype = null).copy(nodeKind = "${sh}IRI"),
            // An IRI sh:in over an unshaped sh:class becomes an IRI enum named after the class.
            prop("kind", targetClass = EX + "Kind").copy(
                inValues = listOf(EX + "A", EX + "B"),
                inValuesTyped = listOf(ShaclInValue(EX + "A", true), ShaclInValue(EX + "B", true)),
            ),
            prop("big", datatype = "${XSD_NS}integer").copy(maxInclusive = BigDecimal("9223372036854775807")),
            prop("code").copy(maxLength = 3, severity = "${sh}Warning", message = "Code must be short"),
            prop("legacy", minCount = 1).copy(deactivated = true),
        ),
    )

    private val probe = """
        package gen.rules

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        private fun p(local: String) = Iri("https://example.test/" + local)
        private fun xsd(local: String) = Iri("http://www.w3.org/2001/XMLSchema#" + local)
        private fun outcome(block: () -> Any?): String = try { block().toString() } catch (e: MaterializationException) { "throws" }

        private fun node(g: MutableRdfGraph, iri: String): Iri {
            val n = Iri(iri)
            g.addTriple(RdfTriple(n, p("status"), Literal("draft")))
            g.addTriple(RdfTriple(n, p("legacy"), Literal("l")))
            return n
        }

        fun reads(): String {
            val g = MemoryGraph()
            val blank = BlankNode("b1")
            val ok = node(g, "urn:ok")
            g.addTriple(RdfTriple(ok, p("ref"), blank))
            g.addTriple(RdfTriple(ok, p("kind"), p("A")))
            val badIri = node(g, "urn:badIri")
            g.addTriple(RdfTriple(badIri, p("homepage"), blank))
            val badEnum = node(g, "urn:badEnum")
            g.addTriple(RdfTriple(badEnum, p("kind"), Literal("A")))
            val badLiteral = node(g, "urn:badLiteral")
            g.addTriple(RdfTriple(badLiteral, p("title"), p("notALiteral")))
            fun doc(n: Iri) = OntoMapper.materialize(RdfRef(n, g), Doc::class.java)
            fun record(n: Iri) = OntoMapper.materialize(RdfRef(n, g), DocRecord::class.java)
            val strict = listOf(
                "ref=" + outcome { doc(ok).ref == blank },
                "recordRef=" + outcome { record(ok).ref == blank },
                "written=" + outcome { DocRecordFactory.toTriples(record(ok), Iri("urn:w")).any { it.obj == blank } },
                "kind=" + outcome { doc(ok).kind != null },
                "homepage=" + outcome { doc(badIri).homepage },
                "recordHomepage=" + outcome { record(badIri) != null },
                "enum=" + outcome { doc(badEnum).kind },
                "recordEnum=" + outcome { record(badEnum) != null },
                "title=" + outcome { doc(badLiteral).title },
                "recordTitle=" + outcome { record(badLiteral) != null },
            )
            val lenient = MaterializationPolicy.withIllTypedValues(IllTypedValueHandling.SKIP) {
                listOf(outcome { doc(badIri).homepage }, outcome { doc(badEnum).kind }, outcome { doc(badLiteral).title })
            }
            return (strict + ("skip=" + lenient.joinToString(","))).joinToString("|")
        }

        fun violations(): String {
            val g = MemoryGraph()
            val a = Iri("urn:a")
            g.addTriple(RdfTriple(a, p("status"), Literal("draft")))
            g.addTriple(RdfTriple(a, p("title"), Literal("T")))
            g.addTriple(RdfTriple(a, p("title"), p("titleIri")))
            g.addTriple(RdfTriple(a, p("big"), TypedLiteral("9223372036854775808", xsd("integer"))))
            g.addTriple(RdfTriple(a, p("code"), Literal("toolong")))
            val r = (OntoMapper.materialize(RdfRef(a, g), Doc::class.java) as DocWrapper).validate()
            if (r !is ValidationResult.Violations) return "ok"
            val items = r.items.map { it.constraintIri.value.substringAfter('#') + ":" + it.path!!.value.substringAfterLast('/') + ":" + it.severity }
            val codeMessage = r.items.firstOrNull { it.path == p("code") }?.message
            return items.sorted().joinToString(",") + "|" + codeMessage
        }
    """.trimIndent()

    private val externalProbe = """
        package gen.rulesext

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        class SharedCountingValidator : ValidationContext {
            companion object { var instances = 0; var closed = 0 }
            init { instances++ }
            override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult = ValidationResult.Ok
            override fun close() { closed++ }
        }

        fun shared(): String {
            val g = MemoryGraph()
            val n = Iri("urn:x")
            (OntoMapper.materialize(RdfRef(n, g), Person::class.java) as PersonWrapper).validate()
            (OntoMapper.materialize(RdfRef(n, g), Org::class.java) as OrgWrapper).validate()
            (OntoMapper.materialize(RdfRef(n, g), Person::class.java) as PersonWrapper).validate()
            val before = SharedCountingValidator.instances
            SharedValidators.close(SharedCountingValidator::class.java)
            (OntoMapper.materialize(RdfRef(n, g), Org::class.java) as OrgWrapper).validate()
            return "" + before + "|" + SharedCountingValidator.closed + "|" + SharedCountingValidator.instances
        }
    """.trimIndent()

    @BeforeAll
    fun compile() {
        val logger = RecordingLogger()
        val model = ShaclEnumExtractor(logger).enrich(OntologyModel(listOf(docShape()), emptyContext))
        val files = mutableListOf<FileSpec>()
        files += EnumGenerator(logger).generateEnums(model, "gen.rules").values
        files += InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(model, "gen.rules").values
        files += OntologyWrapperGenerator(logger, ValidationMode.EMBEDDED).generateWrappers(model, "gen.rules").values
        files += DataClassGenerator(logger, "Record", NestedMode.INTERFACE, false, ValidationAnnotations.NONE).generateDataClasses(model, "gen.rules").values
        files += DataClassFactoryGenerator(logger, "Record", NestedMode.INTERFACE, DataClassWriterGenerator(logger, "Record", NestedMode.INTERFACE))
            .generateFactories(model, "gen.rules").values

        val external = OntologyModel(
            listOf(ShaclShape(EX + "PersonShape", EX + "Person", listOf(prop("name"))), ShaclShape(EX + "OrgShape", EX + "Org", listOf(prop("label")))),
            emptyContext,
        )
        files += InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(external, "gen.rulesext").values
        files += OntologyWrapperGenerator(logger, ValidationMode.EXTERNAL, "gen.rulesext.SharedCountingValidator")
            .generateWrappers(external, "gen.rulesext").values

        result = KotlinSourceCompiler.compile(files, mapOf("gen/rules/Probe.kt" to probe, "gen/rulesext/Probe.kt" to externalProbe))
        result.assertOk()
    }

    private val loader by lazy { result.classLoader() }
    private fun call(className: String, function: String): Any? = loader.loadClass(className).getMethod(function).invoke(null)

    @Test
    fun `readers keep blank nodes for BlankNodeOrIRI and route unexpected term kinds through the policy`() {
        assertEquals(
            "ref=true|recordRef=true|written=true|kind=true|homepage=throws|recordHomepage=throws|enum=throws|" +
                "recordEnum=throws|title=throws|recordTitle=throws|skip=null,null,null",
            call("gen.rules.ProbeKt", "reads"),
        )
    }

    @Test
    fun `embedded validation counts every value, compares bounds exactly and honours severity message and deactivated`() {
        assertEquals(
            "datatype:title:Violation,maxCount:title:Violation,maxInclusive:big:Violation,maxLength:code:Warning|Code must be short",
            call("gen.rules.ProbeKt", "violations"),
        )
    }

    @Test
    fun `wrapper types naming one external validator share a single instance`() {
        assertEquals("1|1|2", call("gen.rulesext.ProbeKt", "shared"))
    }
}
