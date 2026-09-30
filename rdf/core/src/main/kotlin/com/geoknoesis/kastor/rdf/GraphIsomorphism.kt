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

/**
 * Compact structural refinement followed by exact, bijective blank-node matching.
 * A bounded search fails explicitly on excessively symmetric inputs rather than exhausting resources.
 *
 * **Limits** are cooperative (provider snapshots themselves cannot be preempted); exceeding one (or an interrupt)
 * throws [GraphIsomorphismLimitException], an [IllegalStateException] whose [GraphIsomorphismLimitException.reason]
 * names the limit - the graphs are then neither known to be isomorphic nor known not to be:
 * - [maxSearchStates] caps backtracking assignments.
 * - `maxWork` caps abstract work units (terms inspected, signatures built, candidates tried). `null`, the default,
 *   scales with the input: `max(50,000,000, 1,000 x (triples in both graphs))`, so large but easy graphs (long
 *   lists, many independent blank nodes) complete, while pathological symmetric inputs still stop.
 * - `timeout` is a wall-clock limit. The default is 60 seconds; pass an explicit `timeout` to the three-argument
 *   constructor (or to the [isIsomorphicTo] / [findBlankNodeMapping] overloads with limits) to change it, or `null`
 *   for no limit. Thread interruption is always honoured.
 *
 * Matching is near-linear on lists, chains and high-degree (star-shaped) nodes: candidates are bucketed by
 * neighbour, predicate, direction and refined colour, and every candidate scan is charged to the work budget.
 *
 * The same limits are available on [isIsomorphicTo] and [findBlankNodeMapping].
 */
class WeisfeilerLehmanIsomorphism(private val maxSearchStates: Int = 1_000_000) {
    private var maxWork: Long? = null
    private var timeout: java.time.Duration? = DEFAULT_ISOMORPHISM_TIMEOUT

    init { require(maxSearchStates > 0) { "maxSearchStates must be positive" } }

    /** The wall-clock limit in effect, for tests. */
    internal val effectiveTimeout: java.time.Duration? get() = timeout

    /** Explicit work and wall-clock limits (see the class documentation). */
    constructor(maxSearchStates: Int, maxWork: Long, timeout: java.time.Duration) :
        this(maxSearchStates, maxWork as Long?, timeout as java.time.Duration?)

    /**
     * Explicit limits: [maxWork] `null` scales the work budget with the input size, [timeout] `null` disables the
     * wall-clock limit (the other constructors use a 60 second limit).
     */
    constructor(maxSearchStates: Int, maxWork: Long?, timeout: java.time.Duration?) : this(maxSearchStates) {
        require(maxWork == null || maxWork > 0) { "maxWork must be positive" }
        require(timeout == null || (!timeout.isNegative && !timeout.isZero)) { "timeout must be positive" }
        this.maxWork = maxWork
        this.timeout = timeout
    }

    private class WorkBudget(timeout: java.time.Duration?) {
        var remaining = Long.MAX_VALUE
        private val started = System.nanoTime()
        private val nanos = timeout?.let { try { it.toNanos() } catch (_: ArithmeticException) { Long.MAX_VALUE } } ?: Long.MAX_VALUE
        private var calls = 0

        /** Charges [cost] work units; interruption and the clock are polled every 1024 charges. */
        fun check(depth: Int = 0, cost: Long = 1) {
            limit(depth < 128, GraphIsomorphismLimitException.Reason.TRIPLE_TERM_DEPTH) { "Graph isomorphism triple-term depth limit exceeded (128)" }
            remaining -= cost
            limit(remaining >= 0, GraphIsomorphismLimitException.Reason.WORK) { "Graph isomorphism work limit exceeded" }
            if ((++calls and 0x3FF) == 0) poll()
        }

        fun poll() {
            limit(!Thread.currentThread().isInterrupted, GraphIsomorphismLimitException.Reason.INTERRUPTED) { "Graph isomorphism interrupted" }
            limit(System.nanoTime() - started < nanos, GraphIsomorphismLimitException.Reason.TIME) { "Graph isomorphism time limit exceeded" }
        }
    }

    fun areIsomorphic(graph1: RdfGraph, graph2: RdfGraph): Boolean = mapping(graph1, graph2) != null

