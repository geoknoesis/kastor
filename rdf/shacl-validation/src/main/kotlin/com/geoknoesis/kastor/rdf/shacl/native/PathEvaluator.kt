package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm

/**
 * Evaluates SHACL property paths over a [DataGraphIndex] (bounded graph traversal).
 */
internal object PathEvaluator {

    fun evaluate(focus: RdfTerm, path: ShaclPath, graph: DataGraphIndex): List<RdfTerm> =
        if (focus is RdfResource) evaluate(focus, path, graph) else emptyList()

    fun evaluate(focus: RdfResource, path: ShaclPath, graph: DataGraphIndex): List<RdfTerm> =
        expandForward(focus, path, graph).toList()

    private fun expandForward(start: RdfResource, path: ShaclPath, graph: DataGraphIndex): Set<RdfTerm> =
        when (path) {
            is ShaclPath.Predicate -> graph.objects(start, path.iri).toSet()
            is ShaclPath.Inverse -> expandBackward(start, path.child, graph)
            is ShaclPath.Sequence -> expandSequenceForward(start, path.segments, graph)
            is ShaclPath.Alternative -> path.options.flatMapTo(mutableSetOf()) { expandForward(start, it, graph) }
            is ShaclPath.ZeroOrMore -> zeroOrMoreForward(start, path.child, graph)
            is ShaclPath.OneOrMore -> oneOrMoreForward(start, path.child, graph)
            is ShaclPath.ZeroOrOne -> expandForward(start, path.child, graph) + setOf<RdfTerm>(start)
        }

    private fun expandBackward(end: RdfResource, path: ShaclPath, graph: DataGraphIndex): Set<RdfTerm> =
        when (path) {
            is ShaclPath.Predicate -> graph.subjectsWith(path.iri, end).map { it as RdfTerm }.toSet()
            is ShaclPath.Inverse -> expandForward(end, path.child, graph)
            is ShaclPath.Sequence -> expandSequenceBackward(end, path.segments, graph)
            is ShaclPath.Alternative -> path.options.flatMapTo(mutableSetOf()) { expandBackward(end, it, graph) }
            is ShaclPath.ZeroOrMore -> zeroOrMoreBackward(end, path.child, graph)
            is ShaclPath.OneOrMore -> oneOrMoreBackward(end, path.child, graph)
            is ShaclPath.ZeroOrOne -> expandBackward(end, path.child, graph) + setOf<RdfTerm>(end)
        }

    private fun expandSequenceForward(start: RdfResource, segments: List<ShaclPath>, graph: DataGraphIndex): Set<RdfTerm> {
        if (segments.isEmpty()) return emptySet()
        var frontier: Set<RdfResource> = setOf(start)
        segments.forEachIndexed { idx, seg ->
            val last = idx == segments.lastIndex
            val nextAcc = mutableSetOf<RdfTerm>()
            for (node in frontier) {
                val outs = expandForward(node, seg, graph)
                if (last) nextAcc.addAll(outs)
                else nextAcc.addAll(outs.filterIsInstance<RdfResource>())
            }
            if (!last) frontier = nextAcc.filterIsInstance<RdfResource>().toSet()
            else return nextAcc
        }
        return emptySet()
    }

    private fun expandSequenceBackward(end: RdfResource, segments: List<ShaclPath>, graph: DataGraphIndex): Set<RdfTerm> {
        if (segments.isEmpty()) return emptySet()
        if (segments.size == 1) return expandBackward(end, segments.single(), graph)
        val last = segments.last()
        val prefix = segments.dropLast(1)
        val mids = expandBackward(end, last, graph).filterIsInstance<RdfResource>()
        return mids.flatMapTo(mutableSetOf()) { expandSequenceBackward(it, prefix, graph) }
    }

    private fun zeroOrMoreForward(start: RdfResource, child: ShaclPath, graph: DataGraphIndex): Set<RdfTerm> {
        val results = mutableSetOf<RdfTerm>()
        val visited = mutableSetOf<RdfResource>()
        val queue = ArrayDeque<RdfResource>()
        queue.add(start)
        visited.add(start)
        results.add(start)
        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            for (t in expandForward(x, child, graph)) {
                results.add(t)
                if (t is RdfResource && visited.add(t)) queue.add(t)
            }
        }
        return results
    }

    private fun zeroOrMoreBackward(end: RdfResource, child: ShaclPath, graph: DataGraphIndex): Set<RdfTerm> {
        val results = mutableSetOf<RdfTerm>()
        val visited = mutableSetOf<RdfResource>()
        val queue = ArrayDeque<RdfResource>()
        queue.add(end)
        visited.add(end)
        results.add(end)
        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            for (t in expandBackward(x, child, graph)) {
                results.add(t)
                if (t is RdfResource && visited.add(t)) queue.add(t)
            }
        }
        return results
    }

    private fun oneOrMoreForward(start: RdfResource, child: ShaclPath, graph: DataGraphIndex): Set<RdfTerm> {
        val first = expandForward(start, child, graph)
        val results = first.toMutableSet()
        val queue = ArrayDeque<RdfResource>()
        first.filterIsInstance<RdfResource>().forEach { queue.add(it) }
        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            for (t in expandForward(x, child, graph)) {
                if (results.add(t) && t is RdfResource) queue.add(t)
            }
        }
        return results
    }

    private fun oneOrMoreBackward(end: RdfResource, child: ShaclPath, graph: DataGraphIndex): Set<RdfTerm> {
        val first = expandBackward(end, child, graph)
        val results = first.toMutableSet()
        val queue = ArrayDeque<RdfResource>()
        first.filterIsInstance<RdfResource>().forEach { queue.add(it) }
        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            for (t in expandBackward(x, child, graph)) {
                if (results.add(t) && t is RdfResource) queue.add(t)
            }
        }
        return results
    }
}
