package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * The single place where the endpoint adapter turns RDF terms into SPARQL text. Every value is
 * escaped or validated so it cannot terminate its token and inject syntax. Does not rely on the
 * core terms' `toString()`.
 */
internal object SparqlTermFormat {

    /** Render an IRI as an `IRIREF`, rejecting characters that would break out of `<...>`. */
    fun iriRef(value: String): String {
        require(value.isNotEmpty() && value.none { it.code <= 0x20 || it in ILLEGAL_IRI_CHARS }) {
            "IRI contains characters illegal in a SPARQL IRIREF: '$value'"
        }
        requireWellFormedUtf16(value, "IRI")
        return "<$value>"
    }

    /** Escape a lexical form for `"..."` using SPARQL `ECHAR` escapes (and `\u` for other controls). */
    fun escapeString(lexical: String): String {
        requireWellFormedUtf16(lexical, "Literal")
        return buildString(lexical.length + 2) {
            for (c in lexical) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c.code < 0x20 || c.code == 0x7F) append("\\u%04X".format(c.code)) else append(c)
            }
        }
    }

    fun literal(obj: Literal): String = when (obj) {
        is LangString -> {
            require(obj.direction == null) { "This HTTP adapter does not support directional literals" }
            "\"${escapeString(obj.lexical)}\"@${langTag(obj.lang)}"
        }
        is TypedLiteral ->
            if (obj.datatype == XSD.string) "\"${escapeString(obj.lexical)}\""
            else "\"${escapeString(lexicalFor(obj))}\"^^${iriRef(obj.datatype.value)}"
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

    fun langTag(lang: String): String {
        require(LANG_TAG.matches(lang)) { "Invalid language tag for SPARQL: '$lang'" }
        return lang
    }

    fun varName(name: String): String {
        require(VAR_NAME.matches(name)) { "Invalid SPARQL variable name: '$name'" }
        return name
    }

    /** XSD spells the special floating-point values `INF`/`-INF`/`NaN`; the JVM spells them `Infinity`. */
    private fun lexicalFor(obj: TypedLiteral): String {
        if (obj.datatype != XSD.double && obj.datatype != XSD.float) return obj.lexical
        return when (obj.lexical) {
            "Infinity", "+Infinity" -> "INF"
            "-Infinity" -> "-INF"
            else -> obj.lexical
        }
    }

    private fun requireWellFormedUtf16(s: String, what: String) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                require(i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) { "$what contains an unpaired surrogate" }
                i += 2
                continue
            }
            require(!Character.isLowSurrogate(c)) { "$what contains an unpaired surrogate" }
            i++
        }
    }

    private val ILLEGAL_IRI_CHARS = setOf('<', '>', '"', '{', '}', '|', '^', '`', '\\')
    private val LANG_TAG = Regex("[A-Za-z]+(?:-[A-Za-z0-9]+)*")
    private val VAR_NAME = Regex("[\\p{L}0-9_][\\p{L}0-9_\\u00B7\\u0300-\\u036F\\u203F-\\u2040]*")
}
