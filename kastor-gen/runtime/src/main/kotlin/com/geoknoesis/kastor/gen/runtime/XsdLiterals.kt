package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.LiteralValidation
import com.geoknoesis.kastor.rdf.xsd.XsdDecimal
import com.geoknoesis.kastor.rdf.xsd.XsdLexical
import com.geoknoesis.kastor.rdf.xsd.XsdNumbers
import com.geoknoesis.kastor.rdf.xsd.XsdNumeric
import com.geoknoesis.kastor.rdf.xsd.XsdTemporalKind
import com.geoknoesis.kastor.rdf.xsd.XsdTemporals
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
 *
 * Every lexical form is parsed by the shared linear-time XSD parser of `:rdf:core`: a literal of millions of digits
 * (or of fractional-second digits) costs time linear in its length. `BigInteger` / `BigDecimal` values are built only
 * for lexical forms of at most [LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH] characters, since the JDK builds them
 * in quadratic time.
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

  private val INTEGER_DATATYPES: Set<String> = XsdNumbers.INTEGER_DATATYPES
  private val DECIMAL_DATATYPES = INTEGER_DATATYPES + XsdNumbers.DECIMAL
  private val FLOATING_DATATYPES = DECIMAL_DATATYPES + XsdNumbers.FLOAT + XsdNumbers.DOUBLE

  /** The lexical form of [this] with XSD white space collapsed at both ends, as the numeric and temporal types do. */
  private val Literal.collapsed: String get() = XsdNumbers.trimXmlWhitespace(lexical)

  /** The exact value of [this] when its datatype is in [datatypes] and it is well formed for it (range included). */
  private fun Literal.exactIn(datatypes: Set<String>): XsdDecimal? =
    if (datatype.value in datatypes) XsdNumbers.parseExact(datatype.value, collapsed) else null

  /** The trimmed lexical form of [this] when its datatype is in [datatypes] and it is well formed for it. */
  private fun Literal.wellFormedIn(datatypes: Set<String>): String? =
    takeIf { it.datatype.value in datatypes && isWellFormed(it) }?.collapsed

  /** xsd:int and smaller types, read as [Int]: a well-formed XSD integer literal whose value fits in an `Int`. */
  fun int(literal: Literal): Int? = literal.exactIn(INTEGER_DATATYPES)?.toIntOrNull()

  /**
   * [int] limited to the value range of [declared], the member's integer datatype: `"300"^^xsd:integer` is not an
   * `xsd:unsignedByte` value, so it is ill-typed for such a member instead of failing on the next write.
   */
  fun int(literal: Literal, declared: Iri): Int? = int(literal)?.takeIf { inRange(literal, declared) }

  fun long(literal: Literal): Long? = literal.exactIn(INTEGER_DATATYPES)?.toLongOrNull()

  /** [long] limited to the value range of [declared] (e.g. `xsd:unsignedInt`), as for [int]. */
  fun long(literal: Literal, declared: Iri): Long? = long(literal)?.takeIf { inRange(literal, declared) }

  /**
   * The value of a well-formed integer literal, or `null` when its value needs more than
   * [LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH] digits (the generated readers then apply `MaterializationPolicy`,
   * which throws by default).
   */
  fun bigInteger(literal: Literal): BigInteger? = literal.exactIn(INTEGER_DATATYPES)?.toBigIntegerOrNull()

  /** [bigInteger] limited to the value range of [declared] (e.g. `xsd:nonNegativeInteger`), as for [int]. */
  fun bigInteger(literal: Literal, declared: Iri): BigInteger? =
    bigInteger(literal)?.takeIf { inRange(literal, declared) }

  /** Whether the integer value of [literal] is in the value space of the integer datatype [declared]. */
  private fun inRange(literal: Literal, declared: Iri): Boolean {
    val range = XsdNumbers.integerRange(declared.value) ?: return true
    return XsdNumbers.parseInteger(literal.collapsed)?.let { it in range } ?: false
  }

  /**
   * xsd:decimal: `[+-]?(digits[.digits] | .digits)`; exponents are not decimal syntax. The scale is the lexical
   * form's (`"1.50"` is `1.50`); `null` beyond [LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH] characters.
   */
  fun bigDecimal(literal: Literal): BigDecimal? =
    literal.wellFormedIn(DECIMAL_DATATYPES)?.takeIf { it.length <= LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH }?.let(::BigDecimal)

  fun float(literal: Literal): Float? =
    literal.wellFormedIn(FLOATING_DATATYPES)?.let { XsdNumbers.parseFloating(it)?.toFloat() }

  fun double(literal: Literal): Double? =
    literal.wellFormedIn(FLOATING_DATATYPES)?.let { XsdNumbers.parseFloating(it)?.toDouble() }

  /** xsd:boolean: `true`/`1` and `false`/`0`. Literals of other datatypes are ill-typed. */
  fun boolean(literal: Literal): Boolean? =
    literal.takeIf { it.datatype.value == "${XSD}boolean" }?.let { booleanLexical(it.lexical) }

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
    if (literal.datatype.value != "${XSD}date") return null
    val date = XsdTemporals.parse(XsdTemporalKind.DATE, literal.collapsed) ?: return null
    val year = date.year.toIntOrNull() ?: return null // A year outside LocalDate's range.
    return try {
      LocalDate.of(year, date.month, date.day)
    } catch (_: DateTimeException) {
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

  /**
   * Whether [literal]'s lexical form is valid for its own datatype. The XSD numeric types (including the value
   * ranges of the derived integer types), `xsd:boolean`, `xsd:date`, `xsd:dateTime`, `xsd:dateTimeStamp`,
   * `xsd:time`, the Gregorian types and the duration types are checked; other datatypes are accepted. Every
   * decoder of this object, and [encode], apply this check.
   */
  fun isWellFormed(literal: Literal): Boolean = XsdLexical.isValid(literal.datatype.value, literal.collapsed) ?: true

  /** SHACL `sh:datatype`: [term] is a literal of exactly [datatype] whose lexical form is well formed. */
  fun hasDatatype(term: RdfTerm, datatype: Iri): Boolean =
    term is Literal && term.datatype == datatype && isWellFormed(term)

  /**
   * Comparison of a numeric literal with [bound] (a decimal number, an exponent allowed), for `sh:minInclusive` and
   * friends. Returns the sign of `value - bound`, or `null` when the comparison is undefined: [term] is not a
   * well-formed XSD numeric literal, or is `NaN`, or [bound] is not a number. Linear in the lexical forms' lengths.
   */
  fun compareNumeric(term: RdfTerm, bound: String): Int? {
    val limit = XsdNumbers.parseFloating(XsdNumbers.trimXmlWhitespace(bound)) as? com.geoknoesis.kastor.rdf.xsd.XsdFloating.Finite
      ?: return null
    val exact = XsdNumeric.Exact(if (limit.negative) limit.magnitude.negate() else limit.magnitude)
    return numeric(term)?.let { XsdNumeric.compare(it, exact) }
  }

  /**
   * Compare using both operands' datatypes, with XPath numeric promotion: double > float > decimal/integer. `null`
   * when either is not a well-formed XSD numeric literal or the comparison involves NaN.
   */
  fun compareNumeric(term: RdfTerm, bound: String, boundDatatype: Iri): Int? {
    val value = numeric(term) ?: return null
    val limit = XsdNumeric.parse(boundDatatype.value, XsdNumbers.trimXmlWhitespace(bound)) ?: return null
    return XsdNumeric.compare(value, limit)
  }

  private fun numeric(term: RdfTerm): XsdNumeric? =
    (term as? Literal)?.takeIf { it !is LangString }?.let { XsdNumeric.parse(it.datatype.value, it.collapsed) }

  /**
   * Encodes [value] as a literal of [datatype]. Literals (e.g. [LangString]) are returned unchanged.
   *
   * A `BigDecimal` is written without an exponent, keeping a positive scale: `1E+3` as `"1000"`, `10.50` as `"10.50"`.
   *
   * @throws IllegalArgumentException if the encoded literal is not [well formed][isWellFormed] for [datatype]:
   *   `300` as `xsd:unsignedByte`, `BigInteger.ZERO` as `xsd:positiveInteger`, `"yesterday"` as `xsd:dateTime`;
   *   or a `BigDecimal` whose decimal form is longer than [LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH] characters
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
      // Never an exponent form, which xsd:decimal does not allow: 1E+3 is "1000"; a positive scale is kept ("10.50").
      is BigDecimal -> XsdDecimal.canonicalLexical(value).let { if (value.scale() > 0) value.toPlainString() else it }
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
