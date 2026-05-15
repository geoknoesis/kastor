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
