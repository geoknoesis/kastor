package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** High-degree (star-shaped) graphs must be matched in near-linear time under the default limits. */
class IsomorphismStarTest {
    private val p = Iri("urn:p")
    private val q = Iri("urn:q")
    private val back = Iri("urn:back")
    private val degree = 50_000

    /** A blank hub with [degree] blank children; each child points back to the hub and carries a leaf label. */
    private fun star(prefix: String, label: (Int) -> String, odd: Int = -1): MemoryGraph {
        val hub = BlankNode("${prefix}hub")
        return MemoryGraph(buildList {
            for (i in 0 until degree) {
                val child = BlankNode("$prefix$i")
                add(RdfTriple(hub, p, child))
                add(RdfTriple(child, back, hub))
                add(RdfTriple(child, q, string(if (i == odd) "different" else label(i))))
            }
        })
    }

    @Test
    fun `a star with fifty thousand identical children is matched quickly`() {
        val left = star("a", { "same" })
        val right = star("b", { "same" })
        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            val mapping = left.findBlankNodeMapping(right)
            assertEquals(degree + 1, mapping?.size)
            assertEquals(BlankNode("bhub"), mapping!![BlankNode("ahub")])
            assertEquals(degree + 1, mapping.values.toSet().size)
            assertFalse(left.isIsomorphicTo(star("b", { "same" }, odd = degree / 2)))
        }
    }

    @Test
    fun `a star with fifty thousand distinguishable children is matched quickly`() {
        val left = star("a", { "v$it" })
        // Same labels in reverse child order, so identity of blank-node ids cannot help.
        val right = star("b", { "v${degree - 1 - it}" })
        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            val mapping = left.findBlankNodeMapping(right)
            assertEquals(degree + 1, mapping?.size)
            assertEquals(BlankNode("b${degree - 1 - 7}"), mapping!![BlankNode("a7")])
            assertFalse(left.isIsomorphicTo(star("b", { "v$it" }, odd = 3)))
        }
    }

    @Test
    fun `the default wall-clock limit is sixty seconds and an explicit limit fires`() {
        assertEquals(Duration.ofSeconds(60), WeisfeilerLehmanIsomorphism().effectiveTimeout)
        assertEquals(Duration.ofSeconds(60), WeisfeilerLehmanIsomorphism(10).effectiveTimeout)
        assertEquals(null, WeisfeilerLehmanIsomorphism(10, null, null).effectiveTimeout)
        val left = star("a", { "same" })
        val right = star("b", { "same" })
        val error = assertThrows(IllegalStateException::class.java) {
            left.isIsomorphicTo(right, null, Duration.ofNanos(1))
        }
        assertTrue(error.message!!.contains("time limit"), error.message)
    }
}
