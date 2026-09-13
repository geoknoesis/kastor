package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CompileCacheRegressionTest {
    @Test fun `cache and version history remain bounded and can be cleared`() {
        val cache = NativeCompileCache(4)
        val value = ShapesCompiler.compile(emptyList(), ValidationConfig())
        repeat(100) {
            cache.assertTagOrRecord("v$it", "digest$it")
            cache.getOrCompile("key$it") { value }
        }
        cache.getOrCompile("key99") { error("must hit cache") }
        assertEquals(4, cache.statistics().entries)
        assertEquals(4, cache.statistics().versionTags)
        assertEquals(96L, cache.statistics().evictions)
        assertEquals(1L, cache.statistics().hits)
        cache.clear()
        assertEquals(0, cache.statistics().entries)
    }
}
