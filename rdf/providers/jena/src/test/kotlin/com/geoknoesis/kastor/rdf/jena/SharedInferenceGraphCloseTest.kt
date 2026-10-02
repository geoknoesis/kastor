package com.geoknoesis.kastor.rdf.jena

import org.apache.jena.graph.NodeFactory
import org.apache.jena.graph.Triple
import org.apache.jena.query.QueryCancelledException
import org.apache.jena.reasoner.InfGraph
import org.apache.jena.util.iterator.ExtendedIterator
import org.apache.jena.util.iterator.NiceIterator
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A streamed find keeps the reasoner's iterator open on the owner between chunks. When a step fails or its reader
 * is cancelled, that iterator must still be closed on the owner.
 */
class SharedInferenceGraphCloseTest {
    private fun triple(n: Int): Triple =
        Triple.create(NodeFactory.createURI("urn:s$n"), NodeFactory.createURI("urn:p"), NodeFactory.createURI("urn:o"))

    /** Reasoner iterator that records whether it was closed. */
    private class TrackedIterator(count: Int, private val make: (Int) -> Triple) : NiceIterator<Triple>() {
        private val remaining = (0 until count).iterator()
        var closed = false
        override fun hasNext(): Boolean = remaining.hasNext()
        override fun next(): Triple = make(remaining.next())
        override fun close() { closed = true }
    }

    /** An [InfGraph] that only answers `find(Triple)`. */
    private fun infGraph(source: ExtendedIterator<Triple>): InfGraph =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(InfGraph::class.java)) { _, method, _ ->
            if (method.name == "find") source else throw UnsupportedOperationException(method.name)
        } as InfGraph

    /** Runs steps inline; [afterStep] can make the reader's wait for a step fail. */
    private class InlineExecutor(var afterStep: () -> Unit = {}) : InferenceExecutor {
        override fun <T> call(block: () -> T): T {
            val result = block()
            afterStep()
            return result
        }

        override fun submitQuietly(block: () -> Unit) = block()
    }

    @Test
    fun `the reasoner iterator is closed when the reader of a step is cancelled`() {
        val source = TrackedIterator(1_000, ::triple)
        // The step ran (it opened the reasoner's iterator), but its reader was cancelled while waiting for it.
        val executor = InlineExecutor(afterStep = { throw QueryCancelledException() })
        val iterator = SharedInferenceGraph(infGraph(source), executor).find(Triple.ANY)
        val failure = assertFailsWith<QueryCancelledException> { iterator.hasNext() }
        assertTrue(source.closed, "the reasoner's iterator must be closed on the owner after a cancelled step")
        // A failed iterator stays failed: it must never end as if its results were complete.
        executor.afterStep = {}
        assertSame(failure, assertFailsWith<QueryCancelledException> { iterator.hasNext() })
        assertSame(failure, assertFailsWith<QueryCancelledException> { iterator.next() })
        iterator.close()
        assertFalse(iterator.hasNext(), "a closed iterator is finished")
    }

    @Test
    fun `closing a partially consumed find closes the reasoner iterator once`() {
        val source = TrackedIterator(1_000, ::triple)
        val iterator = SharedInferenceGraph(infGraph(source), InlineExecutor()).find(Triple.ANY)
        assertEquals(triple(0), iterator.next())
        assertFalse(source.closed)
        iterator.close()
        assertTrue(source.closed)
    }
}
