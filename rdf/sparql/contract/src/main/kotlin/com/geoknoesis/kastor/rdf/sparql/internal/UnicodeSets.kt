package com.geoknoesis.kastor.rdf.sparql.internal

import java.util.concurrent.ConcurrentHashMap

/**
 * The character sets of XPath / XML Schema regular expressions, computed from `java.lang.Character` (the JVM's
 * Unicode version) and cached: general categories, blocks, scripts, the multi-character escapes, the XML name
 * characters, the POSIX classes accepted for compatibility, and the case orbits behind the `i` flag.
 */
internal object UnicodeSets {

    /** The XML Schema general categories and their `Character.getType` values (`Cs` is accepted as in Java). */
    private val CATEGORY_TYPES: Map<String, IntArray> = buildMap {
        fun put(name: String, vararg types: Byte) = put(name, types.map { it.toInt() }.toIntArray())
        put("Lu", Character.UPPERCASE_LETTER); put("Ll", Character.LOWERCASE_LETTER); put("Lt", Character.TITLECASE_LETTER)
        put("Lm", Character.MODIFIER_LETTER); put("Lo", Character.OTHER_LETTER)
        put("Mn", Character.NON_SPACING_MARK); put("Mc", Character.COMBINING_SPACING_MARK); put("Me", Character.ENCLOSING_MARK)
        put("Nd", Character.DECIMAL_DIGIT_NUMBER); put("Nl", Character.LETTER_NUMBER); put("No", Character.OTHER_NUMBER)
        put("Pc", Character.CONNECTOR_PUNCTUATION); put("Pd", Character.DASH_PUNCTUATION); put("Ps", Character.START_PUNCTUATION)
        put("Pe", Character.END_PUNCTUATION); put("Pi", Character.INITIAL_QUOTE_PUNCTUATION)
        put("Pf", Character.FINAL_QUOTE_PUNCTUATION); put("Po", Character.OTHER_PUNCTUATION)
        put("Zs", Character.SPACE_SEPARATOR); put("Zl", Character.LINE_SEPARATOR); put("Zp", Character.PARAGRAPH_SEPARATOR)
        put("Sm", Character.MATH_SYMBOL); put("Sc", Character.CURRENCY_SYMBOL); put("Sk", Character.MODIFIER_SYMBOL)
        put("So", Character.OTHER_SYMBOL)
        put("Cc", Character.CONTROL); put("Cf", Character.FORMAT); put("Co", Character.PRIVATE_USE)
        put("Cn", Character.UNASSIGNED); put("Cs", Character.SURROGATE)
    }

    /** The one-letter categories: the union of the two-letter categories that start with the letter. */
    private val CATEGORY_NAMES: Set<String> = CATEGORY_TYPES.keys + CATEGORY_TYPES.keys.map { it.substring(0, 1) }

    private val cache = ConcurrentHashMap<String, CodePointSet>()

    private fun cached(key: String, compute: () -> CodePointSet): CodePointSet = cache[key] ?: compute().also { cache[key] = it }

    /** Whether [name] is a general category of XML Schema regular expressions (`L`, `Lu`, ... `Cn`). */
    fun isCategory(name: String): Boolean = name in CATEGORY_NAMES

    /** The general category [name] (see [isCategory]). */
    fun category(name: String): CodePointSet = cached("gc:$name") {
        val types = CATEGORY_TYPES.filterKeys { it == name || (name.length == 1 && it.startsWith(name)) }.values
            .flatMap { it.asIterable() }.toSet()
        CodePointSet.where { Character.getType(it) in types }
    }

