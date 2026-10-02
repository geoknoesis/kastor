package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.vocab.RDF
import java.security.MessageDigest

/**
 * Parse-independent keys for blank nodes, used by [FindingRef] so findings on blank nodes (e.g. `owl:Restriction`)
 * keep their ref when the same file is parsed again (parser labels change on every parse).
 *
 * **What a key covers.** A key is a hash of two things:
 * - the node's **own structure**: its triples with IRIs and literals and, recursively, everything it points to through
 *   other blank nodes (a class expression with all its nested expressions);
 * - its **owner chain**: the predicates leading to the node from the IRIs (or root blank nodes) that own it, without
 *   the rest of what the owners contain.
 *
 * The same restriction under two classes therefore gets two keys, and editing one member of an `owl:intersectionOf`
 * list changes the key of that member and of the owners that contain it, but not the keys of its siblings.
 *
 * **RDF lists** are flat: a well-formed list is described at its head as "item *i* is ...", so a list is one step
 * whatever its length; a member is owned by the list (its position is not part of its key, so inserting or removing
 * another member does not re-key it). Cyclic, branching or shared lists are followed cell by cell.
 *
 * **Work is bounded by the findings, not by the graph.** Only these blank nodes are read (through
 * [RdfGraph.find]; the graph is never read in full):
 * - the connected components (blank nodes linked by triples, in either direction) of the requested nodes;
 * - their **IRI context**: the components hanging off the same IRI by the same predicate as a requested component
 *   (at most [MAX_SIBLINGS] objects per IRI and predicate), which is where a duplicate of a requested node can be.
 *
 * Descriptions are hash accumulators (two `Long`s per node and round, predicates interned), not strings.
 *
 * **Unique within a graph.** Blank nodes that share a key (the same restriction written twice under one class) are
 * numbered `-2`, `-3`, ... in a canonical order that does not depend on labels:
 * 1. by the colour of the node after refining the keys over all links of its component (this tells apart duplicates
 *    with different siblings);
 * 2. for nodes of one component that refinement cannot separate, by a canonical labelling of the component (a node is
 *    singled out, the colours are refined again, and so on; the node to single out is chosen by looking one step
 *    ahead). Components of more than [MAX_CANON_NODES] nodes skip this step;
 * 3. nodes that are still tied are interchangeable (an automorphism of the graph maps one to the other, so they get
 *    the same findings): they are numbered by the position of their component among the objects of its first IRI
 *    owner and predicate, as [RdfGraph.find] returns them. For a given graph and serialisation order that position is
 *    fixed and does not depend on which nodes have findings; either order gives the same set of keys and refs.
 *
 * Duplicates are searched in the components listed above. A duplicate whose component hangs off no IRI at all (a
 * root blank node such as `[] a owl:AllDisjointClasses`) is numbered only together with the requested nodes.
 *
 * Other properties:
 * - **Order-independent**: the triples of a node are accumulated commutatively.
 * - **Cycle-safe**: structure and owner chain are followed for at most [DEPTH_ROUNDS] steps.
 * - **Triple terms** containing blank nodes are described with a placeholder per blank node and linked to those
 *   nodes (from the node inside the term to the subject only for `rdf:reifies` triples); parser labels are never
 *   hashed. Literals and IRIs are length-prefixed, so no literal text can imitate the description of other triples.
 */
internal object BlankNodeKeys {
    /** Most steps followed through blank nodes, down the structure of a node and up its owner chain. */
    const val DEPTH_ROUNDS: Int = 64

    /** Most objects of one IRI and predicate that are searched for duplicates of a requested node. */
    const val MAX_SIBLINGS: Int = 10_000

    /** Largest component that is labelled canonically to order duplicates inside it. */
    const val MAX_CANON_NODES: Int = 512

    /** Largest group of tied nodes for which the node to single out is chosen by looking ahead. */
    const val MAX_CANON_CHOICES: Int = 16

    /** Node visits available to canonical labelling per call; beyond it the remaining ties keep graph order. */
    const val MAX_CANON_WORK: Long = 2_000_000

