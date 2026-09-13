package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.math.BigDecimal
import java.math.BigInteger
import java.time.DateTimeException
import java.time.LocalDate

/*
 * Datatype-aware literal ordering for sh:minInclusive / sh:maxInclusive / sh:minExclusive / sh:maxExclusive /
 * sh:lessThan / sh:lessThanOrEquals, following the SPARQL 1.1 operator mapping for `<`:
 *  - numerics (xsd:integer and all derived types incl. unsigned, xsd:decimal, xsd:float, xsd:double) with
 *    numeric type promotion;
 *  - xsd:string (code point order);
 *  - xsd:boolean (false < true);
 *  - xsd:dateTime / xsd:dateTimeStamp, xsd:date and xsd:time, each only within its own family, using the XSD
 *    partial order for values with and without timezone (±14:00 indeterminacy window).
 * Anything else (language-tagged strings, ill-formed lexical forms, NaN, mixed families) is incomparable,
 * which the SHACL comparison components treat as a violation.
 */

private fun xsd(local: String) = Iri(XSD.namespace + local)

private val integerRanges: Map<Iri, Pair<BigInteger?, BigInteger?>> = run {
    fun r(min: String?, max: String?) = min?.let(::BigInteger) to max?.let(::BigInteger)
    mapOf(
        XSD.integer to r(null, null),
        XSD.long to r("-9223372036854775808", "9223372036854775807"),
        XSD.int to r("-2147483648", "2147483647"),
        XSD.short to r("-32768", "32767"),
        XSD.byte to r("-128", "127"),
        XSD.nonNegativeInteger to r("0", null),
        XSD.positiveInteger to r("1", null),
        XSD.nonPositiveInteger to r(null, "0"),
        XSD.negativeInteger to r(null, "-1"),
        XSD.unsignedLong to r("0", "18446744073709551615"),
        XSD.unsignedInt to r("0", "4294967295"),
        XSD.unsignedShort to r("0", "65535"),
        XSD.unsignedByte to r("0", "255"),
    )
}

private val integerLexical = Regex("[+-]?\\d+")
private val decimalLexical = Regex("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)")
private val floatLexical = Regex("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([Ee][+-]?\\d+)?|[+-]?INF|NaN")

private sealed interface NumericValue {
    data class Exact(val value: BigDecimal) : NumericValue
    data class Approx(val value: Double) : NumericValue
}

private fun parseInteger(lex: String, dt: Iri): BigInteger? {
    val range = integerRanges[dt] ?: return null
    if (!integerLexical.matches(lex)) return null
    val v = BigInteger(lex)
    if (range.first != null && v < range.first) return null
    if (range.second != null && v > range.second) return null
    return v
}

private fun parseApprox(lex: String): Double? {
    if (!floatLexical.matches(lex)) return null
    return when (lex) {
        "INF", "+INF" -> Double.POSITIVE_INFINITY
        "-INF" -> Double.NEGATIVE_INFINITY
        "NaN" -> Double.NaN
        else -> lex.toDouble()
    }
}

private fun parseNumeric(lit: Literal): NumericValue? {
    val dt = lit.datatype
    val lex = lit.lexical
    return when {
        dt in integerRanges -> parseInteger(lex, dt)?.let { NumericValue.Exact(BigDecimal(it)) }
        dt == XSD.decimal -> if (decimalLexical.matches(lex)) NumericValue.Exact(BigDecimal(lex)) else null
        dt == XSD.double -> parseApprox(lex)?.let { NumericValue.Approx(it) }
        dt == XSD.float -> parseApprox(lex)?.let { NumericValue.Approx(it.toFloat().toDouble()) }
        else -> null
    }
}

