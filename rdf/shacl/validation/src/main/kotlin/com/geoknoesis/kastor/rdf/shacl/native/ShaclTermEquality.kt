package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.FalseLiteral
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.util.LinkedHashMap

private const val FP_SEP = '\u0001'

/** Canonical key aligned with [shaclRdfTermEquals]; injective across disjoint RDF shapes (`I/B/T/LS/L`). */
internal fun shaclRdfTermFingerprint(term: RdfTerm): String {
    fun esc(s: String): String =
        buildString(s.length + 4) {
            for (ch in s) {
                when (ch) {
                    FP_SEP -> append(FP_SEP).append('e')
                    else -> append(ch)
                }
            }
        }
    return when (term) {
        is Iri -> "I$FP_SEP${esc(term.value)}"
        is BlankNode -> "B$FP_SEP${esc(term.id)}"
        is TripleTerm -> {
            val s = term.triple.subject as RdfTerm
            val p = term.triple.predicate
            val o = term.triple.obj
            "T$FP_SEP${shaclRdfTermFingerprint(s)}$FP_SEP${shaclRdfTermFingerprint(p)}$FP_SEP${shaclRdfTermFingerprint(o)}"
        }
        is LangString ->
            "LS$FP_SEP${esc(term.lexical)}$FP_SEP${esc(term.lang)}$FP_SEP${term.direction?.token.orEmpty()}"
        is Literal ->
            "L$FP_SEP${esc(term.lexical)}$FP_SEP${esc(term.datatype.value)}"
        else -> error("Unsupported RDF term for SHACL fingerprint: ${term::class.simpleName}")
    }
}

/**
 * RDF term equality aligned with SHACL / RDF 1.2 usage (language literals compare lang;
 * triple terms recurse).
 */
fun shaclRdfTermEquals(a: RdfTerm, b: RdfTerm): Boolean = when {
    a is Iri && b is Iri -> a.value == b.value
    a is BlankNode && b is BlankNode -> a.id == b.id
    a is TripleTerm && b is TripleTerm ->
        shaclRdfTermEquals(a.triple.subject as RdfTerm, b.triple.subject as RdfTerm) &&
            shaclRdfTermEquals(a.triple.predicate as RdfTerm, b.triple.predicate as RdfTerm) &&
            shaclRdfTermEquals(a.triple.obj, b.triple.obj)
    a is LangString && b is LangString ->
        a.lexical == b.lexical && a.lang == b.lang && a.direction == b.direction
    a is Literal && b is Literal ->
        a.lexical == b.lexical && a.datatype == b.datatype
    else -> false
}

/** Distinct terms under [shaclRdfTermEquals] (first occurrence wins). Used for cardinality / node-kind dedup. */
internal fun distinctShaclTerms(values: List<RdfTerm>): List<RdfTerm> {
    val seen = LinkedHashMap<String, RdfTerm>()
    for (v in values) {
        seen.putIfAbsent(shaclRdfTermFingerprint(v), v)
    }
    return seen.values.toList()
}

fun literalLexicalString(term: RdfTerm): String? =
    when (term) {
        is Literal -> term.lexical
        is Iri -> term.value
        else -> null
    }

fun isLexicallyTrue(term: RdfTerm): Boolean =
    term == TrueLiteral ||
        (term is TypedLiteral && term.datatype == XSD.boolean && term.lexical == "true")

fun isLexicallyFalse(term: RdfTerm): Boolean =
    term == FalseLiteral ||
        (term is TypedLiteral && term.datatype == XSD.boolean && term.lexical == "false")
