package com.geoknoesis.kastor.rdf.sparql.internal

/**
 * **Not public API.** Grammar-checked lexical helpers shared by the Kastor SPARQL renderer
 * (`:rdf:sparql-lang`) and the SPARQL HTTP endpoint adapter (`:rdf:sparql`), so both escape
 * caller-supplied text identically. It is public only because Kotlin `internal` cannot be shared
 * across modules; it may change without notice.
 *
 * Every function either returns text that cannot terminate the token it is embedded in, or throws
 * [IllegalArgumentException]. The helpers operate on plain strings so this module stays free of
 * the core term types.
 */
object SparqlLexical {

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
     * Escape a lexical form for a `STRING_LITERAL2` (`"..."`).
     *
     * - `\t \b \n \r \f " \` use the `ECHAR` escapes; other C0 controls and DEL use `\u00XX`.
     * - A `u`/`U` that directly follows a backslash in the text is written as a SPARQL codepoint
     *   escape of that letter (backslash, `u`, `0075` or `0055`).
     *   SPARQL 1.1 (§19.2) lets servers decode `\uXXXX` sequences over the whole query text
     *   *before* tokenizing, and some implementations do so without looking at preceding
     *   backslashes. With plain doubling, the text backslash-u-0022 would be sent as two backslashes
     *   followed by u0022, and such a pre-pass would turn it into an escaped quote, silently changing
     *   the value. The encoded form decodes to
     *   the same text under every reading: no pre-pass (SPARQL 1.2 `UCHAR`), a Java-style pre-pass
     *   that honours escaped backslashes, and a naive pre-pass.
     *
     * Unpaired UTF-16 surrogates cannot be encoded and are rejected.
     */
    fun escapeString(s: String): String {
        requireWellFormedUtf16(s, "String literal")
        return buildString(s.length + 2) {
            var afterBackslash = false
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    'u' -> append(if (afterBackslash) "\\u0075" else "u")
                    'U' -> append(if (afterBackslash) "\\u0055" else "U")
                    else -> if (c.code < 0x20 || c.code == 0x7F) append("\\u%04X".format(c.code)) else append(c)
                }
                afterBackslash = c == '\\'
            }
        }
    }

    /** `"lexical"` with escaping. */
    fun quoted(s: String): String = "\"${escapeString(s)}\""

    /**
     * `"lexical"@lang` or, with a base [direction] (`ltr`/`rtl`), the RDF 1.2 / SPARQL 1.2 form
     * `"lexical"@lang--dir`. The tag is rendered as held (no case normalisation).
     */
    fun langLiteral(lexical: String, lang: String, direction: String? = null): String {
        val base = "${quoted(lexical)}@${langTag(lang)}"
        if (direction == null) return base
        require(direction == "ltr" || direction == "rtl") { "Base direction must be 'ltr' or 'rtl', got '$direction'" }
        return "$base--$direction"
    }

    /** `"lexical"^^<datatype>`, spelling JVM infinities with their XSD lexical forms for float/double. */
    fun typedLiteral(lexical: String, datatype: String): String =
        "${quoted(xsdLexical(lexical, datatype))}^^${iriRef(datatype)}"

    /** XSD spells the special floating-point values `INF`/`-INF`/`NaN`; the JVM spells them `Infinity`. */
    fun xsdLexical(lexical: String, datatype: String): String {
        if (datatype != XSD_DOUBLE && datatype != XSD_FLOAT) return lexical
        return when (lexical) {
            "Infinity", "+Infinity" -> "INF"
            "-Infinity" -> "-INF"
            else -> lexical
        }
    }

    fun langTag(lang: String): String {
        require(LANG_TAG.matches(lang)) { "Invalid language tag for SPARQL: '$lang'" }
        return lang
    }

    /** Render a blank node label (`_:label`); a leading `_:` in [id] is accepted. */
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

    /**
     * Validate a prefix label against `PN_PREFIX`. The label of a namespace (`PNAME_NS`) is optional,
     * so the empty string, the empty prefix of `PREFIX : <...>`, is a prefix label too.
     */
    fun prefixLabel(prefix: String): String {
        require(prefix.isEmpty() || PN_PREFIX.matches(prefix)) { "Invalid SPARQL prefix label: '$prefix'" }
        return prefix
    }

    /** SPARQL 1.2 `VERSION "X.Y"`; the specifier is a string literal. */
    fun versionDecl(version: String): String {
        require(VERSION.matches(version)) { "Version must be in format X.Y: '$version'" }
        return "VERSION \"$version\""
    }

    /**
     * Render a function name. Accepted forms are a built-in keyword (`STRLEN`, `isIRI`), a prefixed
     * name (`ex:fn`, requires a matching PREFIX), an absolute IRI (`http://example.org/fn`,
     * `urn:fn`) or a bracketed IRI (`<http://example.org/fn>`). Anything else is rejected.
     */
    fun functionName(name: String): String = when {
        BUILTIN_NAME.matches(name) -> name
        name.startsWith("<") && name.endsWith(">") -> iriRef(name.substring(1, name.length - 1))
        ABSOLUTE_IRI.containsMatchIn(name) -> iriRef(name)
        PREFIXED_NAME.matches(name) -> name
        else -> throw IllegalArgumentException("Invalid SPARQL function name: '$name'")
    }

    fun requireWellFormedUtf16(s: String, what: String) {
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

    private const val XSD_DOUBLE = "http://www.w3.org/2001/XMLSchema#double"
    private const val XSD_FLOAT = "http://www.w3.org/2001/XMLSchema#float"

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
