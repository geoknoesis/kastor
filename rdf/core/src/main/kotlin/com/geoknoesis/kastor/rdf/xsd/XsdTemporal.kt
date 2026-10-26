package com.geoknoesis.kastor.rdf.xsd

import com.geoknoesis.kastor.rdf.InternalKastorApi

/** The XSD date/time datatypes [XsdTemporals] parses, by IRI local name. */
@InternalKastorApi
enum class XsdTemporalKind(val localName: String) {
    DATE_TIME("dateTime"),
    DATE_TIME_STAMP("dateTimeStamp"),
    DATE("date"),
    TIME("time"),
    G_YEAR("gYear"),
    G_YEAR_MONTH("gYearMonth"),
    G_MONTH_DAY("gMonthDay"),
    G_DAY("gDay"),
    G_MONTH("gMonth"),
    ;

    /** The datatype IRI. */
    val iri: String get() = XSD_NAMESPACE + localName

    internal val hasYear: Boolean get() = this == DATE_TIME || this == DATE_TIME_STAMP || this == DATE || this == G_YEAR || this == G_YEAR_MONTH

    internal val hasTime: Boolean get() = this == DATE_TIME || this == DATE_TIME_STAMP || this == TIME

    @InternalKastorApi
    companion object {
        private val BY_IRI = entries.associateBy { it.iri }

        /** The kind of the datatype [iri], or `null` when it is not one of these. */
        @JvmStatic
        fun of(iri: String): XsdTemporalKind? = BY_IRI[iri]
    }
}

internal const val XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema#"

/**
 * The seven-property value of an XSD date/time literal (XML Schema 1.1 Part 2 §D.2), as written: absent properties
 * hold the reference values of `timeOnTimeline` (year 1972, month 12, day 31, midnight). [year] is exact and
 * unbounded; the fraction of the seconds is kept as its digits ([fraction], no trailing zero), never as a number, so
 * parsing and comparing cost time linear in the length of the lexical form. [hour] is 24 only for `24:00:00`.
 */
@InternalKastorApi
class XsdTemporal internal constructor(
    val kind: XsdTemporalKind,
    val year: XsdDecimal,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val fraction: String,
    /** The timezone offset in minutes (`Z` is 0), or `null` when the literal has none. */
    val timezoneMinutes: Int?,
)

/**
 * Linear-time parsers of the XSD 1.1 date/time lexical spaces (Part 2 §3.3.7 to §3.3.14, §3.4.28), and the order of
 * `xsd:dateTime` (with `xsd:dateTimeStamp`), `xsd:date` and `xsd:time` values (§D.2.4, SPARQL `op:dateTime-less-than`
 * and its siblings).
 *
 * Grammar: years `-?([1-9][0-9]{3,}|0[0-9]{3})` of any length; months and days checked against each other (leap
 * years by the proleptic Gregorian rule, decided by the last four digits of the year; year 0 is a leap year);
 * `hh:mm:ss(.s+)?` with `24:00:00(.0+)?` as the end of the day; no leap second (XSD 1.1 has none); timezones `Z` or
 * `±hh:mm` within ±14:00. Lexical forms are matched as they are (no white space).
 */
@InternalKastorApi
object XsdTemporals {

    /** The value of [lexical] as a literal of [kind], or `null` when it is not valid for it. */
    @JvmStatic
    fun parse(kind: XsdTemporalKind, lexical: CharSequence): XsdTemporal? {
        XsdWork.charge(lexical.length)
        return TemporalScanner(lexical).parse(kind)
    }

    /** The value of [lexical] as a literal of the date/time datatype [datatypeIri], or `null` (invalid, other type). */
    @JvmStatic
    fun parse(datatypeIri: String, lexical: CharSequence): XsdTemporal? =
        XsdTemporalKind.of(datatypeIri)?.let { parse(it, lexical) }

    /**
     * The XSD order of [a] and [b]: negative, zero or positive, or `null` when they are not comparable. Values are
     * comparable within one family: `xsd:dateTime` and `xsd:dateTimeStamp`, `xsd:date`, `xsd:time` (as dateTimes
     * on 1972-12-31, where `24:00:00` is `00:00:00`). A value without a timezone spans ±14:00 around its UTC reading
     * (§E.3.3): it is ordered against a zoned value only outside that window. Linear in the fraction digits.
     */
    @JvmStatic
    fun compare(a: XsdTemporal, b: XsdTemporal): Int? {
        if (family(a.kind) == null || family(a.kind) != family(b.kind)) return null
        val x = Moment.of(a)
        val y = Moment.of(b)
        if ((a.timezoneMinutes == null) == (b.timezoneMinutes == null)) return x.compareTo(y)
        val sign = if (a.timezoneMinutes != null) 1 else -1
        val (zoned, local) = if (sign > 0) x to y else y to x
        return when {
            zoned < local.plusMinutes(-WINDOW_MINUTES) -> -sign
            zoned > local.plusMinutes(WINDOW_MINUTES) -> sign
            else -> null
        }
    }