    fun mapping(graph1: RdfGraph, graph2: RdfGraph): Map<BlankNode, BlankNode>? {
        val budget = WorkBudget(timeout)
        budget.poll()
        val left = graph1.getTriples().toSet()
        budget.poll()
        val right = graph2.getTriples().toSet()
        budget.poll()
        budget.remaining = maxWork ?: defaultIsomorphismWorkBudget(left.size.toLong() + right.size)
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
        fun token(t: RdfTerm, focus: BlankNode, colors: Map<BlankNode, Int>, depth: Int = 0): String = when (t) {
            is BlankNode -> if (t == focus) "SELF" else "B${colors[t]}"
            is TripleTerm -> {
                budget.check(depth)
                "T(${token(t.triple.subject, focus, colors, depth + 1)},${token(t.triple.predicate, focus, colors, depth + 1)},${token(t.triple.obj, focus, colors, depth + 1)})"
            }
            else -> groundTermToken(t)
        }
        // Refinement only prunes the exact search below, so it is capped: a path of n blank nodes needs ~n/2
        // rounds to separate fully, which made the cost quadratic. The anchored search handles long chains.
        repeat(minOf(li.size, MAX_REFINEMENT_ROUNDS)) {
            // Work is charged per signature, in proportion to its triples, rather than per sort comparison.
            fun signatures(index: Map<BlankNode, List<RdfTriple>>, colors: Map<BlankNode, Int>) = index.mapValues { (b, ts) ->
                budget.check(cost = ts.size.toLong() + 1)
                colors.getValue(b).toString() + ":" + ts.map { t ->
                    token(t.subject, b, colors) + "/" + token(t.predicate, b, colors) + "/" + token(t.obj, b, colors)
                }.sorted().joinToString(";")
            }
            val ls = signatures(li, lc)
            val rs = signatures(ri, rc)
            budget.poll()
            val ids = (ls.values + rs.values).distinct().sorted().withIndex().associate { it.value to it.index }
            val nl = ls.mapValues { ids.getValue(it.value) }
            val nr = rs.mapValues { ids.getValue(it.value) }
            if (nl.values.groupingBy { it }.eachCount() != nr.values.groupingBy { it }.eachCount()) return null
            val stable = nl.values.toSet().size == lc.values.toSet().size
            lc = nl; rc = nr
            if (stable) return search(right, li, ri, lc, rc, ::nodes, budget)
        }
        return search(right, li, ri, lc, rc, ::nodes, budget)
    }

    /**
     * Blank nodes adjacent to [anchor] through [predicate] with refined colour [color]: objects of `anchor predicate ?`
     * when [anchorIsSubject], otherwise subjects of `? predicate anchor`.
     */
    private data class Adjacency(val anchor: RdfTerm, val predicate: Iri, val color: Int, val anchorIsSubject: Boolean)

    /** The right-graph candidates of one [Adjacency]; every item below [hint] is currently used. */
    private class CandidateList {
        val items = ArrayList<BlankNode>(1)
        var hint = 0
    }

