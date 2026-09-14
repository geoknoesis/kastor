package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.sparql.internal.SparqlLexical
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * Grammar-checked lexical helpers used by [SparqlRenderer] and
 * [SparqlServiceDescriptionGenerator].
 *
 * Every piece of caller-supplied text that ends up inside a SPARQL (or Turtle)
 * document goes through one of these functions, so a value can never terminate
 * the token it is embedded in and inject trailing syntax. The lexical rules live
 * in [SparqlLexical] (`:rdf:sparql-contract`), shared with the HTTP endpoint
 * adapter; this object only maps the core term types onto them.
 */
internal object SparqlSyntax {

    fun iriRef(value: String): String = SparqlLexical.iriRef(value)

    fun escapeString(s: String): String = SparqlLexical.escapeString(s)

    fun quoted(s: String): String = SparqlLexical.quoted(s)

    /** Render any literal (typed, boolean, language-tagged, directional) with escaping. */
    fun literal(term: Literal): String = when (term) {
        is LangString -> SparqlLexical.langLiteral(term.lexical, term.lang, term.direction?.token)
        is TrueLiteral -> "\"true\"^^${iriRef(XSD.boolean.value)}"
        is FalseLiteral -> "\"false\"^^${iriRef(XSD.boolean.value)}"
        is TypedLiteral -> SparqlLexical.typedLiteral(term.lexical, term.datatype.value)
    }

    fun langTag(lang: String): String = SparqlLexical.langTag(lang)

    fun blankNode(id: String): String = SparqlLexical.blankNode(id)

    fun varName(name: String): String = SparqlLexical.varName(name)

    fun variable(v: Var): String = "?${varName(v.name)}"

    fun prefixLabel(prefix: String): String = SparqlLexical.prefixLabel(prefix)

    /** Render `PREFIX label: <namespace>` with both parts validated. */
    fun prefixDecl(decl: PrefixDeclaration): String =
        "PREFIX ${prefixLabel(decl.prefix)}: ${iriRef(decl.namespace)}"

    fun versionDecl(version: String): String = SparqlLexical.versionDecl(version)

    fun functionName(name: String): String = SparqlLexical.functionName(name)
}
