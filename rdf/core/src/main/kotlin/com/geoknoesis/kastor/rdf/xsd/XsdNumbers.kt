package com.geoknoesis.kastor.rdf.xsd

import com.geoknoesis.kastor.rdf.InternalKastorApi

/**
 * The value of an `xsd:float` or `xsd:double` literal before rounding: a finite number with its sign (so that `-0`
 * stays negative zero), an infinity, or NaN. [toDouble] and [toFloat] round the exact value once, to the nearest
 * value of the type, in time independent of the number of digits.
 */
@InternalKastorApi
sealed class XsdFloating {
    /** The nearest `double`. */
    abstract fun toDouble(): Double

    /** The nearest `float` (rounded once, not through a `double`). */
    abstract fun toFloat(): Float

    /** A finite value: [magnitude] (not negative) with the sign of the lexical form. */
    @InternalKastorApi
    class Finite(val negative: Boolean, val magnitude: XsdDecimal) : XsdFloating() {
        override fun toDouble(): Double = magnitude.toDouble().let { if (negative) -it else it }
        override fun toFloat(): Float = magnitude.toFloat().let { if (negative) -it else it }
    }

    /** `INF` and `+INF`. */
    @InternalKastorApi
    object PositiveInfinity : XsdFloating() {
        override fun toDouble(): Double = Double.POSITIVE_INFINITY
        override fun toFloat(): Float = Float.POSITIVE_INFINITY
    }

    /** `-INF`. */
    @InternalKastorApi
    object NegativeInfinity : XsdFloating() {
        override fun toDouble(): Double = Double.NEGATIVE_INFINITY
        override fun toFloat(): Float = Float.NEGATIVE_INFINITY
    }

    /** `NaN`. */
    @InternalKastorApi
    object NotANumber : XsdFloating() {
        override fun toDouble(): Double = Double.NaN
        override fun toFloat(): Float = Float.NaN
    }
}

/** An inclusive range of integers; a `null` end is unbounded. */
@InternalKastorApi
class XsdIntegerRange(val min: XsdDecimal?, val max: XsdDecimal?) {
    /** Whether [value] is in the range. Linear in the shorter of the value's and the bound's digits. */
    operator fun contains(value: XsdDecimal): Boolean =
        (min == null || value >= min) && (max == null || value <= max)
}

/**
 * Linear-time parsers of the XSD numeric lexical spaces (XML Schema 1.1 Part 2 §3.3.3, §3.3.4, §3.3.5, §3.4.13):
 *
 * - `xsd:integer` and its derived types: `[+-]?[0-9]+`, within the value range of the type ([integerRange]);
 * - `xsd:decimal`: `[+-]?([0-9]+(\.[0-9]*)?|\.[0-9]+)`, no exponent;
 * - `xsd:float` / `xsd:double`: the decimal syntax with an optional `[eE][+-]?[0-9]+` exponent, or `INF`, `+INF`,
 *   `-INF`, `NaN`.
 *
 * Lexical forms are matched as they are: callers that apply the `whiteSpace="collapse"` facet strip XML white space
 * first ([trimXmlWhitespace]). Every parse reads each character once; no `BigInteger` or `BigDecimal` is built.
 */
@InternalKastorApi
object XsdNumbers {
    private const val XSD = "http://www.w3.org/2001/XMLSchema#"

    private val UNSIGNED_LONG_MAX: XsdDecimal = parseInteger("18446744073709551615")!!

    private fun range(min: Long?, max: Long?) = XsdIntegerRange(min?.let(XsdDecimal::of), max?.let(XsdDecimal::of))

    private val RANGES: Map<String, XsdIntegerRange> = mapOf(
        "integer" to range(null, null),
        "nonNegativeInteger" to range(0, null),
        "positiveInteger" to range(1, null),
        "nonPositiveInteger" to range(null, 0),
        "negativeInteger" to range(null, -1),
        "long" to range(Long.MIN_VALUE, Long.MAX_VALUE),
        "int" to range(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()),
        "short" to range(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()),
        "byte" to range(Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong()),
        "unsignedLong" to XsdIntegerRange(XsdDecimal.ZERO, UNSIGNED_LONG_MAX),
        "unsignedInt" to range(0, 4_294_967_295L),
        "unsignedShort" to range(0, 65_535),
        "unsignedByte" to range(0, 255),
    ).mapKeys { XSD + it.key }