private fun compareNumeric(a: NumericValue, b: NumericValue): Int? {
    if (a is NumericValue.Exact && b is NumericValue.Exact) return a.value.compareTo(b.value)
    val x = if (a is NumericValue.Exact) a.value.toDouble() else (a as NumericValue.Approx).value
    val y = if (b is NumericValue.Exact) b.value.toDouble() else (b as NumericValue.Approx).value
    if (x.isNaN() || y.isNaN()) return null
    return x.compareTo(y)
}

// --- XSD date/time -------------------------------------------------------------------------------------------

private const val TZ = "(Z|[+-](?:(?:0\\d|1[0-3]):[0-5]\\d|14:00))"
private const val YEAR = "(-?(?:[1-9]\\d{3,}|0\\d{3}))"
private const val MONTH = "(0[1-9]|1[0-2])"
private const val DAY = "(0[1-9]|[12]\\d|3[01])"
private const val TIME = "(?:([01]\\d|2[0-3]):([0-5]\\d):([0-5]\\d(?:\\.\\d+)?)|(24):00:(00(?:\\.0+)?))"

private val dateTimeRx = Regex("$YEAR-$MONTH-${DAY}T$TIME$TZ?")
private val dateRx = Regex("$YEAR-$MONTH-$DAY$TZ?")
private val timeRx = Regex("$TIME$TZ?")

/** Seconds on the XSD time line (local when [tzMinutes] is null, else normalized to UTC). */
private data class XsdMoment(val seconds: BigDecimal, val tzMinutes: Int?)

private enum class TemporalFamily { DATE_TIME, DATE, TIME }

private val SECONDS_PER_DAY = BigDecimal(86_400)

private fun tzMinutes(tz: String?): Int? =
    when {
        tz.isNullOrEmpty() -> null
        tz == "Z" -> 0
        else -> {
            val sign = if (tz[0] == '-') -1 else 1
            sign * (tz.substring(1, 3).toInt() * 60 + tz.substring(4, 6).toInt())
        }
    }

private fun epochDay(year: String, month: String, day: String): Long? {
    val y = year.toIntOrNull() ?: return null
    return try { LocalDate.of(y, month.toInt(), day.toInt()).toEpochDay() } catch (_: DateTimeException) { null }
}

private fun timeSeconds(h: String?, m: String?, s: String?, h24: String?, s24: String?): BigDecimal =
    if (h24 != null) SECONDS_PER_DAY
    else BigDecimal(h!!.toInt() * 3600 + m!!.toInt() * 60).add(BigDecimal(s!!))

private fun moment(local: BigDecimal, tz: String?): XsdMoment {
    val offset = tzMinutes(tz)
    return XsdMoment(if (offset == null) local else local.subtract(BigDecimal(offset * 60)), offset)
}

private fun groupOrNull(m: MatchResult, i: Int): String? = m.groups[i]?.value

private fun parseTemporal(lit: Literal): Pair<TemporalFamily, XsdMoment>? {
    val lex = lit.lexical
    return when (lit.datatype) {
        XSD.dateTime, XSD.dateTimeStamp -> {
            val m = dateTimeRx.matchEntire(lex) ?: return null
            val g = { i: Int -> groupOrNull(m, i) }
            val tz = g(9)
            if (lit.datatype == XSD.dateTimeStamp && tz == null) return null
            val day = epochDay(g(1)!!, g(2)!!, g(3)!!) ?: return null
            val local = BigDecimal(day).multiply(SECONDS_PER_DAY).add(timeSeconds(g(4), g(5), g(6), g(7), g(8)))
            TemporalFamily.DATE_TIME to moment(local, tz)
        }
        XSD.date -> {
            val m = dateRx.matchEntire(lex) ?: return null
            val day = epochDay(m.groupValues[1], m.groupValues[2], m.groupValues[3]) ?: return null
            TemporalFamily.DATE to moment(BigDecimal(day).multiply(SECONDS_PER_DAY), groupOrNull(m, 4))
        }
        XSD.time -> {
            val m = timeRx.matchEntire(lex) ?: return null
            val g = { i: Int -> groupOrNull(m, i) }
            // XSD compares times as dateTimes on the reference date 1972-12-31.
            val ref = BigDecimal(LocalDate.of(1972, 12, 31).toEpochDay()).multiply(SECONDS_PER_DAY)
            TemporalFamily.TIME to moment(ref.add(timeSeconds(g(1), g(2), g(3), g(4), g(5))), g(6))
        }
        else -> null
    }
}

