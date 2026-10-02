package com.geoknoesis.kastor.gen.processor

import com.geoknoesis.kastor.gen.processor.internal.core.OntoMapperProcessor
import com.geoknoesis.kastor.gen.processor.internal.core.OntoMapperProcessorProvider
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.KspRunner
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the real `OntoMapperProcessor` under KSP: an `@Rdf` interface may extend `RdfBacked` (whose `rdf` the wrapper
 * implements) and library interfaces with default members; member types whose factory is registered by hand are
 * accepted when they opt in; and a wrapper name clash is described as what it is.
 */
class KspOntoMapperOptInTest {

    private fun run(options: Map<String, String> = emptyMap(), vararg sources: Pair<String, String>) =
        KspRunner.run(OntoMapperProcessorProvider(), mapOf(*sources), options)

    @Test
    fun `an Rdf interface that extends RdfBacked and a binary interface with default members compiles and runs`() {
        val source = """
            package com.acme.backed

            import com.example.BinaryDefaults
            import com.geoknoesis.kastor.gen.annotations.Rdf
            import com.geoknoesis.kastor.gen.runtime.RdfBacked
            import com.geoknoesis.kastor.gen.runtime.RdfHandle

            @Rdf(iri = "https://example.test/Doc")
            interface Doc : RdfBacked, BinaryDefaults {
                @Rdf(iri = "https://example.test/title")
                val title: String
            }

            /** Restating the member the wrapper implements is fine too. */
            @Rdf(iri = "https://example.test/Note")
            interface Note : RdfBacked {
                override val rdf: RdfHandle
                @Rdf(iri = "https://example.test/title")
                val title: String
            }
        """.trimIndent()
        val probe = """
            package com.acme.backed

            import com.geoknoesis.kastor.gen.runtime.*
            import com.geoknoesis.kastor.rdf.*
            import com.geoknoesis.kastor.rdf.provider.MemoryGraph

            fun probe(): String {
                val g = MemoryGraph()
                val d = Iri("urn:doc")
                g.addTriple(RdfTriple(d, Iri("https://example.test/title"), Literal("T")))
                g.addTriple(RdfTriple(d, Iri("https://example.test/other"), Literal("extra")))
                val doc = OntoMapper.materialize(RdfRef(d, g), Doc::class.java)
                val note = OntoMapper.materialize(RdfRef(d, g), Note::class.java)
                // `rdf` is a member of the interface itself: no cast to RdfBacked is needed.
                return listOf(
                    doc.title, (doc.rdf.node as Iri).value, doc.rdf.graph === g, doc.rdf.extras.predicates().map { it.value },
                    doc.kindLabel, doc.describe(), (note.rdf.node as Iri).value, note.title,
                ).joinToString("|")
            }
        """.trimIndent()
        val result = run(emptyMap(), "com/acme/backed/Doc.kt" to source)
        assertEquals(emptyList(), result.errors)
        assertEquals(
            setOf("generated/com/acme/backed/DocWrapper.kt", "generated/com/acme/backed/NoteWrapper.kt"),
            result.generated.keys,
        )
        val compiled = KotlinSourceCompiler.compile(emptyList(), result.sources + ("com/acme/backed/Probe.kt" to probe))
        compiled.assertOk()
        val output = compiled.classLoader().loadClass("com.acme.backed.ProbeKt").getMethod("probe").invoke(null)
        assertEquals("T|urn:doc|true|[https://example.test/other]|binary|described as binary|urn:doc|T", output)
    }

    private val handRegistered = """
        package com.acme.hand

        import com.geoknoesis.kastor.gen.annotations.Rdf
        import com.geoknoesis.kastor.gen.annotations.RdfMaterializable
        import com.geoknoesis.kastor.gen.runtime.RdfBacked
        import com.geoknoesis.kastor.gen.runtime.RdfHandle

        /** Materialized by a factory registered by hand under another name than `<Type>Wrapper`. */
        @RdfMaterializable
        interface Address {
            val city: String
        }

        class AddressView(override val rdf: RdfHandle) : Address, RdfBacked {
            override val city: String get() = "city of " + (rdf.node as com.geoknoesis.kastor.rdf.Iri).value
        }

        /** A type that cannot be annotated (think: a library type); listed in the processor option instead. */
        interface Legacy {
            val code: String
        }

        class LegacyView(private val handle: RdfHandle) : Legacy {
            override val code: String get() = "legacy " + (handle.node as com.geoknoesis.kastor.rdf.Iri).value
        }

        @Rdf(iri = "https://example.test/Person")
        interface Person {
            @Rdf(iri = "https://example.test/address")
            val address: Address?
            @Rdf(iri = "https://example.test/address")
            val addresses: List<Address>
            @Rdf(iri = "https://example.test/legacy")
            val legacy: Legacy?
        }
    """.trimIndent()

