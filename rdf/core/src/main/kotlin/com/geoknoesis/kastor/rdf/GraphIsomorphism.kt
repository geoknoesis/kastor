package com.geoknoesis.kastor.rdf

/**
 * Graph isomorphism utilities for RDF graphs with blank nodes.
 * 
 * This implementation uses the Weisfeiler-Lehman algorithm for graph isomorphism
 * testing, which is particularly effective for graphs with labeled nodes and edges.
 * 
 * The algorithm works by iteratively refining node labels based on their neighborhood
 * structure until either a stable labeling is reached or differences are detected.
 */

/**
 * Represents a node in the graph for isomorphism testing
 */
data class GraphNode(
    val id: String,
    val label: String,
    val neighbors: MutableMap<String, MutableSet<String>> = mutableMapOf()
) {
    fun addNeighbor(edgeLabel: String, neighborId: String) {
        neighbors.getOrPut(edgeLabel) { mutableSetOf() }.add(neighborId)
    }
}

/**
 * Represents an RDF graph structure for isomorphism testing.
 * This is an internal representation used by the isomorphism checker.
 */
class GraphIsomorphismStructure {
    private val nodes = mutableMapOf<String, GraphNode>()
    var edgeCounts: Map<String, Int> = emptyMap()
    
    fun addNode(id: String, label: String): GraphNode {
        return nodes.getOrPut(id) { GraphNode(id, label) }
    }
    
    fun getNode(id: String): GraphNode? = nodes[id]
    
    fun getAllNodes(): Collection<GraphNode> = nodes.values
    
    fun addEdge(fromId: String, edgeLabel: String, toId: String) {
        val fromNode = nodes[fromId] ?: throw IllegalArgumentException("Node $fromId not found")
        fromNode.addNeighbor(edgeLabel, toId)
    }
    
    fun size(): Int = nodes.size
}

/** Compact structural refinement followed by exact, bijective blank-node matching.
 * A bounded search fails explicitly on excessively symmetric inputs rather than exhausting resources.
 */
class WeisfeilerLehmanIsomorphism(private val maxSearchStates: Int = 1_000_000) {
    private var maxWork: Long = 50_000_000
    private var timeout: java.time.Duration = java.time.Duration.ofSeconds(30)

    init { require(maxSearchStates > 0) { "maxSearchStates must be positive" } }

    /** Explicit cooperative limits; provider snapshots themselves cannot be preempted. */
    constructor(maxSearchStates: Int, maxWork: Long, timeout: java.time.Duration) : this(maxSearchStates) {
        require(maxWork > 0) { "maxWork must be positive" }
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
        this.maxWork = maxWork
        this.timeout = timeout
    }

    private class WorkBudget(maxWork: Long, timeout: java.time.Duration) {
        private var remaining = maxWork
        private val started = System.nanoTime()
        private val nanos = try { timeout.toNanos() } catch (_: ArithmeticException) { Long.MAX_VALUE }
        fun check(depth: Int = 0) {
            kotlin.check(depth < 128) { "Graph isomorphism triple-term depth limit exceeded (128)" }
            kotlin.check(!Thread.currentThread().isInterrupted) { "Graph isomorphism interrupted" }
            kotlin.check(remaining-- > 0) { "Graph isomorphism work limit exceeded" }
            kotlin.check(System.nanoTime() - started < nanos) { "Graph isomorphism time limit exceeded" }
        }
    }

    fun areIsomorphic(graph1: RdfGraph, graph2: RdfGraph): Boolean = mapping(graph1, graph2) != null

    fun mapping(graph1: RdfGraph, graph2: RdfGraph): Map<BlankNode, BlankNode>? {
        val budget = WorkBudget(maxWork, timeout)
        budget.check()
        val left = graph1.getTriples().toSet()
        budget.check()
        val right = graph2.getTriples().toSet()
        budget.check()
        if (left.size != right.size) return null
        fun blanks(term: RdfTerm, depth: Int = 0): Set<BlankNode> {
            budget.check(depth)
            return when (term) {
                is BlankNode -> setOf(term)
                is TripleTerm -> blanks(term.triple.subject, depth + 1) + blanks(term.triple.obj, depth + 1)
                else -> emptySet()
            }
        }
        fun nodes(t: RdfTriple) = blanks(t.subject) + blanks(t.obj)
        fun incidents(ts: Set<RdfTriple>): Map<BlankNode, List<RdfTriple>> {
            val index = mutableMapOf<BlankNode, MutableList<RdfTriple>>()
            ts.forEach { t -> nodes(t).forEach { b -> index.getOrPut(b) { mutableListOf() }.add(t) } }
            return index
        }
        val li = incidents(left)
        val ri = incidents(right)
        if (li.size != ri.size || left.filter { nodes(it).isEmpty() }.toSet() != right.filter { nodes(it).isEmpty() }.toSet()) return null
        if (li.isEmpty()) return emptyMap()
        var lc = li.keys.associateWith { 0 }
        var rc = ri.keys.associateWith { 0 }
        fun token(t: RdfTerm, focus: BlankNode, colors: Map<BlankNode, Int>, depth: Int = 0): String {
            budget.check(depth)
            return when (t) {
                is BlankNode -> if (t == focus) "SELF" else "B${colors[t]}"
                is TripleTerm -> "T(${token(t.triple.subject, focus, colors, depth + 1)},${token(t.triple.predicate, focus, colors, depth + 1)},${token(t.triple.obj, focus, colors, depth + 1)})"
                else -> t.toString().let { "${t.javaClass.name}:${it.length}:$it" }
            }
        }
        repeat(li.size) {
            fun signatures(index: Map<BlankNode, List<RdfTriple>>, colors: Map<BlankNode, Int>) = index.mapValues { (b, ts) ->
                colors.getValue(b).toString() + ":" + ts.map { t ->
                    token(t.subject, b, colors) + "/" + token(t.predicate, b, colors) + "/" + token(t.obj, b, colors)
                }.sortedWith { a, b -> budget.check(); a.compareTo(b) }.joinToString(";")
            }
            val ls = signatures(li, lc)
            val rs = signatures(ri, rc)
            val ids = (ls.values + rs.values).distinct().sortedWith { a, b -> budget.check(); a.compareTo(b) }.withIndex().associate { it.value to it.index }
            val nl = ls.mapValues { ids.getValue(it.value) }
            val nr = rs.mapValues { ids.getValue(it.value) }
            if (nl.values.groupingBy { it }.eachCount() != nr.values.groupingBy { it }.eachCount()) return null
            val stable = nl.values.toSet().size == lc.values.toSet().size
            lc = nl; rc = nr
            if (stable) return search(right, li, ri, lc, rc, budget)
        }
        return search(right, li, ri, lc, rc, budget)
    }

