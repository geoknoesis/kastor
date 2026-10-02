package com.geoknoesis.kastor.ontoquality.metrics.integration

import com.geoknoesis.kastor.ontoquality.metrics.ImportanceWeights
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetricsReport
import com.geoknoesis.kastor.ontoquality.metrics.compute.CycleDetector
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.vocab.RDFS
import java.util.BitSet
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Shallow-depth signal for classes that participate in a subclass cycle. Their depth below owl:Thing is undefined
 * (the metrics report them with depth 0), and `1 / max(1, depth)` would rank them as central as a root; they get the
 * neutral value of a depth-2 class instead.
 */
internal const val CYCLE_PARTICIPANT_SHALLOW_SIGNAL = 0.5

internal data class ImportanceComputation(
    val importance: Map<String, Double>,
    val hints: Map<String, String>,
)

/**
 * Compute a per-class importance score in [0.0, 1.0] from structural signals
 * (subclass fan-out, incoming domain/range references, hierarchy depth, labels).
 *
 * Cost: one pass over the graph for label/domain/range signals plus one bottom-up pass over the
 * SCC-condensed subclass hierarchy for transitive descendant counts.
 * Descendant counts are exact unless the sets of shared descendants exceed [DESCENDANT_SET_BUDGET_BYTES]
 * (see [countTransitiveDescendants]); a hint then reads "about N transitive descendants".
 */
internal fun computeImportance(
    ontology: RdfGraph,
    report: VocabularyMetricsReport,
    weights: ImportanceWeights,
): ImportanceComputation {
    val fan = report.owl.extensions.subClassFanOut
    val children = fan.fullSubClassChildrenOf
    val entities = report.owl.extensions.integrationNamedClasses
    if (entities.isEmpty()) {
        return ImportanceComputation(emptyMap(), emptyMap())
    }

    val signals = scanGraphSignals(ontology, entities)
    val incoming = signals.incomingDomainRange
    val labels = signals.labelled
    val descendantStats = DescendantCountStats()
    val descendantCounts = countTransitiveDescendants(entities, children, stats = descendantStats)
    val directCounts = entities.associateWith { fan.fullFanOutMap[it] ?: 0 }
    val depthBy = report.owl.extensions.classHierarchyDepth.depthByClass
    val cycleParticipants = report.owl.extensions.classHierarchyDepth.cycleParticipants.toHashSet()

    val fanOutSignal = entities.associateWith { ln(1.0 + descendantCounts.getValue(it).toDouble()) }
    val incomingSignal = entities.associateWith { ln(1.0 + incoming.getValue(it).toDouble()) }
    val shallowSignal =
        entities.associateWith {
            // Depth is measured from owl:Thing (roots = 1), so roots score 1; cycle participants have no defined depth.
            if (it in cycleParticipants) {
                CYCLE_PARTICIPANT_SHALLOW_SIGNAL
            } else {
                1.0 / maxOf(1, depthBy[it] ?: 0)
            }
        }
    val labelSignal = entities.associateWith { if (it in labels) 1.0 else 0.0 }

    fun norm(m: Map<String, Double>): Map<String, Double> {
        val mx = m.values.maxOrNull() ?: 0.0
        if (mx <= 0.0) return entities.associateWith { 0.0 }
        return m.mapValues { (_, v) -> (v / mx).coerceIn(0.0, 1.0) }
    }

    val nFan = norm(fanOutSignal)
    val nIn = norm(incomingSignal)
    val nSh = norm(shallowSignal)
    val nLbl = labelSignal

    val rawImportance =
        entities.associateWith { e ->
            weights.fanOutWeight * nFan.getValue(e) +
                weights.incomingPropertiesWeight * nIn.getValue(e) +
                weights.shallowDepthWeight * nSh.getValue(e) +
                weights.labelPresenceWeight * nLbl.getValue(e)
        }

    val hints =
        entities.associateWith { e ->
            val dc = directCounts.getValue(e)
            val td = descendantCounts.getValue(e)
            val inc = incoming.getValue(e)
            val parts = mutableListOf<String>()
            when {
                td == 0 -> parts.add("no subclasses in asserted hierarchy")
                td == dc -> parts.add("$dc direct subclasses")
                e in descendantStats.estimatedClasses -> parts.add("$dc direct subclasses; about $td transitive descendants")
                else -> parts.add("$dc direct subclasses; $td transitive descendants")
            }
            if (inc > 0) {
                parts.add("referenced by $inc properties (domain/range)")
            }
            parts.joinToString("; ")
        }

    return ImportanceComputation(importance = rawImportance, hints = hints)
}

