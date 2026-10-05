package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
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
    val (sign, year, month, day, timezone) = match.destructured
    if (timezone.isNotEmpty() && timezone != "Z") {
      val hours = timezone.substring(1, 3).toInt()
      val minutes = timezone.substring(4, 6).toInt()
      if (hours > 14 || minutes > 59 || (hours == 14 && minutes != 0)) return null
    }
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

  private const val XSD = "http://www.w3.org/2001/XMLSchema#"

  private fun integerIn(literal: Literal, min: BigInteger?, max: BigInteger?): Boolean {
    val value = bigInteger(literal) ?: return false
    return (min == null || value >= min) && (max == null || value <= max)
  }

  /**
   * Whether [literal]'s lexical form is valid for its own datatype. XSD numeric, boolean and date datatypes are
   * checked (including the ranges of the derived integer types); other datatypes are accepted.
   */
  fun isWellFormed(literal: Literal): Boolean = when (literal.datatype.value) {
    "${XSD}boolean" -> boolean(literal) != null
    "${XSD}integer" -> integerIn(literal, null, null)
    "${XSD}nonNegativeInteger" -> integerIn(literal, BigInteger.ZERO, null)
    "${XSD}positiveInteger" -> integerIn(literal, BigInteger.ONE, null)
    "${XSD}nonPositiveInteger" -> integerIn(literal, null, BigInteger.ZERO)
    "${XSD}negativeInteger" -> integerIn(literal, null, BigInteger.ONE.negate())
    "${XSD}long" -> long(literal) != null
    "${XSD}int" -> int(literal) != null
    "${XSD}short" -> integerIn(literal, BigInteger.valueOf(Short.MIN_VALUE.toLong()), BigInteger.valueOf(Short.MAX_VALUE.toLong()))
    "${XSD}byte" -> integerIn(literal, BigInteger.valueOf(Byte.MIN_VALUE.toLong()), BigInteger.valueOf(Byte.MAX_VALUE.toLong()))
    "${XSD}unsignedLong" -> integerIn(literal, BigInteger.ZERO, BigInteger("18446744073709551615"))
    "${XSD}unsignedInt" -> integerIn(literal, BigInteger.ZERO, BigInteger.valueOf(4294967295L))
    "${XSD}unsignedShort" -> integerIn(literal, BigInteger.ZERO, BigInteger.valueOf(65535L))
    "${XSD}unsignedByte" -> integerIn(literal, BigInteger.ZERO, BigInteger.valueOf(255L))
    "${XSD}decimal" -> bigDecimal(literal) != null
    "${XSD}float" -> float(literal) != null
    "${XSD}double" -> double(literal) != null
    "${XSD}date" -> localDate(literal) != null
    else -> true
  }

  /** SHACL `sh:datatype`: [term] is a literal of exactly [datatype] whose lexical form is well formed. */
  fun hasDatatype(term: RdfTerm, datatype: Iri): Boolean =
    term is Literal && term.datatype == datatype && isWellFormed(term)

  private val NUMERIC_DATATYPES = setOf(
    "integer", "nonNegativeInteger", "positiveInteger", "nonPositiveInteger", "negativeInteger",
    "long", "int", "short", "byte", "unsignedLong", "unsignedInt", "unsignedShort", "unsignedByte",
    "decimal", "float", "double",
  ).map { XSD + it }.toSet()

  /**
   * Comparison of a numeric literal with [bound] (a decimal lexical form), for `sh:minInclusive` and friends.
   * Returns the sign of `value - bound`, or `null` when the comparison is undefined: [term] is not a well-formed
   * XSD numeric literal, or is `NaN`. Decimal bounds are promoted to the literal's floating-point type when needed.
   */
  fun compareNumeric(term: RdfTerm, bound: String): Int? {
    val literal = term as? Literal ?: return null
    if (literal.datatype.value !in NUMERIC_DATATYPES || !isWellFormed(literal)) return null
    val decimalBound = BigDecimal(bound)
    return when (literal.datatype.value) {
      "${XSD}float" -> compareFloating(float(literal)!!.toDouble(), decimalBound.toFloat().toDouble())
      "${XSD}double" -> compareFloating(double(literal)!!, decimalBound.toDouble())
      else -> BigDecimal(literal.lexical.trim()).compareTo(decimalBound).coerceIn(-1, 1)
    }
  }

  private fun compareFloating(value: Double, bound: Double): Int? = when {
    value.isNaN() -> null
    value == bound -> 0 // Numeric equality includes positive and negative zero.
    value < bound -> -1
    else -> 1
  }

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
