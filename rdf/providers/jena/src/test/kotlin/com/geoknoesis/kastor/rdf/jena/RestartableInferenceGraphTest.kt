package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.RdfInferenceException
import org.apache.jena.graph.Graph
import org.apache.jena.graph.NodeFactory
import org.apache.jena.graph.Triple
import org.apache.jena.util.iterator.ExtendedIterator
import org.apache.jena.util.iterator.NiceIterator
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RestartableInferenceGraph] repeats an operation on a fresh view exactly when that is safe: the failing step never
 * ran (its view was invalidated by another reader) and the caller holds no result of the operation yet.
 */
class RestartableInferenceGraphTest {
    private fun triple(n: Int): Triple =
        Triple.create(NodeFactory.createURI("urn:s$n"), NodeFactory.createURI("urn:p"), NodeFactory.createURI("urn:o"))

    /** A view's graph with [count] results that fails as invalidated once [failAfter] results were read. */
    private class ScriptedGraph(private val count: Int, private val failAfter: Int = Int.MAX_VALUE, private val failure: () -> Throwable = ::inferenceViewInvalidated) :
        org.apache.jena.graph.impl.GraphBase() {
        var finds = 0
        var closedFinds = 0

        override fun graphBaseFind(triplePattern: Triple): ExtendedIterator<Triple> {
            finds++
            return object : NiceIterator<Triple>() {
                private var next = 0
                override fun hasNext(): Boolean {
                    if (next >= failAfter) throw failure()
                    return next < count
                }
                override fun next(): Triple = Triple.create(NodeFactory.createURI("urn:s${next++}"), NodeFactory.createURI("urn:p"), NodeFactory.createURI("urn:o"))
                override fun close() { closedFinds++ }
            }
        }

        override fun graphBaseContains(t: Triple): Boolean {
            if (failAfter == 0) throw failure()
            return true
        }
    }

    private fun graphOver(vararg views: Graph): Pair<RestartableInferenceGraph, () -> Int> {
        var resolved = 0
        val graph = RestartableInferenceGraph { views[resolved++] }
        return graph to { resolved }
    }

    @Test
    fun `a find that has returned nothing restarts on the fresh view`() {
        val invalidated = ScriptedGraph(count = 5, failAfter = 0)
        val fresh = ScriptedGraph(count = 3)
        val (graph, resolved) = graphOver(invalidated, fresh)
        val results = graph.find(Triple.ANY).toList()
        assertEquals(listOf(triple(0), triple(1), triple(2)), results)
        assertEquals(2, resolved(), "resolved once more after the invalidation")
        assertEquals(1, fresh.finds)
    }

    @Test
    fun `a find that already returned results fails instead of restarting`() {
        val invalidated = ScriptedGraph(count = 5, failAfter = 2)
        val fresh = ScriptedGraph(count = 5)
        val (graph, resolved) = graphOver(invalidated, fresh)
        val iterator = graph.find(Triple.ANY)
        assertEquals(triple(0), iterator.next())
        assertEquals(triple(1), iterator.next())
        val error = assertFailsWith<RdfInferenceException> { iterator.hasNext() }
        assertTrue(error.message!!.contains("invalidated"), error.message)
        assertEquals(1, resolved(), "results of two runs must never be mixed")
        assertEquals(0, fresh.finds)
    }

    @Test
    fun `a lookup is repeated on the fresh view and gives up when views keep being invalidated`() {
        val (graph, resolved) = graphOver(ScriptedGraph(1, failAfter = 0), ScriptedGraph(1, failAfter = 0), ScriptedGraph(1))
        assertTrue(graph.contains(triple(0)))
        assertEquals(3, resolved())

        val (hopeless, attempts) = graphOver(*Array(10) { ScriptedGraph(1, failAfter = 0) })
        assertFailsWith<RdfInferenceException> { hopeless.contains(triple(0)) }
        assertEquals(4, attempts(), "the first attempt and three repetitions")
    }

    @Test
    fun `failures of a step that ran are never repeated`() {
        // An inference failure without the "never ran" marker, and any other exception: the step itself broke.
        val own = ScriptedGraph(5, failAfter = 0) { RdfInferenceException("the reasoner failed") }
        val (graph, resolved) = graphOver(own, ScriptedGraph(5))
        assertFailsWith<RdfInferenceException> { graph.find(Triple.ANY).hasNext() }
        assertFailsWith<RdfInferenceException> { graph.contains(triple(0)) }
        assertEquals(1, resolved())

        val cancelled = ScriptedGraph(5, failAfter = 0) { org.apache.jena.query.QueryCancelledException() }
        val (other, resolvedOther) = graphOver(cancelled, ScriptedGraph(5))
        assertFailsWith<org.apache.jena.query.QueryCancelledException> { other.find(Triple.ANY).hasNext() }
        assertEquals(1, resolvedOther())
    }

    @Test
    fun `size counts what find exposes and closing ends the iteration`() {
        val (graph, _) = graphOver(ScriptedGraph(7))
        assertEquals(7, graph.size())
        assertFalse(graph.isEmpty)
        val view = ScriptedGraph(7)
        val (closing, _) = graphOver(view)
        val iterator = closing.find(Triple.ANY)
        assertEquals(triple(0), iterator.next())
        iterator.close()
        assertEquals(1, view.closedFinds)
        assertFalse(iterator.hasNext(), "a closed iterator is finished")
    }
}
