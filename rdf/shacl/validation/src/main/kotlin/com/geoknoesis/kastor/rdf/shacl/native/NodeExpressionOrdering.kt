package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TripleTerm

/**
 * The order of SPARQL `ORDER BY` (SPARQL 1.2 Query §15.1), used by `shnex:orderBy`, `shnex:min` and `shnex:max`
 * (Node Expressions §4.2.8, §4.4.2, §4.4.3): unbound (null) first, then blank nodes, IRIs, literals and triple terms.
 * Literals that SPARQL `<` compares ([tryCompareLiterals]) are ordered by value; any other pair of literals, and
 * literals of equal value, by (datatype IRI, lexical form, lower-case language tag), so the order is deterministic.
 *
 * Value comparison is not transitive across value spaces (SPARQL leaves such orders implementation-defined), so the
 * order is applied with [stableSort], which never depends on the comparator being a total order.
 */
internal object NodeExpressionOrdering {
    fun compare(a: RdfTerm?, b: RdfTerm?): Int {
        val byKind = rank(a).compareTo(rank(b))
        if (byKind != 0) return byKind
        return when (a) {
            null -> 0
            is BlankNode -> a.id.compareTo((b as BlankNode).id)
            is Iri -> a.value.compareTo((b as Iri).value)
            is Literal -> {
                val other = b as Literal
                tryCompareLiterals(a, other)?.takeIf { it != 0 } ?: lexicalCompare(a, other)
            }
            // Triple terms, and query variables (never output nodes), by their rendering.
            else -> a.toString().compareTo(b.toString())
        }
    }

    private fun rank(term: RdfTerm?): Int =
        when (term) {
            null -> 0
            is BlankNode -> 1
            is Iri -> 2
            is Literal -> 3
            is TripleTerm -> 4
            else -> 5
        }

    private fun lexicalCompare(a: Literal, b: Literal): Int {
        a.datatype.value.compareTo(b.datatype.value).takeIf { it != 0 }?.let { return it }
        a.lexical.compareTo(b.lexical).takeIf { it != 0 }?.let { return it }
        return lang(a).compareTo(lang(b))
    }

    private fun lang(l: Literal): String = if (l is LangString) l.normalizedLang + (l.direction?.token ?: "") else ""

    /**
     * Stable merge sort of [items] under [compare]: equal elements keep their order, and an inconsistent comparator
     * yields some order instead of an error (unlike `java.util.Collections.sort`).
     */
    fun <T> stableSort(items: List<T>, compare: (T, T) -> Int): List<T> {
        if (items.size < 2) return items
        val mid = items.size / 2
        val left = stableSort(items.subList(0, mid), compare)
        val right = stableSort(items.subList(mid, items.size), compare)
        val out = ArrayList<T>(items.size)
        var i = 0
        var j = 0
        while (i < left.size && j < right.size) {
            if (compare(right[j], left[i]) < 0) out.add(right[j++]) else out.add(left[i++])
        }
        while (i < left.size) out.add(left[i++])
        while (j < right.size) out.add(right[j++])
        return out
    }
}
