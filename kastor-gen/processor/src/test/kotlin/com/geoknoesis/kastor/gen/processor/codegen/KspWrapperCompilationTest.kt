package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.internal.codegen.WrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.model.ClassModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyType
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Wrappers for hand-written `@Rdf` interfaces (KSP `OntoMapperProcessor` path): predicate IRIs are emitted as
 * string arguments (a `%` in an IRI must not be read as a KotlinPoet placeholder), and missing values are never
 * replaced by invented defaults.
 */
class KspWrapperCompilationTest {

    private val iface = """
        package gen.ksp

        interface Doc {
            val title: String
            var count: Int
            var label: String
        }
    """.trimIndent()

    private val probe = """
        package gen.ksp

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        fun probe(): String {
            val g = MemoryGraph()
            val n = Iri("urn:doc")
            g.addTriple(RdfTriple(n, Iri("https://example.test/a%20b"), Literal("T")))
            val d = OntoMapper.materialize(RdfRef(n, g), Doc::class.java)
            // MaterializationException specifically: a plain IllegalStateException would escape and fail the test.
            val count = try { d.count.toString() } catch (e: MaterializationException) { "missing" }
            val label = try { d.label } catch (e: MaterializationException) { "missing" }
            d.label = "L"
            return d.title + "|" + count + "|" + label + "|" + d.label
        }
    """.trimIndent()

    @Test
    fun `percent in predicate IRIs compiles and missing values are not defaulted`() {
        val model = ClassModel(
            qualifiedName = "gen.ksp.Doc",
            simpleName = "Doc",
            packageName = "gen.ksp",
            classIri = "https://example.test/Doc",
            properties = listOf(
                PropertyModel("title", "String", "https://example.test/a%20b", PropertyType.LITERAL),
                PropertyModel("count", "Int", "https://example.test/count%41", PropertyType.LITERAL, mutable = true),
                PropertyModel("label", "String", "https://example.test/label%25s", PropertyType.LITERAL, mutable = true),
            ),
        )
        val file = WrapperGenerator(RecordingLogger()).generateWrapper(model)
        val result = KotlinSourceCompiler.compile(listOf(file), mapOf("gen/ksp/Doc.kt" to iface, "gen/ksp/Probe.kt" to probe))
        result.assertOk()
        val output = result.classLoader().loadClass("gen.ksp.ProbeKt").getMethod("probe").invoke(null)
        assertEquals("T|missing|missing|L", output)
    }
}
