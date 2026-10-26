package com.geoknoesis.kastor.rdf.xsd

import com.geoknoesis.kastor.rdf.InternalKastorApi
import com.geoknoesis.kastor.rdf.LiteralValidation
import java.math.BigDecimal
import java.math.BigInteger

/**
 * An exact decimal number, the value of an `xsd:decimal` or `xsd:integer` literal, kept as its significant digits:
 * the value is `signum × 0.digits × 10^exponent`. [digits] has no leading and no trailing zero and is empty for zero
 * (whose [signum] and [exponent] are 0), so equal values have equal fields: `"1000"`, `"+1000.00"` and `"1E+3"` (as a
 * `BigDecimal`) all have the digits `"1"` and the exponent 4.
 *
 * Parsing, comparing and printing cost time linear in the number of digits. `BigInteger(String)` and
 * `BigDecimal(String)` cost time quadratic in it (about 20 s for a million digits), so this class only builds them
 * below [LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH] ([toBigDecimalOrNull], [toBigIntegerOrNull]).
 */
@InternalKastorApi
class XsdDecimal private constructor(
    /** -1, 0 or 1. */
    val signum: Int,
    /** The significant digits: no leading or trailing zero; empty for zero. */
    val digits: String,
    /** The position of the decimal point relative to the first digit of [digits]. */
    val exponent: Long,
) : Comparable<XsdDecimal> {

    /** Whether the value is a whole number. */
    val isInteger: Boolean get() = signum == 0 || digits.length <= exponent

    /** The length of [toPlainString]'s result, without building it. */
    val plainLength: Long get() = when {
        signum == 0 -> 1
        exponent <= 0 -> (if (signum < 0) 1 else 0) + 2 - exponent + digits.length
        digits.length <= exponent -> (if (signum < 0) 1 else 0) + exponent
        else -> (if (signum < 0) 1L else 0L) + digits.length + 1
    }

    override fun compareTo(other: XsdDecimal): Int {
        if (signum != other.signum) return signum.compareTo(other.signum)
        if (signum == 0) return 0
        return signum * compareMagnitude(other)
    }

    private fun compareMagnitude(other: XsdDecimal): Int {
        if (exponent != other.exponent) return exponent.compareTo(other.exponent)
        val common = minOf(digits.length, other.digits.length)
        XsdWork.charge(common)
        for (i in 0 until common) {
            if (digits[i] != other.digits[i]) return if (digits[i] < other.digits[i]) -1 else 1
        }
        return digits.length.compareTo(other.digits.length)
    }

    override fun equals(other: Any?): Boolean =
        other is XsdDecimal && signum == other.signum && exponent == other.exponent && digits == other.digits

    override fun hashCode(): Int = (31 * signum + exponent.hashCode()) * 31 + digits.hashCode()

    /** The negated value. */
    fun negate(): XsdDecimal = if (signum == 0) this else XsdDecimal(-signum, digits, exponent)

    /**
     * The decimal text of the value without an exponent: the XSD 1.1 canonical form of an `xsd:decimal` (and of an
     * `xsd:integer` when [isInteger]): no `+`, no leading zero except the one before the point of a value below one,
     * no point for a whole number, no trailing fraction zero; `"0"` for zero.
     * @throws ArithmeticException when the text would be longer than [Int.MAX_VALUE] characters
     */
    fun toPlainString(): String {
        val length = plainLength
        if (length > Int.MAX_VALUE - 8) throw ArithmeticException("a decimal of $length characters")
        if (signum == 0) return "0"
        XsdWork.charge(length.toInt())
        val out = StringBuilder(length.toInt())
        if (signum < 0) out.append('-')
        when {
            exponent <= 0 -> { out.append("0."); repeat((-exponent).toInt()) { out.append('0') }; out.append(digits) }
            digits.length <= exponent -> { out.append(digits); repeat((exponent - digits.length).toInt()) { out.append('0') } }
            else -> out.append(digits, 0, exponent.toInt()).append('.').append(digits, exponent.toInt(), digits.length)
        }
        return out.toString()
    }

    override fun toString(): String = if (plainLength <= TO_STRING_LIMIT) toPlainString() else "${if (signum < 0) "-" else ""}0.${digits.take(20)}...E$exponent"

    /**
     * The value as a [BigDecimal] (scale: the number of fraction digits), or `null` when its plain text is longer than
     * [maxLength] characters: building it costs time quadratic in that length.
     */
    fun toBigDecimalOrNull(maxLength: Int = LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH): BigDecimal? {
        if (plainLength > maxLength) return null
        if (signum == 0) return BigDecimal.ZERO
        val unscaled = BigInteger(digits).let { if (signum < 0) it.negate() else it }
        return BigDecimal(unscaled, (digits.length - exponent).toInt())
    }

    /** The value as a [BigInteger], or `null` when it is not whole or longer than [maxLength] characters. */
    fun toBigIntegerOrNull(maxLength: Int = LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH): BigInteger? =
        if (isInteger) toBigDecimalOrNull(maxLength)?.toBigIntegerExact() else null

    /** The value as a [Long], or `null` when it is not whole or out of the `Long` range. Constant time beyond 19 digits. */
    fun toLongOrNull(): Long? {
        if (signum == 0) return 0
        if (!isInteger || exponent > 19) return null
        var value = 0L // Accumulated negatively, so that Long.MIN_VALUE fits.
        for (i in 0 until exponent.toInt()) {
            val digit = if (i < digits.length) digits[i] - '0' else 0
            if (value < (Long.MIN_VALUE + digit) / 10) return null
            value = value * 10 - digit
        }
        return if (signum < 0) value else if (value == Long.MIN_VALUE) null else -value
    }

    /** The value as an [Int], or `null` when it is not whole or out of the `Int` range. */
    fun toIntOrNull(): Int? = toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    /** The `double` nearest to the value (round half to even), as the XPath cast `xs:double(xs:decimal)`. */
    fun toDouble(): Double = nearest(DOUBLE_EXPONENT_LIMIT) { it.toDouble() }

    /** The `float` nearest to the value (rounded once, not through a `double`), as `xs:float(xs:decimal)`. */
    fun toFloat(): Float = nearest(DOUBLE_EXPONENT_LIMIT) { it.toFloat() }

    /**
     * Converts through the text `0.<digits>E<exponent>`, which the JDK parses correctly rounded. Only the first
     * [STICKY_DIGITS] digits are kept, followed by a `1` when any digit was dropped: every rounding boundary of a
     * double (a midpoint between two doubles) has at most 767 significant digits, so the shortened text is on the same
     * side of each one as the value, and rounds to the same result in time independent of the digit count.
     */
    private inline fun <T> nearest(limit: Long, parse: (String) -> T): T {
        val sign = if (signum < 0) "-" else ""
        val text = when {
            signum == 0 -> "0"
            exponent > limit -> "${sign}1E${limit}"
            exponent < -limit -> "${sign}1E-${limit}"
            digits.length <= STICKY_DIGITS -> "${sign}0.${digits}E$exponent"
            else -> "${sign}0.${digits.substring(0, STICKY_DIGITS)}1E$exponent"
        }
        XsdWork.charge(text.length)
        return parse(text)
    }

    @InternalKastorApi
    companion object {
        /** Zero. */
        @JvmField
        val ZERO: XsdDecimal = XsdDecimal(0, "", 0)

        private const val STICKY_DIGITS = 800
        private const val TO_STRING_LIMIT = 64

        /** Beyond 10^±400 every double (and float) conversion is an infinity or a zero. */
        private const val DOUBLE_EXPONENT_LIMIT = 400L

        /**
         * The decimal of sign [negative] whose integer digits are `text[intStart, intEnd)` and fraction digits
         * `text[fracStart, fracEnd)` (ASCII digits, not checked), times `10^shift`. Linear in the number of digits.
         */
        internal fun of(text: CharSequence, negative: Boolean, intStart: Int, intEnd: Int, fracStart: Int, fracEnd: Int, shift: Long): XsdDecimal {
            XsdWork.charge(fracEnd - intStart)
            var first = intStart
            while (first < intEnd && text[first] == '0') first++
            var last = fracEnd
            while (last > fracStart && text[last - 1] == '0') last--
            if (first < intEnd) {
                // The fraction may be all zeros; the integer part's own trailing zeros then go too.
                val end = if (last > fracStart) intEnd else trimZeros(text, first, intEnd)
                val digits = StringBuilder(end - first + (last - fracStart)).append(text, first, end).append(text, fracStart, last)
                return build(negative, digits.toString(), (intEnd - first).toLong(), shift)
            }
            var lead = fracStart
            while (lead < last && text[lead] == '0') lead++
            if (lead == last) return ZERO
            return build(negative, text.subSequence(lead, last).toString(), -(lead - fracStart).toLong(), shift)
        }

        private fun trimZeros(text: CharSequence, start: Int, end: Int): Int {
            var e = end
            while (e > start && text[e - 1] == '0') e--
            return e
        }

        private fun build(negative: Boolean, digits: String, exponent: Long, shift: Long): XsdDecimal =
            XsdDecimal(if (negative) -1 else 1, digits, saturatedAdd(exponent, shift))

        /**
         * Exponents saturate at ±2^60 ([EXPONENT_LIMIT]): no exact arithmetic reaches them, and conversions treat
         * them as infinite. Both operands are within ±2^61, so the sum cannot overflow.
         */
        internal fun saturatedAdd(a: Long, b: Long): Long = (a + b).coerceIn(-EXPONENT_LIMIT, EXPONENT_LIMIT)

        internal const val EXPONENT_LIMIT: Long = 1L shl 60

        /** The value of [value]. */
        @JvmStatic
        fun of(value: Long): XsdDecimal {
            val text = value.toString()
            val negative = value < 0
            val start = if (negative) 1 else 0
            return of(text, negative, start, text.length, text.length, text.length, 0)
        }

        /** The value of [value] (any scale, e.g. `1E+3` or `1000.00`). Linear in its number of digits. */
        @JvmStatic
        fun of(value: BigDecimal): XsdDecimal {
            if (value.signum() == 0) return ZERO
            val unscaled = value.unscaledValue().abs().toString()
            val parsed = of(unscaled, value.signum() < 0, 0, unscaled.length, unscaled.length, unscaled.length, 0)
            return XsdDecimal(parsed.signum, parsed.digits, saturatedAdd(parsed.exponent, -value.scale().toLong()))
        }
    }
}
