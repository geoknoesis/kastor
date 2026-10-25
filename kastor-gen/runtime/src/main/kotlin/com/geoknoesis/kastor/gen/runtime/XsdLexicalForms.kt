package com.geoknoesis.kastor.gen.runtime

import java.math.BigInteger

/**
 * XML Schema lexical-space checks behind [XsdLiterals.isWellFormed]: numeric types (with the value ranges of the
 * derived integer types), boolean, the date/time and Gregorian types, and durations. Leading and trailing
 * whitespace is collapsed first, as XSD does for these types.
 */
internal object XsdLexicalForms {

  const val XSD = "http://www.w3.org/2001/XMLSchema#"

  private val INTEGER = Regex("[+-]?[0-9]+")
  private val DECIMAL = Regex("[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)")
  private val FLOATING = Regex("[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?|[+-]?INF|NaN")

  private const val YEAR = "(-?(?:[1-9][0-9]{4,}|[0-9]{4}))"
  private const val TZ = "(Z|[+-][0-9]{2}:[0-9]{2})"
  private const val TIME = "([0-9]{2}):([0-9]{2}):([0-9]{2}(?:\\.[0-9]+)?)"
  private val DATE = Regex("$YEAR-([0-9]{2})-([0-9]{2})$TZ?")
  private val DATE_TIME = Regex("$YEAR-([0-9]{2})-([0-9]{2})T$TIME$TZ?")
  private val TIME_OF_DAY = Regex("$TIME$TZ?")
  private val G_YEAR = Regex("$YEAR$TZ?")
  private val G_YEAR_MONTH = Regex("$YEAR-([0-9]{2})$TZ?")
  private val G_MONTH_DAY = Regex("--([0-9]{2})-([0-9]{2})$TZ?")
  private val G_DAY = Regex("---([0-9]{2})$TZ?")
  private val G_MONTH = Regex("--([0-9]{2})$TZ?")
  private val DURATION = Regex("-?P(?=[0-9]|T[0-9])([0-9]+Y)?([0-9]+M)?([0-9]+D)?(T(?=[0-9])([0-9]+H)?([0-9]+M)?([0-9]+(\\.[0-9]+)?S)?)?")
  private val DAY_TIME_DURATION = Regex("-?P(?=[0-9]|T[0-9])([0-9]+D)?(T(?=[0-9])([0-9]+H)?([0-9]+M)?([0-9]+(\\.[0-9]+)?S)?)?")
  private val YEAR_MONTH_DURATION = Regex("-?P(?=[0-9])([0-9]+Y)?([0-9]+M)?")

  private fun range(min: Long?, max: Long?) = (min?.let(BigInteger::valueOf)) to (max?.let(BigInteger::valueOf))

  /** Value range (inclusive, `null` = unbounded) of each XSD integer datatype. */
  val INTEGER_RANGES: Map<String, Pair<BigInteger?, BigInteger?>> = mapOf(
    "integer" to range(null, null),
    "nonNegativeInteger" to range(0, null),
    "positiveInteger" to range(1, null),
    "nonPositiveInteger" to range(null, 0),
    "negativeInteger" to range(null, -1),
    "long" to range(Long.MIN_VALUE, Long.MAX_VALUE),
    "int" to range(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()),
    "short" to range(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()),
    "byte" to range(Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong()),
    "unsignedLong" to (BigInteger.ZERO to BigInteger("18446744073709551615")),
    "unsignedInt" to range(0, 4294967295L),
    "unsignedShort" to range(0, 65535),
    "unsignedByte" to range(0, 255),
  ).mapKeys { XSD + it.key }