    /** The Unicode block named [name] (a `Character.UnicodeBlock` name), or null when the JVM knows no such block. */
    fun block(name: String): CodePointSet? {
        val block = try {
            Character.UnicodeBlock.forName(name)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return cached("block:$block") { CodePointSet.where { Character.UnicodeBlock.of(it) == block } }
    }

    /** The Unicode script named [name] (a `Character.UnicodeScript` name), or null when the JVM knows no such script. */
    fun script(name: String): CodePointSet? {
        val script = try {
            Character.UnicodeScript.forName(name)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return cached("script:$script") { CodePointSet.where { Character.UnicodeScript.of(it) == script } }
    }

    /** `\d`: the Unicode decimal digits. */
    val DIGIT: CodePointSet by lazy { category("Nd") }

    /** `\s`: space, tab, line feed and carriage return. */
    val SPACE: CodePointSet = CodePointSet.of(' '.code, '\t'.code, '\n'.code, '\r'.code)

    /**
     * `\w`: XML Schema's "every character except punctuation (P), separators (Z) and other (C)", i.e. the categories
     * L, M, N and S, **plus the underscore** (as in the Java engines shapes are usually written against).
     */
    val WORD: CodePointSet by lazy {
        category("L").union(category("M")).union(category("N")).union(category("S")).union(CodePointSet.of('_'.code))
    }

    /** XML 1.0 (fifth edition) `NameStartChar`: `\i`. */
    val NAME_START: CodePointSet = ranges(
        ':'.code..':'.code, 'A'.code..'Z'.code, '_'.code..'_'.code, 'a'.code..'z'.code,
        0xC0..0xD6, 0xD8..0xF6, 0xF8..0x2FF, 0x370..0x37D, 0x37F..0x1FFF, 0x200C..0x200D, 0x2070..0x218F,
        0x2C00..0x2FEF, 0x3001..0xD7FF, 0xF900..0xFDCF, 0xFDF0..0xFFFD, 0x10000..0xEFFFF,
    )

    /** XML 1.0 (fifth edition) `NameChar`: `\c`. */
    val NAME_CHAR: CodePointSet = NAME_START.union(
        ranges('-'.code..'-'.code, '.'.code..'.'.code, '0'.code..'9'.code, 0xB7..0xB7, 0x300..0x36F, 0x203F..0x2040),
    )

    /** POSIX bracket expressions (`[:alpha:]` inside a class), US-ASCII as in the POSIX locale. */
    val POSIX: Map<String, CodePointSet> = linkedMapOf(
        "alpha" to ranges('a'.code..'z'.code, 'A'.code..'Z'.code),
        "digit" to ranges('0'.code..'9'.code),
        "alnum" to ranges('a'.code..'z'.code, 'A'.code..'Z'.code, '0'.code..'9'.code),
        "upper" to ranges('A'.code..'Z'.code),
        "lower" to ranges('a'.code..'z'.code),
        "space" to ranges(' '.code..' '.code, 0x09..0x0D),
        "punct" to ranges(0x21..0x2F, 0x3A..0x40, 0x5B..0x60, 0x7B..0x7E),
        "xdigit" to ranges('0'.code..'9'.code, 'a'.code..'f'.code, 'A'.code..'F'.code),
        "blank" to ranges(' '.code..' '.code, 0x09..0x09),
        "cntrl" to ranges(0x00..0x1F, 0x7F..0x7F),
        "graph" to ranges(0x21..0x7E),
        "print" to ranges(0x20..0x7E),
    )

    /** `.` without the `s` flag: every character except line feed and carriage return. */
    val NOT_LINE_END: CodePointSet = CodePointSet.of('\n'.code, '\r'.code).complement()

    private fun ranges(vararg ranges: IntRange): CodePointSet =
        CodePointSet.Builder().apply { ranges.forEach { add(it.first, it.last) } }.build()

    /**
     * [set] with every case variant of its members (XPath F&O 5.6.2: under the `i` flag a character, a range or a
     * negated class matches a character when it matches one of its case variants). Case variants are the members of
     * one orbit of `Character.toUpperCase`, `toLowerCase` and `toTitleCase`, closed transitively (so `k`, `K` and the
     * Kelvin sign U+212A are one orbit).
     */
    fun caseClosure(set: CodePointSet): CodePointSet {
        val orbits = CaseOrbits.instance
        val out = CodePointSet.Builder().addAll(set)
        var changed = false
        for (k in orbits.casedCodePoints.indices) {
            if (orbits.casedCodePoints[k] in set) {
                orbits.orbit(k).forEach { out.add(it) }
                changed = true
            }
        }
        return if (changed) out.build() else set
    }

    /** The orbit of [cp] (itself and its case variants), or null when it has no case variant. */
    fun caseOrbit(cp: Int): IntArray? = CaseOrbits.instance.orbitOf(cp)

    /** The case orbits of all code points that have a case variant, computed once. */
    private class CaseOrbits private constructor() {
        /** The code points with at least one case variant, sorted. */
        val casedCodePoints: IntArray

        /** For each entry of [casedCodePoints], its orbit (shared arrays). */
        private val orbits: Array<IntArray>

        init {
            val parent = HashMap<Int, Int>()
            fun find(x: Int): Int {
                var r = x
                while (true) {
                    val p = parent[r] ?: return r
                    if (p == r) return r
                    r = p
                }
            }
            fun union(a: Int, b: Int) {
                if (a == b) return
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
                parent.putIfAbsent(minOf(ra, rb), minOf(ra, rb))
            }
            for (cp in 0..CodePointSet.MAX) {
                union(cp, Character.toLowerCase(cp))
                union(cp, Character.toUpperCase(cp))
                union(cp, Character.toTitleCase(cp))
            }
            val groups = parent.keys.groupBy { find(it) }.values.filter { it.size > 1 }.map { it.sorted().toIntArray() }
            val byCodePoint = sortedMapOf<Int, IntArray>()
            for (group in groups) for (cp in group) byCodePoint[cp] = group
            casedCodePoints = byCodePoint.keys.toIntArray()
            orbits = byCodePoint.values.toTypedArray()
        }

        fun orbit(index: Int): IntArray = orbits[index]

        fun orbitOf(cp: Int): IntArray? {
            val k = casedCodePoints.binarySearch(cp)
            return if (k >= 0) orbits[k] else null
        }

        companion object {
            val instance: CaseOrbits by lazy { CaseOrbits() }
        }
    }
}
