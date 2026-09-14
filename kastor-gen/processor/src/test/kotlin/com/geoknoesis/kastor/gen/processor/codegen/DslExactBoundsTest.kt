package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.api.model.EnumMemberKind
import com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.PropertyBuilderModel
import com.geoknoesis.kastor.gen.processor.api.model.PropertyConstraints
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.InstanceDslGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.PropertyMethodGenerator
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.XSD_NS
import com.geoknoesis.kastor.gen.processor.testing.prop
import com.squareup.kotlinpoet.ClassName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Instance DSL setters and `validate()` compare numeric bounds exactly (bounds and values beyond the `Double` range
 * keep every digit), and enum setter types keep packages with upper-case segments.
 */
class DslExactBoundsTest {

    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())

    private val probe = """
        package gen.dslbounds.dsl

        import com.geoknoesis.kastor.gen.runtime.ValidationException
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph
        import java.math.BigInteger

        private fun outcome(block: () -> Unit): String = try { block(); "ok" } catch (e: IllegalArgumentException) { "rejected" }

        fun probe(): String {
            val builder = AccountBuilder(Iri("urn:a"), MemoryGraph())
            val atMax = outcome { builder.balance(BigInteger("9223372036854775807")) }
            val overMax = outcome { builder.balance(BigInteger("9223372036854775808")) }
            val doubleOverMax = outcome { builder.score(9.223372036854775807E18) }
            val nan = outcome { builder.score(Double.NaN) }

            val g = MemoryGraph()
            val node = Iri("urn:b")
            g.addTriple(RdfTriple(node, Iri("https://example.test/balance"), TypedLiteral("9223372036854775808", Iri("http://www.w3.org/2001/XMLSchema#integer"))))
            val validated = try { AccountBuilder(node, g).validate(); "valid" } catch (e: ValidationException) { "violation" }
            return listOf(atMax, overMax, doubleOverMax, nan, validated).joinToString("|")
        }
    """.trimIndent()

    @Test
    fun `setters and validate compare numeric bounds exactly`() {
        val bound = BigDecimal("9223372036854775807")
        val model = OntologyModel(
            listOf(
                ShaclShape(
                    EX + "AccountShape", EX + "Account",
                    listOf(
                        prop("balance", datatype = "${XSD_NS}integer").copy(maxInclusive = bound),
                        prop("score", datatype = "${XSD_NS}double").copy(maxInclusive = bound),
                    ),
                ),
            ),
            emptyContext,
        )
        val dsl = InstanceDslGenerator(RecordingLogger()).generate(InstanceDslRequest("accounts", model, "gen.dslbounds.dsl", DslGenerationOptions()))
        val result = KotlinSourceCompiler.compile(listOf(dsl), mapOf("gen/dslbounds/dsl/Probe.kt" to probe))
        result.assertOk()
        assertEquals("ok|rejected|rejected|rejected|violation", result.classLoader().loadClass("gen.dslbounds.dsl.ProbeKt").getMethod("probe").invoke(null))
    }

    @Test
    fun `enum setter types keep packages with upper-case segments`() {
        val property = PropertyBuilderModel(
            propertyName = "status",
            propertyIri = EX + "status",
            kotlinType = ClassName("com.Acme.model", "Status"),
            isRequired = false,
            isList = false,
            constraints = PropertyConstraints(),
            enumName = "com.Acme.model.Status",
            enumMemberKind = EnumMemberKind.LITERAL,
        )
        val type = PropertyMethodGenerator(RecordingLogger()).generatePropertyMethods(property, DslGenerationOptions())
            .first().parameters.first().type as ClassName
        assertEquals("com.Acme.model", type.packageName)
        assertEquals(listOf("Status"), type.simpleNames)
    }
}