    private const val MAX_TRIPLE_TERM_DEPTH: Int = 64

    /** Work done by one [compute] call, for tests. */
    class Stats {
        /** Blank nodes whose triples were read. */
        var describedNodes: Int = 0

        /** Calls of [RdfGraph.find]. */
        var lookups: Int = 0
    }

    /**
     * Keys for the [nodes] that occur in [graph] (others are left out).
     *
     * @param prefix start of every key; callers keying nodes of two graphs use two prefixes.
     */
    fun compute(graph: RdfGraph, nodes: Set<BlankNode>, prefix: String = "_:k", stats: Stats? = null): Map<BlankNode, String> {
        if (nodes.isEmpty()) return emptyMap()
        return Keying(graph, stats ?: Stats()).keys(nodes, prefix)
    }

    /** Links of a node: parallel arrays of a label hash and the index of the node at the other end. */
    private class Links {
        var labels = LongArray(2)
        var targets = IntArray(2)
        var size = 0

        fun add(label: Long, target: Int) {
            if (size == labels.size) {
                labels = labels.copyOf(size * 2)
                targets = targets.copyOf(size * 2)
            }
            labels[size] = label
            targets[size] = target
            size++
        }

        fun remove(label: Long, target: Int) {
            for (i in 0 until size) {
                if (labels[i] == label && targets[i] == target) {
                    size--
                    labels[i] = labels[size]
                    targets[i] = targets[size]
                    return
                }
            }
        }
    }

    /** What a blank node says as an RDF list cell. */
    private class Cell {
        val firstNodes = ArrayList<Int>(1)

        /** Hash pairs of the `rdf:first` values that are not blank nodes. */
        val firstLocals = ArrayList<Long>(2)
        var restCount = 0
        var restNode = -1
        var restHi = 0L
        var restLo = 0L
    }

    private class Node(val term: BlankNode, val index: Int) {
        var component = -1

        /** False for a requested node that has no triple in the graph. */
        var present = false

        /** Accumulated hashes of the triples to IRIs, literals and triple terms. */
        var ownHi = 0L
        var ownLo = 0L
        var ownCount = 0

        /** Accumulated hashes of the triples from IRI subjects. */
        var inHi = 0L
        var inLo = 0L
        var inCount = 0

        /** Links to the blank nodes this node points to, and to the blank nodes that own it. */
        val down = Links()
        val up = Links()
        var cell: Cell? = null

        /** Number of blank nodes whose `rdf:rest` is this node. */
        var restIn = 0

        fun addOwn(hi: Long, lo: Long) {
            ownHi += hi
            ownLo += lo
            ownCount++
        }

        fun addIn(hi: Long, lo: Long) {
            inHi += hi
            inLo += lo
            inCount++
        }
    }

    /** An IRI and predicate with blank-node objects: where a component hangs off the named part of the graph. */
    private data class RootEdge(val subject: Iri, val predicate: Iri) : Comparable<RootEdge> {
        override fun compareTo(other: RootEdge): Int {
            val bySubject = subject.value.compareTo(other.subject.value)
            return if (bySubject != 0) bySubject else predicate.value.compareTo(other.predicate.value)
        }
    }

    private data class Colour(val hi: Long, val lo: Long)

    private data class TiedGroup(val hi: Long, val lo: Long, val component: Int)

    private class Keying(private val graph: RdfGraph, private val stats: Stats) {
        private val nodes = ArrayList<Node>()
        private val byTerm = HashMap<BlankNode, Node>()
        private val queue = ArrayDeque<Node>()
        private var components = 0

        /** Root edges of the requested components, where their duplicates are searched. */
        private val contextEdges = LinkedHashSet<RootEdge>()

        /** Smallest root edge of every component. */
        private val firstRootEdge = ArrayList<RootEdge?>()

        private val digest = MessageDigest.getInstance("SHA-256")
        private val predicateIds = HashMap<String, Long>()
        private val inner = ArrayList<BlankNode>()
        private var outHi = 0L
        private var outLo = 0L
        private var canonWork = 0L

