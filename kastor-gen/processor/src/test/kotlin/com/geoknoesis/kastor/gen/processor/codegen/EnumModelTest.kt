package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.api.model.EnumMember
import com.geoknoesis.kastor.gen.processor.api.model.EnumMemberKind
import com.geoknoesis.kastor.gen.processor.api.model.EnumModel
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EnumModelTest {
    @Test
    fun `enum model carries name kind and members`() {
        val m = EnumModel(
            name = "DocumentStatus",
            classIri = "https://ex/#DocumentStatus",
            memberKind = EnumMemberKind.IRI,
            members = listOf(EnumMember(constantName = "DRAFT", iri = "https://ex/#DRAFT")),
        )
        assertEquals("DocumentStatus", m.name)
        assertEquals(EnumMemberKind.IRI, m.memberKind)
        assertEquals("DRAFT", m.members.single().constantName)
    }

    @Test
    fun `shacl property and ontology model default enum fields to empty`() {
        val p = ShaclProperty(
            path = "https://ex/#status", name = "status", description = "",
            datatype = null, targetClass = null, minCount = 0, maxCount = 1,
        )
        assertNull(p.enumName)
        assertNull(p.inValuesTyped)
        val model = OntologyModel(shapes = emptyList(), context = JsonLdContext(emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap()))
        assertEquals(emptyList<EnumModel>(), model.enums)
    }
}
