package com.geoknoesis.kastor.ontoquality.metrics.compute

import kotlin.math.min

internal object CycleDetector {
    /**
     * Tarjan SCC over directed edges [successors]: node -> successor IRIs.
     * Cycle participants: non-trivial SCCs (size > 1) or single-node SCC with a self-loop.
     */
    fun cycleParticipants(nodes: Collection<String>, successors: Map<String, Set<String>>): Set<String> {
        val nodeSet = nodes.toSet()
        val adj = successors.mapValues { (_, v) -> v.filter { it in nodeSet }.toSet() }

        var indexCounter = 0
        val index = mutableMapOf<String, Int>()
        val lowlink = mutableMapOf<String, Int>()
        val stack = ArrayDeque<String>()
        val onStack = mutableSetOf<String>()
        val sccs = mutableListOf<Set<String>>()

        fun strongConnect(v: String) {
            index[v] = indexCounter
            lowlink[v] = indexCounter
            indexCounter++
            stack.addLast(v)
            onStack.add(v)
            for (w in adj[v].orEmpty()) {
                when {
                    w !in index -> {
                        strongConnect(w)
                        lowlink[v] = min(lowlink[v]!!, lowlink[w]!!)
                    }
                    w in onStack -> lowlink[v] = min(lowlink[v]!!, index[w]!!)
                }
            }
            if (lowlink[v] == index[v]) {
                val comp = mutableSetOf<String>()
                while (true) {
                    val w = stack.removeLast()
                    onStack.remove(w)
                    comp.add(w)
                    if (w == v) break
                }
                sccs.add(comp)
            }
        }

        for (n in nodeSet) {
            if (n !in index) strongConnect(n)
        }

        val bad = mutableSetOf<String>()
        for (comp in sccs) {
            when {
                comp.size > 1 -> bad.addAll(comp)
                comp.size == 1 -> {
                    val only = comp.single()
                    if (only in adj[only].orEmpty()) bad.add(only)
                }
            }
        }
        return bad
    }
}
