package com.geoknoesis.kastor.ontoquality.metrics.compute

/** Iterative Kosaraju SCC traversal; deep taxonomies do not consume the JVM call stack. */
internal object CycleDetector {
    fun cycleParticipants(nodes: Collection<String>, successors: Map<String, Set<String>>): Set<String> {
        val allowed = nodes.toSet()
        val adjacency = allowed.associateWith { successors[it].orEmpty().filter(allowed::contains) }
        val reverse = mutableMapOf<String, MutableList<String>>()
        adjacency.forEach { (s, targets) -> targets.forEach { reverse.getOrPut(it) { mutableListOf() }.add(s) } }
        val seen = mutableSetOf<String>()
        val finish = mutableListOf<String>()
        for (start in allowed) {
            if (!seen.add(start)) continue
            val stack = ArrayDeque<Pair<String, Iterator<String>>>()
            stack.addLast(start to adjacency.getValue(start).iterator())
            while (stack.isNotEmpty()) {
                val (node, edges) = stack.last()
                if (!edges.hasNext()) { finish.add(node); stack.removeLast() }
                else { val next = edges.next(); if (seen.add(next)) stack.addLast(next to adjacency.getValue(next).iterator()) }
            }
        }
        seen.clear()
        val result = mutableSetOf<String>()
        for (start in finish.asReversed()) {
            if (!seen.add(start)) continue
            val component = mutableSetOf<String>()
            val stack = ArrayDeque<String>(); stack.addLast(start)
            while (stack.isNotEmpty()) {
                val node = stack.removeLast(); component.add(node)
                reverse[node].orEmpty().forEach { if (seen.add(it)) stack.addLast(it) }
            }
            if (component.size > 1 || start in adjacency.getValue(start)) result.addAll(component)
        }
        return result
    }
}
