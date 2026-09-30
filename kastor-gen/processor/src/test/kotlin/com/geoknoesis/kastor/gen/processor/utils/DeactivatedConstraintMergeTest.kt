package com.geoknoesis.kastor.gen.processor.utils

import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.utils.GenerationNames
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.prop
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `sh:deactivated` declarations (and those of deactivated node shapes) contribute no constraints when merged. */
class DeactivatedConstraintMergeTest {

    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())

    private fun constraints(model: OntologyModel, cls: String, path: String) =
        GenerationNames.effectiveMembers(model, GenerationNames.superTypes(model))
            .getValue(EX + cls).first { it.path == EX + path }.constraints

    @Test
    fun `a deactivated property shape on the same path does not tighten the merged constraints`() {
        val shape = ShaclShape(
            EX + "DocShape", EX + "Doc",
            listOf(
                prop("code", name = "a").copy(maxLength = 10),
                prop("code", name = "b", minCount = 1).copy(maxLength = 2, pattern = "^x", deactivated = true),
            ),
        )
        val merged = constraints(OntologyModel(listOf(shape), emptyContext), "Doc", "code")
        assertEquals(10, merged.maxLength)
        assertNull(merged.pattern)
        assertNull(merged.minCount)
        assertFalse(merged.deactivated)
    }

    @Test
    fun `constraints inherited from a deactivated parent shape are not merged into the child`() {
        val grand = ShaclShape(EX + "ThingShape", EX + "Thing", listOf(prop("label").copy(maxLength = 50)))
        val parent = ShaclShape(
            EX + "AgentShape", EX + "Agent",
            listOf(prop("label").copy(maxLength = 3), prop("code").copy(pattern = "^x")),
            parentClasses = listOf(EX + "Thing"),
            deactivated = true,
        )
        val child = ShaclShape(EX + "PersonShape", EX + "Person", listOf(prop("label")), parentClasses = listOf(EX + "Agent"))
        val model = OntologyModel(listOf(grand, parent, child), emptyContext)

        val label = constraints(model, "Person", "label")
        assertEquals(50, label.maxLength, "the active grandparent's constraint still applies, the deactivated parent's does not")
        assertFalse(label.deactivated)
        val code = constraints(model, "Person", "code")
        assertTrue(code.deactivated, "a member only a deactivated parent constrains is not validated")
        // The deactivated shape's own generated type keeps its declarations.
        assertEquals(3, constraints(model, "Agent", "label").maxLength)
    }
}
