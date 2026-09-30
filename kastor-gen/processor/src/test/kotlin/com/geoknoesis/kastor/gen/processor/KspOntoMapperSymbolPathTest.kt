package com.geoknoesis.kastor.gen.processor

import com.geoknoesis.kastor.gen.processor.internal.core.OntoMapperProcessorProvider
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.KspRunner
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real `OntoMapperProcessor` under KSP over hand-written `@Rdf` interfaces, then compiles and runs the
 * generated wrappers: KSP type normalization (`kotlin.Long`), enum detection (`enum class`, sealed types with a
 * code-based or IRI-based `from`), declaring-package resolution for types in other packages and nested types in a
 * package with upper-case segments, and rejection of unsupported collection members.
 */
class KspOntoMapperSymbolPathTest {

    private val types = """
        package com.acme.model

        import com.geoknoesis.kastor.rdf.Iri

        enum class Status { OPEN, CLOSED }

        sealed interface Code {
            val code: String
            data class Other(override val code: String) : Code
            companion object {
                fun from(code: String): Code = Other(code)
            }
        }

        sealed interface Kind {
            val iri: Iri
            data class Other(override val iri: Iri) : Kind
            companion object {
                fun from(iri: Iri): Kind = Other(iri)
            }
        }
    """.trimIndent()

    private val person = """
        package com.acme.people

        import com.geoknoesis.kastor.gen.annotations.Rdf

        @Rdf(iri = "https://example.test/Person")
        interface Person {
            @Rdf(iri = "https://example.test/name")
            val name: String
        }
    """.trimIndent()

    private val place = """
        package com.acme.Geo

        import com.geoknoesis.kastor.gen.annotations.Rdf

        class Outer {
            @Rdf(iri = "https://example.test/Place")
            interface Inner {
                @Rdf(iri = "https://example.test/title")
                val title: String
            }
        }
    """.trimIndent()

    private val doc = """
        package com.acme.model

        import com.geoknoesis.kastor.gen.annotations.Rdf

        @Rdf(iri = "https://example.test/Doc")
        interface Doc {
            @Rdf(iri = "https://example.test/status")
            val status: Status
            @Rdf(iri = "https://example.test/code")
            val code: Code?
            @Rdf(iri = "https://example.test/kind")
            val kinds: List<Kind>
            @Rdf(iri = "https://example.test/count")
            val count: Long
            @Rdf(iri = "https://example.test/author")
            val author: com.acme.people.Person
            @Rdf(iri = "https://example.test/place")
            val place: com.acme.Geo.Outer.Inner?
            @Rdf(iri = "https://example.test/tag")
            val tags: List<String>
            @Rdf(iri = "https://example.test/label")
            var label: String
        }
    """.trimIndent()

    private val probe = """
        package com.acme.model

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph
        import com.geoknoesis.kastor.rdf.vocab.XSD

        fun probe(): String {
            fun p(local: String) = Iri("https://example.test/" + local)
            val g = MemoryGraph()
            val d = Iri("urn:doc")
            val ann = Iri("urn:ann")
            val here = Iri("urn:here")
            g.addTriple(RdfTriple(d, p("status"), Literal("CLOSED")))
            g.addTriple(RdfTriple(d, p("code"), Literal("c1")))
            g.addTriple(RdfTriple(d, p("kind"), Iri("https://example.test/kinds/big")))
            g.addTriple(RdfTriple(d, p("count"), TypedLiteral("5000000000", XSD.long)))
            g.addTriple(RdfTriple(d, p("author"), ann))
            g.addTriple(RdfTriple(ann, p("name"), Literal("Ann")))
            g.addTriple(RdfTriple(d, p("place"), here))
            g.addTriple(RdfTriple(here, p("title"), Literal("Here")))
            g.addTriple(RdfTriple(d, p("tag"), Literal("a")))
            g.addTriple(RdfTriple(d, p("label"), Literal("L")))
            val doc = OntoMapper.materialize(RdfRef(d, g), Doc::class.java)
            doc.label = "M"
            return listOf(
                doc.status, doc.code?.code, doc.kinds.map { it.iri.value }, doc.count, doc.author.name,
                doc.place?.title, doc.tags, doc.label,
            ).joinToString("|")
        }
    """.trimIndent()

    @Test
    fun `real KSP symbols produce wrappers that compile and read every member kind`() {
        val result = KspRunner.run(
            OntoMapperProcessorProvider(),
            mapOf(
                "com/acme/model/Types.kt" to types,
                "com/acme/model/Doc.kt" to doc,
                "com/acme/people/Person.kt" to person,
                "com/acme/Geo/Place.kt" to place,
            ),
        )
        assertEquals(emptyList(), result.errors)
        assertTrue(result.generated.keys.any { it.endsWith("DocWrapper.kt") }, result.generated.keys.toString())
        assertTrue(result.generated.keys.any { it.endsWith("Outer_InnerWrapper.kt") }, result.generated.keys.toString())

        val compiled = KotlinSourceCompiler.compile(emptyList(), result.sources + ("com/acme/model/Probe.kt" to probe))
        compiled.assertOk()
        val output = compiled.classLoader().loadClass("com.acme.model.ProbeKt").getMethod("probe").invoke(null)
        assertEquals("CLOSED|c1|[https://example.test/kinds/big]|5000000000|Ann|Here|[a]|M", output)
    }

    @Test
    fun `collection members other than List are rejected with an error naming the member`() {
        val bad = """
            package com.acme.bad

            import com.geoknoesis.kastor.gen.annotations.Rdf

            @Rdf(iri = "https://example.test/Bag")
            interface Bag {
                @Rdf(iri = "https://example.test/tag")
                val tags: Set<String>
                @Rdf(iri = "https://example.test/item")
                val items: Collection<Bag>
                @Rdf(iri = "https://example.test/name")
                val name: String
            }
        """.trimIndent()
        val result = KspRunner.run(OntoMapperProcessorProvider(), mapOf("com/acme/bad/Bag.kt" to bad))
        assertTrue(result.errors.any { "tags" in it && "Set" in it }, result.errors.toString())
        assertTrue(result.errors.any { "items" in it && "Collection" in it }, result.errors.toString())
    }
}