    private fun search(right: Set<RdfTriple>, li: Map<BlankNode, List<RdfTriple>>,
        ri: Map<BlankNode, List<RdfTriple>>, lc: Map<BlankNode, Int>, rc: Map<BlankNode, Int>, budget: WorkBudget): Map<BlankNode, BlankNode>? {
        val groups = ri.keys.groupBy { rc.getValue(it) }
        val order = li.keys.sortedWith(compareBy<BlankNode> { groups.getValue(lc.getValue(it)).size }.thenByDescending { li.getValue(it).size })
        val candidates = order.map { groups.getValue(lc.getValue(it)) }
        val available = groups.mapValues { (_, nodes) -> java.util.TreeSet(nodes.indices.toList()) }
        val map = linkedMapOf<BlankNode, BlankNode>()
        val used = mutableSetOf<BlankNode>()
        val positions = IntArray(order.size)
        val byPredicate = right.groupBy { budget.check(); it.predicate }
        val bySubjectPredicate = right.groupBy { budget.check(); it.subject to it.predicate }
        val byPredicateObject = right.groupBy { budget.check(); it.predicate to it.obj }
        fun mapped(term: RdfTerm, depth: Int = 0): RdfTerm? {
            budget.check(depth)
            return when (term) {
                is BlankNode -> map[term]
                is TripleTerm -> {
                    val subject = mapped(term.triple.subject, depth + 1) as? RdfResource ?: return null
                    val obj = mapped(term.triple.obj, depth + 1) ?: return null
                    TripleTerm(RdfTriple(subject, term.triple.predicate, obj))
                }
                else -> term
            }
        }
        fun matches(a: RdfTerm, b: RdfTerm, depth: Int = 0): Boolean {
            budget.check(depth)
            return when (a) {
                is BlankNode -> b is BlankNode && (map[a]?.let { it == b } ?: (b !in used && lc[a] == rc[b]))
                is TripleTerm -> b is TripleTerm && matches(a.triple.subject, b.triple.subject, depth + 1) &&
                    a.triple.predicate == b.triple.predicate && matches(a.triple.obj, b.triple.obj, depth + 1)
                else -> a == b
            }
        }
        fun consistent(node: BlankNode) = li.getValue(node).all { t ->
            val subject = mapped(t.subject) as? RdfResource
            val obj = mapped(t.obj)
            when {
                subject != null && obj != null -> RdfTriple(subject, t.predicate, obj) in right
                subject != null -> bySubjectPredicate[subject to t.predicate].orEmpty().any { matches(t.obj, it.obj) }
                obj != null -> byPredicateObject[t.predicate to obj].orEmpty().any { matches(t.subject, it.subject) }
                else -> byPredicate[t.predicate].orEmpty().any { matches(t.subject, it.subject) && matches(t.obj, it.obj) }
            }
        }
        var depth = 0
        var states = 0
        while (depth >= 0) {
            budget.check()
            if (depth == order.size) return map.toMap()
            val node = order[depth]
            val remaining = available.getValue(lc.getValue(node))
            map.remove(node)?.let { used.remove(it); remaining.add(positions[depth] - 1) }
            var advanced = false
            while (true) {
                budget.check()
                val position = remaining.ceiling(positions[depth]) ?: break
                check(++states <= maxSearchStates) { "Graph isomorphism search limit exceeded ($maxSearchStates states)" }
                positions[depth] = position + 1
                val candidate = candidates[depth][position]
                remaining.remove(position)
                used.add(candidate)
                map[node] = candidate
                if (consistent(node)) { depth++; advanced = true; break }
                map.remove(node); used.remove(candidate)
                remaining.add(position)
            }
            if (!advanced) { positions[depth] = 0; depth-- }
        }
        return null
    }
}
fun RdfGraph.isIsomorphicTo(other: RdfGraph): Boolean = WeisfeilerLehmanIsomorphism().areIsomorphic(this, other)
fun RdfGraph.findBlankNodeMapping(other: RdfGraph): Map<BlankNode, BlankNode>? = WeisfeilerLehmanIsomorphism().mapping(this, other)