        private val firstLabel = label(DOWN, RDF.first)
        private val restLabel = label(DOWN, RDF.rest)

        /** `rdf:reifies` triples by the blank nodes inside their triple term. */
        private val reified: Map<BlankNode, List<RdfTriple>> by lazy(LazyThreadSafetyMode.NONE) {
            val map = HashMap<BlankNode, MutableList<RdfTriple>>()
            for (t in find(null, RDF.reifies, null)) {
                val term = t.obj as? TripleTerm ?: continue
                val found = LinkedHashSet<BlankNode>()
                collectBlankNodes(term, found, 0)
                for (b in found) map.getOrPut(b) { ArrayList(1) }.add(t)
            }
            map
        }

        fun keys(requested: Set<BlankNode>, prefix: String): Map<BlankNode, String> {
            val wanted = ArrayList<Node>(requested.size)
            for (term in requested) {
                val node = byTerm[term] ?: node(term).also { drain(requested = true) }
                if (node.present) wanted += node
            }
            if (wanted.isEmpty()) return emptyMap()
            describeContext()
            flattenLists()

            val n = nodes.size
            val downHi = LongArray(n)
            val downLo = LongArray(n)
            val upHi = LongArray(n)
            val upLo = LongArray(n)
            closure(downHi, downLo, down = true)
            closure(upHi, upLo, down = false)
            val keyHi = LongArray(n) { mix(mix(mix(downHi[it], downLo[it]), upHi[it]), upLo[it]) }
            val keyLo = LongArray(n) { mix(mix(mix(downLo[it] xor ALT, downHi[it]), upLo[it]), upHi[it]) }

            val classes = HashMap<Colour, ArrayList<Node>>()
            for (node in wanted) classes.getOrPut(Colour(keyHi[node.index], keyLo[node.index])) { ArrayList(1) }
            for (node in nodes) {
                if (node.present) classes[Colour(keyHi[node.index], keyLo[node.index])]?.add(node)
            }

            val ordinals = HashMap<Int, Int>()
            if (classes.values.any { it.size > 1 }) {
                val order = DuplicateOrder(keyHi, keyLo)
                for (members in classes.values) {
                    if (members.size == 1) continue
                    order.sort(members)
                    members.forEachIndexed { position, member -> ordinals[member.index] = position + 1 }
                }
            }
            val result = HashMap<BlankNode, String>(wanted.size * 2)
            for (node in wanted) {
                val key = prefix + hex(keyHi[node.index]) + hex(keyLo[node.index])
                val ordinal = ordinals[node.index] ?: 1
                result[node.term] = if (ordinal == 1) key else "$key-$ordinal"
            }
            return result
        }

        // ---- reading the graph ----

        private fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> {
            stats.lookups++
            return graph.find(subject, predicate, obj)
        }

        private fun node(term: BlankNode): Node =
            byTerm[term] ?: Node(term, nodes.size).also {
                byTerm[term] = it
                nodes += it
                queue += it
            }

        /** Describes the queued nodes and everything linked to them: one component. */
        private fun drain(requested: Boolean) {
            val id = components++
            firstRootEdge += null
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                node.component = id
                describe(node, requested)
            }
        }

