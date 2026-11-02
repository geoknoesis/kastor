package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.InstanceDslGenerator
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.XSD_NS
import com.geoknoesis.kastor.gen.processor.testing.prop
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The instance DSL honours `sh:minCount` and `sh:maxCount`: `validate()` checks both bounds (before, only "at least
 * one" for a required property, and never the maximum), and the setter of a single-valued property replaces its
 * value instead of adding a second one, also in a later block for the same resource.
 */
class DslCardinalityTest {
    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())

    private val probe = """
        package gen.dslcount.dsl

        import com.geoknoesis.kastor.gen.runtime.ValidationException
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        private fun values(g: RdfGraph, node: Iri, local: String): String =
            g.find(node, Iri("https://example.test/" + local)).map { t ->
                when (val o = t.obj) { is Literal -> o.lexical; is Iri -> o.value; else -> o.toString() }
            }.sorted().joinToString(",")

        private fun check(block: () -> Unit): String = try { block(); "ok" } catch (e: ValidationException) {
            e.message!!.substringAfter("validation failed: ")
        }

        fun probe(): String {
            val out = ArrayList<String>()
            // Single-valued setters replace.
            val g = MemoryGraph()
            val node = Iri("urn:a")
            val b = AccountBuilder(node, g)
            b.name("a"); b.name("b"); b.age(java.math.BigInteger.ONE); b.age(java.math.BigInteger.TWO); b.owner("urn:o1"); b.owner("urn:o2")
            b.tags("t1"); b.tags("t2")
            out += values(g, node, "name") + "|" + values(g, node, "age") + "|" + values(g, node, "owner") + "|" + values(g, node, "tags")
            out += check { b.validate() }
            // sh:minCount 2 and sh:maxCount 3 of a list property.
            val few = AccountBuilder(Iri("urn:few"), MemoryGraph()).apply { name("n"); tags("t1") }
            out += check { few.validate() }
            val many = AccountBuilder(Iri("urn:many"), MemoryGraph()).apply { name("n"); tags("t1", "t2", "t3", "t4") }
            out += check { many.validate() }
            // Data written around the setters: two names, no name.
            val data = MemoryGraph()
            val d = Iri("urn:d")
            data.addTriple(RdfTriple(d, Iri("https://example.test/name"), Literal("x")))
            data.addTriple(RdfTriple(d, Iri("https://example.test/name"), Literal("y")))
            data.addTriple(RdfTriple(d, Iri("https://example.test/tags"), Literal("t1")))
            data.addTriple(RdfTriple(d, Iri("https://example.test/tags"), Literal("t2")))
            out += check { AccountBuilder(d, data).validate() }
            out += check { AccountBuilder(Iri("urn:none"), MemoryGraph()).apply { tags("t1", "t2") }.validate() }
            // A later block for the same resource replaces the single value in the DSL's graph too.
            val dsl = accounts {
                account("urn:r") { name("first"); tags("t1", "t2") }
                account("urn:r") { name("second") }
            }
            out += values(dsl.build(), Iri("urn:r"), "name") + "|" + values(dsl.build(), Iri("urn:r"), "tags")
            return out.joinToString("\n")
        }
    """.trimIndent()

    @Test
    fun `validate checks minCount and maxCount, single-valued setters replace`() {
        val model = OntologyModel(
            listOf(
                ShaclShape(
                    EX + "AccountShape", EX + "Account",
                    listOf(
                        prop("name", minCount = 1, maxCount = 1),
                        prop("age", datatype = "${XSD_NS}integer", maxCount = 1),
                        prop("owner", targetClass = EX + "Owner", maxCount = 1),
                        prop("tags", minCount = 2, maxCount = 3),
                    ),
                ),
            ),
            emptyContext,
        )
        val dsl = InstanceDslGenerator(RecordingLogger()).generate(InstanceDslRequest("accounts", model, "gen.dslcount.dsl", DslGenerationOptions()))
        val result = KotlinSourceCompiler.compile(listOf(dsl), mapOf("gen/dslcount/dsl/Probe.kt" to probe))
        result.assertOk()
        val output = result.classLoader().loadClass("gen.dslcount.dsl.ProbeKt").getMethod("probe").invoke(null) as String
        assertEquals(
            listOf(
                "b|2|urn:o2|t1,t2",
                "ok",
                "tags requires at least 2 values (minCount=2)",
                "tags allows at most 3 values (maxCount=3)",
                "name allows at most 1 value (maxCount=1)",
                "name is required (minCount=1)",
                "second|t1,t2",
            ),
            output.lines(),
        )
    }
}
