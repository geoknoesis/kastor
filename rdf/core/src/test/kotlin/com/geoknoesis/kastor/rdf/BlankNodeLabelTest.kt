package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Blank node labels: injective for unpaired surrogates, stable across serialize/parse round trips. */
class BlankNodeLabelTest {
    private fun label(id: String) = BlankNode(id).toString().removePrefix("_:")

    @Test
    fun `ids with unpaired surrogates do not collide with each other or with question marks`() {
        val ids = listOf(
            "a\uD800", "a?", "a\uDBFF", "a\uDC00", "a\uDC00\uD800", "a\uD800?", "a??",
            "a b", "a_b", "a\u0000b", "nodeID://b1", "-x", "x.", "\uD800", "\uDFFF", "?",
        )
        val labels = ids.map(::label)
        assertEquals(ids.size, labels.toSet().size, labels.toString())
        labels.forEach { assertTrue(isValidBlankNodeLabel(it), it) }
        // A properly paired surrogate is a valid supplementary code point and stays readable.
        assertEquals("a𐀀", label("a𐀀"))
    }

    @Test
    fun `labels are injective, including ids that collide with the ux_ encoding`() {
        val ids = listOf(
            "ux_00610020", "a ", "a b", "ux_", "ux__", "ux___", "ux_zz", "ux__zz", "ux_0061", "a", "ux", "ux_a", "ux__a",
            "b1", "a\uD800", "nodeID://b1", "été", "x.", "ux_.x", "_ux_", "Ux_0061", "ux-0061",
        )
        val labels = ids.map(::label)
        assertEquals(ids.size, labels.toSet().size, ids.zip(labels).toString())
        labels.forEach { assertTrue(isValidBlankNodeLabel(it), it) }
        // The former collision pair now serializes to distinct labels.
        assertNotEquals(label("ux_00610020"), label("a "))
        assertEquals("ux_00610020", label("a "))
        // Valid ids that start with ux_ are escaped by one extra underscore; other valid ids are unchanged.
        assertEquals("ux__00610020", label("ux_00610020"))
        assertEquals("ux__", label("ux_"))
        assertEquals("b1", label("b1"))
        assertEquals("Ux_0061", label("Ux_0061"))
    }

    @Test
    fun `labels of generated ids are pairwise distinct`() {
        val alphabet = listOf("u", "x", "_", "0", "6", "a", " ", ".", "-", "\uD800")
        val ids = HashSet<String>()
        fun grow(prefix: String, depth: Int) {
            if (prefix.isNotBlank()) ids.add(prefix)
            if (depth == 0) return
            alphabet.forEach { grow(prefix + it, depth - 1) }
        }
        grow("", 4)
        grow("ux_", 3)
        // Include the encoded labels themselves as ids: they must not collide with what they encode.
        ids.addAll(ids.filter { !isValidBlankNodeLabel(it) }.map(::label))
        val byLabel = ids.groupBy(::label)
        val collisions = byLabel.filterValues { it.size > 1 }
        assertTrue(collisions.isEmpty(), collisions.entries.take(5).toString())
        byLabel.keys.forEach { assertTrue(isValidBlankNodeLabel(it), it) }
    }

    @Test
    fun `encoded labels survive a parse and serialize round trip and grow by one character`() {
        val node = BlankNode("nodeID://b1\uD800")
        val first = node.toString()
        val parsed = Rdf.parse("$first <urn:p> <urn:o> .", RdfFormat.N_TRIPLES).getTriples().single().subject as BlankNode
        assertEquals(1, Rdf.parse("${parsed} <urn:p> <urn:o> .", RdfFormat.N_TRIPLES).size())
        val again = BlankNode(first.removePrefix("_:")).toString()
        assertEquals("_:ux__" + first.removePrefix("_:ux_"), again)
    }
}
