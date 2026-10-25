@file:JvmName("RdfTerms")
@file:JvmMultifileClass

package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.XSD
import java.math.BigDecimal
import java.math.BigInteger
import java.time.*
import java.util.Base64

// The factory functions of the term model (`iri`, `string`, `lang`, `toLiteral`, ...). They are part of the
// same JVM facade class `RdfTerms` as the declarations of RdfTerms.kt.

/**
 * Creates a blank node with the given identifier.
 *
 * This is a convenience function that provides a clean API for creating blank nodes.
 *
 * ## Usage
 * ```kotlin
 * val bnode = bnode("b1")
 * val bnode2 = bnode("anonymous")
 * ```
 *
 * @param id The identifier for the blank node
 * @return A new [BlankNode] instance
 * @throws IllegalArgumentException if the id is blank
 * @see [BlankNode]
 */
fun bnode(id: String) = BlankNode(id)

/**
 * Creates an IRI from a string with validation.
 *
 * Prefer this helper in DSLs and call-sites to avoid stringly IRIs.
 */
fun iri(value: String): Iri = Iri.of(value)

/**
 * Creates a string literal with `xsd:string` datatype.
 *
 * This is the standard way to represent strings in RDF. The function creates
 * a plain literal equivalent to `xsd:string` in RDF 1.1.
 *
 * ## Usage
 * ```kotlin
 * val literal = string("Hello, World!")
 * val name = string("John Doe")
 * 
 * // Equivalent to:
 * val literal2 = Literal("Hello, World!")
 * ```
 *
 * @param value The string content
 * @return A new [Literal] with `xsd:string` datatype
 * @see [Literal]
 * @see [XSD.string]
 */
fun string(value: String): Literal = Literal(value, XSD.string)

/**
 * Creates a literal from a lexical value and datatype.
 *
 * Prefer this helper for explicit literal construction.
 */
fun lit(value: String, datatype: Iri = XSD.string): Literal = Literal(value, datatype)

/**
 * Creates an integer literal.
 *
 * ## Usage
 * ```kotlin
 * val age = int(25)
 * val count = int(100)
 * ```
 *
 * @param value The integer value
 * @return A new [Literal] with `xsd:integer` datatype
 */
fun int(value: Int): Literal = Literal(value.toString(), XSD.integer)

/**
 * Creates a decimal literal (xsd:decimal).
 *
 * ## Usage
 * ```kotlin
 * val height = decimal(175.5)
 * val amount = decimal(BigDecimal("123.45"))
 * ```
 *
 * @param value The decimal value
 * @return A new [Literal] with `xsd:decimal` datatype
 */
fun decimal(value: BigDecimal): Literal = Literal(value.stripTrailingZeros().toPlainString(), XSD.decimal)

/**
 * Creates a decimal literal (xsd:decimal) from a double value.
 *
 * @param value The double value
 * @return A new [Literal] with `xsd:decimal` datatype
 */
fun decimal(value: Double): Literal = decimal(BigDecimal.valueOf(value))

/**
 * Creates a decimal literal (xsd:decimal) from a float value.
 *
 * The decimal is the float's shortest decimal form, the one [Float.toString] prints: `decimal(0.1f)` is
 * `"0.1"^^xsd:decimal`. (Widening the float to a double first would give the digits of the double nearest to the
 * float, `0.10000000149011612`.)
 *
 * @param value The float value
 * @return A new [Literal] with `xsd:decimal` datatype
 * @throws NumberFormatException for NaN and the infinities, which xsd:decimal cannot represent
 */
fun decimal(value: Float): Literal = decimal(BigDecimal(value.toString()))

fun boolean(value: Boolean): Literal = Literal(value.toString(), XSD.boolean)

/**
 * Creates a language-tagged string literal.
 *
 * Language-tagged strings are useful for multilingual content where you need
 * to specify the language of the text.
 *
 * ## Usage
 * ```kotlin
 * val english = lang("Hello", "en")
 * val french = lang("Bonjour", "fr")
 * val german = lang("Hallo", "de")
 * ```
 *
 * @param value The string content
 * @param lang The language tag (e.g., "en", "fr", "de")
 * @return A new [Literal] with the specified language
 * @see [Literal]
 * @see [LangString]
 */
