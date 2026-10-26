package com.geoknoesis.kastor.rdf.sparql.internal

/**
 * **Not public API.** The bounds that the Jena and RDF4J providers hold the expressions of a sandboxed SELECT query
 * (`com.geoknoesis.kastor.rdf.SandboxedSelectQuery`) to, so that one small query text cannot hold a worker far past
 * its timeout or exhaust the heap with a single expression (`math:pow(2, 1000000000)`, a chain of `CONCAT(?x, ?x)`).
 * Regular expressions run on a linear-time engine ([SandboxRegex]). It is public only because Kotlin `internal`
 * cannot be shared across modules; it may change without notice.
 *
 * A value an expression **computes** is bounded; values read from the data are not (their size is the data's). An
 * expression whose evaluation would exceed a bound fails the whole query with [SandboxLimitException] (fail closed: a
 * SPARQL error would make a `FILTER` false and could hide a SHACL violation).
 */
object SandboxLimits {

    /** Longest string (lexical form, in UTF-16 chars) an expression may compute: 16 Mi chars (32 MiB). */
    const val MAX_STRING_LENGTH: Int = 16 * 1024 * 1024

    /**
     * Largest integer an expression may compute, in bits (about 19,700 decimal digits), and the largest unscaled
     * value of a computed decimal. Also the largest operand of a multiplication, division or modulo, whose cost grows
     * faster than its operands.
     */
    const val MAX_INTEGER_BITS: Int = 64 * 1024

    /** Largest scale (digits after the point, or zeros before it) of a decimal an expression may compute. */
    const val MAX_DECIMAL_SCALE: Int = 64 * 1024

    /**
     * Longest lexical form converted to an integer or a decimal (a cast such as `xsd:integer(?s)`, `STRDT(?s,
     * xsd:decimal)`): converting digits to a binary number costs more than linear time in their count.
     */
    const val MAX_NUMERIC_LEXICAL_LENGTH: Int = 32 * 1024

    /** Most characters the `GROUP_CONCAT` aggregates of one query may accumulate, over all their groups. */
    const val MAX_GROUP_CONCAT_LENGTH: Long = MAX_STRING_LENGTH.toLong()

    /**
     * How many characters a regular expression ([SandboxRegex]) reads at most between two checks of the deadline (fewer
     * for a large compiled pattern), and how much replacement work `REPLACE` does between two checks.
     */
    const val CHECK_INTERVAL: Int = 4096

    /** Fails with [SandboxLimitException] when a computed string of [length] chars would exceed [MAX_STRING_LENGTH]. */
    @JvmStatic
    fun checkStringLength(length: Long, what: String) {
        if (length > MAX_STRING_LENGTH) {
            throw SandboxLimitException("$what would compute a string of $length characters (the limit is $MAX_STRING_LENGTH)")
        }
    }

    /** Fails with [SandboxLimitException] when an integer of [bits] bits would exceed [MAX_INTEGER_BITS]. */
    @JvmStatic
    fun checkIntegerBits(bits: Long, what: String) {
        if (bits > MAX_INTEGER_BITS) {
            throw SandboxLimitException("$what would compute an integer of $bits bits (the limit is $MAX_INTEGER_BITS)")
        }
    }

    /** Fails with [SandboxLimitException] when a decimal with [unscaledBits] and [scale] would exceed the bounds. */
    @JvmStatic
    fun checkDecimal(unscaledBits: Long, scale: Long, what: String) {
        checkIntegerBits(unscaledBits, what)
        if (scale > MAX_DECIMAL_SCALE || scale < -MAX_DECIMAL_SCALE) {
            throw SandboxLimitException("$what would compute a decimal of scale $scale (the limit is $MAX_DECIMAL_SCALE)")
        }
    }

    /** Fails with [SandboxLimitException] when a numeric lexical form of [length] chars is too long to convert. */
    @JvmStatic
    fun checkNumericLexical(length: Int, what: String) {
        if (length > MAX_NUMERIC_LEXICAL_LENGTH) {
            throw SandboxLimitException(
                "$what would convert a numeric lexical form of $length characters (the limit is $MAX_NUMERIC_LEXICAL_LENGTH)",
            )
        }
    }

    /**
     * About how many bits `base ^ exponent` has, for an integer [base] and a non-negative [exponent] (saturating at
     * [Long.MAX_VALUE]): the estimate a power is checked with before it is computed. A base of -1, 0 or 1 gives at
     * most 1 bit whatever the exponent.
     */
    @JvmStatic
    fun powerBits(base: java.math.BigInteger, exponent: Long): Long {
        val magnitude = base.abs()
        if (magnitude <= java.math.BigInteger.ONE || exponent <= 0) return magnitude.bitLength().toLong()
        val log2 = if (magnitude.bitLength() < 1000) Math.log(magnitude.toDouble()) / Math.log(2.0) else magnitude.bitLength() - 1.0
        val bits = Math.ceil(log2 * exponent)
        return if (bits >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else bits.toLong()
    }
}

/**
 * An expression of a sandboxed query exceeded one of the [SandboxLimits]. The query fails; providers report it as a
 * query error (`RdfQueryException`) whose cause chain holds this exception.
 */
class SandboxLimitException(message: String) : RuntimeException(message)
