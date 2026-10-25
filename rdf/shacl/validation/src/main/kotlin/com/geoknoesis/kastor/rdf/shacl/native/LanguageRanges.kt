package com.geoknoesis.kastor.rdf.shacl.native

/**
 * The language ranges of an `sh:languageIn` list, for lookups (RFC 4647 basic filtering, case insensitive):
 * [matches] costs time linear in the length of the tag, not in the number of ranges. A range `r` or `r-*` matches the tag `r` and every tag that starts with `r-`, case
 * insensitively, so a tag is matched by looking up each of its prefixes that ends at a subtag boundary. `*` and
 * `-*` match every tag; the empty range matches none.
 */
internal class LanguageRanges(ranges: List<String>) {
    private val prefixes = HashSet<String>()
    private val any: Boolean

    init {
        var all = false
        for (range in ranges) {
            val r = range.lowercase()
            when {
                r == "*" || r == "-*" -> all = true
                r.endsWith("-*") -> prefixes.add(r.dropLast(2))
                r.isNotEmpty() -> prefixes.add(r)
            }
        }
        any = all
    }

    fun matches(tag: String): Boolean {
        if (any) return true
        val v = tag.lowercase()
        if (v in prefixes) return true
        var dash = v.indexOf('-')
        while (dash >= 0) {
            if (v.substring(0, dash) in prefixes) return true
            dash = v.indexOf('-', dash + 1)
        }
        return false
    }
}