        private fun describe(n: Node, requested: Boolean) {
            stats.describedNodes++
            for (t in find(n.term, null, null)) {
                n.present = true
                val p = t.predicate
                when (val o = t.obj) {
                    is BlankNode -> {
                        val m = node(o)
                        val label = label(DOWN, p)
                        n.down.add(label, m.index)
                        m.up.add(label, n.index)
                        if (p == RDF.first) {
                            cell(n).firstNodes += m.index
                        } else if (p == RDF.rest) {
                            val c = cell(n)
                            c.restCount++
                            c.restNode = m.index
                            m.restIn++
                        }
                    }
                    is TripleTerm -> {
                        inner.clear()
                        hashLocal(OUT, p, o, inner)
                        n.addOwn(outHi, outLo)
                        val template = outHi
                        val base = label(TERM, p)
                        inner.toList().forEachIndexed { position, b ->
                            val m = node(b)
                            val label = mix(mix(base, template), position.toLong())
                            n.down.add(label, m.index)
                            m.up.add(label, n.index)
                        }
                    }
                    else -> {
                        hashLocal(OUT, p, o, null)
                        n.addOwn(outHi, outLo)
                        if (p == RDF.first) {
                            val c = cell(n)
                            c.firstLocals += outHi
                            c.firstLocals += outLo
                        } else if (p == RDF.rest) {
                            val c = cell(n)
                            c.restCount++
                            c.restHi = outHi
                            c.restLo = outLo
                        }
                    }
                }
            }
            for (t in find(null, null, n.term)) {
                n.present = true
                val s = t.subject
                if (s is BlankNode) {
                    node(s)
                } else if (s is Iri) {
                    hashLocal(IN, t.predicate, s, null)
                    n.addIn(outHi, outLo)
                    rootEdge(n, RootEdge(s, t.predicate), requested)
                }
            }
            for (t in reified[n.term].orEmpty()) {
                n.present = true
                val s = t.subject
                if (s is BlankNode) {
                    node(s)
                } else if (s is Iri) {
                    inner.clear()
                    hashLocal(OUT, t.predicate, t.obj, inner)
                    val template = outHi
                    val positions = inner.toList()
                    hashLocal(IN, t.predicate, s, null)
                    val subjectHi = outHi
                    val subjectLo = outLo
                    positions.forEachIndexed { position, b ->
                        if (b == n.term) {
                            n.addIn(mix(mix(subjectHi, template), position.toLong()), mix(mix(subjectLo, template), position.toLong()))
                        }
                    }
                }
            }
        }

        private fun rootEdge(n: Node, edge: RootEdge, requested: Boolean) {
            if (requested) contextEdges += edge
            val current = firstRootEdge[n.component]
            if (current == null || edge < current) firstRootEdge[n.component] = edge
        }

        private fun cell(n: Node): Cell = n.cell ?: Cell().also { n.cell = it }

        /** The components that hang off the same IRI by the same predicate as a requested component. */
        private fun describeContext() {
            for (edge in contextEdges.toList()) {
                val siblings = find(edge.subject, edge.predicate, null)
                if (siblings.size > MAX_SIBLINGS) continue
                for (t in siblings) {
                    val o = t.obj
                    if (o is BlankNode && !byTerm.containsKey(o)) {
                        node(o)
                        drain(requested = false)
                    }
                }
            }
        }

        private fun collectBlankNodes(term: RdfTerm, into: MutableSet<BlankNode>, depth: Int) {
            when (term) {
                is BlankNode -> into += term
                is TripleTerm ->
                    if (depth < MAX_TRIPLE_TERM_DEPTH) {
                        collectBlankNodes(term.triple.subject, into, depth + 1)
                        collectBlankNodes(term.triple.obj, into, depth + 1)
                    }
                else -> Unit
            }
        }

        // ---- lists ----

