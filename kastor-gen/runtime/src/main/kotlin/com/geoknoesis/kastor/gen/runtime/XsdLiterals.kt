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
 * Decoders return `null` for ill-typed literals (callers decide whether that is an error): a literal that is not
 * [well formed][isWellFormed] for its own datatype (`"300"^^xsd:unsignedByte`, `"INF"^^xsd:decimal`), a datatype
 * outside the target's XSD family (`"42"^^xsd:string` is not an integer), or a value outside the target Kotlin type
 * (`"3000000000"^^xsd:integer` is not an `Int`). Integer decoders accept the XSD integer types (`xsd:integer` and
 * its derived types); [bigDecimal] also `xsd:decimal`; [float] and [double] every XSD numeric type; [boolean]
 * `xsd:boolean`; [localDate] `xsd:date`. Lexical forms follow XML Schema rules rather than Kotlin's (`"1"`/`"0"`
 * are booleans, `INF`/`-INF`/`NaN` are floating-point values, integers are unbounded). [string] reads the lexical
 * form of any literal; [lexical] only that of a well-formed literal of a given datatype. [encode] always produces
 * a literal with the *declared* datatype and rejects values that would not be well formed for it.
 */
object XsdLiterals {

  /**
   * The lexical form of any literal: the decoder of String members without a declared datatype (or with
   * `xsd:string`). Such a member deliberately reads every literal, including language-tagged and other-typed ones,
   * whose tag or datatype is not part of the value; the generated `replaceInGraph` (through [mergeRecord]) keeps
   * the original term while the value is unchanged, so the read is lossy but a write-back is not.
   */
  fun string(literal: Literal): String = literal.lexical

  /**
   * The lexical form of [literal] when it is a [well-formed][isWellFormed] literal of exactly [datatype]; `null`
   * otherwise. The decoder of String members that declare a datatype other than `xsd:string` (`xsd:dateTime`,
   * `xsd:duration`, custom datatypes), so `"2020"^^xsd:gYear` is not read as an `xsd:dateTime` value.
   */
  fun lexical(literal: Literal, datatype: Iri): String? =
    literal.lexical.takeIf { literal.datatype == datatype && isWellFormed(literal) }

  private val INTEGER_DATATYPES = XsdLexicalForms.INTEGER_RANGES.keys
  private val DECIMAL_DATATYPES = INTEGER_DATATYPES + "http://www.w3.org/2001/XMLSchema#decimal"
  private val FLOATING_DATATYPES =
    DECIMAL_DATATYPES + "http://www.w3.org/2001/XMLSchema#float" + "http://www.w3.org/2001/XMLSchema#double"

  /** The trimmed lexical form of [this] when its datatype is in [datatypes] and it is well formed for it. */
  private fun Literal.wellFormedIn(datatypes: Set<String>): String? =
    takeIf { it.datatype.value in datatypes && isWellFormed(it) }?.lexical?.trim()

  /**
   * Longest integer or decimal lexical form, in characters, that is converted to a [BigInteger] or [BigDecimal].
   * The JDK parses those in time quadratic in the number of digits (a million digits take tens of seconds), so a
   * longer literal is treated as not representable: [bigInteger] and [bigDecimal] return `null` (the generated
   * readers then apply `MaterializationPolicy`, which throws by default) and [compareNumeric] returns `null` (a
   * generated `sh:minInclusive`-style check then reports a violation).
   */
  private const val MAX_DECODED_NUMBER_LENGTH = 10_000

  /** xsd:int and smaller types, read as [Int]: a well-formed XSD integer literal whose value fits in an `Int`. */
  fun int(literal: Literal): Int? = literal.wellFormedIn(INTEGER_DATATYPES)?.toIntOrNull()

  /**
   * [int] limited to the value range of [declared], the member's integer datatype: `"300"^^xsd:integer` is not an
   * `xsd:unsignedByte` value, so it is ill-typed for such a member instead of failing on the next write.
   */
  fun int(literal: Literal, declared: Iri): Int? = int(literal)?.takeIf { inRange(literal, declared) }

  fun long(literal: Literal): Long? = literal.wellFormedIn(INTEGER_DATATYPES)?.toLongOrNull()

  /** [long] limited to the value range of [declared] (e.g. `xsd:unsignedInt`), as for [int]. */
  fun long(literal: Literal, declared: Iri): Long? = long(literal)?.takeIf { inRange(literal, declared) }

  fun bigInteger(literal: Literal): BigInteger? =
    literal.wellFormedIn(INTEGER_DATATYPES)?.takeIf { it.length <= MAX_DECODED_NUMBER_LENGTH }?.let(::BigInteger)

  /** [bigInteger] limited to the value range of [declared] (e.g. `xsd:nonNegativeInteger`), as for [int]. */
  fun bigInteger(literal: Literal, declared: Iri): BigInteger? =
    bigInteger(literal)?.takeIf { inRange(literal, declared) }

  /** Whether the integer value of [literal] is in the value space of the integer datatype [declared]. */
  private fun inRange(literal: Literal, declared: Iri): Boolean =
    XsdLexicalForms.check(literal.lexical, declared.value) ?: true

  /** xsd:decimal: `[+-]?(digits[.digits] | .digits)`; exponents are not decimal syntax. */
  fun bigDecimal(literal: Literal): BigDecimal? =
    literal.wellFormedIn(DECIMAL_DATATYPES)?.takeIf { it.length <= MAX_DECODED_NUMBER_LENGTH }?.let(::BigDecimal)

