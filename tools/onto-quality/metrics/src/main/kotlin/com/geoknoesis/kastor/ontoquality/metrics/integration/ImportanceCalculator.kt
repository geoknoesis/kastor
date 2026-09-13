package com.geoknoesis.kastor.ontoquality.metrics.integration

import com.geoknoesis.kastor.ontoquality.metrics.ImportanceWeights
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetricsReport
import com.geoknoesis.kastor.ontoquality.metrics.compute.CycleDetector
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.vocab.RDFS
import java.util.BitSet
import kotlin.math.ln

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
    val descendantCounts = countTransitiveDescendants(entities, children)
    val directCounts = entities.associateWith { fan.fullFanOutMap[it] ?: 0 }
    val depthBy = report.owl.extensions.classHierarchyDepth.depthByClass

    val fanOutSignal = entities.associateWith { ln(1.0 + descendantCounts.getValue(it).toDouble()) }
    val incomingSignal = entities.associateWith { ln(1.0 + incoming.getValue(it).toDouble()) }
    val shallowSignal =
        entities.associateWith {
            val d = depthBy[it] ?: 0
            1.0 / (1.0 + d)
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

/**
 * Number of distinct classes reachable through one or more subclass edges from each entity (a class in a
 * cycle reaches itself). Computed bottom-up over the strongly connected component condensation: each
 * component's descendant set is the union of its child components' members and descendants. Descendant
 * bitsets are released once every parent component has consumed them.
 */
internal fun countTransitiveDescendants(entities: Set<String>, children: Map<String, Set<String>>): Map<String, Int> {
    val nodes = LinkedHashSet<String>(entities)
    children.forEach { (parent, ch) -> nodes.add(parent); nodes.addAll(ch) }
    val components = CycleDetector.stronglyConnectedComponents(nodes, children)
    val componentOf = HashMap<String, Int>(nodes.size * 2)
    components.forEachIndexed { index, component -> component.members.forEach { componentOf[it] = index } }
    val nodeIndex = HashMap<String, Int>(nodes.size * 2)
    nodes.forEachIndexed { index, node -> nodeIndex[node] = index }

    val childComponents = Array(components.size) { IntArray(0) }
    val pendingParents = IntArray(components.size)
    components.forEachIndexed { index, component ->
        val targets = HashSet<Int>()
        for (member in component.members) {
            for (child in children[member].orEmpty()) {
                val target = componentOf.getValue(child)
                if (target != index) targets.add(target)
            }
        }
        childComponents[index] = targets.toIntArray()
        targets.forEach { pendingParents[it]++ }
    }

    val descendants = arrayOfNulls<BitSet>(components.size)
    val result = HashMap<String, Int>(entities.size * 2)
    // Components are in topological order (parents before children): walk backwards so children come first.
    for (index in components.indices.reversed()) {
        val component = components[index]
        val bits = BitSet()
        for (child in childComponents[index]) {
            components[child].members.forEach { bits.set(nodeIndex.getValue(it)) }
            descendants[child]?.let(bits::or)
            if (--pendingParents[child] == 0) descendants[child] = null
        }
        if (component.cyclic) component.members.forEach { bits.set(nodeIndex.getValue(it)) }
        val count = bits.cardinality()
        component.members.forEach { if (it in entities) result[it] = count }
        if (pendingParents[index] > 0) descendants[index] = bits
    }
    return result
}
