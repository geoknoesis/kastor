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
    val pathKey = path?.joinToString("|") { termParityKey(it) }.orEmpty()
    val valueKey = value?.let { termParityKey(it) }.orEmpty()
    return listOf(focusKey, componentKey, pathKey, valueKey, severity.name).joinToString("\u0001")
}

fun ValidationReport.sortedParityViolationKeys(): List<String> =
    violations.map { it.paritySortKey() }.sorted()

private fun termParityKey(t: RdfTerm): String =
    when (t) {
        is Iri -> "I|<${t.value}>"
        is BlankNode -> "B|${t.id}"
        is LangString -> "LANG|${t.lexical}|${t.lang}|${t.direction ?: ""}"
        is TypedLiteral -> "T|${t.lexical}|${t.datatype.value}"
        is Literal -> "L|${t.lexical}|${t.datatype.value}"
        is TripleTerm ->
            "TT|${termParityKey(t.triple.subject)}|${termParityKey(t.triple.predicate)}|${termParityKey(t.triple.obj)}"
        else -> "X|$t"
    }