    /** The IRIs of `xsd:integer` and of every built-in type derived from it. */
    @JvmField
    val INTEGER_DATATYPES: Set<String> = RANGES.keys

    /** `xsd:decimal`, `xsd:float` and `xsd:double`. */
    const val DECIMAL: String = XSD + "decimal"
    const val FLOAT: String = XSD + "float"
    const val DOUBLE: String = XSD + "double"

    /** The value range of the integer datatype [datatypeIri], or `null` when it is not one. */
    @JvmStatic
    fun integerRange(datatypeIri: String): XsdIntegerRange? = RANGES[datatypeIri]

    /** The value of an `xsd:integer` lexical form, or `null` when [lexical] is not one. */
    @JvmStatic
    fun parseInteger(lexical: CharSequence): XsdDecimal? = scan(lexical, fraction = false, exponent = false)?.value

    /**
     * The value of [lexical] as a literal of the integer datatype [datatypeIri]: `null` when it is not an integer
     * lexical form, its value is outside the type's range (`"300"` as `xsd:unsignedByte`), or the datatype is not
     * `xsd:integer` or derived from it.
     */
    @JvmStatic
    fun parseIntegerOf(datatypeIri: String, lexical: CharSequence): XsdDecimal? {
        val range = RANGES[datatypeIri] ?: return null
        return parseInteger(lexical)?.takeIf { it in range }
    }

    /** The value of an `xsd:decimal` lexical form, or `null` when [lexical] is not one. */
    @JvmStatic
    fun parseDecimal(lexical: CharSequence): XsdDecimal? = scan(lexical, fraction = true, exponent = false)?.value

    /** The value of an `xsd:float` / `xsd:double` lexical form, or `null` when [lexical] is not one. */
    @JvmStatic
    fun parseFloating(lexical: CharSequence): XsdFloating? {
        if (lexical.length <= 4) {
            when (lexical.toString()) {
                "INF", "+INF" -> return XsdFloating.PositiveInfinity
                "-INF" -> return XsdFloating.NegativeInfinity
                "NaN" -> return XsdFloating.NotANumber
            }
        }
        val scanned = scan(lexical, fraction = true, exponent = true) ?: return null
        return XsdFloating.Finite(scanned.negative, scanned.value.let { if (it.signum < 0) it.negate() else it })
    }

    /**
     * The exact value of a numeric literal of [datatypeIri] (`xsd:decimal`, `xsd:integer` or a type derived from it,
     * within its range), or `null` when [lexical] is not valid for it or the datatype is none of those.
     */
    @JvmStatic
    fun parseExact(datatypeIri: String, lexical: CharSequence): XsdDecimal? =
        if (datatypeIri == DECIMAL) parseDecimal(lexical) else parseIntegerOf(datatypeIri, lexical)

