package com.geoknoesis.kastor.rdf.sparql.internal

/**
 * An immutable set of Unicode code points (`0..0x10FFFF`, surrogates included), held as sorted, disjoint,
 * non-adjacent inclusive ranges: `ranges[2k]..ranges[2k + 1]`. The XPath regular expression translator
 * ([XPathRegexTranslator]) computes every character class with these sets (union, complement, subtraction, case
 * closure) and hands RE2 the resulting ranges, so no class semantics depend on RE2's own flags or tables.
 */
internal class CodePointSet private constructor(private val ranges: IntArray) {

    val isEmpty: Boolean get() = ranges.isEmpty()

    /** How many ranges the set has (the size of its RE2 class text grows with it). */
    val rangeCount: Int get() = ranges.size / 2

    operator fun contains(cp: Int): Boolean {
        var lo = 0
        var hi = rangeCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < ranges[2 * mid] -> hi = mid - 1
                cp > ranges[2 * mid + 1] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /** The single code point of a one-element set, or -1. */
    fun single(): Int = if (ranges.size == 2 && ranges[0] == ranges[1]) ranges[0] else -1

    fun union(other: CodePointSet): CodePointSet = Builder().addAll(this).addAll(other).build()

    fun complement(): CodePointSet {
        val out = Builder()
        var next = 0
        for (k in 0 until rangeCount) {
            if (ranges[2 * k] > next) out.add(next, ranges[2 * k] - 1)
            next = ranges[2 * k + 1] + 1
        }
        if (next <= MAX) out.add(next, MAX)
        return out.build()
    }

    fun minus(other: CodePointSet): CodePointSet = complement().union(other).complement()

    /** Calls [action] with the first and last code point of every range, in order. */
    inline fun forEachRange(action: (Int, Int) -> Unit) {
        for (k in 0 until rangeCount) action(rangeStart(k), rangeEnd(k))
    }

    fun rangeStart(k: Int): Int = ranges[2 * k]

    fun rangeEnd(k: Int): Int = ranges[2 * k + 1]

    override fun equals(other: Any?): Boolean = other is CodePointSet && ranges.contentEquals(other.ranges)

    override fun hashCode(): Int = ranges.contentHashCode()

    override fun toString(): String = buildString {
        append('{')
        forEachRange { a, b -> append(Integer.toHexString(a)); if (a != b) append('-').append(Integer.toHexString(b)); append(' ') }
        append('}')
    }

    /** Accumulates ranges in any order; [build] sorts and merges them. */
    class Builder {
        private var data = IntArray(16)
        private var size = 0

        fun add(cp: Int): Builder = add(cp, cp)

        fun add(first: Int, last: Int): Builder {
            require(first in 0..last && last <= MAX) { "Invalid code point range $first..$last" }
            if (size + 2 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = first
            data[size++] = last
            return this
        }

        fun addAll(set: CodePointSet): Builder {
            set.forEachRange { a, b -> add(a, b) }
            return this
        }

        fun build(): CodePointSet {
            val count = size / 2
            if (count == 0) return EMPTY
            val order = (0 until count).sortedWith(compareBy({ data[2 * it] }, { data[2 * it + 1] }))
            val out = IntArray(size)
            var n = 0
            for (k in order) {
                val a = data[2 * k]
                val b = data[2 * k + 1]
                if (n > 0 && a <= out[n - 1] + 1) {
                    if (b > out[n - 1]) out[n - 1] = b
                } else {
                    out[n++] = a
                    out[n++] = b
                }
            }
            return CodePointSet(out.copyOf(n))
        }
    }

    companion object {
        const val MAX: Int = Character.MAX_CODE_POINT

        val EMPTY = CodePointSet(IntArray(0))
        val ALL = CodePointSet(intArrayOf(0, MAX))

        fun of(vararg codePoints: Int): CodePointSet = Builder().apply { codePoints.forEach { add(it) } }.build()

        fun range(first: Int, last: Int): CodePointSet = Builder().add(first, last).build()

        /** The code points `cp` in `0..MAX` for which [test] holds, scanned once. */
        fun where(test: (Int) -> Boolean): CodePointSet {
            val out = Builder()
            var start = -1
            for (cp in 0..MAX) {
                if (test(cp)) {
                    if (start < 0) start = cp
                } else if (start >= 0) {
                    out.add(start, cp - 1)
                    start = -1
                }
            }
            if (start >= 0) out.add(start, MAX)
            return out.build()
        }
    }
}