    private fun search(right: Set<RdfTriple>, li: Map<BlankNode, List<RdfTriple>>,
        ri: Map<BlankNode, List<RdfTriple>>, lc: Map<BlankNode, Int>, rc: Map<BlankNode, Int>,
        blankNodesOf: (RdfTriple) -> Set<BlankNode>, budget: WorkBudget): Map<BlankNode, BlankNode>? {
        val groups = ri.keys.groupBy { rc.getValue(it) }
        val indexInGroup = HashMap<BlankNode, Int>().apply { groups.values.forEach { nodes -> nodes.forEachIndexed { i, b -> put(b, i) } } }
        val available = groups.mapValues { (_, nodes) -> java.util.TreeSet(nodes.indices.toList()) }
        val map = linkedMapOf<BlankNode, BlankNode>()
        val used = HashSet<BlankNode>()
        val byPredicate = right.groupBy { budget.check(); it.predicate }
        val bySubjectPredicate = right.groupBy { budget.check(); it.subject to it.predicate }
        val byPredicateObject = right.groupBy { budget.check(); it.predicate to it.obj }

        // Candidate buckets: a high-degree node's neighbours of one colour are found without scanning the others,
        // and a shared scan hint skips candidates already used, so stars are matched in near-linear time.
        val adjacency = HashMap<Adjacency, CandidateList>()
        val memberships = HashMap<BlankNode, MutableList<Pair<CandidateList, Int>>>()
        fun index(key: Adjacency, node: BlankNode) {
            val list = adjacency.getOrPut(key) { CandidateList() }
            memberships.getOrPut(node) { ArrayList(2) }.add(list to list.items.size)
            list.items.add(node)
        }
        for (t in right) {
            budget.check()
            (t.obj as? BlankNode)?.let { index(Adjacency(t.subject, t.predicate, rc.getValue(it), true), it) }
            (t.subject as? BlankNode)?.let { index(Adjacency(t.obj, t.predicate, rc.getValue(it), false), it) }
        }
        /** Index of the first unused candidate at or after [from]; every skipped candidate is charged. */
        fun firstUnused(list: CandidateList, from: Int): Int {
            var i = maxOf(from, list.hint)
            while (i < list.items.size && list.items[i] in used) {
                budget.check()
                if (i == list.hint) list.hint++
                i++
            }
            return i
        }
        fun release(candidate: BlankNode) {
            used.remove(candidate)
            memberships[candidate]?.forEach { (list, position) ->
                budget.check()
                if (position < list.hint) list.hint = position
            }
        }

        // Breadth-first order over blank nodes that share a triple, each component starting from its most
        // constrained node. A node reached through a triple that directly links it to an earlier node gets that
        // triple as its anchor: once the earlier node is mapped, only blank nodes of the node's colour adjacent to
        // its image are candidates, so chains, lists and stars are matched in near-linear time.
        val roots = li.keys.sortedWith(compareBy<BlankNode> { groups.getValue(lc.getValue(it)).size }.thenByDescending { li.getValue(it).size })
        val order = ArrayList<BlankNode>(li.size)
        val anchors = HashMap<BlankNode, Pair<RdfTriple, BlankNode>>()
        val placed = HashSet<BlankNode>()
        val queue = ArrayDeque<BlankNode>()
        for (root in roots) {
            if (!placed.add(root)) continue
            order.add(root)
            queue.add(root)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                for (t in li.getValue(current)) {
                    budget.check()
                    for (next in blankNodesOf(t)) {
                        if (!placed.add(next)) continue
                        order.add(next)
                        queue.add(next)
                        if ((t.subject == next && t.obj == current) || (t.subject == current && t.obj == next)) anchors[next] = t to current
                    }
                }
            }
        }
        val noCandidates = CandidateList()
        fun anchoredCandidates(node: BlankNode): CandidateList? {
            val (t, anchor) = anchors[node] ?: return null
            val image = map.getValue(anchor)
            return adjacency[Adjacency(image, t.predicate, lc.getValue(node), anchorIsSubject = t.subject != node)] ?: noCandidates
        }
        val positions = IntArray(order.size)
        val anchored = arrayOfNulls<CandidateList>(order.size)
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
        /** Same answer as scanning the anchor's neighbours with [matches] for the unmapped blank node [node]. */
        fun hasUnusedNeighbour(anchor: RdfTerm, predicate: Iri, node: BlankNode, anchorIsSubject: Boolean): Boolean {
            val list = adjacency[Adjacency(anchor, predicate, lc.getValue(node), anchorIsSubject)] ?: return false
            return firstUnused(list, 0) < list.items.size
        }
        fun consistent(node: BlankNode) = li.getValue(node).all { t ->
            val subject = mapped(t.subject) as? RdfResource
            val obj = mapped(t.obj)
            val blankObject = t.obj as? BlankNode
            val blankSubject = t.subject as? BlankNode
            when {
                subject != null && obj != null -> RdfTriple(subject, t.predicate, obj) in right
                subject != null && blankObject != null -> hasUnusedNeighbour(subject, t.predicate, blankObject, true)
                subject != null -> bySubjectPredicate[subject to t.predicate].orEmpty().any { matches(t.obj, it.obj) }
                obj != null && blankSubject != null -> hasUnusedNeighbour(obj, t.predicate, blankSubject, false)
                obj != null -> byPredicateObject[t.predicate to obj].orEmpty().any { matches(t.subject, it.subject) }
                else -> byPredicate[t.predicate].orEmpty().any { matches(t.subject, it.subject) && matches(t.obj, it.obj) }
            }
        }
        var states = 0
        fun assign(node: BlankNode, candidate: BlankNode, remaining: java.util.TreeSet<Int>): Boolean {
            limit(++states <= maxSearchStates, GraphIsomorphismLimitException.Reason.SEARCH_STATES) {
                "Graph isomorphism search limit exceeded ($maxSearchStates states)"
            }
            remaining.remove(indexInGroup.getValue(candidate))
            used.add(candidate)
            map[node] = candidate
            if (consistent(node)) return true
            map.remove(node)
            release(candidate)
            remaining.add(indexInGroup.getValue(candidate))
            return false
        }
        var depth = 0
        var entering = true
        while (depth >= 0) {
            budget.check()
            if (depth == order.size) return map.toMap()
            val node = order[depth]
            val remaining = available.getValue(lc.getValue(node))
            if (entering) { anchored[depth] = anchoredCandidates(node); positions[depth] = 0 }
            map.remove(node)?.let { release(it); remaining.add(indexInGroup.getValue(it)) }
            val fixed = anchored[depth]
            var advanced = false
            if (fixed != null) {
                while (true) {
                    budget.check()
                    val i = firstUnused(fixed, positions[depth])
                    if (i >= fixed.items.size) break
                    positions[depth] = i + 1
                    if (assign(node, fixed.items[i], remaining)) { advanced = true; break }
                }
            } else {
                val candidates = groups.getValue(lc.getValue(node))
                while (true) {
                    budget.check()
                    val position = remaining.ceiling(positions[depth]) ?: break
                    positions[depth] = position + 1
                    if (assign(node, candidates[position], remaining)) { advanced = true; break }
                }
            }
            if (advanced) { depth++; entering = true } else { depth--; entering = false }
        }
        return null
    }

    private companion object {
        const val MAX_REFINEMENT_ROUNDS = 16
    }
}

