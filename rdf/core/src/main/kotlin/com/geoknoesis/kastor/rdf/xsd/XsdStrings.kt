package com.geoknoesis.kastor.rdf.xsd

import com.geoknoesis.kastor.rdf.InternalKastorApi
import com.geoknoesis.kastor.rdf.LiteralValidation

/**
 * The XSD lexical-space check of the string, binary, name and URI datatypes, shared by the SHACL validator and Kastor
 * Gen (the companion of [XsdLexical], which covers the numeric, boolean, date/time and duration datatypes):
 * `xsd:string`, `xsd:language`, `xsd:hexBinary`, `xsd:base64Binary`, `xsd:normalizedString`, `xsd:token`, the XML
 * name and token types `Name`, `NCName`, `ID`, `IDREF`, `ENTITY`, `NMTOKEN`, `NMTOKENS`, `IDREFS`, `ENTITIES` and
 * `QName`, and `xsd:anyURI`. One linear pass per literal.
 *
 * `xsd:normalizedString`, `xsd:token` and `xsd:anyURI` also require every character to be a legal XML 1.0 character;
 * `xsd:string` accepts any Unicode string (W3C SHACL test singleLine-001 expects `"\u000B"^^xsd:string` to conform).
 * The lexical form is matched as it is, without white-space processing.
 */
@InternalKastorApi
object XsdStrings {
    private const val XSD = XSD_NAMESPACE

    /**
     * Whether [lexical] is in the lexical space of [datatypeIri]; `null` when the datatype is not one this object
     * checks (the types of [XsdLexical], other XSD types, non-XSD datatypes).
     */
    @JvmStatic
    fun isValid(datatypeIri: String, lexical: String): Boolean? {
        if (!datatypeIri.startsWith(XSD)) return null
        return when (datatypeIri.substring(XSD.length)) {
            "string" -> true
            "language" -> LiteralValidation.isWellFormedLanguageTag(lexical)
            "hexBinary" -> lexical.length % 2 == 0 && lexical.all { it.isHexDigitChar() }
            "base64Binary" -> isBase64Binary(lexical)
            "normalizedString" -> allXmlChars(lexical) && lexical.none { it == '\r' || it == '\n' || it == '\t' }
            "token" -> isToken(lexical)
            "Name" -> isXmlName(lexical, allowColon = true)
            "NCName", "ID", "IDREF", "ENTITY" -> isXmlName(lexical, allowColon = false)
            "NMTOKEN" -> isNmtoken(lexical)
            "NMTOKENS" -> isXmlList(lexical, ::isNmtoken)
            "IDREFS", "ENTITIES" -> isXmlList(lexical) { isXmlName(it, allowColon = false) }
            "QName" -> lexical.split(':').let { parts -> parts.size in 1..2 && parts.all { isXmlName(it, allowColon = false) } }
            "anyURI" -> allXmlChars(lexical) && hasValidPercentEscapes(lexical)
            else -> null
        }
    }

    private fun isToken(s: String): Boolean =
        allXmlChars(s) && s.none { it == '\r' || it == '\n' || it == '\t' } && !s.startsWith(" ") && !s.endsWith(" ") && !s.contains("  ")

    // --- XML 1.0 (fifth edition) character classes -----------------------------------------------------------------

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

    private fun isXmlWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    /** XSD list types: one or more items separated by XML white space. */
    private fun isXmlList(s: String, item: (String) -> Boolean): Boolean {
        val items = ArrayList<String>()
        var start = -1
        for (i in 0..s.length) {
            val separator = i == s.length || isXmlWhitespace(s[i])
            if (!separator && start < 0) start = i
            if (separator && start >= 0) {
                items.add(s.substring(start, i))
                start = -1
            }
        }
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
     * XSD `base64Binary` (Part 2 §3.3.17): quanta of four characters of the base64 alphabet, the last one possibly
     * padded (`xx==` with the second character in `AQgw`, `xxx=` with the third in `AEIMQUYcgkosw048`), each character
     * but the last optionally followed by one space. One linear pass: the grammar as a Java regular expression repeats
     * a group per character and overflows the stack on long values.
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
}
