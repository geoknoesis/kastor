package com.geoknoesis.kastor.rdf.sparql

// How text taken from a response (an error body, a Location header) may appear in a message or a log.

private const val MAX_ERROR_DETAIL_CHARS = 512

/**
 * What of an HTTP error body goes into an exception message (and from there into logs): one line of
 * at most [MAX_ERROR_DETAIL_CHARS] characters, followed by `...` when the body was longer. Runs of
 * white space and line breaks become one space; control characters, invisible format characters
 * (bidirectional overrides among them) and anything that is not a character are written as their
 * escape, so that a body can neither forge log lines nor hide what it says.
 */
internal fun errorDetail(body: String): String {
    val out = StringBuilder()
    var space = false
    var i = 0
    while (i < body.length) {
        val codePoint = body.codePointAt(i)
        i += Character.charCount(codePoint)
        val type = Character.getType(codePoint)
        if (codePoint in LINE_BREAKS || type == Character.SPACE_SEPARATOR.toInt()) {
            space = out.isNotEmpty()
            continue
        }
        val escaped = type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt() ||
            type == Character.PRIVATE_USE.toInt() || type == Character.UNASSIGNED.toInt()
        val text = when {
            !escaped -> String(Character.toChars(codePoint))
            codePoint <= 0xFFFF -> "%cu%04X".format(BACKSLASH, codePoint)
            else -> "%cu{%X}".format(BACKSLASH, codePoint)
        }
        if (out.length + (if (space) 1 else 0) + text.length > MAX_ERROR_DETAIL_CHARS) return out.append("...").toString()
        if (space) out.append(' ')
        space = false
        out.append(text)
    }
    return out.toString()
}

/**
 * A URL taken from a response (a `Location` header) as it may appear in a message or log: without
 * user information, query and fragment (they can carry credentials or signed tokens), and
 * sanitised and truncated like an error body by [errorDetail].
 */
internal fun urlDetail(url: String): String {
    // User information first: a password may hold a raw '?' or '#', which must not end it early.
    val withoutUserInfo = url.replace(USER_INFO, "***@")
    val cut = withoutUserInfo.indexOfFirst { it == '?' || it == '#' }
    return errorDetail((if (cut < 0) withoutUserInfo else withoutUserInfo.substring(0, cut)) + if (cut < 0) "" else "?...")
}

/**
 * User information in a URL that could not be parsed: everything after `//` up to the last `@`
 * before the first `/`, so that a password holding a raw `@`, `?` or `#` is cut out whole. A URL
 * without a path whose query holds an `@` (`http://host?x=a@b`) is over-redacted, which is safe.
 */
internal val USER_INFO = Regex("(?<=//)[^/]*@")

private val BACKSLASH = 92.toChar()

/** Tab, line feed, vertical tab, form feed, carriage return, next line, line separator, paragraph separator. */
private val LINE_BREAKS = setOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029)
