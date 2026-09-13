package com.geoknoesis.kastor.ontoquality.embed

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource
import kotlin.math.sqrt
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Exact metric-tree range search over unit embeddings. Dense outputs remain inherently quadratic. */
class SimilarityIndex(embeddings: Map<RdfResource, FloatArray>) {
    private val entries = embeddings.entries.mapNotNull { (key, value) -> (key as? Iri)?.let { it to value.copyOf() } }
    init {
        val dimension = entries.firstOrNull()?.second?.size ?: 0
        entries.forEach { (_, vector) ->
            require(dimension > 0 && vector.size == dimension && vector.all { it.isFinite() }) { "Embedding dimensions must agree and values must be finite" }
            val norm = vector.sumOf { it.toDouble() * it }
            require(kotlin.math.abs(norm - 1.0) < 0.001) { "Similarity embeddings must be L2 normalized" }
        }
    }
    private data class Node(val index: Int, val split: Double, val near: Node?, val far: Node?) {
        // A query only emits pairs with a later entry. Entire earlier subtrees can be skipped.
        val maxIndex: Int = maxOf(index, near?.maxIndex ?: index, far?.maxIndex ?: index)
    }
    private class Budget(val limits: SimilaritySearchLimits) {
        private val start = System.nanoTime()
        private val timeout = try { limits.timeout.toNanos() } catch (_: ArithmeticException) { Long.MAX_VALUE }
        private var evaluations = 0L
        fun check() {
            kotlin.check(!Thread.currentThread().isInterrupted) { "Similarity search interrupted" }
            kotlin.check(System.nanoTime() - start < timeout) { "Similarity search deadline exceeded" }
        }
        fun evaluate() {
            check()
            kotlin.check(evaluations++ < limits.maxDistanceEvaluations) { "Similarity distance-evaluation limit exceeded" }
        }
    }
    private fun distance(a: Int, b: Int, budget: Budget): Double {
        budget.evaluate()
        val av = entries[a].second; val bv = entries[b].second
        var sum = 0.0
        for (i in av.indices) { val d = av[i].toDouble() - bv[i]; sum += d * d }
        return sqrt(sum)
    }
    private fun build(indices: List<Int>, budget: Budget): Node? {
        budget.check()
        if (indices.isEmpty()) return null
        val pivot = indices.first()
        val ordered = indices.drop(1).map { it to distance(pivot, it, budget) }
            .sortedWith { a, b -> budget.check(); a.second.compareTo(b.second) }
        val median = ordered.size / 2
        return Node(pivot, ordered.getOrNull(median)?.second ?: 0.0,
            build(ordered.take(median).map { it.first }, budget), build(ordered.drop(median).map { it.first }, budget))
    }
    @Volatile private var treeReady = false
    private var root: Node? = null
    private val treeLock = ReentrantLock()
    private fun tree(budget: Budget): Node? {
        budget.check()
        if (treeReady) return root
        try {
            while (!treeLock.tryLock(10, TimeUnit.MILLISECONDS)) budget.check()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Similarity tree wait interrupted", interrupted)
        }
        try {
            budget.check()
            if (!treeReady) {
                root = build(entries.indices.toList(), budget)
                treeReady = true
            }
            return root
        } finally { treeLock.unlock() }
    }
    fun pairsAboveThreshold(threshold: Double): Sequence<Pair<Iri, Iri>> =
        pairsAboveThreshold(threshold, SimilaritySearchLimits())

    /** Throws on exhaustion instead of silently returning an incomplete exact result. */
    fun pairsAboveThreshold(threshold: Double, limits: SimilaritySearchLimits): Sequence<Pair<Iri, Iri>> {
        require(threshold.isFinite() && threshold in -1.0..1.0)
        return sequence {
            val budget = Budget(limits)
            val searchRoot = tree(budget)
            var pairs = 0
            // The norm tolerance is included in the radius to avoid discarding boundary candidates.
            val radius = sqrt(2.002 - 2 * threshold) + 1e-8
            val stack = ArrayDeque<Node>()
            for (i in entries.indices) {
                searchRoot?.let(stack::addLast)
                while (stack.isNotEmpty()) {
                    val n = stack.removeLast()
                    if (n.maxIndex <= i) continue
                    val d = distance(i, n.index, budget)
                    if (n.index > i && d <= radius) {
                        val a = entries[i]; val b = entries[n.index]
                        var dot = 0.0
                        for (k in a.second.indices) dot += a.second[k].toDouble() * b.second[k]
                        if (dot >= threshold) {
                            check(pairs++ < limits.maxPairs) { "Similarity result limit exceeded (${limits.maxPairs} pairs)" }
                            yield(if (a.first.value < b.first.value) a.first to b.first else b.first to a.first)
                        }
                    }
                    if (d - radius <= n.split) n.near?.let(stack::addLast)
                    if (d + radius >= n.split) n.far?.let(stack::addLast)
                }
            }
        }
    }
}
