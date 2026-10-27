package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.sparql.internal.XPathPattern
import com.geoknoesis.kastor.rdf.sparql.internal.XPathRegex

/**
 * A SHACL `sh:pattern` with its `sh:flags`, as generated validation code checks it: an XPath (`fn:matches`)
 * regular expression run on **RE2/J**, a linear-time engine - the same translation and the same engine as the Kastor
 * SHACL validator and the sandboxed SPARQL `REGEX` of both providers. A match costs at most the size of the compiled
 * pattern per character of the value, so no value can make a pattern backtrack catastrophically.
 *
 * A pattern that the engine refuses (a back-reference, a lookaround, a word boundary, a repetition count above 1000,
 * ...; see the Kastor SHACL documentation) fails code generation; the constructor refuses it too, with an
 * [IllegalArgumentException].
 */
class ShaclPattern(
    /** The `sh:pattern` value. */
    val pattern: String,
    /** The `sh:flags` value, or null. */
    val flags: String? = null,
) {
    private val compiled: XPathPattern = XPathRegex.compile(pattern, flags)

    /** Whether the pattern matches somewhere in [value] (SHACL `sh:pattern`, XPath `fn:matches`). */
    fun containsMatchIn(value: CharSequence): Boolean = compiled.containsMatchIn(value)

    override fun toString(): String = "ShaclPattern($pattern${flags?.let { ", flags=$it" } ?: ""})"
}
