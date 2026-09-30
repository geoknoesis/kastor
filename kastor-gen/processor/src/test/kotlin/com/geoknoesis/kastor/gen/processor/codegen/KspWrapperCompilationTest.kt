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

    private val kindIface = """
        package gen.kind

        interface Friend {
            val name: String
        }

        interface Doc {
            var label: String
            val title: String
            val tags: List<String>
            var best: Friend?
            val friend: Friend?
            val friends: List<Friend>
        }
    """.trimIndent()

    private val kindProbe = """
        package gen.kind

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        private fun read(block: () -> Any?): String =
            try { block().toString() } catch (e: MaterializationException) { "rejected" }

        fun probe(): String {
            fun p(local: String) = Iri("https://example.test/" + local)
            val g = MemoryGraph()
            val n = Iri("urn:doc")
            // Every member holds a value of the wrong term kind.
            g.addTriple(RdfTriple(n, p("label"), Iri("urn:not-a-literal")))
            g.addTriple(RdfTriple(n, p("title"), Iri("urn:not-a-literal")))
            g.addTriple(RdfTriple(n, p("tags"), Iri("urn:not-a-literal")))
            g.addTriple(RdfTriple(n, p("best"), Literal("not an object")))
            g.addTriple(RdfTriple(n, p("friend"), Literal("not an object")))
            g.addTriple(RdfTriple(n, p("friends"), Literal("not an object")))
            val d = OntoMapper.materialize(RdfRef(n, g), Doc::class.java)
            val strict = listOf(read { d.label }, read { d.title }, read { d.tags }, read { d.best }, read { d.friend }, read { d.friends })
            val lenient = MaterializationPolicy.withIllTypedValues(IllTypedValueHandling.SKIP) {
                val s = OntoMapper.materialize(RdfRef(n, g), Doc::class.java)
                listOf(read { s.label }, read { s.title }, read { s.tags }, read { s.best }, read { s.friend }, read { s.friends })
            }
            return strict.joinToString(",") + "|" + lenient.joinToString(",")
        }
    """.trimIndent()

    @Test
    fun `values of the wrong term kind follow the materialization policy in every reader`() {
        fun p(name: String, type: String, kind: PropertyType, mutable: Boolean = false, nullable: Boolean = false) =
            PropertyModel(name, type, "https://example.test/$name", kind, mutable = mutable, nullable = nullable)
        val friend = ClassModel(
            qualifiedName = "gen.kind.Friend", simpleName = "Friend", packageName = "gen.kind",
            classIri = "https://example.test/Friend",
            properties = listOf(p("name", "String", PropertyType.LITERAL)),
        )
        val doc = ClassModel(
            qualifiedName = "gen.kind.Doc", simpleName = "Doc", packageName = "gen.kind",
            classIri = "https://example.test/Doc",
            properties = listOf(
                p("label", "String", PropertyType.LITERAL, mutable = true),
                p("title", "String", PropertyType.LITERAL),
                p("tags", "List<String>", PropertyType.LITERAL),
                p("best", "Friend", PropertyType.OBJECT, mutable = true, nullable = true),
                p("friend", "Friend", PropertyType.OBJECT, nullable = true),
                p("friends", "List<Friend>", PropertyType.OBJECT_LIST),
            ),
        )
        val generator = WrapperGenerator(RecordingLogger())
        val result = KotlinSourceCompiler.compile(
            listOf(generator.generateWrapper(friend), generator.generateWrapper(doc)),
            mapOf("gen/kind/Doc.kt" to kindIface, "gen/kind/Probe.kt" to kindProbe),
        )
        result.assertOk()
        val output = result.classLoader().loadClass("gen.kind.ProbeKt").getMethod("probe").invoke(null)
        // Required single members still report the missing value once the wrong-kind value is skipped.
        assertEquals(
            "rejected,rejected,rejected,rejected,rejected,rejected|rejected,rejected,[],null,null,[]",
            output,
        )
    }
}
