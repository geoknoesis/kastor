package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.vocab.RDF
import java.security.MessageDigest

/**
 * Parse-independent keys for blank nodes, used by [FindingRef] so findings on blank nodes (e.g. `owl:Restriction`)
 * keep their ref when the same file is parsed again (parser labels change on every parse).
 *
 * A key describes a node by its content **and its context**. Every blank node starts from a hash of its own triples
 * with IRIs and literals (outgoing, and incoming from IRI subjects); the hashes are then refined [ROUNDS] times, each
 * round mixing in the hashes of the blank nodes it is linked to, in both directions. After the last round a key covers
 * everything within [ROUNDS] links of the node, so the same restriction used under two different classes gets two
 * keys.
 *
 * - **RDF lists** are flat: the head of a list is linked directly to every member and every cell with its position,
 *   so a list counts as one link whatever its length (up to [MAX_LIST_LINKS] links in a graph; beyond that lists are
 *   followed cell by cell).
 * - **Order-independent**: the triples of a node are sorted before they are hashed and nothing is truncated, so a key
 *   does not depend on the order in which the graph returns its triples. Work is linear in the number of triples
 *   with a blank node, per round.
 * - **Cycle-safe**: the number of rounds is fixed.
 * - **Triple terms** containing blank nodes are described with a placeholder per blank node and linked to those
 *   nodes; parser labels are never hashed. Literals and IRIs are length-prefixed, so no literal text can imitate the
 *   description of other triples.
 * - **Unique within a graph**: blank nodes that still share a hash (true duplicates, such as the same restriction
 *   written twice under one class, or context further than [ROUNDS] links away) are numbered `-2`, `-3`, … in the
 *   order of a longer refinement (up to [TIE_BREAK_ROUNDS] more rounds). The set of keys is the same on every parse;
 *   which of two indistinguishable duplicates gets which number is arbitrary.
 */
internal object BlankNodeKeys {
    const val ROUNDS: Int = 8
    const val TIE_BREAK_ROUNDS: Int = 24
    const val MAX_LIST_LINKS: Int = 2_000_000
    private const val MAX_TRIPLE_TERM_DEPTH: Int = 64
    private const val KEY_HEX_LENGTH: Int = 32

    /** A link to another blank node; [label] carries the direction, the predicate and, for lists, the position. */
    private class Link(val label: String, val target: BlankNode)

    private class Description {
        /** Triples with IRIs and literals only, already encoded. */
        val local = ArrayList<String>()
        val links = ArrayList<Link>()
    }

    /**
     * Keys for the [nodes] that occur in [graph] (others are left out); the graph is scanned once.
     *
     * @param prefix start of every key; callers keying nodes of two graphs use two prefixes.
     */
    fun compute(graph: RdfGraph, nodes: Set<BlankNode>, prefix: String = "_:k"): Map<BlankNode, String> {
        if (nodes.isEmpty()) return emptyMap()
        val descriptions = describe(graph)
        val requested = nodes.filter { it in descriptions }
        if (requested.isEmpty()) return emptyMap()

        val digest = MessageDigest.getInstance("SHA-256")
        var colours = HashMap<BlankNode, String>(descriptions.size * 2)
        for ((node, description) in descriptions) {
            description.local.sort()
            colours[node] = hash(digest, "0", description.local)
        }
        repeat(ROUNDS) { colours = refine(digest, descriptions, colours) }

        val base = colours
        // Nodes sharing a hash are numbered over the whole graph, so a number does not depend on which nodes were asked for.
        val classes = HashMap<String, MutableList<BlankNode>>()
        for ((node, colour) in base) classes.getOrPut(colour) { ArrayList(1) }.add(node)
        val ambiguous = requested.any { classes.getValue(base.getValue(it)).size > 1 }
        var extended = base
        if (ambiguous) {
            var distinct = classes.size
            for (round in 0 until TIE_BREAK_ROUNDS) {
                extended = refine(digest, descriptions, extended)
                val refined = extended.values.toHashSet().size
                // A round that splits no class means no later round will.
                if (refined == distinct) break
                distinct = refined
            }
        }
        val tieBreak = extended
        val ordinals = HashMap<String, Map<BlankNode, Int>>()
        val result = HashMap<BlankNode, String>(requested.size * 2)
        for (node in requested) {
            val colour = base.getValue(node)
            val members = classes.getValue(colour)
            val key = prefix + colour.take(KEY_HEX_LENGTH)
            if (members.size == 1) {
                result[node] = key
            } else {
                val ordinal =
                    ordinals.getOrPut(colour) {
                        members.sortedBy { tieBreak.getValue(it) }.withIndex().associate { (index, member) -> member to index + 1 }
                    }.getValue(node)
                result[node] = if (ordinal == 1) key else "$key-$ordinal"
            }
        }
        return result
    }

    private fun refine(
        digest: MessageDigest,
        descriptions: Map<BlankNode, Description>,
        colours: Map<BlankNode, String>,
    ): HashMap<BlankNode, String> {
        val next = HashMap<BlankNode, String>(colours.size * 2)
        for ((node, description) in descriptions) {
            val parts = ArrayList<String>(description.links.size)
            for (link in description.links) parts += encode(link.label, colours.getValue(link.target))
            parts.sort()
            next[node] = hash(digest, colours.getValue(node), parts)
        }
        return next
    }

