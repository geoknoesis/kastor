package com.geoknoesis.kastor.ontoquality.metrics.integration

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Descendant counting must not keep one node-indexed set per parent: a tree needs no set at all, and a DAG keeps
 * sets over its shared classes only, within a memory budget. Bounds are asserted on the counting seam
 * ([DescendantCountStats]), never on wall-clock time or heap size.
 */
class DescendantCountScaleTest {

    /** Root -> 100 top classes -> 1,000 mid-level parents each -> 2 or 3 leaves each (350,101 classes). */
    private fun taxonomy(): Pair<Set<String>, Map<String, Set<String>>> {
        val children = HashMap<String, MutableSet<String>>()
        val nodes = LinkedHashSet<String>()
        nodes.add("root")
        var leaves = 0
        for (t in 0 until 100) {
            val top = "t$t"
            nodes.add(top)
            children.getOrPut("root") { LinkedHashSet() }.add(top)
            for (m in 0 until 1_000) {
                val mid = "t${t}m$m"
                nodes.add(mid)
                children.getOrPut(top) { LinkedHashSet() }.add(mid)
                val n = if (m % 2 == 0) 2 else 3
                for (l in 0 until n) {
                    val leaf = "$mid-$l"
                    nodes.add(leaf)
                    children.getOrPut(mid) { LinkedHashSet() }.add(leaf)
                    leaves++
                }
            }
        }
        assertEquals(250_000, leaves)
        return nodes to children
    }

    @Test
    fun `a 350k class tree with 100k mid-level parents keeps no descendant set`() {
        val (nodes, children) = taxonomy()
        val stats = DescendantCountStats()
        val counts = countTransitiveDescendants(nodes, children, stats = stats)

        assertEquals(350_100, counts.getValue("root"))
        assertEquals(1_000 + 2_500, counts.getValue("t7"))
        assertEquals(2, counts.getValue("t7m0"))
        assertEquals(3, counts.getValue("t7m1"))
        assertEquals(0, counts.getValue("t7m1-2"))
        assertEquals(0L, stats.setsRetained, "a tree is counted by adding subtree sizes")
        assertEquals(0L, stats.peakLiveBytes)
        assertFalse(stats.approximate)
    }

    @Test
    fun `shared descendants are counted once and sets cover shared classes only`() {
        // 2,000 parents over a tree of 100,000 leaves, plus 50 shared classes each under every parent.
        val children = HashMap<String, MutableSet<String>>()
        val nodes = LinkedHashSet<String>()
        nodes.add("root")
        for (p in 0 until 2_000) {
            val parent = "p$p"
            nodes.add(parent)
            children.getOrPut("root") { LinkedHashSet() }.add(parent)
            for (l in 0 until 50) {
                val leaf = "$parent-$l"
                nodes.add(leaf)
                children.getOrPut(parent) { LinkedHashSet() }.add(leaf)
            }
            for (s in 0 until 50) {
                children.getValue(parent).add("shared$s")
            }
        }
        for (s in 0 until 50) {
            nodes.add("shared$s")
            children.getOrPut("shared$s") { LinkedHashSet() }.add("shared$s-leaf")
            nodes.add("shared$s-leaf")
        }
        val stats = DescendantCountStats()
        val counts = countTransitiveDescendants(nodes, children, stats = stats)

        assertEquals(50 + 100, counts.getValue("p3"))
        assertEquals(2_000 + 100_000 + 100, counts.getValue("root"))
        assertEquals(1, counts.getValue("shared7"))
        assertFalse(stats.approximate)
        // One set of at most 50 shared classes per parent: 4 bytes per member, not one bit per class of the graph.
        assertTrue(stats.setsRetained <= 2_000, "sets retained: ${stats.setsRetained}")
        assertTrue(stats.peakLiveBytes <= 2_000L * 50 * 4, "peak live bytes: ${stats.peakLiveBytes}")
    }

    @Test
    fun `over the memory budget shared descendants are estimated within the documented error`() {
        // 200 layers of 40 classes, every class a subclass of every class of the layer above.
        val layers = 200
        val width = 40
        val children = HashMap<String, MutableSet<String>>()
        val nodes = LinkedHashSet<String>()
        for (l in 0 until layers) {
            for (i in 0 until width) {
                nodes.add("L$l-$i")
                if (l > 0) {
                    for (j in 0 until width) {
                        children.getOrPut("L${l - 1}-$j") { LinkedHashSet() }.add("L$l-$i")
                    }
                }
            }
        }
        val exact = countTransitiveDescendants(nodes, children)
        assertEquals((layers - 1) * width, exact.getValue("L0-0"))

        val budget = 32L * 1024
        val stats = DescendantCountStats()
        val estimated = countTransitiveDescendants(nodes, children, memoryBudgetBytes = budget, stats = stats)
        assertTrue(stats.approximate, "the exact sets do not fit in $budget bytes")
        assertTrue(stats.peakLiveBytes <= budget, "peak live bytes ${stats.peakLiveBytes} over budget $budget")
        // The bottom layer has no descendant; counts without shared descendants stay exact.
        assertEquals(0, estimated.getValue("L${layers - 1}-0"))
        val tolerance = 4 * descendantSketchStandardError(stats.sketchPrecision)
        for (l in 0 until layers - 1) {
            val expected = exact.getValue("L$l-0").toDouble()
            val got = estimated.getValue("L$l-0").toDouble()
            assertTrue(abs(got - expected) <= tolerance * expected + 2.0, "layer $l: expected about $expected, got $got")
        }
        // Same input, same estimate: the sketch hashes are not seeded per run.
        assertEquals(estimated, countTransitiveDescendants(nodes, children, memoryBudgetBytes = budget))
    }
}
