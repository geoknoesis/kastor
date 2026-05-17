package com.geoknoesis.kastor.ontoquality.metrics.integration

import com.geoknoesis.kastor.ontoquality.metrics.ImportanceWeights
import com.geoknoesis.kastor.ontoquality.metrics.VocabularyMetricsReport
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.vocab.RDFS
import kotlin.math.ln

internal data class ImportanceComputation(
    val importance: Map<String, Double>,
    val hints: Map<String, String>,
)

/**
 * Compute a per-class importance score in [0.0, 1.0] from structural signals
 * (subclass fan-out, incoming domain/range references, hierarchy depth, labels).
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

    val incoming = countIncomingDomainRange(ontology, entities)
    val labels = entities.filter { hasLabel(ontology, it) }.toSet()
    val descendantCounts = entities.associateWith { countTransitiveDescendants(it, children) }
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

private fun countTransitiveDescendants(root: String, children: Map<String, Set<String>>): Int {
    val seen = mutableSetOf<String>()
    val stack = ArrayDeque<String>()
    for (c in children[root].orEmpty()) {
        stack.add(c)
    }
    while (stack.isNotEmpty()) {
        val n = stack.removeLast()
        if (!seen.add(n)) continue
        for (ch in children[n].orEmpty()) {
            stack.add(ch)
        }
    }
    return seen.size
}

private fun countIncomingDomainRange(graph: RdfGraph, entities: Set<String>): Map<String, Int> {
    val dom = RDFS.domain.value
    val rng = RDFS.range.value
    val counts = entities.associateWith { 0 }.toMutableMap()
    for (t in graph.getTriplesSequence()) {
        val pred = t.predicate.value
        if (pred != dom && pred != rng) continue
        val obj = (t.obj as? Iri)?.value ?: continue
        if (obj in entities) {
            counts[obj] = counts.getValue(obj) + 1
        }
    }
    return counts
}

private fun hasLabel(graph: RdfGraph, iri: String): Boolean {
    val subj = Iri(iri)
    val lab = RDFS.label
    return graph.getTriplesSequence().any { it.subject == subj && it.predicate == lab }
}
