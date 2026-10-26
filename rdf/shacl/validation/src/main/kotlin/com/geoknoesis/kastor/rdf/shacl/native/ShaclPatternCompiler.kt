package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.sparql.internal.XPathPattern
import com.geoknoesis.kastor.rdf.sparql.internal.XPathRegex
import com.geoknoesis.kastor.rdf.sparql.internal.XPathRegexException

/**
 * Compiles `sh:pattern` with `sh:flags` as an XPath `fn:matches` regular expression (XML Schema regular expressions,
 * which is what SHACL prescribes through SPARQL `REGEX`), for the linear-time RE2/J engine shared with the
 * sandboxed SPARQL of both providers ([XPathRegex]): a pattern from an untrusted shapes graph cannot backtrack
 * catastrophically, whatever its shape.
 *
 * **The rule**: a pattern is read as an XPath regular expression and translated with the same meaning; the
 * java.util.regex idioms that shapes written for Java engines use (a leading `(?i)`, `\Q...\E`, POSIX bracket
 * expressions, script names in `\p{IsX}`, hexadecimal escapes) are translated with the meaning their author
 * intends; everything else that would not mean the same, or that the linear-time engine cannot evaluate
 * (back-references, lookarounds, atomic groups, possessive quantifiers, word boundaries, inline flag groups that are
 * not leading, class intersection, nested classes, repetition counts above 1000), is **rejected** with a
 * [ShapeCompileException] when the shape is compiled. A pattern is never silently given another meaning, and nothing
 * falls back to java.util.regex. [XPathRegex] documents the translation in full; notably `$` matches only at the end
 * of the value (or before a line feed with `m`), `.` does not match line feed or carriage return, and `\w` includes
 * the underscore.
 */
internal fun compileShaclPattern(pattern: String, flags: String?): XPathPattern = try {
    XPathRegex.compile(pattern, flags)
} catch (e: XPathRegexException) {
    throw ShapeCompileException("Invalid sh:pattern \"$pattern\": ${e.reason}", e)
}