private class GraphSignals(
    val incomingDomainRange: Map<String, Int>,
    val labelled: Set<String>,
)

/** Single pass: rdfs:domain / rdfs:range references to entities and entities carrying an rdfs:label. */
private fun scanGraphSignals(graph: RdfGraph, entities: Set<String>): GraphSignals {
    val counts = entities.associateWithTo(HashMap()) { 0 }
    val labelled = HashSet<String>()
    for (t in graph.getTriplesSequence()) {
        when (t.predicate) {
            RDFS.domain, RDFS.range -> {
                val obj = (t.obj as? Iri)?.value ?: continue
                if (obj in entities) counts[obj] = counts.getValue(obj) + 1
            }
            RDFS.label -> {
                val subj = (t.subject as? Iri)?.value ?: continue
                if (subj in entities) labelled.add(subj)
            }
            else -> Unit
        }
    }
    return GraphSignals(counts, labelled)
}

/** Counting seam of [countTransitiveDescendants]: what the computation kept, for tests and diagnostics. */
internal class DescendantCountStats {
    /** Sets of shared classes (or sketches) kept for a later parent. A tree keeps none. */
    var setsRetained: Long = 0

    /** Largest amount of set (or sketch) memory alive at one time, in bytes. */
    var peakLiveBytes: Long = 0

    /** Whether the exact sets exceeded the budget, so counts above a shared class are estimates. */
    var approximate: Boolean = false

    /** Precision `p` of the sketches (2^p one-byte registers each); 0 when counts are exact. */
    var sketchPrecision: Int = 0

    /** Classes whose count includes an estimate. */
    val estimatedClasses: MutableSet<String> = HashSet()
}

/** Memory that the sets of shared descendants may use before [countTransitiveDescendants] switches to estimates. */
internal const val DESCENDANT_SET_BUDGET_BYTES: Long = 64L * 1024 * 1024

private const val MIN_SKETCH_PRECISION = 4
private const val MAX_SKETCH_PRECISION = 10

/** Relative standard error of a count estimated with sketches of the given precision (HyperLogLog: 1.04 / sqrt(2^p)). */
internal fun descendantSketchStandardError(precision: Int): Double = 1.04 / sqrt((1 shl precision).toDouble())

/**
 * Number of distinct classes reachable through one or more subclass edges from each entity (a class in a
 * cycle reaches itself), computed bottom-up over the strongly connected component condensation.
 *
 * No per-class descendant set is materialised:
 *
 * 1. **Tree part, exact, no set.** A class with a single parent is reached from an ancestor by one path only, so
 *    subtree sizes add up. A hierarchy that is a tree or a forest (the common case for taxonomies) is counted with
 *    one integer per class.
 * 2. **Shared part, exact, sets over shared classes only.** A *shared* class has two or more parents. The
 *    descendants of a class are its tree part plus, for every shared class it reaches, that class and its own tree
 *    part, and those parts are disjoint. So a class only needs the set of shared classes it reaches: a sorted
 *    array when small, a bit set over the shared classes (not over all classes) when dense. A set is released as
 *    soon as the last parent has consumed it (reference count).
 * 3. **Over budget, estimated.** When the live sets would exceed [memoryBudgetBytes], the shared part is
 *    estimated with HyperLogLog sketches instead (2^p one-byte registers each, p chosen in 4..10 so the sketches
 *    alive at one time fit the budget; relative standard error [descendantSketchStandardError], 3.25 % at p = 10).
 *    The tree part stays exact, so classes that reach no shared class are exact, and the estimate is deterministic
 *    (fixed hash). Only at p = 4 (more than `budget / 16` sketches alive at once) can the budget be exceeded, by 16
 *    bytes per further sketch.
 *
 * Work is O(V + E) for the tree part, plus one union per edge above a shared class.
 */
