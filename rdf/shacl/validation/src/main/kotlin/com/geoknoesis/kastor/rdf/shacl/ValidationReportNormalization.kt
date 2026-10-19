package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.TypedLiteral

/**
 * Deterministic row keys for parity / diff jobs (see validation architecture §13.1 parity normalization).
 * Omits free-text [ValidationViolation.message] from the sort key.
 */
fun ValidationViolation.paritySortKey(): String {
    val focusKey = termParityKey(focusNode)
    val componentKey =
        constraint.constraintType.toSourceConstraintComponentIri()?.value
            ?: constraint.constraintType.name
    val pathKey = path?.joinToString("") { termParityKey(it) }.orEmpty()
    val valueKey = value?.let { termParityKey(it) }.orEmpty()
    // Every field is length-prefixed, so no field content can imitate a separator and the key is injective.
    return listOf(focusKey, componentKey, pathKey, valueKey, severity.name, shapeParityKey(shapeUri))
        .joinToString("") { lengthPrefixed(it) }
}

/**
 * The source shape of a result. A blank node shape (an anonymous property shape, typically) has no engine-independent
 * name: each engine and each parse labels it differently (`_:...`), so only the fact that it is blank is kept. Its
 * results are still told apart by their focus node, path, component, value and severity; two blank shapes that agree
 * on all of these give rows with the same key, which a parity comparison counts as a multiset.
 */
private fun shapeParityKey(shapeUri: String?): String =
    when {
        shapeUri == null -> "N"
        shapeUri.startsWith("_:") -> "B"
        else -> "I" + shapeUri
    }

fun ValidationReport.sortedParityViolationKeys(): List<String> =
    violations.map { it.paritySortKey() }.sorted()

private fun lengthPrefixed(s: String): String = "${s.length}:$s"

/** Language tags compare case-insensitively (BCP 47), so their casing must not split otherwise equal rows. */
private fun termParityKey(t: RdfTerm): String =
    when (t) {
        is Iri -> "I" + lengthPrefixed(t.value)
        is BlankNode -> "B" + lengthPrefixed(t.id)
        is LangString ->
            "LANG" + lengthPrefixed(t.lexical) + lengthPrefixed(t.normalizedLang) + lengthPrefixed(t.direction?.token.orEmpty())
        is TypedLiteral -> "T" + lengthPrefixed(t.lexical) + lengthPrefixed(t.datatype.value)
        is Literal -> "L" + lengthPrefixed(t.lexical) + lengthPrefixed(t.datatype.value)
        is TripleTerm ->
            "TT" + termParityKey(t.triple.subject) + termParityKey(t.triple.predicate) + termParityKey(t.triple.obj)
        else -> "X" + lengthPrefixed(t.toString())
    }
