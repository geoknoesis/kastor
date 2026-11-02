package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Eager (`NestedMode.DATA_CLASS`) materialization recurses once per link of the data: a long acyclic chain ended in
 * `StackOverflowError`. It now stops at [OntoMapper.MAX_EAGER_DEPTH] nested objects with a
 * [MaterializationException], also on a thread with a small stack.
 */
class MaterializationDepthTest {
    private val next = Iri("https://example.test/next")

    data class Link(val id: String, val next: List<Link>) : RdfProjection

    @AfterEach
    fun cleanup() {
        OntoMapper.unregister(Link::class.java)
    }

    private fun chain(links: Int): MemoryGraph = MemoryGraph().apply {
        for (i in 0 until links) addTriple(RdfTriple(Iri("urn:n$i"), next, Iri("urn:n${i + 1}")))
    }

    /** Runs [block] on a thread with a 512 KiB stack and returns its outcome. */
    private fun onSmallStack(block: () -> Any): Any? {
        var outcome: Any? = null
        val thread = Thread(null, { outcome = try { block() } catch (e: Throwable) { e } }, "small-stack", 512L * 1024)
        thread.start()
        thread.join()
        return outcome
    }

    private fun depth(link: Link): Int = generateSequence(link) { it.next.firstOrNull() }.count()

    @Test
    fun `a chain longer than the depth cap fails with a MaterializationException, not a StackOverflowError`() {
        OntoMapper.register(Link::class.java, replace = true) { handle ->
            Link(handle.node.toString(), KastorGraphOps.getObjectValues(handle.graph, handle.node, next) { child ->
                OntoMapper.materialize(RdfRef(child, handle.graph), Link::class.java)
            })
        }
        for (links in listOf(OntoMapper.MAX_EAGER_DEPTH, 1_000, 100_000)) {
            val graph = chain(links)
            val outcome = onSmallStack { OntoMapper.materialize(RdfRef(Iri("urn:n0"), graph), Link::class.java) }
            assertTrue(outcome is MaterializationException, "$links: $outcome")
            assertTrue(outcome.message!!.contains("${OntoMapper.MAX_EAGER_DEPTH}"), outcome.message)
            assertTrue(outcome.message!!.contains("NestedMode.INTERFACE"), outcome.message)
        }
        // At the cap: MAX_EAGER_DEPTH nested objects (MAX_EAGER_DEPTH - 1 links) materialize, on the same small stack.
        val graph = chain(OntoMapper.MAX_EAGER_DEPTH - 1)
        val link = onSmallStack { OntoMapper.materialize(RdfRef(Iri("urn:n0"), graph), Link::class.java) }
        assertTrue(link is Link, "$link")
        assertEquals(OntoMapper.MAX_EAGER_DEPTH, depth(link))
        // The scope is cleaned up after a failure: the next call on the thread starts from depth zero.
        val outcomes = onSmallStack {
            listOf(
                runCatching { OntoMapper.materialize(RdfRef(Iri("urn:n0"), chain(1_000)), Link::class.java) }.isFailure,
                depth(OntoMapper.materialize(RdfRef(Iri("urn:n0"), chain(10)), Link::class.java)),
            )
        }
        assertEquals(listOf(true, 11), outcomes)
    }
}
