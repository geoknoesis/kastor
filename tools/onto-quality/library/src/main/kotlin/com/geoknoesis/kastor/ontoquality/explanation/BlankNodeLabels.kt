package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.rdf.BlankNode

private const val LABEL_CHARS = "A-Za-z0-9_"

/**
 * Replaces the parser labels of the blank nodes in [keys] by their keys, in one pass over [text].
 *
 * SHACL engines interpolate blank nodes into messages (`{$this}`) as `_:label` or as the bare label; both forms are
 * replaced. A bare label is replaced only as a whole word, so a short label such as `b1` is not replaced inside
 * `b12` or `web1`.
 */
internal fun stabiliseBlankNodeLabels(text: String, keys: Map<BlankNode, String>): String {
    if (keys.isEmpty() || text.isEmpty()) return text
    val forms = HashMap<String, String>()
    for ((node, key) in keys) {
        if (!text.contains(node.id) && !text.contains(node.toString())) continue
        forms[node.toString()] = key
        forms["_:${node.id}"] = key
        forms[node.id] = key
    }
    if (forms.isEmpty()) return text
    // Longest form first, so `_:b1` wins over `b1` and `b12` over `b1`.
    val alternatives = forms.keys.sortedWith(compareByDescending<String> { it.length }.thenBy { it }).joinToString("|") { Regex.escape(it) }
    val pattern = Regex("(?<![$LABEL_CHARS:.-])(?:$alternatives)(?![$LABEL_CHARS-])")
    return pattern.replace(text) { match -> Regex.escapeReplacement(forms.getValue(match.value)) }
}