  /**
   * Whether [lexical] is in the lexical space of [datatype] (and, for integers, its value space); `null` when
   * [datatype] is not one of the checked XSD datatypes.
   */
  fun check(lexical: String, datatype: String): Boolean? {
    val s = lexical.trim()
    INTEGER_RANGES[datatype]?.let { (min, max) -> return integerIn(s, min, max) }
    if (!datatype.startsWith(XSD)) return null
    return when (datatype.removePrefix(XSD)) {
      "boolean" -> s == "true" || s == "false" || s == "1" || s == "0"
      "decimal" -> DECIMAL.matches(s)
      "float", "double" -> FLOATING.matches(s)
      "date" -> DATE.matchEntire(s)?.let { m -> validDay(m, 1) && validTimezone(m.groupValues[4]) } ?: false
      "dateTime" -> validDateTime(s, requireTimezone = false)
      "dateTimeStamp" -> validDateTime(s, requireTimezone = true)
      "time" -> TIME_OF_DAY.matchEntire(s)?.let { m -> validTime(m, 1) && validTimezone(m.groupValues[4]) } ?: false
      "gYear" -> G_YEAR.matchEntire(s)?.let { m -> validTimezone(m.groupValues[2]) } ?: false
      "gYearMonth" -> G_YEAR_MONTH.matchEntire(s)?.let { m -> m.groupValues[2].toInt() in 1..12 && validTimezone(m.groupValues[3]) } ?: false
      "gMonthDay" -> G_MONTH_DAY.matchEntire(s)?.let { m ->
        dayInMonth(2000, m.groupValues[1].toInt(), m.groupValues[2].toInt()) && validTimezone(m.groupValues[3])
      } ?: false
      "gDay" -> G_DAY.matchEntire(s)?.let { m -> m.groupValues[1].toInt() in 1..31 && validTimezone(m.groupValues[2]) } ?: false
      "gMonth" -> G_MONTH.matchEntire(s)?.let { m -> m.groupValues[1].toInt() in 1..12 && validTimezone(m.groupValues[2]) } ?: false
      "duration" -> DURATION.matches(s)
      "dayTimeDuration" -> DAY_TIME_DURATION.matches(s)
      "yearMonthDuration" -> YEAR_MONTH_DURATION.matches(s)
      else -> null
    }
  }

  /**
   * Whether [s] (trimmed) is an integer within [min]..[max]. Linear in the length of [s]: a magnitude with more
   * digits than any finite bound is decided by its sign alone, so a hostile multi-megabyte literal is never parsed
   * into a [BigInteger].
   */
  private fun integerIn(s: String, min: BigInteger?, max: BigInteger?): Boolean {
    if (!INTEGER.matches(s)) return false
    if (min == null && max == null) return true
    val negative = s.startsWith('-')
    val digits = s.trimStart('+', '-').trimStart('0')
    if (digits.length > MAX_BOUND_DIGITS) return if (negative) min == null else max == null
    val value = if (digits.isEmpty()) BigInteger.ZERO else BigInteger(digits).let { if (negative) it.negate() else it }
    return (min == null || value >= min) && (max == null || value <= max)
  }

  /** Digits of the largest finite bound in [INTEGER_RANGES] (`unsignedLong`'s maximum has 20). */
  private const val MAX_BOUND_DIGITS = 20

  private fun validDateTime(s: String, requireTimezone: Boolean): Boolean {
    val m = DATE_TIME.matchEntire(s) ?: return false
    val timezone = m.groupValues[7]
    if (requireTimezone && timezone.isEmpty()) return false
    return validDay(m, 1) && validTime(m, 4) && validTimezone(timezone)
  }

  /** Year, month and day groups starting at [first]. */
  private fun validDay(m: MatchResult, first: Int): Boolean {
    val year = m.groupValues[first]
    val month = m.groupValues[first + 1].toInt()
    val day = m.groupValues[first + 2].toInt()
    // The leap-year rule only depends on the year modulo 400, and 10000 is a multiple of 400: the last four digits
    // decide it, so a year of any length is checked without parsing all of its digits.
    val lastDigits = year.takeLast(4).trimStart('-').toInt()
    val yearMod400 = Math.floorMod(if (year.startsWith('-')) -lastDigits else lastDigits, 400)
    return dayInMonth(yearMod400 + 2000, month, day)
  }

  private fun dayInMonth(year: Int, month: Int, day: Int): Boolean {
    if (month !in 1..12 || day < 1) return false
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val days = when (month) {
      2 -> if (leap) 29 else 28
      4, 6, 9, 11 -> 30
      else -> 31
    }
    return day <= days
  }

  /** Hour, minute and second groups starting at [first]; `24:00:00` is the end of the day. */
  private fun validTime(m: MatchResult, first: Int): Boolean {
    val hour = m.groupValues[first].toInt()
    val minute = m.groupValues[first + 1].toInt()
    val second = m.groupValues[first + 2].toBigDecimal()
    if (hour == 24) return minute == 0 && second.signum() == 0
    return hour in 0..23 && minute in 0..59 && second < 60.toBigDecimal()
  }

  fun validTimezone(timezone: String): Boolean {
    if (timezone.isEmpty() || timezone == "Z") return true
    val hours = timezone.substring(1, 3).toInt()
    val minutes = timezone.substring(4, 6).toInt()
    return minutes <= 59 && (hours < 14 || (hours == 14 && minutes == 0))
  }
}