fun lang(value: String, lang: String): Literal = Literal(value, lang)

/**
 * Creates a directional language-tagged string literal (RDF 1.2,
 * `rdf:dirLangString`).
 *
 * ```kotlin
 * lang("\u0645\u0631\u062D\u0628\u0627", "ar", Direction.RTL)  // "..."@ar--rtl
 * lang("Hello",   "en", Direction.LTR)  // "Hello"@en--ltr
 * ```
 */
fun lang(value: String, lang: String, direction: Direction): Literal =
    LangString(value, lang, direction)



/**
 * Creates a quoted triple term from an RDF triple.
 *
 * This function enables RDF-star functionality by allowing triples to be
 * quoted and used as subjects or objects in other triples.
 *
 * ## Usage
 * ```kotlin
 * val baseTriple = triple(
 *     Iri("http://example.org/person"),
 *     Iri("http://example.org/name"),
 *     string("John Doe")
 * )
 * 
 * val quoted = quoted(baseTriple)
 * ```
 *
 * @param triple The RDF triple to quote
 * @return A new [TripleTerm] instance
 * @see [TripleTerm]
 * @see [RdfTriple]
 */
fun quoted(triple: RdfTriple) = TripleTerm(triple)


// ---- Essential Extension Functions ----

/**
 * Converts a boolean to a literal.
 */
fun Boolean.toLiteral(): Literal = if (this) TrueLiteral else FalseLiteral

// Essential numeric extensions
fun Int.toLiteral(): Literal = Literal(this.toString(), XSD.integer)
fun Long.toLiteral(): Literal = Literal(this.toString(), XSD.integer)
fun Double.toLiteral(): Literal = Literal(
    when {
        isNaN() -> "NaN"
        this == Double.POSITIVE_INFINITY -> "INF"
        this == Double.NEGATIVE_INFINITY -> "-INF"
        else -> toString()
    },
    XSD.double,
)
fun Float.toLiteral(): Literal = Literal(
    when {
        isNaN() -> "NaN"
        this == Float.POSITIVE_INFINITY -> "INF"
        this == Float.NEGATIVE_INFINITY -> "-INF"
        else -> toString()
    },
    XSD.float,
)
fun BigDecimal.toLiteral(): Literal = Literal(this.stripTrailingZeros().toPlainString(), XSD.decimal)
fun BigInteger.toLiteral(): Literal = Literal(this.toString(), XSD.integer)

// Essential date/time extensions. java.time's toString() omits zero seconds ("10:15"), pads years
// only to 4 digits for LocalDate/YearMonth, and prefixes years > 9999 with '+'; none of which are
// valid XSD lexical forms, so explicit ISO formatters are used.
fun LocalDate.toLiteral(): Literal =
    Literal(xsdYearFix(format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)), XSD.date)
fun LocalTime.toLiteral(): Literal = Literal(format(java.time.format.DateTimeFormatter.ISO_LOCAL_TIME), XSD.time)
fun LocalDateTime.toLiteral(): Literal =
    Literal(xsdYearFix(format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)), XSD.dateTime)
fun Instant.toLiteral(): Literal = Literal(xsdYearFix(toString()), XSD.dateTimeStamp)
fun Year.toLiteral(): Literal = Literal(xsdYear(value), XSD.gYear)
fun YearMonth.toLiteral(): Literal =
    Literal("${xsdYear(year)}-${monthValue.toString().padStart(2, '0')}", XSD.gYearMonth)

private fun xsdYear(year: Int): String =
    if (year < 0) "-" + (-year).toString().padStart(4, '0') else year.toString().padStart(4, '0')

/** XSD year lexical forms never carry a leading '+', which ISO formatters emit for years above 9999. */
internal fun xsdYearFix(isoText: String): String = if (isoText.startsWith('+')) isoText.substring(1) else isoText

// Essential binary extension
fun ByteArray.toLiteral(): Literal = Literal(Base64.getEncoder().encodeToString(this), XSD.base64Binary)

/**
 * Converts a string to an IRI with validation.
 * 
 * @return A validated Iri instance
 * @throws IllegalArgumentException if the string is not a valid IRI
 */
fun String.toIri(): Iri = Iri.of(this)
