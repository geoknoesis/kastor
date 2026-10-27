package com.geoknoesis.kastor.rdf.jena

import org.apache.jena.graph.NodeFactory
import org.apache.jena.sparql.expr.NodeValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The large-literal clean-up of Jena's `NodeValue` cache reaches the cache through reflection on a private field. With
 * the pinned Jena version the field must be found, so a Jena upgrade that removes or retypes it fails this test
 * instead of silently skipping the clean-up; a missing field is reported with one warning.
 */
class JenaNodeValueCacheTest {

    @Test
    fun `the cache is found with the pinned Jena version`() {
        assertTrue(JenaNodeValueCache.available, "NodeValue.nodeValueCache was not found: the clean-up of large literals is skipped")
    }

    @Test
    fun `a large literal is removed from the cache, a small one is kept`() {
        val cache = JenaNodeValueCache.lookUp(NodeValue::class.java, "nodeValueCache") { error(it) }
        assertNotNull(cache)
        val large = NodeFactory.createLiteralString("x".repeat(JenaNodeValueCache.MAX_CACHED_LENGTH + 1))
        val small = NodeFactory.createLiteralString("small-cache-test")
        val values = listOf(large, small).map { node -> NodeValue.makeNode(node).also { cache!!.put(node, it) } }
        values.forEach(JenaNodeValueCache::forget)
        assertNull(cache!!.getIfPresent(large))
        assertNotNull(cache.getIfPresent(small))
    }

    private class NoCache

    @Test
    fun `a missing field is reported once, with the reason`() {
        val warnings = mutableListOf<String>()
        assertNull(JenaNodeValueCache.lookUp(NoCache::class.java, "nodeValueCache") { warnings.add(it) })
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("nodeValueCache"), warnings.single())
    }
}
