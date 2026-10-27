package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.sparql.internal.XPathPattern
import com.geoknoesis.kastor.rdf.sparql.internal.XPathRegex

/**
 * Thrown by [ShaclPattern.containsMatchIn] when a match would take more than [ShaclPattern.MAX_STEPS] steps of the
 * regular expression engine: whether [valueLength] characters match [pattern] is not known, so the value is neither
 * accepted nor reported as a violation. An [IllegalArgumentException]: the value is too long for the pattern.
 */
class ShaclPatternBudgetException(
    /** The `sh:pattern` value. */
    val pattern: String,
    /** The length of the value, in UTF-16 code units. */
    val valueLength: Int,
    message: String,
) : IllegalArgumentException(message)

/**
 * A SHACL `sh:pattern` with its `sh:flags`, as generated validation code checks it: an XPath (`fn:matches`)
 * regular expression run on **RE2/J**, a linear-time engine - the same translation and the same engine as the Kastor
 * SHACL validator and the sandboxed SPARQL `REGEX` of both providers. No value can make a pattern backtrack.
 *
 * **Budget.** Linear time is still long for a large pattern on a large value (`.{0,1000}x` on a value of a million
 * characters takes tens of seconds), so a match is charged to a budget of [MAX_STEPS] steps, with the weights of the
 * Kastor SHACL validator: each character the engine reads costs as many steps as the compiled pattern has
 * instructions, the most work the engine can do per read. Steps are counted, not time, so whether a value is decided
 * does not depend on the machine. Past the budget, [containsMatchIn] throws [ShaclPatternBudgetException]: generated
 * `validate()` methods and setters then fail instead of accepting or rejecting the value. Bound such values with
 * `sh:maxLength`. The budget is the Kastor SHACL validator's default (`ValidationConfig.patternTimeout` of one
 * second, at 200,000 steps per millisecond).
 *
 * A pattern that the engine refuses (a back-reference, a lookaround, a word boundary, a repetition count above 1000,
 * ...; see the Kastor SHACL documentation) fails code generation; the constructor refuses it too, with an
 * [IllegalArgumentException].
 */
class ShaclPattern internal constructor(
    /** The `sh:pattern` value. */
    val pattern: String,
    /** The `sh:flags` value, or null. */
    val flags: String?,
    private val maxSteps: Long,
) {
    /** A pattern charged to the budget of [MAX_STEPS] steps. */
    constructor(pattern: String, flags: String? = null) : this(pattern, flags, MAX_STEPS)

    private val compiled: XPathPattern = XPathRegex.compile(pattern, flags)
    private val weight: Long = compiled.programSize.toLong().coerceAtLeast(1)

    /**
     * Whether the pattern matches somewhere in [value] (SHACL `sh:pattern`, XPath `fn:matches`).
     *
     * @throws ShaclPatternBudgetException when the match would take more than [MAX_STEPS] steps (see the class
     *   documentation)
     */
    fun containsMatchIn(value: CharSequence): Boolean {
        val text = Charged(value.toString(), weight, maxSteps)
        try {
            return compiled.containsMatchIn(text)
        } catch (e: BudgetExceeded) {
            throw ShaclPatternBudgetException(
                pattern,
                value.length,
                "sh:pattern \"$pattern\"${flags?.let { " (sh:flags \"$it\")" }.orEmpty()} needs more than $maxSteps steps " +
                    "of the regular expression engine on a value of ${value.length} characters (each character read " +
                    "costs $weight steps, the size of the compiled pattern), so whether it matches is unknown and " +
                    "the value is not accepted; bound the value with sh:maxLength",
            )
        }
    }

    override fun toString(): String = "ShaclPattern($pattern${flags?.let { ", flags=$it" } ?: ""})"

    companion object {
        /** The steps (characters read times the size of the compiled pattern) one match may take. */
        const val MAX_STEPS: Long = 200_000_000
    }

    private class BudgetExceeded : RuntimeException(null, null, false, false)

    /** [value], each character read charged [weight] steps against [limit]. Sub-sequences are not charged. */
    private class Charged(private val value: String, private val weight: Long, private val limit: Long) : CharSequence {
        private var steps = 0L

        override val length: Int get() = value.length

        override fun get(index: Int): Char {
            steps += weight
            if (steps > limit) throw BudgetExceeded()
            return value[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = value.substring(startIndex, endIndex)

        override fun toString(): String = value
    }
}
