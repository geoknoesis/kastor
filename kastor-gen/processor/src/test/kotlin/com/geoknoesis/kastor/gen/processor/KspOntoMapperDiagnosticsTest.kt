package com.geoknoesis.kastor.gen.processor

import com.geoknoesis.kastor.gen.processor.internal.core.OntoMapperProcessorProvider
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.KspRunner
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the real `OntoMapperProcessor` under KSP over `@Rdf` interfaces it cannot (fully) handle: every such case is a
 * KSP diagnostic naming the type or member, instead of a `FileAlreadyExistsException`, an obscure compile error in
 * generated code, or a "No wrapper factory registered" failure at the first read.
 */
class KspOntoMapperDiagnosticsTest {

    private fun run(vararg sources: Pair<String, String>) = KspRunner.run(OntoMapperProcessorProvider(), mapOf(*sources))

    @Test
    fun `a nested and a top-level interface that map to the same wrapper name are reported`() {
        val source = """
            package com.acme.clash

            import com.geoknoesis.kastor.gen.annotations.Rdf

            class Outer {
                @Rdf(iri = "https://example.test/Inner")
                interface Inner {
                    @Rdf(iri = "https://example.test/a")
                    val a: String
                }
            }

            @Rdf(iri = "https://example.test/OuterInner")
            interface Outer_Inner {
                @Rdf(iri = "https://example.test/b")
                val b: String
            }
        """.trimIndent()
        val result = run("com/acme/clash/Clash.kt" to source)
        val error = result.errors.singleOrNull { "Outer_InnerWrapper" in it }
        assertTrue(error != null, result.errors.toString())
        assertTrue("com.acme.clash.Outer.Inner" in error && "com.acme.clash.Outer_Inner" in error, error)
        assertFalse(result.errors.any { "FileAlreadyExists" in it }, result.errors.toString())
        assertTrue(result.generated.isEmpty(), "neither wrapper is generated: " + result.generated.keys)
    }

    @Test
    fun `a class that takes the wrapper name without being a wrapper is reported as a warning`() {
        val source = """
            package com.acme.taken

            import com.geoknoesis.kastor.gen.annotations.Rdf
            import com.geoknoesis.kastor.gen.runtime.RdfBacked
            import com.geoknoesis.kastor.gen.runtime.RdfHandle

            @Rdf(iri = "https://example.test/Foo")
            interface Foo {
                @Rdf(iri = "https://example.test/a")
                val a: String
            }

            /** Unrelated to Kastor: it only happens to have the wrapper's name. */
            class FooWrapper(val foo: Foo)

            @Rdf(iri = "https://example.test/Bar")
            interface Bar {
                @Rdf(iri = "https://example.test/a")
                val a: String
            }

            /** A deliberate hand-written wrapper. */
            class BarWrapper(override val rdf: RdfHandle) : Bar, RdfBacked {
                override val a: String get() = "hand-written"
            }
        """.trimIndent()
        val result = run("com/acme/taken/Taken.kt" to source)
        assertEquals(emptyList(), result.errors)
        val warning = result.warnings.singleOrNull { "FooWrapper" in it }
        assertTrue(warning != null, result.warnings.toString())
        assertTrue("com.acme.taken.Foo" in warning && "no wrapper is generated" in warning, warning)
        assertFalse(result.warnings.any { "BarWrapper" in it }, "a real hand-written wrapper is not reported: " + result.warnings)
        assertTrue(result.generated.isEmpty(), result.generated.keys.toString())
    }

    @Test
    fun `abstract members without an Rdf annotation are errors naming the member`() {
        val source = """
            package com.acme.plain

            import com.geoknoesis.kastor.gen.annotations.Rdf

            interface Named {
                val inheritedPlain: String
            }

            @Rdf(iri = "https://example.test/Doc")
            interface Doc : Named {
                @Rdf(iri = "https://example.test/title")
                val title: String
                val forgotten: String
                fun compute(): Int
                val derived: String get() = title.uppercase()
                fun describe(): String = "doc " + title
            }
        """.trimIndent()
        val result = run("com/acme/plain/Doc.kt" to source)
        assertTrue(result.errors.any { "'forgotten'" in it && "com.acme.plain.Doc" in it && "@Rdf" in it }, result.errors.toString())
        assertTrue(result.errors.any { "'inheritedPlain'" in it && "com.acme.plain.Named" in it }, result.errors.toString())
        assertTrue(result.errors.any { "'compute'" in it }, result.errors.toString())
        assertFalse(result.errors.any { "'derived'" in it || "'describe'" in it || "'title'" in it }, result.errors.toString())
        assertTrue(result.generated.isEmpty(), "no wrapper that cannot compile is generated: " + result.generated.keys)
    }

