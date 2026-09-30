package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import java.security.MessageDigest

/**
 * Parse-independent keys for blank nodes, used by [FindingRef] so findings on blank nodes (e.g. `owl:Restriction`)
 * keep their ref when the same file is parsed again (parser labels change on every parse).
 *
 * The key is a SHA-256 over a canonical description of the node: its outgoing triples (predicate + object, blank-node
 * objects described recursively up to [MAX_DEPTH] levels, cycles cut) and its incoming triples from IRI subjects.
 * Structurally identical blank nodes get the same key. Work per node is bounded by [MAX_TRIPLES_PER_KEY].
 */
internal object BlankNodeKeys {
    const val MAX_DEPTH: Int = 6
    const val MAX_TRIPLES_PER_KEY: Int = 10_000

    /** Keys for [nodes] (other blank nodes are ignored); the graph is scanned once. */
    fun compute(graph: RdfGraph, nodes: Set<BlankNode>): Map<BlankNode, String> {
        if (nodes.isEmpty()) return emptyMap()
        val outgoing = HashMap<BlankNode, MutableList<RdfTriple>>()
        val incoming = HashMap<BlankNode, MutableList<RdfTriple>>()
        for (t in graph.getTriplesSequence()) {
            (t.subject as? BlankNode)?.let { outgoing.getOrPut(it) { mutableListOf() }.add(t) }
            val o = t.obj
            if (o is BlankNode && o in nodes && t.subject is Iri) incoming.getOrPut(o) { mutableListOf() }.add(t)
        }
        return nodes.associateWith { node ->
            val budget = intArrayOf(MAX_TRIPLES_PER_KEY)
            val text =
                buildString {
                    append(describe(node, outgoing, MAX_DEPTH, HashSet(), budget))
                    append("^[")
                    append(
                        incoming[node].orEmpty()
                            .map { "<${(it.subject as Iri).value}> <${it.predicate.value}>" }
                            .sorted()
                            .joinToString(","),
                    )
                    append(']')
                }
            "_:k" + sha256(text).take(32)
        }
    }

    private fun describe(
        node: BlankNode,
        outgoing: Map<BlankNode, List<RdfTriple>>,
        depth: Int,
        path: MutableSet<BlankNode>,
        budget: IntArray,
    ): String {
        if (depth == 0) return "[…]"
        if (!path.add(node)) return "[cycle]"
        try {
            val parts = ArrayList<String>()
            for (t in outgoing[node].orEmpty()) {
                if (budget[0]-- <= 0) {
                    parts += "…"
                    break
                }
                val o = t.obj
                val obj = if (o is BlankNode) describe(o, outgoing, depth - 1, path, budget) else termText(o)
                parts += "<${t.predicate.value}> $obj"
            }
            parts.sort()
            return parts.joinToString(";", "[", "]")
        } finally {
            path.remove(node)
        }
    }

    private fun termText(term: RdfTerm): String =
        when (term) {
            is Iri -> "<${term.value}>"
            is LangString -> "\"${term.lexical}\"@${term.lang}"
            is Literal -> "\"${term.lexical}\"^^<${term.datatype.value}>"
            is TripleTerm -> "<<${term.triple}>>"
            else -> term.toString()
        }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
