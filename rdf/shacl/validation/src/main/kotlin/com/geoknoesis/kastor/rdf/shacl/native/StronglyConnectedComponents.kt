package com.geoknoesis.kastor.rdf.shacl.native

/**
 * Iterative Tarjan strongly connected components (no JVM recursion, so arbitrarily deep graphs are safe).
 *
 * Components are returned in **reverse topological order**: every component appears after all components reachable
 * from it, i.e. dependencies first. Only vertices reachable from [roots] are visited.
 */
internal fun <V : Any> stronglyConnectedComponents(
    roots: Iterable<V>,
    budget: ValidationBudget = ValidationBudget.NONE,
    successors: (V) -> List<V>,
): List<List<V>> {
    val index = HashMap<V, Int>()
    val low = HashMap<V, Int>()
    val onStack = HashSet<V>()
    val stack = ArrayList<V>()
    val result = ArrayList<List<V>>()
    var counter = 0
    for (root in roots) {
        if (root in index) continue
        val work = ArrayDeque<Pair<V, Iterator<V>>>()
        fun push(v: V) {
            index[v] = counter
            low[v] = counter
            counter++
            stack.add(v)
            onStack.add(v)
            work.addLast(v to successors(v).iterator())
        }
        push(root)
        while (work.isNotEmpty()) {
            budget.tick("recursion analysis")
            val (v, successorIterator) = work.last()
            if (successorIterator.hasNext()) {
                val w = successorIterator.next()
                if (w !in index) {
                    push(w)
                } else if (w in onStack) {
                    low[v] = minOf(low.getValue(v), index.getValue(w))
                }
            } else {
                work.removeLast()
                if (work.isNotEmpty()) {
                    val parent = work.last().first
                    low[parent] = minOf(low.getValue(parent), low.getValue(v))
                }
                if (low.getValue(v) == index.getValue(v)) {
                    val component = ArrayList<V>()
                    while (true) {
                        val w = stack.removeAt(stack.size - 1)
                        onStack.remove(w)
                        component.add(w)
                        if (w == v) break
                    }
                    result.add(component)
                }
            }
        }
    }
    return result
}