    /** The comparison family of [kind], or `null` when values of that kind are not ordered here. */
    @JvmStatic
    fun family(kind: XsdTemporalKind): XsdTemporalKind? = when (kind) {
        XsdTemporalKind.DATE_TIME, XsdTemporalKind.DATE_TIME_STAMP -> XsdTemporalKind.DATE_TIME
        XsdTemporalKind.DATE, XsdTemporalKind.TIME -> kind
        else -> null
    }

    private const val WINDOW_MINUTES = 14 * 60

    /** The number of days of [month] (1 to 12) in a year whose remainder modulo 400 is [yearMod400]. */
    internal fun daysInMonth(yearMod400: Int, month: Int): Int = when (month) {
        2 -> if (yearMod400 % 4 == 0 && (yearMod400 % 100 != 0 || yearMod400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** The remainder of the integer [year] modulo 400 (0 to 399); 10,000 is a multiple of 400, so four digits decide it. */
    internal fun mod400(year: XsdDecimal): Int {
        if (year.signum == 0) return 0
        val zeros = year.exponent - year.digits.length
        if (zeros >= 4) return 0
        val tail = year.digits.takeLast((4 - zeros).toInt()).toInt() * POWERS_OF_TEN[zeros.toInt()]
        return Math.floorMod(if (year.signum < 0) -tail else tail, 400)
    }

    private val POWERS_OF_TEN = intArrayOf(1, 10, 100, 1000)
}

/** A point on the UTC (or, without a timezone, local) time line, compared field by field. */
private class Moment(
    val year: XsdDecimal,
    val month: Int,
    val day: Int,
    /** Minutes since midnight. */
    val minutes: Int,
    val second: Int,
    val fraction: String,
) : Comparable<Moment> {

    override fun compareTo(other: Moment): Int {
        year.compareTo(other.year).let { if (it != 0) return it }
        if (month != other.month) return month.compareTo(other.month)
        if (day != other.day) return day.compareTo(other.day)
        if (minutes != other.minutes) return minutes.compareTo(other.minutes)
        if (second != other.second) return second.compareTo(other.second)
        XsdWork.charge(minOf(fraction.length, other.fraction.length))
        // Without trailing zeros, string order is numeric order of the fractions.
        return fraction.compareTo(other.fraction).coerceIn(-1, 1)
    }

    /** This moment moved by [delta] minutes, so that it moves by at most one day (|delta| <= 1440). */
    fun plusMinutes(delta: Int): Moment {
        val total = minutes + delta
        val shifted = Moment(year, month, day, Math.floorMod(total, MINUTES_PER_DAY), second, fraction)
        return shifted.plusDays(Math.floorDiv(total, MINUTES_PER_DAY))
    }

    /** This moment moved by [days], -1, 0 or 1. */
    private fun plusDays(days: Int): Moment = when {
        days == 0 -> this
        days > 0 && day < length(year, month) -> Moment(year, month, day + 1, minutes, second, fraction)
        days > 0 && month < 12 -> Moment(year, month + 1, 1, minutes, second, fraction)
        days > 0 -> Moment(year.plusOne(1), 1, 1, minutes, second, fraction)
        day > 1 -> Moment(year, month, day - 1, minutes, second, fraction)
        month > 1 -> Moment(year, month - 1, length(year, month - 1), minutes, second, fraction)
        else -> year.plusOne(-1).let { Moment(it, 12, 31, minutes, second, fraction) }
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        private fun length(year: XsdDecimal, month: Int) = XsdTemporals.daysInMonth(XsdTemporals.mod400(year), month)

        fun of(t: XsdTemporal): Moment {
            val endOfDay = t.hour == 24
            val local = Moment(t.year, t.month, t.day, if (endOfDay) 0 else t.hour * 60 + t.minute, t.second, t.fraction)
            // 24:00:00 is the first instant of the next day; for xsd:time the reference day does not move.
            val normal = if (endOfDay && t.kind != XsdTemporalKind.TIME) local.plusMinutes(MINUTES_PER_DAY) else local
            return t.timezoneMinutes?.let { normal.plusMinutes(-it) } ?: normal
        }
    }
}

/** [this] integer plus [delta] (1 or -1), in time linear in its digits. */
private fun XsdDecimal.plusOne(delta: Int): XsdDecimal {
    toLongOrNull()?.takeIf { it > Long.MIN_VALUE + 1 && it < Long.MAX_VALUE - 1 }?.let { return XsdDecimal.of(it + delta) }
    // |this| >= 2^63 - 2 > 1: the sign does not change and the magnitude moves by one.
    val chars = (if (signum < 0) negate() else this).toPlainString().toCharArray()
    val up = (signum > 0) == (delta > 0)
    var i = chars.size - 1
    while (i >= 0 && chars[i] == (if (up) '9' else '0')) chars[i--] = if (up) '0' else '9'
    // Only an all-nines magnitude carries out of its first digit (a magnitude this large is never all zeros).
    val text = if (i < 0) "1" + String(chars) else { chars[i] = chars[i] + (if (up) 1 else -1); String(chars) }
    return XsdDecimal.of(text, signum < 0, 0, text.length, text.length, text.length, 0)
}