private val FOURTEEN_HOURS = BigDecimal(14 * 3600)

private fun compareMoments(a: XsdMoment, b: XsdMoment): Int? {
    if ((a.tzMinutes == null) == (b.tzMinutes == null)) return a.seconds.compareTo(b.seconds)
    // XSD 1.1 §E.3.3: a timezone-less value spans [value - 14:00, value + 14:00] on the UTC time line.
    val (zoned, local, sign) = if (a.tzMinutes != null) Triple(a, b, 1) else Triple(b, a, -1)
    return when {
        zoned.seconds < local.seconds.subtract(FOURTEEN_HOURS) -> -1 * sign
        zoned.seconds > local.seconds.add(FOURTEEN_HOURS) -> 1 * sign
        else -> null
    }
}

// --- public API ---------------------------------------------------------------------------------------------------

/** Three-way comparison per the SPARQL `<` operator mapping, or `null` when the terms are incomparable. */
internal fun tryCompareLiterals(a: RdfTerm, b: RdfTerm): Int? {
    val la = a as? Literal ?: return null
    val lb = b as? Literal ?: return null
    if (la is LangString || lb is LangString) return null
    parseNumeric(la)?.let { na -> return parseNumeric(lb)?.let { nb -> compareNumeric(na, nb) } }
    if (la.datatype == XSD.string) {
        return if (lb.datatype == XSD.string) la.lexical.codePoints().toArray().let { x ->
            lb.lexical.codePoints().toArray().let { y -> java.util.Arrays.compare(x, y) }
        } else null
    }
    if (la.datatype == XSD.boolean) {
        if (lb.datatype != XSD.boolean) return null
        val x = parseBoolean(la.lexical) ?: return null
        val y = parseBoolean(lb.lexical) ?: return null
        return x.compareTo(y)
    }
    val ta = parseTemporal(la) ?: return null
    val tb = parseTemporal(lb) ?: return null
    if (ta.first != tb.first) return null
    return compareMoments(ta.second, tb.second)
}

private fun parseBoolean(lex: String): Boolean? =
    when (lex) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

internal fun literalLess(a: RdfTerm, b: RdfTerm): Boolean =
    tryCompareLiterals(a, b)?.let { it < 0 } ?: false

internal fun literalLessOrEqual(a: RdfTerm, b: RdfTerm): Boolean =
    tryCompareLiterals(a, b)?.let { it <= 0 } ?: false

internal fun satisfiesMinInclusive(value: RdfTerm, bound: RdfTerm): Boolean {
    val c = tryCompareLiterals(value, bound) ?: return false
    return c >= 0
}

internal fun satisfiesMaxInclusive(value: RdfTerm, bound: RdfTerm): Boolean {
    val c = tryCompareLiterals(value, bound) ?: return false
    return c <= 0
}

internal fun satisfiesMinExclusive(value: RdfTerm, bound: RdfTerm): Boolean {
    val c = tryCompareLiterals(value, bound) ?: return false
    return c > 0
}

internal fun satisfiesMaxExclusive(value: RdfTerm, bound: RdfTerm): Boolean {
    val c = tryCompareLiterals(value, bound) ?: return false
    return c < 0
}

// --- lexical validity -----------------------------------------------------------------------------------------------