        /**
         * Describes every well-formed list at its head: members are linked to the head with their position and owned
         * by it, cells after the head are owned by the head, and the `rdf:rest` chain between the cells is dropped.
         * A list stops at a cell with no or several `rdf:rest`, or whose next cell is the `rdf:rest` of several cells.
         */
        private fun flattenLists() {
            for (head in nodes) {
                if (head.cell == null || head.restIn != 0) continue
                val cells = ArrayList<Node>()
                cells += head
                var current = head
                while (true) {
                    val c = current.cell ?: break
                    if (c.restCount != 1 || c.restNode < 0) break
                    val next = nodes[c.restNode]
                    if (next.restIn != 1 || next === head) break
                    cells += next
                    current = next
                }
                for (i in cells.indices) {
                    val cell = cells[i]
                    val position = i.toLong()
                    if (i > 0) {
                        val previous = cells[i - 1]
                        previous.down.remove(restLabel, cell.index)
                        cell.up.remove(restLabel, previous.index)
                        cell.up.add(mix(CELL_OF, position), head.index)
                    }
                    val c = cell.cell ?: continue
                    for (memberIndex in c.firstNodes) {
                        val member = nodes[memberIndex]
                        member.up.remove(firstLabel, cell.index)
                        member.up.add(ITEM_OF, head.index)
                        if (i > 0) head.down.add(mix(ITEM, position), memberIndex)
                    }
                    if (i > 0) {
                        for (k in 0 until c.firstLocals.size step 2) {
                            head.addOwn(mix(mix(ITEM, position), c.firstLocals[k]), mix(mix(ITEM xor ALT, position), c.firstLocals[k + 1]))
                        }
                    }
                }
                val size = cells.size.toLong()
                head.addOwn(mix(LENGTH, size), mix(LENGTH xor ALT, size))
                val last = cells.last().cell
                if (last != null && last.restCount == 1 && last.restNode < 0) {
                    head.addOwn(mix(TAIL, last.restHi), mix(TAIL xor ALT, last.restLo))
                } else {
                    head.addOwn(mix(TAIL, OPEN), mix(TAIL xor ALT, OPEN))
                }
            }
        }

        // ---- hashing ----

        /**
         * The hash of what every node reaches going [down] its links (its own structure) or up (its owner chain).
         * A node without links has the same value in every round, so on a structure without cycles the values stop
         * changing after as many rounds as it is deep and do not depend on the other nodes that are described.
         */
        private fun closure(hi: LongArray, lo: LongArray, down: Boolean) {
            val n = nodes.size
            val baseHi = LongArray(n)
            val baseLo = LongArray(n)
            for (node in nodes) {
                val i = node.index
                if (down) {
                    baseHi[i] = mix(node.ownHi, node.ownCount.toLong())
                    baseLo[i] = mix(node.ownLo xor ALT, node.ownCount.toLong())
                } else if (node.inCount == 0 && node.up.size == 0) {
                    // A root blank node has no owner: its own triples stand for it.
                    baseHi[i] = mix(mix(ROOT, node.ownHi), node.ownCount.toLong())
                    baseLo[i] = mix(mix(ROOT xor ALT, node.ownLo), node.ownCount.toLong())
                } else {
                    baseHi[i] = mix(node.inHi, node.inCount.toLong())
                    baseLo[i] = mix(node.inLo xor ALT, node.inCount.toLong())
                }
                hi[i] = nodeHi(baseHi[i], baseLo[i], 0, 0, 0)
                lo[i] = nodeLo(baseHi[i], baseLo[i], 0, 0, 0)
            }
            val nextHi = LongArray(n)
            val nextLo = LongArray(n)
            for (round in 0 until DEPTH_ROUNDS) {
                var changed = false
                for (node in nodes) {
                    val i = node.index
                    val links = if (down) node.down else node.up
                    var accHi = 0L
                    var accLo = 0L
                    for (k in 0 until links.size) {
                        val target = links.targets[k]
                        accHi += edgeHi(links.labels[k], hi[target], lo[target])
                        accLo += edgeLo(links.labels[k], hi[target], lo[target])
                    }
                    nextHi[i] = nodeHi(baseHi[i], baseLo[i], accHi, accLo, links.size)
                    nextLo[i] = nodeLo(baseHi[i], baseLo[i], accHi, accLo, links.size)
                    if (nextHi[i] != hi[i] || nextLo[i] != lo[i]) changed = true
                }
                if (!changed) break
                nextHi.copyInto(hi)
                nextLo.copyInto(lo)
            }
        }

        private fun predicateId(predicate: Iri): Long =
            predicateIds.getOrPut(predicate.value) {
                digest.reset()
                digest.update('P'.code.toByte())
                put(predicate.value)
                long(digest.digest(), 0)
            }

        private fun label(kind: Long, predicate: Iri): Long = mix(kind, predicateId(predicate))

