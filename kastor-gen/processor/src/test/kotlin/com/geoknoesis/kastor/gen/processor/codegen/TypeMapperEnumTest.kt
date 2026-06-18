package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.internal.utils.TypeMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TypeMapperEnumTest {
    private val ctx = JsonLdContext(emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())

    @Test
    fun `enum property maps to the sealed type with optional cardinality`() {
        val p = ShaclProperty(
            path = "https://ex/#status", name = "status", description = "",
            datatype = null, targetClass = "https://ex/#DocumentStatus", minCount = 0, maxCount = 1,
            enumName = "DocumentStatus",
        )
        assertEquals("DocumentStatus?", TypeMapper.toKotlinType(p, ctx).toString())
    }

    @Test
    fun `multi-valued enum property maps to a List`() {
        val p = ShaclProperty(
            path = "https://ex/#tags", name = "tags", description = "",
            datatype = null, targetClass = "https://ex/#Tag", minCount = 0, maxCount = null,
            enumName = "Tag",
        )
        assertEquals("kotlin.collections.List<Tag>", TypeMapper.toKotlinType(p, ctx).toString())
    }

    @Test
    fun `enum property with no targetClass still maps to the sealed type`() {
        val p = ShaclProperty(
            path = "https://ex/#code", name = "code", description = "",
            datatype = "http://www.w3.org/2001/XMLSchema#string", targetClass = null, minCount = 1, maxCount = 1,
            enumName = "Priority",
        )
        assertEquals("Priority", TypeMapper.toKotlinType(p, ctx).toString())
    }
}
