package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.sparql.internal.SparqlLexical
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * The single place where the endpoint adapter turns RDF terms into SPARQL text. The lexical rules
 * (IRI validation, string escaping, language tags, variable names) are shared with the SPARQL
 * renderer through [SparqlLexical], so both modules escape identically. Does not rely on the core
 * terms' `toString()`.
 */
internal object SparqlTermFormat {

    fun iriRef(value: String): String = SparqlLexical.iriRef(value)

    fun escapeString(lexical: String): String = SparqlLexical.escapeString(lexical)

    /**
     * Directional literals (`"x"@ar--rtl`) are SPARQL 1.2 syntax. This adapter speaks SPARQL 1.1
     * Protocol to servers of unknown version, so it rejects them rather than sending text a 1.1
     * server cannot parse.
     */
    fun literal(obj: Literal): String = when (obj) {
        is LangString -> {
            require(obj.direction == null) { "This HTTP adapter does not support directional literals (SPARQL 1.2 syntax)" }
            SparqlLexical.langLiteral(obj.lexical, obj.lang)
        }
        is TypedLiteral ->
            if (obj.datatype == XSD.string) SparqlLexical.quoted(obj.lexical)
            else SparqlLexical.typedLiteral(obj.lexical, obj.datatype.value)
        is TrueLiteral -> "\"true\"^^${iriRef(XSD.boolean.value)}"
        is FalseLiteral -> "\"false\"^^${iriRef(XSD.boolean.value)}"
    }

    /**
     * Render a constant term. [blankNode] decides how blank nodes are written (or rejects them),
     * because their labels are only meaningful within a single request.
     */
    fun term(term: RdfTerm, blankNode: (BlankNode) -> String): String = when (term) {
        is Iri -> iriRef(term.value)
        is Literal -> literal(term)
        is BlankNode -> blankNode(term)
        is TripleTerm -> throw UnsupportedOperationException("Configure a provider with RDF 1.2 support for triple terms")
        is Var -> throw IllegalArgumentException("Variables cannot be used as data constants")
    }

    fun langTag(lang: String): String = SparqlLexical.langTag(lang)

    fun varName(name: String): String = SparqlLexical.varName(name)
}
