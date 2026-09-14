package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import java.math.BigDecimal
import java.math.BigInteger
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Lexical-form codecs used by generated wrappers, data-class factories, writers and DSL builders.
 *
 * Decoders return `null` for ill-typed lexical forms (callers decide whether that is an error) and follow
 * XML Schema lexical rules rather than Kotlin's (`"1"`/`"0"` are booleans, `INF`/`-INF`/`NaN` are
 * floating-point values, integers are unbounded). [encode] always produces a literal with the
 * *declared* datatype so values round-trip without losing their type.
 */
object XsdLiterals {

  fun string(literal: Literal): String = literal.lexical

  private val INTEGER = Regex("[+-]?[0-9]+")
  private val DECIMAL = Regex("[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)")
  private val DATE = Regex("(-?)([0-9]{4,})-([0-9]{2})-([0-9]{2})(Z|[+-][0-9]{2}:[0-9]{2})?")

  private fun integerLexical(literal: Literal): String? = literal.lexical.trim().takeIf { INTEGER.matches(it) }

  /** xsd:int and smaller types; `[+-]?digits` (a leading `+` is valid XSD), range-checked. */
  fun int(literal: Literal): Int? = integerLexical(literal)?.toIntOrNull()

  fun long(literal: Literal): Long? = integerLexical(literal)?.toLongOrNull()

  fun bigInteger(literal: Literal): BigInteger? = integerLexical(literal)?.let(::BigInteger)

  /** xsd:decimal: `[+-]?(digits[.digits] | .digits)`; exponents are not decimal syntax. */
  fun bigDecimal(literal: Literal): BigDecimal? =
    literal.lexical.trim().takeIf { DECIMAL.matches(it) }?.let(::BigDecimal)

  fun float(literal: Literal): Float? = when (val s = literal.lexical.trim()) {
    "INF", "+INF" -> Float.POSITIVE_INFINITY
    "-INF" -> Float.NEGATIVE_INFINITY
    "NaN" -> Float.NaN
    else -> if (s.any { it.isLetter() && it != 'e' && it != 'E' }) null else s.toFloatOrNull()
  }

  fun double(literal: Literal): Double? = when (val s = literal.lexical.trim()) {
    "INF", "+INF" -> Double.POSITIVE_INFINITY
    "-INF" -> Double.NEGATIVE_INFINITY
    "NaN" -> Double.NaN
    else -> if (s.any { it.isLetter() && it != 'e' && it != 'E' }) null else s.toDoubleOrNull()
  }

  /** xsd:boolean: `true`/`1` and `false`/`0`. */
  fun boolean(literal: Literal): Boolean? = booleanLexical(literal.lexical)

  fun booleanLexical(lexical: String): Boolean? = when (lexical.trim()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
  }

  /**
   * xsd:date; an optional timezone suffix is accepted and dropped. Years have at least four digits and may have
   * more (`12345-06-07`, without the `+` that ISO-8601 requires) or be negative (`-0044-03-15`, proleptic).
   */
  fun localDate(literal: Literal): LocalDate? {
    val match = DATE.matchEntire(literal.lexical.trim()) ?: return null
    val (sign, year, month, day) = match.destructured
    return try {
      LocalDate.of((sign + year).toInt(), month.toInt(), day.toInt())
    } catch (_: DateTimeException) {
      null
    } catch (_: NumberFormatException) {
      null
    }
  }

  /** Lexical xsd:date for [date]: `yyyy-MM-dd` with at least four year digits and no `+` for years above 9999. */
  private fun dateLexical(date: LocalDate): String {
    val year = date.year
    val digits = (if (year < 0) -year else year).toString().padStart(4, '0')
    return (if (year < 0) "-" else "") + digits + "-" +
      date.monthValue.toString().padStart(2, '0') + "-" + date.dayOfMonth.toString().padStart(2, '0')
  }

  /** rdf:langString values are exposed as the core [LangString] term. */
  fun langString(literal: Literal): LangString? = literal as? LangString

  /**
   * Encodes [value] as a literal of [datatype]. Literals (e.g. [LangString]) are returned unchanged.
   *
   * @throws IllegalArgumentException if the lexical form is invalid for [datatype] (e.g. xsd:boolean)
   */
  fun encode(value: Any, datatype: Iri): Literal {
    if (value is Literal) return value
    val lexical = when (value) {
      is Float -> when {
        value.isNaN() -> "NaN"
        value == Float.POSITIVE_INFINITY -> "INF"
        value == Float.NEGATIVE_INFINITY -> "-INF"
        else -> value.toString()
      }
      is Double -> when {
        value.isNaN() -> "NaN"
        value == Double.POSITIVE_INFINITY -> "INF"
        value == Double.NEGATIVE_INFINITY -> "-INF"
        else -> value.toString()
      }
      is BigDecimal -> value.toPlainString()
      is LocalDate -> dateLexical(value)
      else -> value.toString()
    }
    return Literal(lexical, datatype)
  }
}

/**
 * Thrown when materializing a domain object from RDF fails: a nested value could not be converted,
 * a factory threw, or an immutable snapshot graph is cyclic.
 */
class MaterializationException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
