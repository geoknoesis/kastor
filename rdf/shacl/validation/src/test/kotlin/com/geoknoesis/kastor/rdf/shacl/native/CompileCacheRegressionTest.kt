package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.StaleShapesGraphTagException
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompileCacheRegressionTest {
    @Test fun `cache and version history remain bounded and can be cleared`() {
        val cache = NativeCompileCache(4, tagCapacity = 4)
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

    @Test fun `capacity eviction never drops an in-flight compilation`() {
        val cache = NativeCompileCache(1)
        val value = ShapesCompiler.compile(emptyList(), ValidationConfig())
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val compilesOfA = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val owner = pool.submit<Any> {
                cache.getOrCompile("a") {
                    compilesOfA.incrementAndGet()
                    started.countDown()
                    check(release.await(60, TimeUnit.SECONDS))
                    value
                }
            }
            assertEquals(true, started.await(60, TimeUnit.SECONDS))
            // A second key fills the cache past its capacity while "a" is still compiling.
            cache.getOrCompile("b") { value }
            // A late caller for "a" must join the running compilation, not start another one.
            val joiner = pool.submit<Any> { cache.getOrCompile("a") { compilesOfA.incrementAndGet(); value } }
            release.countDown()
            owner.get(60, TimeUnit.SECONDS)
            joiner.get(60, TimeUnit.SECONDS)
            assertEquals(1, compilesOfA.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `version tags are not silently forgotten at the entry capacity`() {
        val cache = NativeCompileCache(2)
        repeat(10) { cache.assertTagOrRecord("v$it", "digest$it") }
        assertFailsWith<StaleShapesGraphTagException> { cache.assertTagOrRecord("v0", "changed") }
    }
}
