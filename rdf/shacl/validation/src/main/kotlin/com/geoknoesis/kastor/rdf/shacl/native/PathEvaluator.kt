package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException

/**
 * Evaluates SHACL property paths over a [DataGraphIndex] with **set semantics** (SHACL §2.3.2: value nodes are a
 * set).
 *
 * Every step maps a frontier *set* of nodes to the set of nodes reachable from it, so sequences whose segments
 * reach the same node in several ways (e.g. nested `sh:zeroOrOnePath` over a self-loop) stay linear in the number
 * of distinct nodes instead of multiplying bindings. Transitive closures expand only newly discovered nodes.
 *
 * Every loop consults the validation deadline through [ValidationBudget.tick], and every node set is capped by
 * [DataGraphIndex.maxPathValueNodes]. From a literal, predicate steps yield nothing but zero-length paths
 * (`sh:zeroOrMorePath`, `sh:zeroOrOnePath`) include the literal itself. Results keep discovery order and are
 * distinct under [shaclRdfTermFingerprint].
 */
internal object PathEvaluator {

    fun evaluate(focus: RdfTerm, path: ShaclPath, graph: DataGraphIndex): List<RdfTerm> =
        distinctShaclTerms(step(linkedSetOf(focus), path, forward = true, graph).toList())

    private fun add(out: LinkedHashSet<RdfTerm>, term: RdfTerm, graph: DataGraphIndex): Boolean {
        graph.budget.tick("path evaluation")
        val added = out.add(term)
        if (added && out.size > graph.maxPathValueNodes) {
            throw ShaclValidationException(
                "SHACL property path evaluation exceeded ValidationConfig.maxPathValueNodes=${graph.maxPathValueNodes}",
            )
        }
        return added
    }

    private fun step(frontier: Set<RdfTerm>, path: ShaclPath, forward: Boolean, graph: DataGraphIndex): LinkedHashSet<RdfTerm> =
        when (path) {
            is ShaclPath.Predicate -> {
                val out = LinkedHashSet<RdfTerm>()
                for (node in frontier) {
                    graph.budget.tick("path evaluation")
                    if (forward) {
                        if (node is RdfResource) for (o in graph.objects(node, path.iri)) add(out, o, graph)
                    } else {
                        for (s in graph.subjectsWith(path.iri, node)) add(out, s, graph)
                    }
                }
                out
            }
            is ShaclPath.Inverse -> step(frontier, path.child, !forward, graph)
            is ShaclPath.Sequence -> {
                var current = LinkedHashSet<RdfTerm>()
                if (path.segments.isNotEmpty()) {
                    current.addAll(frontier)
                    for (segment in if (forward) path.segments else path.segments.asReversed()) {
                        current = step(current, segment, forward, graph)
                        if (current.isEmpty()) break
                    }
                }
                current
            }
            is ShaclPath.Alternative -> {
                val out = LinkedHashSet<RdfTerm>()
                for (option in path.options) {
                    for (t in step(frontier, option, forward, graph)) add(out, t, graph)
                }
                out
            }
            is ShaclPath.ZeroOrOne -> {
                val out = LinkedHashSet<RdfTerm>()
                for (t in frontier) add(out, t, graph)
                for (t in step(frontier, path.child, forward, graph)) add(out, t, graph)
                out
            }
            is ShaclPath.ZeroOrMore -> closure(frontier, path.child, forward, graph)
            is ShaclPath.OneOrMore -> closure(step(frontier, path.child, forward, graph), path.child, forward, graph)
        }

    /** [start] plus every node reachable from it by one or more [child] steps (breadth-first over new nodes). */
    private fun closure(start: Set<RdfTerm>, child: ShaclPath, forward: Boolean, graph: DataGraphIndex): LinkedHashSet<RdfTerm> {
        val result = LinkedHashSet<RdfTerm>()
        var level = LinkedHashSet<RdfTerm>()
        for (t in start) if (add(result, t, graph)) level.add(t)
        while (level.isNotEmpty()) {
            val next = LinkedHashSet<RdfTerm>()
            for (t in step(level, child, forward, graph)) {
                if (add(result, t, graph)) next.add(t)
            }
            level = next
        }
        return result
    }
}