    private fun describe(graph: RdfGraph): Map<BlankNode, Description> {
        val descriptions = HashMap<BlankNode, Description>()
        fun description(node: BlankNode): Description = descriptions.getOrPut(node) { Description() }
        val first = HashMap<BlankNode, MutableList<RdfTerm>>()
        val rest = HashMap<BlankNode, MutableList<RdfTerm>>()
        val continued = HashSet<BlankNode>()

        for (t in graph.getTriplesSequence()) {
            val s = t.subject
            val o = t.obj
            val predicate = t.predicate.value
            if (s is BlankNode) {
                if (t.predicate == RDF.first) first.getOrPut(s) { ArrayList(1) }.add(o)
                if (t.predicate == RDF.rest) {
                    rest.getOrPut(s) { ArrayList(1) }.add(o)
                    if (o is BlankNode) continued.add(o)
                }
            }
            when (o) {
                is BlankNode ->
                    if (s is BlankNode) {
                        description(s).links += Link(encode(">", predicate), o)
                        description(o).links += Link(encode("<", predicate), s)
                    } else {
                        description(o).local += encode("<", predicate, termText(s, null, 0))
                    }
                is TripleTerm -> {
                    val inner = ArrayList<BlankNode>()
                    val template = termText(o, inner, 0)
                    if (s is BlankNode) description(s).local += encode(">", predicate, template)
                    inner.forEachIndexed { position, node ->
                        if (s is BlankNode) {
                            description(s).links += Link(encode(">t", predicate, template, position.toString()), node)
                            description(node).links += Link(encode("<t", predicate, template, position.toString()), s)
                        } else {
                            description(node).local += encode("<t", predicate, template, position.toString(), termText(s, null, 0))
                        }
                    }
                }
                else -> if (s is BlankNode) description(s).local += encode(">", predicate, termText(o, null, 0))
            }
        }
        linkLists(descriptions, first, rest, continued)
        return descriptions
    }

    /**
     * Links every list head (a cell that is not the `rdf:rest` of another cell) to its members and cells, with their
     * position. Nothing is added when the lists of the graph hold more than [MAX_LIST_LINKS] cells in total; that
     * total does not depend on the order of the triples.
     */
    private fun linkLists(
        descriptions: HashMap<BlankNode, Description>,
        first: Map<BlankNode, List<RdfTerm>>,
        rest: Map<BlankNode, List<RdfTerm>>,
        continued: Set<BlankNode>,
    ) {
        val heads = (first.keys + rest.keys).filter { it !in continued }
        if (heads.isEmpty()) return

        /** Cells of the list starting at [head], in order; stops at a cycle, a branch or a missing `rdf:rest`. */
        fun cells(head: BlankNode, limit: Int): List<BlankNode>? {
            val cells = ArrayList<BlankNode>()
            val seen = HashSet<BlankNode>()
            var cell: BlankNode? = head
            while (cell != null && seen.add(cell)) {
                if (cells.size >= limit) return null
                cells += cell
                cell = rest[cell]?.singleOrNull() as? BlankNode
            }
            return cells
        }

        var budget = MAX_LIST_LINKS
        val lists = ArrayList<List<BlankNode>>(heads.size)
        for (head in heads) {
            val cells = cells(head, budget) ?: return
            budget -= cells.size
            lists += cells
        }
        for (cells in lists) {
            val head = descriptions.getOrPut(cells[0]) { Description() }
            cells.forEachIndexed { position, cell ->
                val index = position.toString()
                if (position > 0) {
                    head.links += Link(encode("cell", index), cell)
                    descriptions.getOrPut(cell) { Description() }.links += Link(encode("cell-of", index), cells[0])
                }
                for (member in first[cell].orEmpty()) {
                    if (member is BlankNode) {
                        head.links += Link(encode("item", index), member)
                        descriptions.getOrPut(member) { Description() }.links += Link(encode("item-of", index), cells[0])
                    } else {
                        head.local += encode("item", index, termText(member, null, 0))
                    }
                }
            }
            head.local += encode("length", cells.size.toString())
        }
    }

    /**
     * Canonical text of a term. Blank nodes (only reachable inside triple terms) are written as `_:` and collected
     * in [blankNodes], in order of appearance.
     */
    private fun termText(term: RdfTerm, blankNodes: MutableList<BlankNode>?, depth: Int): String =
        when (term) {
            is Iri -> encode("I", term.value)
            is BlankNode -> {
                blankNodes?.add(term)
                "_:"
            }
            is LangString -> encode("L", term.lexical, term.normalizedLang, term.direction?.name ?: "")
            is Literal -> encode("D", term.lexical, term.datatype.value)
            is TripleTerm ->
                if (depth >= MAX_TRIPLE_TERM_DEPTH) {
                    "T…"
                } else {
                    val triple: RdfTriple = term.triple
                    encode(
                        "T",
                        termText(triple.subject, blankNodes, depth + 1),
                        triple.predicate.value,
                        termText(triple.obj, blankNodes, depth + 1),
                    )
                }
            // Query variables never occur in a data graph.
            else -> encode("?", term.toString())
        }

    /** Length-prefixed concatenation: unambiguous whatever the parts contain. */
    private fun encode(vararg parts: String): String {
        val sb = StringBuilder(parts.sumOf { it.length + 8 })
        for (part in parts) sb.append(part.length).append(':').append(part)
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun hash(digest: MessageDigest, seed: String, parts: List<String>): String {
        digest.reset()
        digest.update(seed.toByteArray(Charsets.UTF_8))
        for (part in parts) {
            digest.update(SEPARATOR)
            digest.update(part.toByteArray(Charsets.UTF_8))
        }
        val bytes = digest.digest()
        val out = CharArray(bytes.size * 2)
        for ((i, b) in bytes.withIndex()) {
            val v = b.toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    /** 0xFF never occurs in UTF-8 text, so it separates parts unambiguously. */
    private val SEPARATOR = byteArrayOf(0xFF.toByte())
}
