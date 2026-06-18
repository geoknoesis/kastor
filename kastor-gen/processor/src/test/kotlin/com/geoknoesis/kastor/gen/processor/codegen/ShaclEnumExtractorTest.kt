package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.api.model.*
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.ShaclEnumExtractor
import com.geoknoesis.kastor.rdf.Iri
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ShaclEnumExtractorTest {
    private val logger = object : KSPLogger {
        override fun logging(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun info(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun warn(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun error(m: String, s: com.google.devtools.ksp.symbol.KSNode?) {}
        override fun exception(e: Throwable) {}
    }
    private fun ctx(propertyMappings: Map<String, JsonLdProperty> = emptyMap()) =
        JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = propertyMappings)

    @Test
    fun `iri members with sh class produce an IRI enum named from the class`() {
        val prop = ShaclProperty(
            path = "https://ex/#status", name = "status", description = "",
            datatype = null, targetClass = "https://ex/#DocumentStatus", minCount = 0, maxCount = 1,
            inValuesTyped = listOf(
                ShaclInValue("https://ex/#DRAFT", isIri = true),
                ShaclInValue("https://ex/#ACTIVE", isIri = true),
            ),
        )
        val model = OntologyModel(listOf(ShaclShape("s", "https://ex/#Doc", listOf(prop))), ctx())
        val out = ShaclEnumExtractor(logger).enrich(model)
        val e = out.enums.single()
        assertEquals("DocumentStatus", e.name)
        assertEquals(EnumMemberKind.IRI, e.memberKind)
        assertEquals(setOf("DRAFT", "ACTIVE"), e.members.map { it.constantName }.toSet())
        assertEquals("DocumentStatus", out.shapes.single().properties.single().enumName)
    }

    @Test
    fun `literal members with json-ld class type produce a literal enum`() {
        val prop = ShaclProperty(
            path = "https://ex/#code", name = "code", description = "",
            datatype = "http://www.w3.org/2001/XMLSchema#string", targetClass = null, minCount = 0, maxCount = 1,
            inValuesTyped = listOf(ShaclInValue("LOW", isIri = false), ShaclInValue("HIGH", isIri = false)),
        )
        val context = ctx(mapOf("code" to JsonLdProperty(
            id = Iri("https://ex/#code"), type = JsonLdType.Iri(Iri("https://ex/#Priority")))))
        val out = ShaclEnumExtractor(logger).enrich(OntologyModel(
            listOf(ShaclShape("s", "https://ex/#Doc", listOf(prop))), context))
        val e = out.enums.single()
        assertEquals("Priority", e.name)
        assertEquals(EnumMemberKind.LITERAL, e.memberKind)
        assertEquals("LOW", e.members.first().code)
    }

    @Test
    fun `sh in without a name source yields no enum`() {
        val prop = ShaclProperty(
            path = "https://ex/#code", name = "code", description = "",
            datatype = "http://www.w3.org/2001/XMLSchema#string", targetClass = null, minCount = 0, maxCount = 1,
            inValuesTyped = listOf(ShaclInValue("LOW", isIri = false)),
        )
        val out = ShaclEnumExtractor(logger).enrich(OntologyModel(
            listOf(ShaclShape("s", "https://ex/#Doc", listOf(prop))), ctx()))
        assertTrue(out.enums.isEmpty())
        assertNull(out.shapes.single().properties.single().enumName)
    }

    @Test
    fun `mixed member kinds yield no enum`() {
        val prop = ShaclProperty(
            path = "https://ex/#x", name = "x", description = "",
            datatype = null, targetClass = "https://ex/#T", minCount = 0, maxCount = 1,
            inValuesTyped = listOf(ShaclInValue("https://ex/#A", isIri = true), ShaclInValue("B", isIri = false)),
        )
        val out = ShaclEnumExtractor(logger).enrich(OntologyModel(
            listOf(ShaclShape("s", "https://ex/#Doc", listOf(prop))), ctx()))
        assertTrue(out.enums.isEmpty())
    }

    @Test
    fun `property whose targetClass has its own shape is treated as object reference not enum`() {
        // DocumentStatus has its own NodeShape, so the property should NOT become an enum
        val prop = ShaclProperty(
            path = "https://ex/#status", name = "status", description = "",
            datatype = null, targetClass = "https://ex/#DocumentStatus", minCount = 0, maxCount = 1,
            inValuesTyped = listOf(
                ShaclInValue("https://ex/#DRAFT", isIri = true),
                ShaclInValue("https://ex/#ACTIVE", isIri = true),
            ),
        )
        val docShape = ShaclShape("s1", "https://ex/#Doc", listOf(prop))
        // DocumentStatus has its own shape → it's a real entity, not an enum
        val statusShape = ShaclShape("s2", "https://ex/#DocumentStatus", emptyList())
        val out = ShaclEnumExtractor(logger).enrich(OntologyModel(listOf(docShape, statusShape), ctx()))
        assertTrue(out.enums.isEmpty())
        assertNull(out.shapes.first().properties.single().enumName)
    }

    @Test
    fun `enum name collision with different members keeps first enum and leaves second property as non-enum`() {
        val prop1 = ShaclProperty(
            path = "https://ex/#orderStatus", name = "orderStatus", description = "",
            datatype = null, targetClass = "https://ex/#Status", minCount = 0, maxCount = 1,
            inValuesTyped = listOf(
                ShaclInValue("https://ex/#DRAFT", isIri = true),
                ShaclInValue("https://ex/#ACTIVE", isIri = true),
            ),
        )
        val prop2 = ShaclProperty(
            path = "https://ex/#ticketStatus", name = "ticketStatus", description = "",
            datatype = null, targetClass = "https://ex/#Status", minCount = 0, maxCount = 1,
            inValuesTyped = listOf(
                ShaclInValue("https://ex/#OPEN", isIri = true),
                ShaclInValue("https://ex/#CLOSED", isIri = true),
            ),
        )
        val shape1 = ShaclShape("s1", "https://ex/#Order", listOf(prop1))
        val shape2 = ShaclShape("s2", "https://ex/#Ticket", listOf(prop2))
        val out = ShaclEnumExtractor(logger).enrich(OntologyModel(listOf(shape1, shape2), ctx()))
        // Only one enum named "Status" (the first one)
        assertEquals(1, out.enums.size)
        assertEquals("Status", out.enums.single().name)
        // First property is tagged with the enum name
        assertEquals("Status", out.shapes[0].properties.single().enumName)
        // Second property is left as non-enum due to collision
        assertNull(out.shapes[1].properties.single().enumName)
    }
}