private val booleanLexical = Regex("true|false|1|0")
private val gYearRx = Regex("$YEAR$TZ?")
private val gYearMonthRx = Regex("$YEAR-$MONTH$TZ?")
private val gMonthDayRx = Regex("--$MONTH-$DAY$TZ?")
private val gDayRx = Regex("---$DAY$TZ?")
private val gMonthRx = Regex("--$MONTH$TZ?")
private val durationRx = Regex("-?P(?=\\d|T\\d)(\\d+Y)?(\\d+M)?(\\d+D)?(T(?=\\d)(\\d+H)?(\\d+M)?(\\d+(\\.\\d+)?S)?)?")
private val dayTimeDurationRx = Regex("-?P(?=\\d|T\\d)(\\d+D)?(T(?=\\d)(\\d+H)?(\\d+M)?(\\d+(\\.\\d+)?S)?)?")
private val yearMonthDurationRx = Regex("-?P(?=\\d)(\\d+Y)?(\\d+M)?")
private val languageRx = Regex("[a-zA-Z]{1,8}(-[a-zA-Z0-9]{1,8})*")
private val hexBinaryRx = Regex("([0-9a-fA-F]{2})*")
private val base64Rx = Regex(
    "((([A-Za-z0-9+/] ?){4})*(([A-Za-z0-9+/] ?){3}[A-Za-z0-9+/]|([A-Za-z0-9+/] ?){2}[AEIMQUYcgkosw048] ?=|[A-Za-z0-9+/] ?[AQgw] ?= ?=))?",
)
private val normalizedStringForbidden = Regex("[\\r\\n\\t]")

/**
 * XSD lexical validity for typed literals used by `sh:datatype`: an ill-formed literal fails even when the RDF
 * term carries the requested datatype IRI. Covers the commonly used XSD built-ins (string types, boolean,
 * decimal, integer and all derived integer types with range checks, float/double, date/time types, Gregorian
 * types, durations, language, hexBinary, base64Binary). Datatypes without a lexical-space check here
 * (e.g. xsd:anyURI, whose lexical space is effectively unrestricted, or non-XSD datatypes) are accepted.
 */
internal fun typedLiteralLexicallyValidForShaclDatatype(lit: TypedLiteral): Boolean = literalLexicallyValid(lit)

internal fun literalLexicallyValid(lit: Literal): Boolean {
    val lex = lit.lexical
    val dt = lit.datatype
    return when {
        dt == XSD.string -> true
        dt == XSD.boolean -> booleanLexical.matches(lex)
        dt in integerRanges -> parseInteger(lex, dt) != null
        dt == XSD.decimal -> decimalLexical.matches(lex)
        dt == XSD.double || dt == XSD.float -> floatLexical.matches(lex)
        dt == XSD.dateTime || dt == XSD.dateTimeStamp || dt == XSD.date || dt == XSD.time -> parseTemporal(lit) != null
        dt == XSD.gYear -> gYearRx.matches(lex)
        dt == XSD.gYearMonth -> gYearMonthRx.matches(lex)
        dt == xsd("gMonthDay") -> gMonthDayRx.matchEntire(lex)?.let { m ->
            epochDay("2000", m.groupValues[1], m.groupValues[2]) != null // leap year admits --02-29
        } ?: false
        dt == xsd("gDay") -> gDayRx.matches(lex)
        dt == xsd("gMonth") -> gMonthRx.matches(lex)
        dt == xsd("duration") -> durationRx.matches(lex)
        dt == xsd("dayTimeDuration") -> dayTimeDurationRx.matches(lex)
        dt == xsd("yearMonthDuration") -> yearMonthDurationRx.matches(lex)
        dt == XSD.language -> languageRx.matches(lex)
        dt == XSD.hexBinary -> hexBinaryRx.matches(lex)
        dt == xsd("base64Binary") -> base64Rx.matches(lex)
        dt == xsd("normalizedString") -> !normalizedStringForbidden.containsMatchIn(lex)
        dt == xsd("token") -> !normalizedStringForbidden.containsMatchIn(lex) && !lex.startsWith(" ") && !lex.endsWith(" ") && !lex.contains("  ")
        else -> true
    }
}