internal fun countTransitiveDescendants(
    entities: Set<String>,
    children: Map<String, Set<String>>,
    memoryBudgetBytes: Long = DESCENDANT_SET_BUDGET_BYTES,
    stats: DescendantCountStats? = null,
): Map<String, Int> {
    val dag = Condensation.of(entities, children)
    val exact = dag.countWithExactSets(memoryBudgetBytes, stats)
    val shared = exact ?: dag.countWithSketches(memoryBudgetBytes, stats ?: DescendantCountStats())
    val result = HashMap<String, Int>(entities.size * 2)
    dag.components.forEachIndexed { index, component ->
        val count = (dag.treeMembers[index] + shared[index] + if (component.cyclic) dag.size[index] else 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val estimated = exact == null && shared[index] > 0
        for (member in component.members) {
            if (member !in entities) continue
            result[member] = count
            if (estimated) stats?.estimatedClasses?.add(member)
        }
    }
    return result
}

/** The subclass hierarchy with every cycle collapsed, parents before children, plus the exact tree part. */
private class Condensation(
    val components: List<CycleDetector.Component>,
    val childComponents: Array<IntArray>,
    val parentCount: IntArray,
) {
    val size: IntArray = IntArray(components.size) { components[it].members.size }

    /** Members reached through single-parent classes only (each by exactly one path). */
    val treeMembers: LongArray = LongArray(components.size)

    /** Whether the component reaches a shared class. */
    val reachesShared: BooleanArray = BooleanArray(components.size)

    /** Dense index of a shared component (two or more parents), or -1. */
    val sharedIndex: IntArray = IntArray(components.size) { -1 }

    /** Per shared index: the class itself (all members of its component) plus its tree part. */
    val sharedWeight: LongArray

    init {
        var shared = 0
        for (index in components.indices) if (parentCount[index] >= 2) sharedIndex[index] = shared++
        sharedWeight = LongArray(shared)
        // Children come after their parents: walk backwards so children are final first.
        for (index in components.indices.reversed()) {
            var tree = 0L
            var reaches = false
            for (child in childComponents[index]) {
                if (sharedIndex[child] >= 0) {
                    reaches = true
                } else {
                    tree += size[child] + treeMembers[child]
                    if (reachesShared[child]) reaches = true
                }
            }
            treeMembers[index] = tree
            reachesShared[index] = reaches
            if (sharedIndex[index] >= 0) sharedWeight[sharedIndex[index]] = size[index] + tree
        }
    }

    /**
     * Per component, the number of members reached through a shared class, with exact sets of shared classes; null
     * when the sets alive at one time would exceed [budgetBytes].
     */
    fun countWithExactSets(budgetBytes: Long, stats: DescendantCountStats?): LongArray? {
        val out = LongArray(components.size)
        val sharedCount = sharedWeight.size
        if (sharedCount == 0) return out
        val denseBytes = ((sharedCount + 63) / 64) * 8L
        val pending = parentCount.copyOf()
        val stored = arrayOfNulls<Any>(components.size) // IntArray (sorted, sparse) or BitSet (dense)
        val scratch = BitSet(sharedCount)
        var live = 0L
        var peak = 0L
        var retained = 0L

        fun bytesOf(set: Any): Long = if (set is IntArray) set.size * 4L else denseBytes

        for (index in components.indices.reversed()) {
            if (!reachesShared[index]) continue
            for (child in childComponents[index]) {
                val sharedChild = sharedIndex[child]
                if (sharedChild >= 0) scratch.set(sharedChild)
                when (val set = stored[child]) {
                    null -> Unit
                    is IntArray -> for (bit in set) scratch.set(bit)
                    else -> scratch.or(set as BitSet)
                }
                if (--pending[child] == 0 && stored[child] != null) {
                    live -= bytesOf(stored[child]!!)
                    stored[child] = null
                }
            }
            var sum = 0L
            var cardinality = 0
            var bit = scratch.nextSetBit(0)
            while (bit >= 0) {
                sum += sharedWeight[bit]
                cardinality++
                bit = scratch.nextSetBit(bit + 1)
            }
            out[index] = sum
            if (pending[index] > 0) {
                val set: Any =
                    if (cardinality * 4L <= denseBytes) {
                        val sparse = IntArray(cardinality)
                        var at = 0
                        var b = scratch.nextSetBit(0)
                        while (b >= 0) {
                            sparse[at++] = b
                            b = scratch.nextSetBit(b + 1)
                        }
                        sparse
                    } else {
                        scratch.clone()
                    }
                live += bytesOf(set)
                if (live > budgetBytes) return null
                stored[index] = set
                retained++
                if (live > peak) peak = live
            }
            scratch.clear()
        }
        stats?.let {
            it.setsRetained = retained
            it.peakLiveBytes = peak
        }
        return out
    }

    /** As [countWithExactSets], with the set of members below the reached shared classes estimated by sketches. */
    fun countWithSketches(budgetBytes: Long, stats: DescendantCountStats): LongArray {
        val out = LongArray(components.size)
        fun keepsSketch(index: Int): Boolean = parentCount[index] > 0 && (reachesShared[index] || sharedIndex[index] >= 0)

        // Dry run of the reference counts: how many sketches are alive at one time.
        var live = 0L
        var peakSketches = 1L
        val pending = parentCount.copyOf()
        for (index in components.indices.reversed()) {
            for (child in childComponents[index]) if (--pending[child] == 0 && keepsSketch(child)) live--
            if (keepsSketch(index)) {
                live++
                if (live > peakSketches) peakSketches = live
            }
        }
        val perSketch = (budgetBytes / peakSketches).coerceAtLeast(1)
        val precision = (63 - java.lang.Long.numberOfLeadingZeros(perSketch)).coerceIn(MIN_SKETCH_PRECISION, MAX_SKETCH_PRECISION)
        val registers = 1 shl precision

        parentCount.copyInto(pending)
        val stored = arrayOfNulls<ByteArray>(components.size)
        val scratch = ByteArray(registers)
        var retained = 0L
        var peak = 0L
        live = 0L
        for (index in components.indices.reversed()) {
            val own = sharedIndex[index]
            if (!reachesShared[index] && own < 0) continue
            scratch.fill(0)
            var any = false
            for (child in childComponents[index]) {
                stored[child]?.let { sketch ->
                    for (r in 0 until registers) if (sketch[r] > scratch[r]) scratch[r] = sketch[r]
                    any = true
                }
                if (--pending[child] == 0 && stored[child] != null) {
                    stored[child] = null
                    live--
                }
            }
            if (any) out[index] = estimate(scratch, precision)
            if (pending[index] > 0) {
                val sketch = scratch.copyOf()
                if (own >= 0) for (i in 0 until sharedWeight[own]) add(sketch, precision, mix((own.toLong() shl 32) xor i))
                stored[index] = sketch
                retained++
                live++
                if (live * registers > peak) peak = live * registers
            }
        }
        stats.approximate = true
        stats.sketchPrecision = precision
        stats.setsRetained = retained
        stats.peakLiveBytes = peak
        return out
    }

    companion object {
        fun of(entities: Set<String>, children: Map<String, Set<String>>): Condensation {
            val nodes = LinkedHashSet<String>(entities)
            children.forEach { (parent, ch) ->
                nodes.add(parent)
                nodes.addAll(ch)
            }
            val components = CycleDetector.stronglyConnectedComponents(nodes, children)
            val componentOf = HashMap<String, Int>(nodes.size * 2)
            components.forEachIndexed { index, component -> component.members.forEach { componentOf[it] = index } }
            val childComponents = Array(components.size) { IntArray(0) }
            val parentCount = IntArray(components.size)
            val targets = HashSet<Int>()
            components.forEachIndexed { index, component ->
                targets.clear()
                for (member in component.members) {
                    for (child in children[member].orEmpty()) {
                        val target = componentOf.getValue(child)
                        if (target != index) targets.add(target)
                    }
                }
                childComponents[index] = targets.toIntArray()
                targets.forEach { parentCount[it]++ }
            }
            return Condensation(components, childComponents, parentCount)
        }

        /** SplitMix64 finaliser: a fixed, well-mixed 64-bit hash, so estimates are the same on every run. */
        private fun mix(value: Long): Long {
            var z = value + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        private fun add(registers: ByteArray, precision: Int, hash: Long) {
            val index = (hash ushr (64 - precision)).toInt()
            val rest = hash shl precision
            val rank = if (rest == 0L) 64 - precision + 1 else java.lang.Long.numberOfLeadingZeros(rest) + 1
            if (rank > registers[index]) registers[index] = rank.toByte()
        }

        /** HyperLogLog estimate with the linear-counting correction for small cardinalities. */
        private fun estimate(registers: ByteArray, precision: Int): Long {
            val m = registers.size.toDouble()
            var sum = 0.0
            var zeros = 0
            for (r in registers) {
                sum += 1.0 / (1L shl r.toInt()).toDouble()
                if (r.toInt() == 0) zeros++
            }
            val alpha =
                when (precision) {
                    4 -> 0.673
                    5 -> 0.697
                    6 -> 0.709
                    else -> 0.7213 / (1.0 + 1.079 / m)
                }
            val raw = alpha * m * m / sum
            val corrected = if (raw <= 2.5 * m && zeros > 0) m * ln(m / zeros) else raw
            return Math.round(corrected)
        }
    }
}
