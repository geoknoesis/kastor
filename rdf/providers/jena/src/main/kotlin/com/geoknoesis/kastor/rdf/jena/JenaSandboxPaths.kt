package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.sparql.internal.SandboxLimitException
import org.apache.jena.graph.Graph
import org.apache.jena.graph.Node
import org.apache.jena.graph.Triple
import org.apache.jena.sparql.algebra.op.OpPath
import org.apache.jena.sparql.core.TriplePath
import org.apache.jena.sparql.engine.ExecutionContext
import org.apache.jena.sparql.engine.QueryIterator
import org.apache.jena.sparql.engine.binding.Binding
import org.apache.jena.sparql.engine.iterator.QueryIterRepeatApply
import org.apache.jena.sparql.engine.main.OpExecutor
import org.apache.jena.sparql.engine.main.OpExecutorFactory
import org.apache.jena.sparql.graph.GraphWrapper
import org.apache.jena.sparql.path.PathLib
import org.apache.jena.util.iterator.ExtendedIterator

/**
 * Property paths of a sandboxed query on Jena, **metered**: set as the execution's operator executor
 * ([org.apache.jena.sparql.ARQConstants.sysOpExecutorFactory]), it evaluates every `OpPath` with Jena's own path
 * engine (so the results are exactly Jena's) over a view of the active graph whose every `find` and every triple it
 * returns is charged to a [PathMeter]: the meter checks [budget] (the deadline, Jena's cancellation and the thread's
 * interrupt flag) every [PathMeter.CHECK_INTERVAL] steps, and fails the query with [SandboxLimitException] once one
 * path pattern has taken more than [MAX_PATH_STEPS] steps without producing a solution.
 *
 * Why: Jena evaluates a path for one binding by collecting every node it reaches into a list, duplicates included,
 * and checks no timeout while it does. A sequence nested in `?`, `*`, `+` or `|` (`(p/p/p/p/p/p)?` over a 20-node
 * complete graph) collected 20^6 nodes: it ran past a 1 s timeout and out of heap. Every node Jena collects comes
 * from a `find` or a triple of one, so the meter bounds both the time (to the deadline) and the size of what one
 * evaluation can collect (to [MAX_PATH_STEPS]), whatever the shape of the path.
 *
 * Jena's path evaluation recurses on the path (bounded by [JenaQuerySandbox]'s structural limit) and, for `*` and
 * `+`, on the length of the graph's chains; a chain too deep for the stack fails the query with
 * [SandboxLimitException] instead of an unwinding [StackOverflowError].
 */
internal class JenaSandboxPaths(private val budget: JenaSandboxBudget) : OpExecutorFactory {

    override fun create(execCxt: ExecutionContext): OpExecutor = Executor(execCxt)

    private inner class Executor(execCxt: ExecutionContext) : OpExecutor(execCxt) {
        override fun execute(opPath: OpPath, input: QueryIterator): QueryIterator =
            MeteredPath(opPath.triplePath, input, execCxt, budget)
    }

    /** One path pattern, evaluated for each input binding over a metered view of the active graph. */
    private class MeteredPath(
        private val path: TriplePath,
        input: QueryIterator,
        private val cxt: ExecutionContext,
        budget: JenaSandboxBudget,
    ) : QueryIterRepeatApply(input, cxt) {
        private val meter = PathMeter(budget, cxt)
        private val pathCxt = ExecutionContext.copyChangeActiveGraph(cxt, MeteredGraph(cxt.activeGraph, meter))

        override fun nextStage(binding: Binding): QueryIterator = metered {
            meter.reset()
            PathLib.execTriplePath(binding, path, pathCxt)
        }

        override fun hasNextBinding(): Boolean = metered { super.hasNextBinding() }

        // The meter counts the work towards the next solution: what one evaluation can collect before it yields.
        override fun moveToNextBinding(): Binding = metered { super.moveToNextBinding() }.also { meter.reset() }

        private inline fun <T> metered(block: () -> T): T = JenaSandboxBounds.failing {
            try {
                block()
            } catch (e: StackOverflowError) {
                throw SandboxLimitException("A property path ($path) nests or chains too deeply to evaluate")
            }
        }
    }

    /** A view of [graph] whose finds and the triples they return are charged to [meter]. */
    private class MeteredGraph(graph: Graph, private val meter: PathMeter) : GraphWrapper(graph) {
        override fun find(triple: Triple): ExtendedIterator<Triple> = metered(super.find(triple))

        override fun find(s: Node, p: Node, o: Node): ExtendedIterator<Triple> = metered(super.find(s, p, o))

        override fun contains(s: Node, p: Node, o: Node): Boolean = meter.tick().let { super.contains(s, p, o) }

        override fun contains(t: Triple): Boolean = meter.tick().let { super.contains(t) }

        private fun metered(found: ExtendedIterator<Triple>): ExtendedIterator<Triple> {
            meter.tick()
            return found.filterKeep { meter.tick(); true }
        }
    }

    /** The steps of one path pattern since its last solution, checked against the budget and [MAX_PATH_STEPS]. */
    private class PathMeter(private val budget: JenaSandboxBudget, private val cxt: ExecutionContext) {
        private var steps = 0L

        fun reset() {
            steps = 0
        }

        fun tick() {
            if (++steps % CHECK_INTERVAL == 0L) {
                budget.check(cxt)
                if (steps > MAX_PATH_STEPS) {
                    throw SandboxLimitException(
                        "A property path took more than $MAX_PATH_STEPS steps (graph reads) without producing a solution",
                    )
                }
            }
        }

        companion object {
            const val CHECK_INTERVAL = 256L
        }
    }

    companion object {
        /**
         * Most graph reads (finds and the triples they return) one path pattern may take between two of its solutions:
         * the bound of what one evaluation of Jena's path engine collects in memory (each collected node costs a read).
         */
        const val MAX_PATH_STEPS: Long = 4L * 1024 * 1024
    }
}
