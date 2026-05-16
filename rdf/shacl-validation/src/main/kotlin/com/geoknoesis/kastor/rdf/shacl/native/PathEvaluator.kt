package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm

/**
 * Evaluates SHACL property paths over a [DataGraphIndex].
 *
 * **Multiset expansion.** Predicate walks preserve duplicate objects — parallel triples yield duplicate
 * elements — and sequence segments concatenate those lists so bindings multiply accordingly.
 *
 * **Cardinality vs multiplicity.** `sh:minCount` / `sh:maxCount` (native validator) count values
 * distinct under [shaclRdfTermFingerprint]; multiset duplicates collapse once validated,
 * matching tests such as W3C `path-sequence-duplicate-001`.
 *
 * **Transitive closures.** `sh:zeroOrMorePath` / `sh:oneOrMorePath` accumulate reachable endpoints
 * uniquely under the same fingerprint (rather than plain Kotlin `Set` hash/`equals` alone): literals
 * that differ only by canonical encoding (`vs`) collapse consistently with cardinality dedup.
 */
internal object PathEvaluator {

    fun evaluate(focus: RdfTerm, path: ShaclPath, graph: DataGraphIndex): List<RdfTerm> =
        if (focus is RdfResource) evaluate(focus, path, graph) else emptyList()

    fun evaluate(focus: RdfResource, path: ShaclPath, graph: DataGraphIndex): List<RdfTerm> =
        expandForwardList(focus, path, graph)

    private fun expandForwardList(start: RdfResource, path: ShaclPath, graph: DataGraphIndex): List<RdfTerm> =
        when (path) {
            is ShaclPath.Predicate -> graph.objects(start, path.iri)
            is ShaclPath.Inverse -> expandBackwardList(start, path.child, graph)
            is ShaclPath.Sequence -> expandSequenceForwardList(start, path.segments, graph)
            is ShaclPath.Alternative -> path.options.flatMap { expandForwardList(start, it, graph) }
            is ShaclPath.ZeroOrMore -> zeroOrMoreForwardList(start, path.child, graph)
            is ShaclPath.OneOrMore -> oneOrMoreForwardList(start, path.child, graph)
            is ShaclPath.ZeroOrOne ->
                expandForwardList(start, path.child, graph) + listOf<RdfTerm>(start)
        }

    private fun expandBackwardList(end: RdfResource, path: ShaclPath, graph: DataGraphIndex): List<RdfTerm> =
        when (path) {
            is ShaclPath.Predicate -> graph.subjectsWith(path.iri, end).map { it as RdfTerm }
            is ShaclPath.Inverse -> expandForwardList(end, path.child, graph)
            is ShaclPath.Sequence -> expandSequenceBackwardList(end, path.segments, graph)
            is ShaclPath.Alternative -> path.options.flatMap { expandBackwardList(end, it, graph) }
            is ShaclPath.ZeroOrMore -> zeroOrMoreBackwardList(end, path.child, graph)
            is ShaclPath.OneOrMore -> oneOrMoreBackwardList(end, path.child, graph)
            is ShaclPath.ZeroOrOne ->
                expandBackwardList(end, path.child, graph) + listOf<RdfTerm>(end)
        }

    private fun expandSequenceForwardList(start: RdfResource, segments: List<ShaclPath>, graph: DataGraphIndex): List<RdfTerm> {
        if (segments.isEmpty()) return emptyList()
        var frontier: List<RdfResource> = listOf(start)
        for (i in segments.indices) {
            val seg = segments[i]
            if (i == segments.lastIndex) {
                return frontier.flatMap { node -> expandForwardList(node, seg, graph) }
            }
            frontier = frontier.flatMap { node -> expandForwardList(node, seg, graph).filterIsInstance<RdfResource>() }
        }
        return emptyList()
    }

    private fun expandSequenceBackwardList(end: RdfResource, segments: List<ShaclPath>, graph: DataGraphIndex): List<RdfTerm> {
        if (segments.isEmpty()) return emptyList()
        if (segments.size == 1) return expandBackwardList(end, segments.single(), graph)
        val last = segments.last()
        val prefix = segments.dropLast(1)
        return expandBackwardList(end, last, graph).filterIsInstance<RdfResource>().flatMap { mid ->
            expandSequenceBackwardList(mid, prefix, graph)
        }
    }

    private fun zeroOrMoreForwardList(start: RdfResource, child: ShaclPath, graph: DataGraphIndex): List<RdfTerm> {
        val results = linkedMapOf<String, RdfTerm>()
        fun bind(term: RdfTerm) {
            results.putIfAbsent(shaclRdfTermFingerprint(term), term)
        }
        val visited = mutableSetOf<RdfResource>()
        val queue = ArrayDeque<RdfResource>()
        queue.add(start)
        visited.add(start)
        bind(start)
        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            for (t in expandForwardList(x, child, graph)) {
                bind(t)
                if (t is RdfResource && visited.add(t)) queue.add(t)
            }
        }
        return results.values.toList()
    }

    private fun zeroOrMoreBackwardList(end: RdfResource, child: ShaclPath, graph: DataGraphIndex): List<RdfTerm> {
        val results = linkedMapOf<String, RdfTerm>()
        fun bind(term: RdfTerm) {
            results.putIfAbsent(shaclRdfTermFingerprint(term), term)
        }
        val visited = mutableSetOf<RdfResource>()
        val queue = ArrayDeque<RdfResource>()
        queue.add(end)
        visited.add(end)
        bind(end)
        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            for (t in expandBackwardList(x, child, graph)) {
                bind(t)
                if (t is RdfResource && visited.add(t)) queue.add(t)
            }
        }
        return results.values.toList()
    }

    private fun oneOrMoreForwardList(start: RdfResource, child: ShaclPath, graph: DataGraphIndex): List<RdfTerm> {
        val results = linkedMapOf<String, RdfTerm>()
        fun bind(term: RdfTerm) =
            results.putIfAbsent(shaclRdfTermFingerprint(term), term)
        val queue = ArrayDeque<RdfResource>()
        val first = expandForwardList(start, child, graph)
        for (t in first) {
            if (bind(t) == null && t is RdfResource) queue.add(t)
        }
        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            for (t in expandForwardList(x, child, graph)) {
                if (bind(t) == null && t is RdfResource) queue.add(t)
            }
        }
        return results.values.toList()
    }

    private fun oneOrMoreBackwardList(end: RdfResource, child: ShaclPath, graph: DataGraphIndex): List<RdfTerm> {
        val results = linkedMapOf<String, RdfTerm>()
        fun bind(term: RdfTerm) =
            results.putIfAbsent(shaclRdfTermFingerprint(term), term)
        val queue = ArrayDeque<RdfResource>()
        val first = expandBackwardList(end, child, graph)
        for (t in first) {
            if (bind(t) == null && t is RdfResource) queue.add(t)
        }
        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            for (t in expandBackwardList(x, child, graph)) {
                if (bind(t) == null && t is RdfResource) queue.add(t)
            }
        }
        return results.values.toList()
    }
}
