package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.rdf.BlankNode

/**
 * A blank-node reference in message text: `_:label`, as SHACL engines write a blank node they interpolate into a
 * message (`{$this}`, `{?var}`).
 *
 * The reference must not continue an IRI or a word (`http://example.org/x/_:b1`, `a_:b1` are left alone): it starts
 * the text or follows whitespace, a quote, a bracket, `,` or `;`. After `=` it is a reference too (`node=_:b1`, a
 * message such as `"node={$this}"`), unless the text before the `=` is part of an IRI or a prefixed name (it contains
 * one of `: / # ? @ % &`, as in `http://example.org/q?node=_:b1`): text inside an IRI is never rewritten. The
 * label follows the `BLANK_NODE_LABEL` grammar of Turtle (letters, digits, `_`, and `.`, `-` or a middle dot inside),
 * so the whole label is read: `_:b1` is never taken out of `_:b12`.
 */
private val BLANK_NODE_REFERENCE =
    Regex("(?<![\\p{L}\\p{N}\\p{M}_:/#.%?&@~+\\-])_:([\\p{L}\\p{N}_](?:[\\p{L}\\p{N}\\p{M}_.\\-\\u00B7]*[\\p{L}\\p{N}\\p{M}_\\-\\u00B7])?)")

/**
 * The blank nodes that [text] refers to as `_:label`. Whether such a node exists is for the caller to check: any
 * text may contain `_:x`.
 */
internal fun blankNodeReferences(text: String): Set<BlankNode> {
    if (!text.contains("_:")) return emptySet()
    return BLANK_NODE_REFERENCE.findAll(text).filter { isReference(text, it.range.first) }.mapTo(LinkedHashSet()) { BlankNode(it.groupValues[1]) }
}

private const val IRI_ONLY_CHARACTERS = ":/#?@%&"

/**
 * Whether the `_:label` at [start] is a blank-node reference. After `=` it is one only when the token the `=` ends
 * (back to the previous whitespace) cannot be part of an IRI or a prefixed name.
 */
private fun isReference(text: String, start: Int): Boolean {
    if (start == 0 || text[start - 1] != '=') return true
    var at = start - 2
    while (at >= 0 && !text[at].isWhitespace()) {
        if (text[at] in IRI_ONLY_CHARACTERS) return false
        at--
    }
    return true
}

/**
 * Replaces the references (`_:label`) to the blank nodes in [keys] by their keys, in one pass over [text].
 *
 * Only whole `_:label` references are replaced, the form in which a SHACL engine interpolates a blank node. A label
 * alone is never touched, so a short label such as `b1` or `a` is not replaced inside an IRI
 * (`http://example.org/onto#b1`) or where it is an ordinary word.
 */
internal fun stabiliseBlankNodeLabels(text: String, keys: Map<BlankNode, String>): String {
    if (keys.isEmpty() || !text.contains("_:")) return text
    // The label as the engine writes it (BlankNode.toString encodes ids that are no valid label) and the id itself.
    val byLabel = HashMap<String, String>(keys.size * 2)
    for ((node, key) in keys) {
        byLabel[node.id] = key
        byLabel[node.toString().removePrefix("_:")] = key
    }
    return BLANK_NODE_REFERENCE.replace(text) { match ->
        if (isReference(text, match.range.first)) byLabel[match.groupValues[1]] ?: match.value else match.value
    }
}