  fun float(literal: Literal): Float? = when (val s = literal.wellFormedIn(FLOATING_DATATYPES)) {
    null -> null
    "INF", "+INF" -> Float.POSITIVE_INFINITY
    "-INF" -> Float.NEGATIVE_INFINITY
    "NaN" -> Float.NaN
    else -> s.toFloatOrNull()
  }

  fun double(literal: Literal): Double? = when (val s = literal.wellFormedIn(FLOATING_DATATYPES)) {
    null -> null
    "INF", "+INF" -> Double.POSITIVE_INFINITY
    "-INF" -> Double.NEGATIVE_INFINITY
    "NaN" -> Double.NaN
    else -> s.toDoubleOrNull()
  }

  /** xsd:boolean: `true`/`1` and `false`/`0`. Literals of other datatypes are ill-typed. */
  fun boolean(literal: Literal): Boolean? =
    literal.takeIf { it.datatype.value == "${XSD}boolean" }?.let { booleanLexical(it.lexical) }

  private val DATE = Regex("(-?)([0-9]{4,})-([0-9]{2})-([0-9]{2})(Z|[+-][0-9]{2}:[0-9]{2})?")

  fun booleanLexical(lexical: String): Boolean? = when (lexical.trim()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
  }

  /**
   * xsd:date; an optional timezone suffix is accepted and dropped (the generated `replaceInGraph` keeps the
   * original literal of an unchanged value). Years have at least four digits and may have more (`12345-06-07`,
   * without the `+` that ISO-8601 requires) or be negative (`-0044-03-15`, proleptic).
   */
  fun localDate(literal: Literal): LocalDate? {
    if (literal.datatype.value != "${XSD}date" || !isWellFormed(literal)) return null
    val match = DATE.matchEntire(literal.lexical.trim()) ?: return null
    val (sign, year, month, day) = match.destructured
    return try {
      LocalDate.of((sign + year).toInt(), month.toInt(), day.toInt())
    } catch (_: DateTimeException) {
      null // A year outside LocalDate's range.
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

  private const val XSD = XsdLexicalForms.XSD

  /**
   * Whether [literal]'s lexical form is valid for its own datatype. The XSD numeric types (including the value
   * ranges of the derived integer types), `xsd:boolean`, `xsd:date`, `xsd:dateTime`, `xsd:dateTimeStamp`,
   * `xsd:time`, the Gregorian types and the duration types are checked; other datatypes are accepted. Every
   * decoder of this object, and [encode], apply this check.
   */
  fun isWellFormed(literal: Literal): Boolean = XsdLexicalForms.check(literal.lexical, literal.datatype.value) ?: true

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
   * XSD numeric literal, is `NaN`, or has a lexical form longer than 10,000 characters. Decimal bounds are promoted to the literal's floating-point type when needed.
   */
  fun compareNumeric(term: RdfTerm, bound: String): Int? =
    compareNumeric(term, BigDecimal(bound).toPlainString(), Iri("${XSD}decimal"))

  /** Compare using both operands' datatypes: double > float > decimal/integer numeric promotion. */
  fun compareNumeric(term: RdfTerm, bound: String, boundDatatype: Iri): Int? {
    val literal = term as? Literal ?: return null
    if (literal.lexical.length > MAX_DECODED_NUMBER_LENGTH || bound.length > MAX_DECODED_NUMBER_LENGTH) return null
    if (literal.datatype.value !in NUMERIC_DATATYPES || !isWellFormed(literal)) return null
    val limit = Literal(bound, boundDatatype)
    if (boundDatatype.value !in NUMERIC_DATATYPES || !isWellFormed(limit)) return null
    fun asDouble(value: Literal): Double = when (value.datatype.value) {
      "${XSD}double" -> double(value)!!
      "${XSD}float" -> float(value)!!.toDouble() // Round in its declared type before promotion.
      else -> BigDecimal(value.lexical.trim()).toDouble()
    }
    fun asFloat(value: Literal): Double = when (value.datatype.value) {
      "${XSD}float" -> float(value)!!.toDouble()
      else -> BigDecimal(value.lexical.trim()).toFloat().toDouble()
    }
    return when {
      literal.datatype.value == "${XSD}double" || boundDatatype.value == "${XSD}double" ->
        compareFloating(asDouble(literal), asDouble(limit))
      literal.datatype.value == "${XSD}float" || boundDatatype.value == "${XSD}float" ->
        compareFloating(asFloat(literal), asFloat(limit))
      else -> BigDecimal(literal.lexical.trim()).compareTo(BigDecimal(bound.trim())).coerceIn(-1, 1)
    }
  }

  private fun compareFloating(value: Double, bound: Double): Int? = when {
    value.isNaN() || bound.isNaN() -> null
    value == bound -> 0 // Numeric equality includes positive and negative zero.
    value < bound -> -1
    else -> 1
  }

  /**
   * Encodes [value] as a literal of [datatype]. Literals (e.g. [LangString]) are returned unchanged.
   *
   * @throws IllegalArgumentException if the encoded literal is not [well formed][isWellFormed] for [datatype]:
   *   `300` as `xsd:unsignedByte`, `BigInteger.ZERO` as `xsd:positiveInteger`, `"yesterday"` as `xsd:dateTime`
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
    val literal = Literal(lexical, datatype)
    require(isWellFormed(literal)) { "Value '$value' is not a valid <${datatype.value}> literal" }
    return literal
  }
}

/**
 * Thrown when materializing a domain object from RDF fails: a nested value could not be converted,
 * a factory threw, or an immutable snapshot graph is cyclic.
 */
class MaterializationException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