        /** Hash of one triple of a node with a term that is not a blank node, into [outHi] / [outLo]. */
        private fun hashLocal(direction: Long, predicate: Iri, term: RdfTerm, blankNodes: MutableList<BlankNode>?) {
            val id = predicateId(predicate)
            digest.reset()
            putLong(direction)
            putLong(id)
            feed(term, blankNodes, 0)
            val bytes = digest.digest()
            outHi = long(bytes, 0)
            outLo = long(bytes, 8)
        }

        /**
         * Canonical bytes of a term: every part is tagged and length-prefixed. Blank nodes (only reachable inside
         * triple terms) are written as a placeholder and collected in [blankNodes], in order of appearance.
         */
        private fun feed(term: RdfTerm, blankNodes: MutableList<BlankNode>?, depth: Int) {
            when (term) {
                is Iri -> {
                    digest.update('I'.code.toByte())
                    put(term.value)
                }
                is BlankNode -> {
                    digest.update('B'.code.toByte())
                    blankNodes?.add(term)
                }
                is LangString -> {
                    digest.update('L'.code.toByte())
                    put(term.lexical)
                    put(term.normalizedLang)
                    put(term.direction?.name ?: "")
                }
                is Literal -> {
                    digest.update('D'.code.toByte())
                    put(term.lexical)
                    put(term.datatype.value)
                }
                is TripleTerm ->
                    if (depth >= MAX_TRIPLE_TERM_DEPTH) {
                        digest.update('X'.code.toByte())
                    } else {
                        digest.update('T'.code.toByte())
                        feed(term.triple.subject, blankNodes, depth + 1)
                        put(term.triple.predicate.value)
                        feed(term.triple.obj, blankNodes, depth + 1)
                    }
                // Query variables never occur in a data graph.
                else -> {
                    digest.update('?'.code.toByte())
                    put(term.toString())
                }
            }
        }

        private fun put(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            putLong(bytes.size.toLong())
            digest.update(bytes)
        }

        private fun putLong(value: Long) {
            for (shift in 56 downTo 0 step 8) digest.update((value ushr shift).toByte())
        }

