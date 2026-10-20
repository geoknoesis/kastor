@file:OptIn(KastorGenInternalApi::class)

package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GraphStateCacheLifetimeTest {
    private class State {
        val releases = AtomicInteger()
    }

    private fun graph() = MemoryGraph().apply { change(this, 1) }

    private fun change(graph: MemoryGraph, id: Int) {
        graph.addTriple(RdfTriple(Iri("urn:s:$id"), Iri("urn:p"), Literal("$id")))
    }

    private fun cache(failRelease: Boolean = false) = GraphStateCache<State>(
        maxEntries = 1,
        exclusive = false,
        load = { _, previous -> assertEquals(null, previous); State() },
        release = {
            it.releases.incrementAndGet()
            if (failRelease) throw IllegalStateException("release failed")
        },
    )

    @Test
    fun `a replaced generation stays alive until both readers leave including after close`() {
        val cache = cache()
        val graph = graph()
        val original = cache.use(graph) { it }
        val entered = CountDownLatch(2)
        val finish = List(2) { CountDownLatch(1) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val readers = finish.map { gate ->
                pool.submit<Boolean> {
                    cache.use(graph) {
                        assertSame(original, it)
                        entered.countDown()
                        assertTrue(gate.await(10, TimeUnit.SECONDS))
                        it.releases.get() == 0
                    }
                }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            change(graph, 2)
            val replacement = cache.use(graph) { it }
            assertFalse(original === replacement)
            assertEquals(0, original.releases.get())
            finish[0].countDown()
            assertTrue(readers[0].get(10, TimeUnit.SECONDS))
            assertEquals(0, original.releases.get(), "the second reader still owns the old generation")
            cache.close()
            finish[1].countDown()
            assertTrue(readers[1].get(10, TimeUnit.SECONDS))
            assertEquals(1, original.releases.get())
            assertEquals(1, replacement.releases.get())
            cache.close()
            assertEquals(1, original.releases.get(), "close is idempotent")
        } finally {
            finish.forEach { it.countDown() }
            pool.shutdownNow()
            cache.close()
        }
    }

    @Test
    fun `a throwing reader releases its retired generation without masking the failure`() {
        cache(failRelease = true).use { cache ->
            val graph = graph()
            var original: State? = null
            val expected = IllegalArgumentException("reader failed")
            val failure = assertThrows(IllegalArgumentException::class.java) {
                cache.use(graph) { state ->
                    original = state
                    change(graph, 2)
                    cache.use(graph) { assertFalse(it === state) }
                    assertEquals(0, state.releases.get())
                    throw expected
                }
            }
            assertSame(expected, failure)
            assertEquals(1, original!!.releases.get())
            // Close propagates the current generation's release error; the retired generation is not released twice.
            assertThrows(IllegalStateException::class.java) { cache.close() }
            assertEquals(1, original.releases.get())
        }
    }

    @Test
    fun `successive replacements release each generation as its own reader leaves`() {
        cache().use { cache ->
            val graph = graph()
            lateinit var first: State
            lateinit var second: State
            cache.use(graph) { outer ->
                first = outer
                change(graph, 2)
                cache.use(graph) { middle ->
                    second = middle
                    change(graph, 3)
                    cache.use(graph) { assertEquals(0, it.releases.get()) }
                    assertEquals(0, first.releases.get())
                    assertEquals(0, second.releases.get())
                }
                assertEquals(0, first.releases.get())
                assertEquals(1, second.releases.get())
            }
            assertEquals(1, first.releases.get())
            assertEquals(1, second.releases.get())
        }
    }
}
