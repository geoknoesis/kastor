package com.geoknoesis.kastor.rdf.shacl.native

/**
 * The language ranges of an `sh:languageIn` list, for lookups (RFC 4647 basic filtering, case insensitive). A range
 * `r` or `r-*` matches the tag `r` and every tag that starts with `r-`, so a tag is matched by looking up each of its
 * prefixes that ends at a subtag boundary. `*` and `-*` match every tag; the empty range matches none.
 *
 * Only prefixes up to the longest range can match, so [matches] reads at most the first [longest] + 1 characters of
 * the tag and costs time bounded by the ranges, whatever the tag's length (a legal tag can hold millions of subtags).
 */
internal class LanguageRanges(ranges: List<String>) {
    private val prefixes = HashSet<String>()
    private val any: Boolean
    private val longest: Int

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
        longest = prefixes.maxOfOrNull { it.length } ?: 0
    }

    fun matches(tag: String): Boolean = matches(tag) {}

    /** [matches], reporting the characters it copies or hashes to [charge] (for tests). */
    fun matches(tag: String, charge: (Int) -> Unit): Boolean {
        if (any) return true
        if (prefixes.isEmpty()) return false
        // A prefix longer than every range cannot match; the character after the longest one tells a whole-tag match.
        val head = tag.substring(0, minOf(tag.length, longest + 1)).lowercase()
        charge(head.length)
        if (tag.length <= longest && head in prefixes) return true
        var dash = head.indexOf('-')
        while (dash in 1..longest) {
            charge(dash)
            if (head.substring(0, dash) in prefixes) return true
            dash = head.indexOf('-', dash + 1)
        }
        return false
    }
}
