package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.shacl.native.ShaclPath
import com.geoknoesis.kastor.rdf.shacl.native.literalLexicallyValid
import com.geoknoesis.kastor.rdf.vocab.SHACL

// Stateless term helpers of [NativeShaclValidator]: they read no validation state.

internal fun displayTerm(term: RdfTerm): String =
    when (term) {
        is Literal -> term.lexical
        is Iri -> term.value
        is BlankNode -> term.toString()
        else -> term.toString()
    }

internal fun RdfResource.displayId(): String = when (this) {
    is Iri -> value
    is BlankNode -> toString()
}

internal fun pathToTerms(path: ShaclPath): List<RdfTerm>? =
    when (path) {
        is ShaclPath.Predicate -> listOf(path.iri)
        is ShaclPath.Sequence -> path.segments.map { seg -> (seg as? ShaclPath.Predicate)?.iri ?: return null }
        else -> null
    }

internal fun literalMatchesShaclDatatypes(term: RdfTerm, allowed: List<Iri>): Boolean =
    when (term) {
        is LangString -> term.datatype in allowed
        is Literal -> term.datatype in allowed && literalLexicallyValid(term)
        else -> false
    }

/** BCP47-style prefix match (`en` ⊇ `en-NZ`), case-insensitive. Supports trailing `-*` wildcard ranges. */
internal fun languageTagMatchesLanguageRange(valueLang: String, range: String): Boolean {
    if (range == "*") return true
    val v = valueLang.lowercase()
    val r = range.lowercase()
    if (v == r) return true
    if (r.endsWith("-*")) {
        val prefix = r.dropLast(2)
        return prefix.isEmpty() || v == prefix || v.startsWith("$prefix-")
    }
    return v.startsWith("$r-")
}

internal fun matchesNodeKind(term: RdfTerm, kind: Iri): Boolean =
    when (kind) {
        SHACL.IRI -> term is Iri
        SHACL.BlankNode -> term is BlankNode
        SHACL.Literal -> term is Literal
        SHACL.BlankNodeOrIRI -> term is Iri || term is BlankNode
        SHACL.BlankNodeOrLiteral -> term is BlankNode || term is Literal
        SHACL.IRIOrLiteral -> term is Iri || term is Literal
        SHACL.TripleTerm -> term is TripleTerm
        else -> false
    }
