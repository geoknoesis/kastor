package com.geoknoesis.kastor.rdf.xsd

/**
 * One left-to-right pass over a date/time lexical form (see [XsdTemporals]); every method reads each character at
 * most once, and the year and the fraction digits are sliced, never converted to a binary number.
 */
internal class TemporalScanner(private val s: CharSequence) {
    private var i = 0
    private var year: XsdDecimal = REFERENCE_YEAR
    private var month = 12
    private var day = -1
    private var hour = 0
    private var minute = 0
    private var second = 0
    private var fraction = ""
    private var timezone: Int? = null

    fun parse(kind: XsdTemporalKind): XsdTemporal? {
        val ok = when (kind) {
            XsdTemporalKind.DATE_TIME, XsdTemporalKind.DATE_TIME_STAMP -> date() && expect('T') && time()
            XsdTemporalKind.DATE -> date()
            XsdTemporalKind.TIME -> time()
            XsdTemporalKind.G_YEAR -> year()
            XsdTemporalKind.G_YEAR_MONTH -> year() && expect('-') && month()
            XsdTemporalKind.G_MONTH_DAY -> expect('-') && expect('-') && month() && expect('-') && day()
            XsdTemporalKind.G_DAY -> expect('-') && expect('-') && expect('-') && day()
            XsdTemporalKind.G_MONTH -> expect('-') && expect('-') && month()
        }
        if (!ok || !timezone() || i != s.length) return null
        if (kind == XsdTemporalKind.DATE_TIME_STAMP && timezone == null) return null
        // gMonthDay is checked against a leap year (--02-29 is valid); an absent day is the month's last.
        val days = XsdTemporals.daysInMonth(if (kind.hasYear) XsdTemporals.mod400(year) else 0, month)
        if (day > days) return null
        return XsdTemporal(kind, year, month, if (day < 0) days else day, hour, minute, second, fraction, timezone)
    }

    private fun date(): Boolean = year() && expect('-') && month() && expect('-') && day()

    private fun expect(c: Char): Boolean = (i < s.length && s[i] == c).also { if (it) i++ }

    /** Exactly [n] ASCII digits as a number, or -1. */
    private fun fixed(n: Int): Int {
        if (i + n > s.length) return -1
        var value = 0
        for (k in i until i + n) {
            val c = s[k]
            if (c !in '0'..'9') return -1
            value = value * 10 + (c - '0')
        }
        i += n
        return value
    }

    /** `-?([1-9][0-9]{3,}|0[0-9]{3})`. */
    private fun year(): Boolean {
        val negative = expect('-')
        val start = i
        while (i < s.length && s[i] in '0'..'9') i++
        val length = i - start
        if (length < 4 || (length > 4 && s[start] == '0')) return false
        year = XsdDecimal.of(s, negative, start, i, i, i, 0)
        return true
    }

    private fun month(): Boolean = fixed(2).let { month = it; it in 1..12 }

    private fun day(): Boolean = fixed(2).let { day = it; it in 1..31 }

    /** `hh:mm:ss(.s+)?`, hours 00 to 23, or `24:00:00(.0+)?`. */
    private fun time(): Boolean {
        hour = fixed(2)
        if (hour < 0 || !expect(':')) return false
        minute = fixed(2)
        if (minute < 0 || !expect(':')) return false
        second = fixed(2)
        if (second < 0 || !fractionDigits()) return false
        if (hour == 24) return minute == 0 && second == 0 && fraction.isEmpty()
        return hour < 24 && minute < 60 && second < 60
    }

    /** An optional `.s+`, kept without trailing zeros. */
    private fun fractionDigits(): Boolean {
        if (!expect('.')) return true
        val start = i
        while (i < s.length && s[i] in '0'..'9') i++
        if (i == start) return false
        var end = i
        while (end > start && s[end - 1] == '0') end--
        fraction = s.subSequence(start, end).toString()
        return true
    }

    /** An optional `Z` or `±hh:mm` within ±14:00. */
    private fun timezone(): Boolean {
        if (i == s.length) return true
        if (expect('Z')) return true.also { timezone = 0 }
        val sign = when {
            expect('+') -> 1
            expect('-') -> -1
            else -> return false
        }
        val hours = fixed(2)
        if (hours < 0 || !expect(':')) return false
        val minutes = fixed(2)
        if (minutes !in 0..59 || hours > 14 || (hours == 14 && minutes != 0)) return false
        timezone = sign * (hours * 60 + minutes)
        return true
    }

    private companion object {
        /** The year of the reference date 1972-12-31 that XSD fills an absent year with. */
        val REFERENCE_YEAR: XsdDecimal = XsdDecimal.of(1972)
    }
}
