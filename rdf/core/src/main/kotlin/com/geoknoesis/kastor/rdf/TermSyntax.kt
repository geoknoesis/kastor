package com.geoknoesis.kastor.rdf

// The lexical rules of the term model: which strings are absolute IRIs ([Iri]) and how a [BlankNode] id is
// written as a BLANK_NODE_LABEL. Kept apart from the term classes in RdfTerms.kt.

/**
 * Validates an absolute IRI with an allocation-free, single-pass scanner modelled on RFC 3986 / RFC 3987.
 *
 * **Checks:**
 * - `scheme ":"` prefix with an ASCII scheme (`ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )`); the
 *   hier-part may be empty (`urn:` and `about:` are valid absolute IRIs), relative references are rejected
 * - No ASCII space or control characters (U+0000-U+0020, U+007F-U+009F), no Unicode non-characters
 *   (U+FDD0-U+FDEF and U+xxFFFE / U+xxFFFF), no unpaired surrogates, and none of the delimiters
 *   `< > " { } | \ ^ `` (these enable SPARQL/Turtle injection when an IRI is interpolated into `<...>`)
 * - Percent-encodings are `%` followed by two hex digits; at most one `#`
 * - When an authority (`//...`) is present: an optional userinfo (a single `@`), a host that is either a
 *   closed IP literal (`[...]`) or a reg-name without brackets, and an all-digit port
 *
 * All other non-ASCII characters - RFC 3987 `ucschar` and `iprivate`, including U+00A0 NO-BREAK SPACE and
 * U+3000 IDEOGRAPHIC SPACE - are accepted anywhere after the scheme; IDN and IP-address semantics are not
 * validated. Square brackets are tolerated outside the authority for compatibility with real-world data.
 */
internal fun isValidIri(value: String): Boolean {
    val length = value.length
    if (length == 0 || !value[0].isAsciiLetter()) return false
    var i = 1
    while (i < length) {
        val c = value[i]
        if (c == ':') break
        if (!(c.isAsciiLetter() || c in '0'..'9' || c == '+' || c == '-' || c == '.')) return false
        i++
    }
    if (i >= length) return false // no scheme separator
    if (!hasValidCodePoints(value)) return false
    var pos = i + 1
    if (pos + 1 < length && value[pos] == '/' && value[pos + 1] == '/') {
        pos = scanAuthority(value, pos + 2)
        if (pos < 0) return false
    }
    var sawFragment = false
    while (pos < length) {
        val c = value[pos]
        when {
            c.isForbiddenIriChar() -> return false
            c == '%' -> {
                if (pos + 2 >= length || !value[pos + 1].isHexDigit() || !value[pos + 2].isHexDigit()) return false
                pos += 2
            }
            c == '#' -> {
                if (sawFragment) return false
                sawFragment = true
            }
        }
        pos++
    }
    return true
}

/** Scans `[userinfo "@"] host [":" port]` starting at [start]; returns the end index or -1 if malformed. */
private fun scanAuthority(value: String, start: Int): Int {
    var end = start
    while (end < value.length && value[end] != '/' && value[end] != '?' && value[end] != '#') end++
    var hostStart = start
    for (k in start until end) {
        if (value[k] == '@') {
            if (hostStart != start) return -1 // '@' may only separate userinfo from host (RFC 3986 3.2.1)
            hostStart = k + 1
        }
    }
    for (k in start until hostStart) {
        val c = value[k]
        if (c == '[' || c == ']' || c.isForbiddenIriChar()) return -1
        if (c == '%' && !validPercent(value, k, hostStart)) return -1
    }
    var k = hostStart
    if (k < end && value[k] == '[') {
        val close = value.indexOf(']', k + 1)
        if (close < 0 || close >= end || close == k + 1) return -1
        for (j in k + 1 until close) {
            val c = value[j]
            if (!(c.isAsciiLetter() || c in '0'..'9' || c in ":.-_~!$&'()*+,;=")) return -1
        }
        k = close + 1
    } else {
        while (k < end && value[k] != ':') {
            val c = value[k]
            if (c == '[' || c == ']' || c.isForbiddenIriChar()) return -1
            if (c == '%' && !validPercent(value, k, end)) return -1
            k++
        }
    }
    if (k < end) {
        if (value[k] != ':') return -1
        for (j in k + 1 until end) if (value[j] !in '0'..'9') return -1
    }
    return end
}

private fun validPercent(value: String, index: Int, limit: Int): Boolean =
    index + 2 < limit && value[index + 1].isHexDigit() && value[index + 2].isHexDigit()

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** BMP characters that are neither `ucschar`, `iprivate` nor an allowed ASCII character (RFC 3987 section 2.2). */
private fun Char.isForbiddenIriChar(): Boolean = when (this) {
    '<', '>', '"', '{', '}', '|', '\\', '^', '`' -> true
    in '\u0000'..'\u0020', in '\u007F'..'\u009F', in '\uFDD0'..'\uFDEF', '\uFFFE', '\uFFFF' -> true
    else -> false
}

/** Rejects unpaired surrogates and supplementary-plane non-characters (U+xxFFFE / U+xxFFFF). */
private fun hasValidCodePoints(value: String): Boolean {
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c.isHighSurrogate()) {
            if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) return false
            if ((Character.toCodePoint(c, value[i + 1]) and 0xFFFE) == 0xFFFE) return false
            i += 2
            continue
        }
        if (c.isLowSurrogate()) return false
        i++
    }
    return true
}

private const val ENCODED_BLANK_NODE_PREFIX = "ux_"
private const val HEX_DIGITS = "0123456789abcdef"

/**
 * An injective `BLANK_NODE_LABEL` for [id]: the id itself when valid and not starting with `ux_`; `ux__` + the rest
 * when valid and starting with `ux_`; otherwise `ux_` + a UTF-16 hex encoding (the three forms never overlap).
 */
internal fun blankNodeLabel(id: String): String {
    if (isValidBlankNodeLabel(id)) {
        if (!id.startsWith(ENCODED_BLANK_NODE_PREFIX)) return id
        return ENCODED_BLANK_NODE_PREFIX + "_" + id.substring(ENCODED_BLANK_NODE_PREFIX.length)
    }
    return buildString(ENCODED_BLANK_NODE_PREFIX.length + id.length * 4) {
        append(ENCODED_BLANK_NODE_PREFIX)
        for (unit in id) {
            val v = unit.code
            append(HEX_DIGITS[(v shr 12) and 0xF]).append(HEX_DIGITS[(v shr 8) and 0xF])
                .append(HEX_DIGITS[(v shr 4) and 0xF]).append(HEX_DIGITS[v and 0xF])
        }
    }
}

/** True if [label] matches `(PN_CHARS_U | [0-9]) ((PN_CHARS | '.')* PN_CHARS)?` (RDF 1.1 N-Triples / Turtle). */
internal fun isValidBlankNodeLabel(label: String): Boolean {
    if (label.isEmpty()) return false
    var i = 0
    var last = -1
    while (i < label.length) {
        val cp = label.codePointAt(i)
        val ok = if (i == 0) isPnCharsU(cp) || cp in 0x30..0x39 else isPnChars(cp) || cp == '.'.code
        if (!ok) return false
        last = cp
        i += Character.charCount(cp)
    }
    return last != '.'.code
}

private fun isPnCharsU(cp: Int): Boolean = RdfNameChars.isPnCharsBase(cp) || cp == '_'.code

private fun isPnChars(cp: Int): Boolean =
    isPnCharsU(cp) || cp == '-'.code || cp in 0x30..0x39 || cp == 0xB7 || cp in 0x300..0x36F || cp in 0x203F..0x2040
