@file:OptIn(KastorGenInternalApi::class)

package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handle equality is an explicit contract, `equals` of a graph never runs under the cache lock, and the number of
 * temporary states is bounded.
 */
class GraphStateCacheContractTest {

    private fun ex(local: String) = Iri("http://example.org/$local")
    private fun triple(i: Int) = RdfTriple(ex("s$i"), ex("p"), Literal("v$i"))
    private fun graph(vararg ids: Int): MemoryGraph = MemoryGraph().apply { ids.forEach { addTriple(triple(it)) } }

    private fun cache(max: Int) = GraphStateCache<Set<RdfTriple>>(
        maxEntries = max,
        exclusive = true,
        load = { triples, _ -> triples.toSet() },
        release = { },
    )

    private fun daemonPool() = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    /**
     * A foreign graph class with content equality whose properties are private (so it has no public `component1`),
     * as a data class with private properties or a Java record would be.
     */
    private class ContentEqual(private val content: List<RdfTriple>, private val equalsCalls: AtomicInteger) : RdfGraph {
        override fun hasTriple(triple: RdfTriple) = triple in content
        override fun getTriples() = content
        override fun size() = content.size
        override fun equals(other: Any?): Boolean {
            equalsCalls.incrementAndGet()
            return other is ContentEqual && other.content == content
        }
        override fun hashCode(): Int = content.hashCode()
    }

    /** A stamped handle of [source] with handle equality on [key]; [onEquals] runs inside `equals`. */
    private class Handle(val key: String, val source: MemoryGraph, val onEquals: () -> Unit = {}) : VersionedRdfGraph {
        override val modificationStamp: Long get() = source.modificationStamp
        override fun hasTriple(triple: RdfTriple) = source.hasTriple(triple)
        override fun getTriples() = source.getTriples()
        override fun size() = source.size()
        override fun equals(other: Any?): Boolean {
            onEquals()
            return other is Handle && other.key == key
        }
        override fun hashCode(): Int = key.hashCode()
    }

    @Test
    fun `a foreign class with content equality is matched by instance and content and its equals is never called`() {
        val cache = cache(4)
        val equalsCalls = AtomicInteger()
        cache.use(ContentEqual(listOf(triple(1)), equalsCalls)) { }
        cache.use(ContentEqual(listOf(triple(2)), equalsCalls)) { }
        cache.use(ContentEqual(listOf(triple(1)), equalsCalls)) { assertEquals(setOf(triple(1)), it) }
        assertEquals(0, equalsCalls.get(), "equals of a class that does not declare handle equality is not consulted")
        assertEquals(2, cache.loadCount, "equal content is found by its digest")
        assertEquals(2, cache.size)
    }

    @Test
    fun `equal handles share a state and their equals runs outside the cache lock`() {
        val cache = cache(4)
        val source = graph(1)
        val inEquals = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        // The cache compares the handle it remembers with a new one; that comparison blocks until released below.
        val firstHandle = Handle("g", source) { inEquals.countDown(); proceed.await(30, TimeUnit.SECONDS) }
        cache.use(firstHandle) { }
        val pool = daemonPool()
        try {
            val blocked = pool.submit<Set<RdfTriple>> { cache.use(Handle("g", source)) { it } }
            assertTrue(inEquals.await(10, TimeUnit.SECONDS), "the equal handle is compared with equals")
            // Had the comparison run under the cache lock, no other graph could be used meanwhile.
            val other = pool.submit<Set<RdfTriple>> { cache.use(graph(2)) { it } }
            assertEquals(setOf(triple(2)), other.get(10, TimeUnit.SECONDS))
            proceed.countDown()
            assertEquals(setOf(triple(1)), blocked.get(10, TimeUnit.SECONDS))
            assertEquals(2, cache.loadCount, "the equal handle found the state of the first one")
            assertEquals("g", firstHandle.key)
        } finally {
            proceed.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `temporary states are bounded - further graphs wait for a state to become free`() {
        val waiting = CountDownLatch(1)
        val cache = GraphStateCache<Set<RdfTriple>>(
            maxEntries = 1,
            exclusive = true,
            load = { triples, _ -> triples.toSet() },
            release = { },
            owner = "TestCache",
            // No overflow: the bound on the number of states is strict, a further call waits.
            settings = GraphStateCache.Settings(
                overflowWaitMillis = null,
                temporaryWaitMillis = 60_000,
                probe = { event -> if (event == GraphStateCache.Event.SLOT_WAIT) waiting.countDown() },
            ),
        )
        val pool = daemonPool()
        val finish = List(3) { CountDownLatch(1) }
        val entered = List(3) { CountDownLatch(1) }
        try {
            fun start(i: Int) = pool.submit<Set<RdfTriple>> {
                cache.use(graph(i)) { state -> entered[i].countDown(); finish[i].await(30, TimeUnit.SECONDS); state }
            }
            val first = start(0)
            assertTrue(entered[0].await(10, TimeUnit.SECONDS))
            val second = start(1) // the only entry is in use: a temporary state
            assertTrue(entered[1].await(10, TimeUnit.SECONDS))
            assertEquals(1, cache.temporaryCount)
            val third = start(2) // one cached and one temporary state are in use: no third copy is built
            assertTrue(waiting.await(10, TimeUnit.SECONDS), "the third call waits for a free state")
            assertEquals(2, cache.loadCount, "a third state was built while two were in use")
            assertEquals(1L, entered[2].count)
            finish[1].countDown()
            assertEquals(setOf(triple(1)), second.get(10, TimeUnit.SECONDS))
            assertTrue(entered[2].await(10, TimeUnit.SECONDS), "the waiting call proceeds once a state is free")
            finish[2].countDown()
            finish[0].countDown()
            assertEquals(setOf(triple(2)), third.get(10, TimeUnit.SECONDS))
            assertEquals(setOf(triple(0)), first.get(10, TimeUnit.SECONDS))
        } finally {
            finish.forEach { it.countDown() }
            pool.shutdownNow()
        }
    }
}
