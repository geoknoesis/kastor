package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.internal.codegen.WrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.model.ClassModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyType
import com.geoknoesis.kastor.gen.processor.internal.model.RdfEnumKind
import com.geoknoesis.kastor.gen.processor.internal.model.RdfMemberTypes
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Hand-written `@Rdf` interfaces (KSP `OntoMapperProcessor` path) support every literal type of the SHACL readers,
 * enums and RDF term members, and missing required values throw `MaterializationException` (not a plain
 * `IllegalStateException` from an object reader).
 */
class KspLiteralTypesCompilationTest {

    private val iface = """
        package gen.ksptypes

        import com.geoknoesis.kastor.rdf.LangString
        import com.geoknoesis.kastor.rdf.RdfResource
        import java.math.BigDecimal
        import java.math.BigInteger
        import java.time.LocalDate

        enum class Level { LOW, HIGH }

        interface Item {
            val big: Long
            val ratio: Float?
            val amount: BigDecimal
            val count: BigInteger?
            val day: LocalDate
            val label: LangString?
            val level: Level
            val levels: List<Level>
            val longs: List<Long>
            var size: Long
            val ref: RdfResource?
        }
    """.trimIndent()

    private val probe = """
        package gen.ksptypes

        import com.geoknoesis.kastor.gen.runtime.*
        import com.geoknoesis.kastor.rdf.*
        import com.geoknoesis.kastor.rdf.provider.MemoryGraph

        private fun p(local: String) = Iri("https://example.test/" + local)
        private fun xsd(local: String) = Iri("http://www.w3.org/2001/XMLSchema#" + local)
        private fun outcome(block: () -> Any?): String = try { block().toString() } catch (e: MaterializationException) { "MaterializationException" }

        fun probe(): String {
            val g = MemoryGraph()
            val n = Iri("urn:item")
            g.addTriple(RdfTriple(n, p("big"), TypedLiteral("9007199254740993", xsd("long"))))
            g.addTriple(RdfTriple(n, p("ratio"), TypedLiteral("1.5", xsd("float"))))
            g.addTriple(RdfTriple(n, p("amount"), TypedLiteral("0.10", xsd("decimal"))))
            g.addTriple(RdfTriple(n, p("day"), TypedLiteral("2020-02-29", xsd("date"))))
            g.addTriple(RdfTriple(n, p("label"), LangString("hi", "en")))
            g.addTriple(RdfTriple(n, p("level"), Literal("HIGH")))
            g.addTriple(RdfTriple(n, p("levels"), Literal("LOW")))
            g.addTriple(RdfTriple(n, p("levels"), Literal("HIGH")))
            g.addTriple(RdfTriple(n, p("longs"), TypedLiteral("2", xsd("long"))))
            g.addTriple(RdfTriple(n, p("longs"), TypedLiteral("1", xsd("long"))))
            g.addTriple(RdfTriple(n, p("size"), TypedLiteral("3", xsd("long"))))
            g.addTriple(RdfTriple(n, p("ref"), BlankNode("r1")))
            val bad = Iri("urn:bad")
            g.addTriple(RdfTriple(bad, p("level"), Literal("MEDIUM")))

            val item = OntoMapper.materialize(RdfRef(n, g), Item::class.java)
            item.size = 4L
            val none = OntoMapper.materialize(RdfRef(Iri("urn:none"), g), Item::class.java)
            return listOf(
                item.big, item.ratio, item.amount, item.count, item.day, item.label?.lexical, item.level,
                item.levels.sorted(), item.longs.sorted(), item.size, item.ref is BlankNode,
                outcome { none.big }, outcome { none.day }, outcome { none.ratio }, outcome { none.levels },
                outcome { OntoMapper.materialize(RdfRef(bad, g), Item::class.java).level },
            ).joinToString("|")
        }
    """.trimIndent()

    @Test
    fun `member types are classified like the SHACL readers`() {
        listOf("String", "Int", "Long", "Float", "Double", "Boolean", "java.math.BigInteger", "java.math.BigDecimal",
            "java.time.LocalDate", "com.geoknoesis.kastor.rdf.LangString", "List<Long>", "List<java.time.LocalDate>",
        ).forEach { assertEquals(PropertyType.LITERAL, RdfMemberTypes.propertyType(it, null), it) }
        assertEquals(PropertyType.LITERAL, RdfMemberTypes.propertyType("gen.Level", RdfEnumKind.NAME))
        assertEquals(PropertyType.LITERAL, RdfMemberTypes.propertyType("List<gen.Status>", RdfEnumKind.CODE))
        assertEquals(PropertyType.TERM, RdfMemberTypes.propertyType("com.geoknoesis.kastor.rdf.RdfResource", null))
        assertEquals(PropertyType.TERM, RdfMemberTypes.propertyType("List<com.geoknoesis.kastor.rdf.Iri>", null))
        assertEquals(PropertyType.OBJECT, RdfMemberTypes.propertyType("gen.Person", null))
        assertEquals(PropertyType.OBJECT_LIST, RdfMemberTypes.propertyType("List<gen.Person>", null))
        assertEquals("Long", RdfMemberTypes.normalize("kotlin.Long"))
        assertEquals("java.time.LocalDate", RdfMemberTypes.normalize("java.time.LocalDate"))
    }

    @Test
    fun `wrappers read every literal type, enums and terms and throw MaterializationException for missing values`() {
        fun member(name: String, type: String, nullable: Boolean = false, mutable: Boolean = false, enumKind: RdfEnumKind? = null) =
            PropertyModel(
                name = name, kotlinType = type, predicateIri = "https://example.test/$name",
                type = RdfMemberTypes.propertyType(type, enumKind), mutable = mutable, nullable = nullable, enumKind = enumKind,
                typePackage = "gen.ksptypes".takeIf { RdfMemberTypes.element(type).startsWith("gen.ksptypes.") },
            )
        val model = ClassModel(
            qualifiedName = "gen.ksptypes.Item", simpleName = "Item", packageName = "gen.ksptypes", classIri = "https://example.test/Item",
            properties = listOf(
                member("big", "Long"),
                member("ratio", "Float", nullable = true),
                member("amount", "java.math.BigDecimal"),
                member("count", "java.math.BigInteger", nullable = true),
                member("day", "java.time.LocalDate"),
                member("label", "com.geoknoesis.kastor.rdf.LangString", nullable = true),
                member("level", "gen.ksptypes.Level", enumKind = RdfEnumKind.NAME),
                member("levels", "List<gen.ksptypes.Level>", enumKind = RdfEnumKind.NAME),
                member("longs", "List<Long>"),
                member("size", "Long", mutable = true),
                member("ref", "com.geoknoesis.kastor.rdf.RdfResource", nullable = true),
            ),
        )
        val file = WrapperGenerator(RecordingLogger()).generateWrapper(model)
        val result = KotlinSourceCompiler.compile(listOf(file), mapOf("gen/ksptypes/Item.kt" to iface, "gen/ksptypes/Probe.kt" to probe))
        result.assertOk()
        assertEquals(
            "9007199254740993|1.5|0.10|null|2020-02-29|hi|HIGH|[LOW, HIGH]|[1, 2]|4|true|" +
                "MaterializationException|MaterializationException|null|[]|MaterializationException",
            result.classLoader().loadClass("gen.ksptypes.ProbeKt").getMethod("probe").invoke(null),
        )
    }
}