    @Test
    fun `member types registered by hand are accepted with the annotation or the processor option`() {
        val probe = """
            package com.acme.hand

            import com.geoknoesis.kastor.gen.runtime.*
            import com.geoknoesis.kastor.rdf.*
            import com.geoknoesis.kastor.rdf.provider.MemoryGraph

            fun probe(): String {
                OntoMapper.register(Address::class.java) { handle -> AddressView(handle) }
                OntoMapper.register(Legacy::class.java) { handle -> LegacyView(handle) }
                val g = MemoryGraph()
                val p = Iri("urn:p")
                g.addTriple(RdfTriple(p, Iri("https://example.test/address"), Iri("urn:a")))
                g.addTriple(RdfTriple(p, Iri("https://example.test/legacy"), Iri("urn:l")))
                val person = OntoMapper.materialize(RdfRef(p, g), Person::class.java)
                return listOf(person.address?.city, person.addresses.map { it.city }, person.legacy?.code).joinToString("|")
            }
        """.trimIndent()
        val result = run(
            mapOf(OntoMapperProcessor.MATERIALIZABLE_TYPES_OPTION to " com.acme.hand.Legacy , com.acme.hand.Unused"),
            "com/acme/hand/Person.kt" to handRegistered,
        )
        assertEquals(emptyList(), result.errors)
        val compiled = KotlinSourceCompiler.compile(emptyList(), result.sources + ("com/acme/hand/Probe.kt" to probe))
        compiled.assertOk()
        val output = compiled.classLoader().loadClass("com.acme.hand.ProbeKt").getMethod("probe").invoke(null)
        assertEquals("city of urn:a|[city of urn:a]|legacy urn:l", output)
    }

    @Test
    fun `without the opt-in the error names both ways to opt in`() {
        val result = run(emptyMap(), "com/acme/hand/Person.kt" to handRegistered)
        val error = result.errors.singleOrNull { "'legacy'" in it }
        assertTrue(error != null, result.errors.toString())
        assertTrue("com.acme.hand.Legacy" in error, error)
        assertTrue("@RdfMaterializable" in error && OntoMapperProcessor.MATERIALIZABLE_TYPES_OPTION in error, error)
        assertFalse(result.errors.any { "'address'" in it || "'addresses'" in it }, "the annotated type is accepted: " + result.errors)
        assertTrue(result.generated.isEmpty(), result.generated.keys.toString())
    }

    @Test
    fun `wrappers whose file names differ only in case are reported as a file collision, not as one class`() {
        val source = """
            package com.acme.letters

            import com.geoknoesis.kastor.gen.annotations.Rdf

            @Rdf(iri = "https://example.test/Foo")
            interface Foo {
                @Rdf(iri = "https://example.test/a")
                val a: String
            }

            @Rdf(iri = "https://example.test/FOO")
            interface FOO {
                @Rdf(iri = "https://example.test/b")
                val b: String
            }
        """.trimIndent()
        val result = run(emptyMap(), "com/acme/letters/Letters.kt" to source)
        val error = result.errors.singleOrNull { "FooWrapper" in it }
        assertTrue(error != null, result.errors.toString())
        assertTrue("com.acme.letters.FOO" in error && "com.acme.letters.Foo" in error, error)
        assertTrue("FOOWrapper.kt" in error && "FooWrapper.kt" in error, error)
        assertTrue("differ only in case" in error && "case-insensitive file system" in error, error)
        assertFalse("same wrapper class" in error, "they are two classes: $error")
        assertTrue(result.generated.isEmpty(), "neither wrapper is generated: " + result.generated.keys)
    }
}
