package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.LiteralValidation
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.xsd.XsdLexical

private fun xsd(local: String) = Iri(XSD.namespace + local)

private val normalizedStringForbidden = Regex("[\\r\\n\\t]")

/**
 * XSD lexical validity for typed literals used by `sh:datatype`: an ill-formed literal fails even when the RDF
 * term carries the requested datatype IRI. Covers the commonly used XSD built-ins (string types, boolean,
 * decimal, integer and all derived integer types with range checks, float/double, date/time types, Gregorian
 * types, durations, language, hexBinary, base64Binary, the XML name and token types NCName / Name / NMTOKEN(S) /
 * QName / ID / IDREF(S) / ENTITY / ENTITIES, and xsd:anyURI). xsd:normalizedString, xsd:token and xsd:anyURI
 * additionally require every character to be a legal XML 1.0 character; plain xsd:string accepts any Unicode string
 * (W3C SHACL singleLine-001). Non-XSD datatypes are accepted.
 */
internal fun typedLiteralLexicallyValidForShaclDatatype(lit: TypedLiteral): Boolean = literalLexicallyValid(lit)

internal fun literalLexicallyValid(lit: Literal): Boolean {
    val lex = lit.lexical
    val dt = lit.datatype
    // Numbers (with the integer types' ranges), booleans, dates, times, Gregorian types and durations: the shared
    // linear-time parser of :rdf:core.
    XsdLexical.isValid(dt.value, lex)?.let { return it }
    return when {
        // RDF literals may hold any Unicode string; W3C SHACL singleLine-001 expects xsd:string values with control
        // characters such as U+000B to conform to sh:datatype xsd:string, so the XML Char restriction is not applied.
        dt == XSD.string -> true
        dt == XSD.language -> LiteralValidation.isWellFormedLanguageTag(lex)
        dt == XSD.hexBinary -> lex.length % 2 == 0 && lex.all { it.isHexDigitChar() }
        dt == xsd("base64Binary") -> isBase64Binary(lex)
        dt == xsd("normalizedString") -> allXmlChars(lex) && !normalizedStringForbidden.containsMatchIn(lex)
        dt == xsd("token") -> allXmlChars(lex) && !normalizedStringForbidden.containsMatchIn(lex) && !lex.startsWith(" ") && !lex.endsWith(" ") && !lex.contains("  ")
        dt == xsd("Name") -> isXmlName(lex, allowColon = true)
        dt == xsd("NCName") || dt == xsd("ID") || dt == xsd("IDREF") || dt == xsd("ENTITY") -> isXmlName(lex, allowColon = false)
        dt == xsd("NMTOKEN") -> isNmtoken(lex)
        dt == xsd("NMTOKENS") -> isXmlList(lex, ::isNmtoken)
        dt == xsd("IDREFS") || dt == xsd("ENTITIES") -> isXmlList(lex) { isXmlName(it, allowColon = false) }
        dt == xsd("QName") -> lex.split(':').let { parts -> parts.size in 1..2 && parts.all { isXmlName(it, allowColon = false) } }
        dt == XSD.anyURI -> allXmlChars(lex) && hasValidPercentEscapes(lex)
        else -> true
    }
}

// --- XML 1.0 (fifth edition) character classes ---------------------------------------------------------------------

private fun isXmlChar(cp: Int): Boolean =
    cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF

private fun allXmlChars(s: String): Boolean = s.codePoints().allMatch(::isXmlChar)

private fun isNameStartChar(cp: Int, allowColon: Boolean): Boolean =
    (allowColon && cp == ':'.code) || cp == '_'.code || cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code ||
        cp in 0xC0..0xD6 || cp in 0xD8..0xF6 || cp in 0xF8..0x2FF || cp in 0x370..0x37D || cp in 0x37F..0x1FFF ||
        cp in 0x200C..0x200D || cp in 0x2070..0x218F || cp in 0x2C00..0x2FEF || cp in 0x3001..0xD7FF ||
        cp in 0xF900..0xFDCF || cp in 0xFDF0..0xFFFD || cp in 0x10000..0xEFFFF

private fun isNameChar(cp: Int, allowColon: Boolean): Boolean =
    isNameStartChar(cp, allowColon) || cp == '-'.code || cp == '.'.code || cp in '0'.code..'9'.code || cp == 0xB7 ||
        cp in 0x300..0x36F || cp in 0x203F..0x2040

private fun isXmlName(s: String, allowColon: Boolean): Boolean {
    if (s.isEmpty()) return false
    val cps = s.codePoints().toArray()
    return isNameStartChar(cps[0], allowColon) && (1 until cps.size).all { isNameChar(cps[it], allowColon) }
}

private fun isNmtoken(s: String): Boolean = s.isNotEmpty() && s.codePoints().allMatch { isNameChar(it, allowColon = true) }

private val xmlWhitespaceRun = Regex("[\\u0020\\u0009\\u000A\\u000D]+")

/** XSD list types: one or more items separated by XML whitespace. */
private fun isXmlList(s: String, item: (String) -> Boolean): Boolean {
    val items = s.split(xmlWhitespaceRun).filter { it.isNotEmpty() }
    return items.isNotEmpty() && items.all(item)
}

private fun hasValidPercentEscapes(s: String): Boolean {
    var i = s.indexOf('%')
    while (i >= 0) {
        if (i + 2 >= s.length || !s[i + 1].isHexDigitChar() || !s[i + 2].isHexDigitChar()) return false
        i = s.indexOf('%', i + 3)
    }
    return true
}

private fun Char.isHexDigitChar(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/**
 * XSD `base64Binary` (Part 2 §3.3.17): quanta of four characters of the base64 alphabet, the last one possibly padded
 * (`xx==` with the second character in `AQgw`, `xxx=` with the third in `AEIMQUYcgkosw048`), each character but the
 * last optionally followed by one space. One linear pass: the grammar as a Java regular expression repeats a group
 * per character and overflows the stack on long values.
 */
private fun isBase64Binary(s: String): Boolean {
    val chars = StringBuilder(s.length)
    for ((i, c) in s.withIndex()) {
        if (c != ' ') { chars.append(c); continue }
        // A single space, after a character and before another one.
        if (i == 0 || s[i - 1] == ' ' || i == s.length - 1) return false
    }
    val n = chars.length
    if (n % 4 != 0) return false
    val padding = if (n > 0 && chars[n - 1] == '=') (if (chars[n - 2] == '=') 2 else 1) else 0
    for (k in 0 until n - padding) if (BASE64.indexOf(chars[k]) < 0) return false
    return when (padding) {
        0 -> true
        1 -> chars[n - 2] in "AEIMQUYcgkosw048"
        else -> chars[n - 3] in "AQgw"
    }
}