        private fun long(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xff)
            return value
        }

        // ---- ordering duplicates ----

        /** The canonical order of the nodes that share a key (see [BlankNodeKeys], "Unique within a graph"). */
        private inner class DuplicateOrder(keyHi: LongArray, keyLo: LongArray) {
            private val stableHi = keyHi.copyOf()
            private val stableLo = keyLo.copyOf()
            private val canonHi = LongArray(nodes.size)
            private val canonLo = LongArray(nodes.size)
            private val members: Array<IntArray> by lazy(LazyThreadSafetyMode.NONE) {
                val lists = Array(components) { ArrayList<Int>() }
                for (node in nodes) lists[node.component].add(node.index)
                Array(components) { lists[it].toIntArray() }
            }
            private val refined = BooleanArray(components)
            private val labelled = BooleanArray(components)
            private val positions = HashMap<Int, Int>()

            fun sort(nodesOfClass: ArrayList<Node>) {
                for (node in nodesOfClass) {
                    if (!refined[node.component]) {
                        refined[node.component] = true
                        refine(members[node.component], stableHi, stableLo)
                    }
                }
                // Components holding several nodes that refinement left tied are labelled canonically.
                val tied = HashMap<TiedGroup, Int>()
                for (node in nodesOfClass) tied.merge(TiedGroup(stableHi[node.index], stableLo[node.index], node.component), 1, Int::plus)
                for ((group, count) in tied) {
                    val component = group.component
                    if (count > 1 && !labelled[component] && members[component].size <= MAX_CANON_NODES) {
                        labelled[component] = true
                        canon(members[component])
                    }
                }
                nodesOfClass.sortWith(
                    Comparator<Node> { a, b -> stableHi[a.index].compareTo(stableHi[b.index]) }
                        .thenComparator { a, b -> stableLo[a.index].compareTo(stableLo[b.index]) }
                        .thenComparator { a, b -> canonHi[a.index].compareTo(canonHi[b.index]) }
                        .thenComparator { a, b -> canonLo[a.index].compareTo(canonLo[b.index]) }
                        .thenComparator { a, b -> compareComponents(a.component, b.component) }
                        .thenComparator { a, b -> a.index.compareTo(b.index) },
                )
            }

            /** By first root edge, then by position among the objects of that edge; components without one last. */
            private fun compareComponents(a: Int, b: Int): Int {
                if (a == b) return 0
                val edgeA = firstRootEdge[a]
                val edgeB = firstRootEdge[b]
                if (edgeA == null || edgeB == null) {
                    return if (edgeA == null && edgeB == null) a.compareTo(b) else if (edgeA == null) 1 else -1
                }
                val byEdge = edgeA.compareTo(edgeB)
                if (byEdge != 0) return byEdge
                val byPosition = position(a, edgeA).compareTo(position(b, edgeB))
                return if (byPosition != 0) byPosition else a.compareTo(b)
            }

            private fun position(component: Int, edge: RootEdge): Int =
                positions.getOrPut(component) {
                    find(edge.subject, edge.predicate, null).indexOfFirst { t -> (t.obj as? BlankNode)?.let { byTerm[it]?.component } == component }
                }

            /**
             * Refines the colours of the nodes of one component over all their links until no group of equal colours
             * splits any more.
             */
            private fun refine(component: IntArray, hi: LongArray, lo: LongArray) {
                val nextHi = LongArray(component.size)
                val nextLo = LongArray(component.size)
                var distinct = distinct(component, hi, lo)
                for (round in 0..component.size) {
                    canonWork += component.size
                    for (j in component.indices) {
                        val node = nodes[component[j]]
                        var accHi = 0L
                        var accLo = 0L
                        for (k in 0 until node.down.size) {
                            val target = node.down.targets[k]
                            accHi += edgeHi(node.down.labels[k], hi[target], lo[target])
                            accLo += edgeLo(node.down.labels[k], hi[target], lo[target])
                        }
                        for (k in 0 until node.up.size) {
                            val target = node.up.targets[k]
                            accHi += edgeHi(node.up.labels[k] xor UP, hi[target], lo[target])
                            accLo += edgeLo(node.up.labels[k] xor UP, hi[target], lo[target])
                        }
                        val count = node.down.size + node.up.size
                        nextHi[j] = nodeHi(hi[node.index], lo[node.index], accHi, accLo, count)
                        nextLo[j] = nodeLo(hi[node.index], lo[node.index], accHi, accLo, count)
                    }
                    for (j in component.indices) {
                        hi[component[j]] = nextHi[j]
                        lo[component[j]] = nextLo[j]
                    }
                    val now = distinct(component, hi, lo)
                    if (now == distinct) break
                    distinct = now
                }
            }

            private fun distinct(component: IntArray, hi: LongArray, lo: LongArray): Int {
                val seen = HashSet<Colour>(component.size * 2)
                for (i in component) seen += Colour(hi[i], lo[i])
                return seen.size
            }

            /**
             * Gives every node of the component its own colour, in a way that depends on the structure only: while
             * some nodes share a colour, one node of the group with the smallest colour is singled out and the
             * colours are refined again. The node is the one whose singling out gives the smallest colouring (nodes
             * that give the same colouring are interchangeable as far as one step ahead can tell; the first in graph
             * order is taken).
             */
            private fun canon(component: IntArray) {
                for (i in component) {
                    canonHi[i] = stableHi[i]
                    canonLo[i] = stableLo[i]
                }
                for (step in component.indices) {
                    val group = smallestTiedGroup(component) ?: break
                    if (canonWork > 2 * MAX_CANON_WORK) break
                    var choice = group[0]
                    if (group.size <= MAX_CANON_CHOICES && canonWork <= MAX_CANON_WORK) {
                        val savedHi = LongArray(component.size) { canonHi[component[it]] }
                        val savedLo = LongArray(component.size) { canonLo[component[it]] }
                        var bestHi = 0L
                        var bestLo = 0L
                        var first = true
                        for (candidate in group) {
                            singleOut(candidate)
                            refine(component, canonHi, canonLo)
                            var sumHi = 0L
                            var sumLo = 0L
                            for (i in component) {
                                sumHi += mix(canonHi[i], canonLo[i])
                                sumLo += mix(canonLo[i] xor ALT, canonHi[i])
                            }
                            for (j in component.indices) {
                                canonHi[component[j]] = savedHi[j]
                                canonLo[component[j]] = savedLo[j]
                            }
                            if (first || sumHi < bestHi || (sumHi == bestHi && sumLo < bestLo)) {
                                first = false
                                bestHi = sumHi
                                bestLo = sumLo
                                choice = candidate
                            }
                        }
                    }
                    singleOut(choice)
                    refine(component, canonHi, canonLo)
                }
            }

            private fun singleOut(index: Int) {
                val hi = canonHi[index]
                val lo = canonLo[index]
                canonHi[index] = mix(mix(hi, lo), SINGLED)
                canonLo[index] = mix(mix(lo xor ALT, hi), SINGLED)
            }

            /** The nodes sharing the smallest colour that is shared at all, in graph order; null when none is. */
            private fun smallestTiedGroup(component: IntArray): IntArray? {
                val sorted =
                    component.sortedWith(
                        Comparator<Int> { a, b -> canonHi[a].compareTo(canonHi[b]) }
                            .thenComparator { a, b -> canonLo[a].compareTo(canonLo[b]) }
                            .thenComparator { a, b -> a.compareTo(b) },
                    )
                var start = 0
                while (start < sorted.size) {
                    var end = start + 1
                    while (end < sorted.size && canonHi[sorted[end]] == canonHi[sorted[start]] && canonLo[sorted[end]] == canonLo[sorted[start]]) end++
                    if (end - start > 1) return IntArray(end - start) { sorted[start + it] }
                    start = end
                }
                return null
            }
        }
    }

    // Tags mixed into link labels and accumulators.
    private const val DOWN = 0x646f776eL
    private const val TERM = 0x7465726dL
    private const val UP = 0x7570L
    private const val OUT = 0x3eL
    private const val IN = 0x3cL
    private const val ITEM = 0x6974656dL
    private const val ITEM_OF = 0x6974656d6f66L
    private const val CELL_OF = 0x63656c6c6f66L
    private const val LENGTH = 0x6c656e67L
    private const val TAIL = 0x7461696cL
    private const val OPEN = 0x6f70656eL
    private const val ROOT = 0x726f6f74L
    private const val SINGLED = 0x73696e67L
    private const val ALT = 0x5bd1e9955bd1e995L
    private const val GOLDEN = -0x61c8864680b583ebL

    /** MurmurHash3's 64-bit finaliser. */
    private fun scramble(value: Long): Long {
        var h = value
        h = h xor (h ushr 33)
        h *= -0xae502812aa7333L
        h = h xor (h ushr 33)
        h *= -0x3b314601e57a13adL
        h = h xor (h ushr 33)
        return h
    }

    /** Order-dependent combination of two values. */
    private fun mix(a: Long, b: Long): Long = scramble(java.lang.Long.rotateLeft(a, 29) xor scramble(b + GOLDEN))

    private fun edgeHi(label: Long, hi: Long, lo: Long): Long = mix(mix(label, hi), lo)

    private fun edgeLo(label: Long, hi: Long, lo: Long): Long = mix(mix(label xor ALT, lo), hi)

    private fun nodeHi(baseHi: Long, baseLo: Long, accHi: Long, accLo: Long, count: Int): Long =
        mix(mix(mix(mix(baseHi, baseLo), accHi), accLo), count.toLong())

    private fun nodeLo(baseHi: Long, baseLo: Long, accHi: Long, accLo: Long, count: Int): Long =
        mix(mix(mix(mix(baseLo xor ALT, baseHi), accLo), accHi), count.toLong())

    private val HEX = "0123456789abcdef".toCharArray()

    private fun hex(value: Long): String {
        val out = CharArray(16)
        for (i in 0 until 16) out[i] = HEX[((value ushr (60 - 4 * i)) and 0xf).toInt()]
        return String(out)
    }
}