    @Test
    fun `member types without a reader are errors naming the member and the type`() {
        val source = """
            package com.acme.types

            import com.geoknoesis.kastor.gen.annotations.Rdf

            class Plain(val x: Int)

            @Rdf(iri = "https://example.test/Doc")
            interface Doc {
                @Rdf(iri = "https://example.test/small")
                val small: Short
                @Rdf(iri = "https://example.test/at")
                val at: java.time.Instant?
                @Rdf(iri = "https://example.test/plain")
                val plain: Plain
                @Rdf(iri = "https://example.test/plains")
                val plains: List<Plain>
                @Rdf(iri = "https://example.test/title")
                val title: String
            }
        """.trimIndent()
        val result = run("com/acme/types/Doc.kt" to source)
        assertTrue(result.errors.any { "'small'" in it && "kotlin.Short" in it }, result.errors.toString())
        assertTrue(result.errors.any { "'at'" in it && "java.time.Instant" in it }, result.errors.toString())
        assertTrue(result.errors.any { "'plain'" in it && "com.acme.types.Plain" in it && "@Rdf" in it }, result.errors.toString())
        assertTrue(result.errors.any { "'plains'" in it && "com.acme.types.Plain" in it }, result.errors.toString())
        assertFalse(result.errors.any { "'title'" in it }, result.errors.toString())
    }

    @Test
    fun `type aliases are resolved to the member type they stand for`() {
        val source = """
            package com.acme.alias

            import com.geoknoesis.kastor.gen.annotations.Rdf

            typealias Name = String
            typealias Names = List<Name>
            typealias Count = Long
            typealias MaybeName = String?

            @Rdf(iri = "https://example.test/Friend")
            interface Friend {
                @Rdf(iri = "https://example.test/name")
                val name: Name
            }
            typealias Buddy = Friend

            @Rdf(iri = "https://example.test/Doc")
            interface Doc {
                @Rdf(iri = "https://example.test/name")
                var name: Name
                @Rdf(iri = "https://example.test/alias")
                val aliases: Names
                @Rdf(iri = "https://example.test/count")
                val count: Count?
                @Rdf(iri = "https://example.test/nick")
                val nick: MaybeName
                @Rdf(iri = "https://example.test/buddy")
                val buddy: Buddy?
                @Rdf(iri = "https://example.test/buddies")
                val buddies: List<Buddy>
            }
        """.trimIndent()
        val probe = """
            package com.acme.alias

            import com.geoknoesis.kastor.gen.runtime.*
            import com.geoknoesis.kastor.rdf.*
            import com.geoknoesis.kastor.rdf.provider.MemoryGraph
            import com.geoknoesis.kastor.rdf.vocab.XSD

            fun probe(): String {
                fun p(local: String) = Iri("https://example.test/" + local)
                val g = MemoryGraph()
                val d = Iri("urn:doc")
                val ann = Iri("urn:ann")
                g.addTriple(RdfTriple(d, p("name"), Literal("N")))
                g.addTriple(RdfTriple(d, p("alias"), Literal("a")))
                g.addTriple(RdfTriple(d, p("count"), TypedLiteral("5000000000", XSD.long)))
                g.addTriple(RdfTriple(d, p("buddy"), ann))
                g.addTriple(RdfTriple(d, p("buddies"), ann))
                g.addTriple(RdfTriple(ann, p("name"), Literal("Ann")))
                val doc = OntoMapper.materialize(RdfRef(d, g), Doc::class.java)
                doc.name = "M"
                return listOf(doc.name, doc.aliases, doc.count, doc.nick, doc.buddy?.name, doc.buddies.map { it.name }).joinToString("|")
            }
        """.trimIndent()
        val result = run("com/acme/alias/Doc.kt" to source)
        assertEquals(emptyList(), result.errors)
        val compiled = KotlinSourceCompiler.compile(emptyList(), result.sources + ("com/acme/alias/Probe.kt" to probe))
        compiled.assertOk()
        val output = compiled.classLoader().loadClass("com.acme.alias.ProbeKt").getMethod("probe").invoke(null)
        assertEquals("M|[a]|5000000000|null|Ann|[Ann]", output)
    }
}
