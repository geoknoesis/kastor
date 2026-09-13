package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * Grammar-checked lexical helpers shared by [SparqlRenderer] and
 * [SparqlServiceDescriptionGenerator].
 *
 * Every piece of caller-supplied text that ends up inside a SPARQL (or Turtle)
 * document goes through one of these functions, so a value can never terminate
 * the token it is embedded in and inject trailing syntax. Nothing here relies on
 * `toString()` of the core term types.
 */
internal object SparqlSyntax {

    /** Render an IRI as an `IRIREF` (`<...>`), rejecting characters the grammar forbids. */
    fun iriRef(value: String): String {
        require(value.isNotEmpty()) { "IRI must not be empty" }
        for (c in value) {
            require(c.code > 0x20 && c !in ILLEGAL_IRI_CHARS) {
                "IRI contains a character illegal in a SPARQL IRIREF: '$value'"
            }
        }
        requireWellFormedUtf16(value, "IRI")
        return "<$value>"
    }

    /**
     * Escape a lexical form for a `STRING_LITERAL2` (`"..."`). Uses the `ECHAR`
     * escapes for `\t \b \n \r \f " \`, and `\u` escapes for the remaining C0
     * controls. Unpaired UTF-16 surrogates cannot be encoded and are rejected.
     */
    fun escapeString(s: String): String {
        requireWellFormedUtf16(s, "String literal")
        return buildString(s.length + 2) {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '' -> append("\\f")
                else -> if (c.code < 0x20 || c.code == 0x7F) append("\\u%04X".format(c.code)) else append(c)
            }
        }
    }

    /** `"lexical"` with escaping. */
    fun quoted(s: String): String = "\"${escapeString(s)}\""

    /** Render any literal (typed, boolean, language-tagged, directional) with escaping. */
    fun literal(term: Literal): String = when (term) {
        is LangString -> {
            val base = "${quoted(term.lexical)}@${langTag(term.lang)}"
            term.direction?.let { "$base--${it.token}" } ?: base
        }
        is TrueLiteral -> "\"true\"^^${iriRef(XSD.boolean.value)}"
        is FalseLiteral -> "\"false\"^^${iriRef(XSD.boolean.value)}"
        is TypedLiteral -> "${quoted(lexicalFor(term))}^^${iriRef(term.datatype.value)}"
    }

    /**
     * XSD spells the special floating-point values `INF`, `-INF` and `NaN`; the JVM
     * (and therefore `Literal(Double)`) spells them `Infinity`/`-Infinity`.
     */
    private fun lexicalFor(term: TypedLiteral): String {
        if (term.datatype != XSD.double && term.datatype != XSD.float) return term.lexical
        return when (term.lexical) {
            "Infinity", "+Infinity" -> "INF"
            "-Infinity" -> "-INF"
            else -> term.lexical
        }
    }

    fun langTag(lang: String): String {
        require(LANG_TAG.matches(lang)) { "Invalid language tag for SPARQL: '$lang'" }
        return lang
    }

    fun blankNode(id: String): String {
        val label = id.removePrefix("_:")
        require(BNODE_LABEL.matches(label)) { "Invalid blank node label for SPARQL: '$id'" }
        return "_:$label"
    }

    /** Validate a variable name against the SPARQL `VARNAME` production. */
    fun varName(name: String): String {
        require(VARNAME.matches(name)) { "Invalid SPARQL variable name: '$name'" }
        return name
    }

    fun variable(v: Var): String = "?${varName(v.name)}"

    /** Validate a prefix label against `PN_PREFIX`. */
    fun prefixLabel(prefix: String): String {
        require(PN_PREFIX.matches(prefix)) { "Invalid SPARQL prefix label: '$prefix'" }
        return prefix
    }

    /** Render `PREFIX label: <namespace>` with both parts validated. */
    fun prefixDecl(decl: PrefixDeclaration): String =
        "PREFIX ${prefixLabel(decl.prefix)}: ${iriRef(decl.namespace)}"

    /** SPARQL 1.2 `VERSION "X.Y"` — the specifier is a string literal. */
    fun versionDecl(version: String): String {
        require(VERSION.matches(version)) { "Version must be in format X.Y: '$version'" }
        return "VERSION \"$version\""
    }

    /**
     * Render a function name. Accepted forms are a built-in keyword
     * (`STRLEN`, `isIRI`), a prefixed name (`ex:fn`, requires a matching PREFIX),
     * an absolute IRI (`http://example.org/fn`, `urn:fn`) or a bracketed IRI
     * (`<http://example.org/fn>`). Anything else is rejected.
     */
    fun functionName(name: String): String = when {
        BUILTIN_NAME.matches(name) -> name
        name.startsWith("<") && name.endsWith(">") -> iriRef(name.substring(1, name.length - 1))
        ABSOLUTE_IRI.containsMatchIn(name) -> iriRef(name)
        PREFIXED_NAME.matches(name) -> name
        else -> throw IllegalArgumentException("Invalid SPARQL function name: '$name'")
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

    private const val PN_CHARS_BASE =
        "A-Za-z\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u02FF\\u0370-\\u037D\\u037F-\\u1FFF" +
            "\\u200C-\\u200D\\u2070-\\u218F\\u2C00-\\u2FEF\\u3001-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFFD" +
            "\\x{10000}-\\x{EFFFF}"
    private const val PN_CHARS_U = "${PN_CHARS_BASE}_"
    private const val PN_CHARS = "$PN_CHARS_U\\-0-9\\u00B7\\u0300-\\u036F\\u203F-\\u2040"

    private val VARNAME = Regex("[${PN_CHARS_U}0-9][${PN_CHARS_U}0-9\\u00B7\\u0300-\\u036F\\u203F-\\u2040]*")
    private val PN_PREFIX = Regex("[$PN_CHARS_BASE](?:[$PN_CHARS.]*[$PN_CHARS])?")
    private val BUILTIN_NAME = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val PREFIXED_NAME = Regex("(?:[$PN_CHARS_BASE](?:[$PN_CHARS.]*[$PN_CHARS])?)?:[${PN_CHARS_U}0-9](?:[$PN_CHARS.]*[$PN_CHARS])?")
    private val ABSOLUTE_IRI = Regex("^(?:[A-Za-z][A-Za-z0-9+.-]*://|urn:)")
    private val LANG_TAG = Regex("[A-Za-z]+(?:-[A-Za-z0-9]+)*")
    private val BNODE_LABEL = Regex("[A-Za-z0-9_](?:[A-Za-z0-9_.-]*[A-Za-z0-9_-])?")
    private val VERSION = Regex("\\d+\\.\\d+")
}