/** Wall-clock limit used unless a timeout is passed explicitly: 60 seconds. */
internal val DEFAULT_ISOMORPHISM_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(60)

/** Default work budget for graphs with [tripleCount] triples in total: `max(50,000,000, 1,000 x tripleCount)`. */
internal fun defaultIsomorphismWorkBudget(tripleCount: Long): Long =
    maxOf(50_000_000L, if (tripleCount > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else tripleCount * 1_000)

/**
 * Refinement token for a term without blank nodes. Equal terms get equal tokens, whatever their class
 * ([TrueLiteral] and `"true"^^xsd:boolean`) or language-tag case, and every variable-length part is
 * length-prefixed so joined tokens stay unambiguous.
 */
private fun groundTermToken(term: RdfTerm): String = when (term) {
    is Iri -> "I${term.value.length}:${term.value}"
    is LangString ->
        "S${term.lexical.length}:${term.lexical}${term.normalizedLang.length}:${term.normalizedLang}${term.direction?.token ?: ""}"
    is Literal -> "L${term.lexical.length}:${term.lexical}${term.datatype.value.length}:${term.datatype.value}"
    else -> term.toString().let { "X${term.javaClass.name}:${it.length}:$it" }
}

/**
 * True if the graphs are equal up to blank-node renaming, with the default limits of [WeisfeilerLehmanIsomorphism]
 * (including its 60 second wall-clock limit).
 *
 * @throws GraphIsomorphismLimitException if a limit is exceeded or the thread is interrupted (no answer is known)
 */
fun RdfGraph.isIsomorphicTo(other: RdfGraph): Boolean = WeisfeilerLehmanIsomorphism().areIsomorphic(this, other)

/**
 * A blank-node bijection making the graphs equal, or null; default limits of [WeisfeilerLehmanIsomorphism] (60 s).
 *
 * @throws GraphIsomorphismLimitException if a limit is exceeded or the thread is interrupted (no answer is known)
 */
fun RdfGraph.findBlankNodeMapping(other: RdfGraph): Map<BlankNode, BlankNode>? = WeisfeilerLehmanIsomorphism().mapping(this, other)

/**
 * [isIsomorphicTo] with explicit limits: [maxWork] `null` scales with the graph size, [timeout] `null` means no
 * wall-clock limit (the overload without limits uses 60 seconds).
 *
 * @throws GraphIsomorphismLimitException if a limit is exceeded or the thread is interrupted (no answer is known)
 */
fun RdfGraph.isIsomorphicTo(other: RdfGraph, maxWork: Long?, timeout: java.time.Duration?): Boolean =
    WeisfeilerLehmanIsomorphism(1_000_000, maxWork, timeout).areIsomorphic(this, other)

/**
 * [findBlankNodeMapping] with explicit limits: [maxWork] `null` scales with the graph size, [timeout] `null` means
 * no wall-clock limit (the overload without limits uses 60 seconds).
 *
 * @throws GraphIsomorphismLimitException if a limit is exceeded or the thread is interrupted (no answer is known)
 */
fun RdfGraph.findBlankNodeMapping(other: RdfGraph, maxWork: Long?, timeout: java.time.Duration?): Map<BlankNode, BlankNode>? =
    WeisfeilerLehmanIsomorphism(1_000_000, maxWork, timeout).mapping(this, other)

/**
 * Thrown by [isIsomorphicTo], [findBlankNodeMapping] and [WeisfeilerLehmanIsomorphism] when the check is abandoned
 * before an answer was found: a limit was exceeded or the thread was interrupted. It is not a "not isomorphic"
 * answer. It extends [IllegalStateException] so existing handlers keep working; catch this type to tell a
 * limit apart from other failures, and use [reason] to tell the limits apart.
 *
 * @property reason which limit stopped the check
 */
class GraphIsomorphismLimitException(val reason: Reason, message: String) : IllegalStateException(message) {
    /** The limit that stopped an isomorphism check. */
    enum class Reason {
        /** The wall-clock `timeout` elapsed. */
        TIME,

        /** The `maxWork` budget was used up. */
        WORK,

        /** Backtracking tried more than `maxSearchStates` assignments. */
        SEARCH_STATES,

        /** Triple terms were nested more than 128 levels deep. */
        TRIPLE_TERM_DEPTH,

        /** The calling thread was interrupted (its interrupt flag is left set). */
        INTERRUPTED,
    }
}

private inline fun limit(ok: Boolean, reason: GraphIsomorphismLimitException.Reason, message: () -> String) {
    if (!ok) throw GraphIsomorphismLimitException(reason, message())
}