    /** [text] without leading and trailing XML white space (`#x20 | #x9 | #xD | #xA`), the `collapse` facet's trim. */
    @JvmStatic
    fun trimXmlWhitespace(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && isXmlSpace(text[start])) start++
        while (end > start && isXmlSpace(text[end - 1])) end--
        return if (start == 0 && end == text.length) text else text.substring(start, end)
    }

    private fun isXmlSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private class Scanned(val negative: Boolean, val value: XsdDecimal)

    private fun scan(s: CharSequence, fraction: Boolean, exponent: Boolean): Scanned? {
        XsdWork.charge(s.length)
        val n = s.length
        var i = 0
        val negative = n > 0 && s[0] == '-'
        if (n > 0 && (s[0] == '+' || negative)) i++
        val intStart = i
        i = skipDigits(s, i)
        val intEnd = i
        var fracStart = i
        if (fraction && i < n && s[i] == '.') fracStart = ++i
        i = if (fracStart > intEnd) skipDigits(s, i) else i
        val fracEnd = i
        if (intEnd == intStart && fracEnd == fracStart) return null
        var shift = 0L
        if (exponent && i < n && (s[i] == 'e' || s[i] == 'E')) {
            val end = exponentEnd(s, i + 1) ?: return null
            shift = exponentValue(s, i + 1, end)
            i = end
        }
        if (i != n) return null
        return Scanned(negative, XsdDecimal.of(s, negative, intStart, intEnd, fracStart, fracEnd, shift))
    }

    private fun skipDigits(s: CharSequence, from: Int): Int {
        var i = from
        while (i < s.length && s[i] in '0'..'9') i++
        return i
    }

    /** The end of `[+-]?[0-9]+` at [from], or `null` when there is none. */
    private fun exponentEnd(s: CharSequence, from: Int): Int? {
        val start = if (from < s.length && (s[from] == '+' || s[from] == '-')) from + 1 else from
        val end = skipDigits(s, start)
        return if (end == start) null else end
    }

    /** The exponent `s[from, end)`, saturating at ±2^60 (far beyond every float and double). */
    private fun exponentValue(s: CharSequence, from: Int, end: Int): Long {
        val negative = s[from] == '-'
        var value = 0L
        for (k in (if (s[from] == '+' || negative) from + 1 else from) until end) {
            value = if (value >= XsdDecimal.EXPONENT_LIMIT / 10) XsdDecimal.EXPONENT_LIMIT else value * 10 + (s[k] - '0')
        }
        return if (negative) -value else value
    }
}

/**
 * A numeric literal's value in its XPath type class: `xs:decimal` (with the integer types), `xs:float` or `xs:double`.
 */
@InternalKastorApi
sealed class XsdNumeric {
    /** An `xsd:decimal` or integer value. */
    @InternalKastorApi
    class Exact(val value: XsdDecimal) : XsdNumeric()

    /** An `xsd:float` value. */
    @InternalKastorApi
    class Single(val value: Float) : XsdNumeric()

    /** An `xsd:double` value. */
    @InternalKastorApi
    class Approx(val value: Double) : XsdNumeric()

    /** The value as the nearest `double` (exact for floats). */
    fun toDouble(): Double = when (this) {
        is Exact -> value.toDouble()
        is Single -> value.toDouble()
        is Approx -> value
    }

    /** Whether the value is NaN. */
    val isNaN: Boolean get() = (this is Single && value.isNaN()) || (this is Approx && value.isNaN())

    @InternalKastorApi
    companion object {
        /**
         * The value of a literal of [datatypeIri] (an XSD integer type within its range, `xsd:decimal`, `xsd:float`,
         * `xsd:double`), or `null` when [lexical] is not valid for it or the datatype is not numeric.
         */
        @JvmStatic
        fun parse(datatypeIri: String, lexical: CharSequence): XsdNumeric? = when (datatypeIri) {
            XsdNumbers.DOUBLE -> XsdNumbers.parseFloating(lexical)?.let { Approx(it.toDouble()) }
            XsdNumbers.FLOAT -> XsdNumbers.parseFloating(lexical)?.let { Single(it.toFloat()) }
            else -> XsdNumbers.parseExact(datatypeIri, lexical)?.let(::Exact)
        }

        /**
         * The XPath / SPARQL numeric order (`op:numeric-less-than`, `op:numeric-equal`) after numeric type promotion
         * (XPath 2.0 B.1): two decimals compare exactly; a decimal meeting a float becomes the nearest float; any
         * operand meeting a double becomes a double. The comparison is IEEE 754's: `-0` equals `0`, and NaN is
         * comparable to nothing (`null`).
         */
        @JvmStatic
        fun compare(a: XsdNumeric, b: XsdNumeric): Int? {
            if (a is Exact && b is Exact) return a.value.compareTo(b.value)
            if (a !is Approx && b !is Approx) return ieee(a.asFloat(), b.asFloat())
            return ieee(a.toDouble(), b.toDouble())
        }

        private fun XsdNumeric.asFloat(): Float = if (this is Exact) value.toFloat() else (this as Single).value

        private fun ieee(x: Float, y: Float): Int? = if (x < y) -1 else if (x > y) 1 else if (x == y) 0 else null

        private fun ieee(x: Double, y: Double): Int? = if (x < y) -1 else if (x > y) 1 else if (x == y) 0 else null
    }
}
