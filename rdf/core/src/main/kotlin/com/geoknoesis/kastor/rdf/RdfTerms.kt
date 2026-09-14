@file:JvmName("RdfTerms")

package com.geoknoesis.kastor.rdf

import java.math.BigDecimal
import java.math.BigInteger
import java.time.*
import java.util.Base64
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.vocab.RDF

/**
 * Core RDF term model providing idiomatic Kotlin interfaces for RDF data structures.
 *
 * This module defines the fundamental building blocks of the RDF data model using a sealed type hierarchy.
 * The design follows Kotlin best practices with value classes for performance and sealed interfaces for
 * type safety.
 *
 * ## Key Features
 * - **Type Safety**: Sealed interfaces ensure exhaustive pattern matching
 * - **Performance**: Value classes for IRI and Blank Node with minimal allocation overhead
 * - **Validation**: Centralized literal creation with proper XSD datatype validation
 * - **Simplicity**: Clean, focused API without overwhelming complexity
 *
 * ## Usage Examples
 * ```kotlin
 * // Create basic RDF terms
 * val iri = Iri("http://example.org/resource")
 * val bnode = bnode("b1")
 * val literal = string("Hello, World!")
 * 
 * // Create typed literals
 * val number = 42.toLiteral()  // xsd:integer
 * val date = Literal(LocalDate.now())  // xsd:date
 * val boolean = true.toLiteral()  // xsd:boolean
 * 
 * // Create triples
 * val triple = triple(iri, Iri("http://example.org/name"), literal)
 * ```
 *
 * @see [RdfCore] for the main RDF API interfaces
 * @see [QueryTerms] for SPARQL variable definitions
 */

// ---- RDF Term Type System ----

/**
 * The base interface for any value that can appear in an RDF triple.
 *
 * RdfTerm is the most general interface in the RDF type hierarchy. It includes:
 * - [RdfResource]: Values that can be subjects (IRIs, Blank Nodes, Triple Terms)
 * - [Literal]: Values that can only be objects (strings, numbers, dates, etc.)
 *
 * This interface is sealed, meaning all implementations are known at compile time,
 * enabling exhaustive when expressions and type-safe operations.
 *
 * ## Usage
 * ```kotlin
 * fun processTerm(term: RdfTerm) = when (term) {
 *     is RdfResource -> "Can be a subject: $term"
 *     is Literal -> "Can only be an object: $term"
 * }
 * ```
 */
sealed interface RdfTerm

/**
 * A sub-interface of [RdfTerm] for values that can appear as subjects in RDF triples.
 *
 * In RDF 1.2 only IRIs and blank nodes can occupy the subject position:
 * - [Iri]: Internationalised Resource Identifiers
 * - [BlankNode]: Anonymous resources
 *
 * Triple terms ([TripleTerm]) and literals ([Literal]) are *not* resources and
 * therefore cannot be subjects. Triple terms are object-position only; to
 * attach metadata to a triple, use the [`rdf:reifies`][RDF.reifies] pattern
 * with an IRI or blank node as the reifier.
 *
 * ## Usage
 * ```kotlin
 * fun createTriple(subject: RdfResource, predicate: Iri, obj: RdfTerm): RdfTriple {
 *     return RdfTriple(subject, predicate, obj)
 * }
 * ```
 */
sealed interface RdfResource : RdfTerm

/**
 * Represents an Internationalized Resource Identifier (IRI).
 *
 * IRIs are the primary way to identify resources in RDF. They can appear as:
 * - Subjects in RDF triples
 * - Predicates in RDF triples  
 * - Objects in RDF triples
 *
 * This class uses Kotlin's `@JvmInline` value class for efficient memory usage
 * while maintaining type safety and stable equality semantics.
 *
 * IRIs are validated according to RFC 3987. Invalid IRIs will throw [IllegalArgumentException].
 *
 * ## Usage
 * ```kotlin
 * val iri = Iri("http://example.org/resource")
 * val predicate = Iri("http://example.org/name")
 * 
 * // Or use the companion factory (recommended for explicit validation)
 * val iri2 = Iri.of("http://example.org/resource")
 * 
 * // Or use the extension function
 * val iri3 = "http://example.org/resource".toIri()
 * ```
 *
 * @property value The string representation of the IRI
 * @note toString() returns the `<iri>` form suitable for SPARQL/N-Triples serialization.
 * @throws IllegalArgumentException if the IRI is invalid according to RFC 3987
 * @see [RdfResource]
 */
@JvmInline
value class Iri(val value: String) : RdfResource {
    init {
        require(isValidIri(value)) { 
            "Invalid IRI: '$value'. IRIs must be valid according to RFC 3987." 
        }
    }
    
    companion object {
        /**
         * Creates an IRI with validation (explicit factory method).
         * 
         * @param value The IRI string to validate and create
         * @return A validated Iri instance
         * @throws IllegalArgumentException if the IRI is invalid
         */
        fun of(value: String): Iri = Iri(value)
        
        /**
         * Creates an IRI without validation (use only for trusted sources).
         * 
         * This method still validates the IRI, but is intended for use when:
         * - The IRI comes from a trusted source (e.g., vocabulary constants)
         * - The IRI has already been validated
         * - Code generation produces known-valid IRIs
         * 
         * Note: Due to value class limitations, validation still occurs.
         * This method serves as documentation that the IRI is from a trusted source.
         * 
         * @param value The IRI string (assumed to be valid)
         * @return An Iri instance (still validated)
         */
        internal fun unsafe(value: String): Iri = Iri(value)
    }
    
    override fun toString(): String = "<$value>"
}

/**
 * Validates an absolute IRI with an allocation-free, single-pass scanner modelled on RFC 3987.
 *
 * **Checks:**
 * - `scheme ":"` prefix with an ASCII scheme (`ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )`) and a
 *   non-empty remainder (relative references are rejected)
 * - No whitespace, control characters, or the RFC 3987-excluded delimiters `< > " { } | \ ^ ``
 *   (these enable SPARQL/Turtle injection when an IRI is interpolated into `<...>`)
 * - Percent-encodings are `%` followed by two hex digits; at most one `#`
 * - When an authority (`//...`) is present: an optional userinfo, a host that is either a closed
 *   IP literal (`[...]`) or a reg-name without brackets, and an all-digit port
 *
 * Non-ASCII characters are accepted anywhere after the scheme (IRIs are a superset of URIs); IDN
 * and IP-address semantics are not validated. Square brackets are tolerated outside the authority
 * for compatibility with real-world data.
 */
private fun isValidIri(value: String): Boolean {
    val length = value.length
    if (length == 0 || !value[0].isAsciiLetter()) return false
    var i = 1
    while (i < length) {
        val c = value[i]
        if (c == ':') break
        if (!(c.isAsciiLetter() || c in '0'..'9' || c == '+' || c == '-' || c == '.')) return false
        i++
    }
    if (i >= length - 1) return false // no scheme separator, or nothing after it
    var pos = i + 1
    if (pos + 1 < length && value[pos] == '/' && value[pos + 1] == '/') {
        pos = scanAuthority(value, pos + 2)
        if (pos < 0) return false
    }
    var sawFragment = false
    while (pos < length) {
        val c = value[pos]
        when {
            c.isForbiddenIriChar() -> return false
            c == '%' -> {
                if (pos + 2 >= length || !value[pos + 1].isHexDigit() || !value[pos + 2].isHexDigit()) return false
                pos += 2
            }
            c == '#' -> {
                if (sawFragment) return false
                sawFragment = true
            }
        }
        pos++
    }
    return true
}

/** Scans `[userinfo "@"] host [":" port]` starting at [start]; returns the end index or -1 if malformed. */
private fun scanAuthority(value: String, start: Int): Int {
    var end = start
    while (end < value.length && value[end] != '/' && value[end] != '?' && value[end] != '#') end++
    var hostStart = start
    for (k in start until end) if (value[k] == '@') hostStart = k + 1
    for (k in start until hostStart) {
        val c = value[k]
        if (c == '[' || c == ']' || c.isForbiddenIriChar()) return -1
        if (c == '%' && !validPercent(value, k, hostStart)) return -1
    }
    var k = hostStart
    if (k < end && value[k] == '[') {
        val close = value.indexOf(']', k + 1)
        if (close < 0 || close >= end || close == k + 1) return -1
        for (j in k + 1 until close) {
            val c = value[j]
            if (!(c.isAsciiLetter() || c in '0'..'9' || c in ":.-_~!$&'()*+,;=")) return -1
        }
        k = close + 1
    } else {
        while (k < end && value[k] != ':') {
            val c = value[k]
            if (c == '[' || c == ']' || c.isForbiddenIriChar()) return -1
            if (c == '%' && !validPercent(value, k, end)) return -1
            k++
        }
    }
    if (k < end) {
        if (value[k] != ':') return -1
        for (j in k + 1 until end) if (value[j] !in '0'..'9') return -1
    }
    return end
}

private fun validPercent(value: String, index: Int, limit: Int): Boolean =
    index + 2 < limit && value[index + 1].isHexDigit() && value[index + 2].isHexDigit()

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun Char.isForbiddenIriChar(): Boolean = when (this) {
    '<', '>', '"', '{', '}', '|', '\\', '^', '`' -> true
    else -> isWhitespace() || isISOControl()
}

/**
 * Represents a Blank Node (anonymous resource) in RDF.
 *
 * Blank nodes are used to represent resources that don't have a specific IRI.
 * They can appear as:
 * - Subjects in RDF triples
 * - Objects in RDF triples
 *
 * This class uses Kotlin's `@JvmInline` value class for efficient memory usage.
 * The ID must be non-blank and is used for internal identification only.
 *
 * ## Usage
 * ```kotlin
 * val bnode = BlankNode("b1")
 * 
 * // Or use the top-level function
 * val bnode2 = bnode("b1")
 * 
 * // Blank node IDs must not be empty
 * // BlankNode("") // This will throw IllegalArgumentException
 * ```
 *
 * @property id The internal identifier for the blank node
 * @throws IllegalArgumentException if the id is blank
 * @see [RdfResource]
 */
@JvmInline
value class BlankNode(val id: String) : RdfResource {
    init { require(id.isNotBlank()) { "Blank node id must not be blank" } }
    override fun toString(): String = "_:$id"
}

/**
 * Represents a literal value in RDF.
 *
 * Literals are used to represent data values such as strings, numbers, dates, etc.
 * They can only appear as objects in RDF triples, never as subjects.
 *
 * The literal consists of:
 * - [lexical]: The string representation of the value
 * - [datatype]: The IRI that identifies the data type (e.g., xsd:string, xsd:integer)
 *
 * ## Usage
 * ```kotlin
 * // Create typed literals
 * val stringLiteral = Literal("Hello", XSD.string)
 * val numberLiteral = Literal("42", XSD.integer)
 * val dateLiteral = Literal("2025-01-15", XSD.date)
 * 
 * // Or use the top-level functions
 * val string2 = string("Hello")
 * val number2 = 42.toLiteral()
 * val date2 = Literal(LocalDate.now())
 * ```
 *
 * @property lexical The string representation of the literal value
 * @property datatype The IRI identifying the data type
 * @see [RdfTerm]
 */
sealed interface Literal : RdfTerm {
    val lexical: String
    val datatype: Iri

    companion object {
        /**
         * Creates a literal from a lexical value and a datatype IRI.
         * This is the recommended factory for creating any literal.
         *
         * The lexical form is always preserved: RDF terms with different lexical forms are
         * different terms even when they denote the same value, and ill-typed literals are
         * legal RDF. For `xsd:boolean` the singletons [TrueLiteral] / [FalseLiteral] are
         * returned only for the exact lexical forms `"true"` / `"false"`; use
         * [booleanValue] to interpret `"1"` / `"0"`.
         *
         * ## Examples
         * ```kotlin
         * // Plain string literals (default)
         * Literal("Hello, World!")  // "Hello, World!"^^xsd:string
         * Literal("John Doe")       // "John Doe"^^xsd:string
         *
         * // Boolean literals
         * Literal("true", XSD.boolean)  // Returns TrueLiteral
         * Literal("1", XSD.boolean)     // "1"^^xsd:boolean (TypedLiteral)
         * Literal("false", XSD.boolean) // Returns FalseLiteral
         * Literal("maybe", XSD.boolean) // "maybe"^^xsd:boolean (ill-typed, still a valid RDF term)
         * 
         * // Typed literals
         * Literal("42", XSD.integer)      // "42"^^xsd:integer
         * Literal("2025-01-15", XSD.date) // "2025-01-15"^^xsd:date
         * ```
         *
         * @param lexical The string representation of the value
         * @param datatype The IRI identifying the data type (defaults to xsd:string)
         * @return A new [Literal] with the specified datatype
         * @see [TypedLiteral]
         * @see [TrueLiteral]
         * @see [FalseLiteral]
         */
        operator fun invoke(lexical: String, datatype: Iri = XSD.string): Literal {
            if (datatype == XSD.boolean) {
                if (lexical == "true") return TrueLiteral
                if (lexical == "false") return FalseLiteral
            }
            return TypedLiteral(lexical, datatype)
        }

        /**
         * Creates a literal with automatic type inference from Kotlin primitive types.
         * This provides a convenient way to create typed literals without explicitly
         * specifying the datatype.
         *
         * ## Examples
         * ```kotlin
         * // Integer literals
         * 42.toLiteral()           // "42"^^xsd:integer
         * Literal(123456789L)   // "123456789"^^xsd:integer
         * 
         * // Floating-point literals
         * Literal(3.14)         // "3.14"^^xsd:double
         * Literal(3.14f)         // "3.14"^^xsd:float
         * 
         * // Boolean literals
         * true.toLiteral()          // Returns TrueLiteral
         * false.toLiteral()         // Returns FalseLiteral
         * 
         * // Date/Time literals
         * Literal(LocalDate.of(2025, 1, 15))  // "2025-01-15"^^xsd:date
         * Literal(LocalDateTime.now())        // "2025-01-15T14:30:45"^^xsd:dateTime
         * Literal(Instant.now())              // "2025-01-15T14:30:45Z"^^xsd:dateTimeStamp
         * 
         * // String literals (explicit)
         * Literal("Hello")       // "Hello"^^xsd:string
         * ```
         *
         * @param value The value to convert to a literal
         * @return A new [Literal] with the appropriate datatype inferred from the value type
         * @throws IllegalArgumentException if the value type is not supported
         * @see [TypedLiteral]
         * @see [TrueLiteral]
         * @see [FalseLiteral]
         */
        operator fun invoke(value: Int): Literal = Literal(value.toString(), XSD.integer)
        operator fun invoke(value: Long): Literal = Literal(value.toString(), XSD.integer)
        operator fun invoke(value: Double): Literal = value.toLiteral()
        operator fun invoke(value: Float): Literal = value.toLiteral()
        operator fun invoke(value: Boolean): Literal = if (value) TrueLiteral else FalseLiteral
        operator fun invoke(value: BigDecimal): Literal = Literal(value.stripTrailingZeros().toPlainString(), XSD.decimal)
        operator fun invoke(value: BigInteger): Literal = Literal(value.toString(), XSD.integer)
        operator fun invoke(value: LocalDate): Literal = value.toLiteral()
        operator fun invoke(value: LocalTime): Literal = value.toLiteral()
        operator fun invoke(value: LocalDateTime): Literal = value.toLiteral()
        // OffsetDateTime always carries a zone offset, so xsd:dateTimeStamp (which
        // mandates a timezone) is the precise type, consistent with Instant below.
        operator fun invoke(value: OffsetDateTime): Literal =
            Literal(xsdYearFix(value.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)), XSD.dateTimeStamp)
        operator fun invoke(value: Instant): Literal = value.toLiteral()
        operator fun invoke(value: Year): Literal = value.toLiteral()
        operator fun invoke(value: YearMonth): Literal = value.toLiteral()
        operator fun invoke(value: ByteArray): Literal = Literal(Base64.getEncoder().encodeToString(value), XSD.base64Binary)

        /**
         * Creates a language-tagged string literal.
         *
         * Language-tagged strings are used when the literal has a specific language
         * (e.g., "Hello"@en, "Bonjour"@fr). The datatype is automatically set to
         * `rdf:langString` as per the RDF specification.
         *
         * ## Examples
         * ```kotlin
         * Literal("Hello", "en")  // "Hello"@en
         * Literal("Bonjour", "fr") // "Bonjour"@fr
         * Literal("Hallo", "de")   // "Hallo"@de
         * ```
         *
         * @param lexical The string content
         * @param lang The language tag (e.g., "en", "fr", "de")
         * @return A new [LangString] with the specified language
         * @see [LangString]
         */
        operator fun invoke(lexical: String, lang: String): Literal = LangString(lexical, lang)

        /**
         * Creates a directional language-tagged string literal (RDF 1.2).
         *
         * Equivalent to `LangString(lexical, lang, direction)`. The resulting
         * literal has datatype `rdf:dirLangString` and serialises as
         * `"text"@lang--ltr` or `"text"@lang--rtl`.
         */
        operator fun invoke(lexical: String, lang: String, direction: Direction): Literal =
            LangString(lexical, lang, direction)

    }
}

/**
 * Base text direction for an RDF 1.2 directional language string.
 *
 * RDF 1.2 introduces a new datatype, [`rdf:dirLangString`][RDF.dirLangString],
 * that pairs a language tag with an explicit base direction so that
 * bi-directional content (Arabic, Hebrew, mixed scripts, ...) round-trips
 * correctly through serialisation.
 *
 * The Turtle 1.2 lexical form for a directional literal is
 * `"text"@lang--ltr` or `"text"@lang--rtl`.
 */
enum class Direction(val token: String) {
    LTR("ltr"),
    RTL("rtl");

    companion object {
        /** Parse a direction token (`"ltr"` / `"rtl"`, case-insensitive). Returns null on miss. */
        fun fromToken(token: String?): Direction? = when (token?.lowercase()) {
            null, "" -> null
            "ltr" -> LTR
            "rtl" -> RTL
            else -> null
        }
    }
}

/**
 * Represents a language-tagged string literal in RDF.
 *
 * Language-tagged strings are used when the literal has a specific language
 * (e.g., "Hello"@en, "Bonjour"@fr). When [direction] is null the datatype is
 * `rdf:langString` (RDF 1.1 / 1.2 plain language string). When [direction] is
 * set, the datatype is `rdf:dirLangString` (RDF 1.2 directional language
 * string) and the literal serialises as `"text"@lang--ltr` / `--rtl`.
 *
 * ## Usage
 * ```kotlin
 * val english = LangString("Hello", "en")                       // "Hello"@en
 * val arabic  = LangString("\u0645\u0631\u062D\u0628\u0627", "ar", Direction.RTL) // "..."@ar--rtl
 *
 * // Or use the Literal factory
 * val german  = Literal("Hallo", "de")                          // "Hallo"@de
 * val hebrew  = Literal("\u05E9\u05DC\u05D5\u05DD", "he", Direction.RTL)
 *
 * // Or use the top-level function
 * val spanish = lang("Hola", "es")                              // "Hola"@es
 * ```
 *
 * Language tags must match the BCP 47 shape used by Turtle/SPARQL
 * (`[a-zA-Z]{1,8}(-[a-zA-Z0-9]{1,8})*`) and are normalised to lower case, since
 * RDF compares language tags case-insensitively.
 *
 * @property lexical The string content of the literal
 * @property lang The language tag, normalised to lower case (e.g., "en", "en-us")
 * @property direction Optional base direction (RDF 1.2). null means the
 *   literal is a plain `rdf:langString`.
 * @throws IllegalArgumentException if [lang] is not a well-formed language tag
 * @see [Literal]
 * @see [RDF.langString]
 * @see [RDF.dirLangString]
 */
class LangString(
    override val lexical: String,
    lang: String,
    val direction: Direction? = null,
) : Literal {
    val lang: String = normalizeLanguageTag(lang)

    override val datatype: Iri
        get() = if (direction == null) RDF.langString else RDF.dirLangString

    operator fun component1(): String = lexical
    operator fun component2(): String = lang
    operator fun component3(): Direction? = direction

    fun copy(lexical: String = this.lexical, lang: String = this.lang, direction: Direction? = this.direction): LangString =
        LangString(lexical, lang, direction)

    override fun equals(other: Any?): Boolean =
        other is LangString && lexical == other.lexical && lang == other.lang && direction == other.direction

    override fun hashCode(): Int = (lexical.hashCode() * 31 + lang.hashCode()) * 31 + (direction?.hashCode() ?: 0)

    override fun toString(): String = when (direction) {
        null -> "\"${escapeLiteralLexical(lexical)}\"@$lang"
        else -> "\"${escapeLiteralLexical(lexical)}\"@$lang--${direction.token}"
    }
}

/**
 * Validates a language tag against the Turtle/SPARQL `LANGTAG` shape and returns it in lower case.
 *
 * @throws IllegalArgumentException if the tag is empty or malformed
 */
fun normalizeLanguageTag(tag: String): String {
    var segment = 0
    var first = true
    for (c in tag) {
        if (c == '-') {
            require(segment > 0) { "Invalid language tag: '$tag'" }
            segment = 0
            first = false
            continue
        }
        val ok = c in 'a'..'z' || c in 'A'..'Z' || (!first && c in '0'..'9')
        require(ok && ++segment <= 8) { "Invalid language tag: '$tag'" }
    }
    require(segment > 0) { "Invalid language tag: '$tag'" }
    return tag.lowercase(java.util.Locale.ROOT)
}

/** Escapes a lexical form for use inside a double-quoted N-Triples/Turtle/SPARQL string. */
internal fun escapeLiteralLexical(value: String): String {
    if (value.none { it == '"' || it == '\\' || it == '\n' || it == '\r' || it == '\t' || it == '\b' || it == '\u000C' }) {
        return value
    }
    return buildString(value.length + 8) {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> append(c)
            }
        }
    }
}

/**
 * Represents a datatyped literal in RDF.
 *
 * Datatyped literals have a specific data type that defines how the lexical
 * form should be interpreted. Common examples include:
 * - `xsd:string`: Plain text strings
 * - `xsd:integer`: Integer numbers
 * - `xsd:decimal`: Decimal numbers
 * - `xsd:dateTime`: Date and time values
 *
 * ## Usage
 * ```kotlin
 * val stringLiteral = TypedLiteral("Hello", XSD.string)
 * val numberLiteral = TypedLiteral("42", XSD.integer)
 * val dateLiteral = TypedLiteral("2025-01-15", XSD.date)
 * 
 * // Or use the Literal factory
 * val string2 = Literal("Hello", XSD.string)
 * val number2 = Literal("42", XSD.integer)
 * 
 * // Or use extension functions
 * val number3 = 42.toLiteral()
 * val date2 = LocalDate.now().toLiteral()
 * ```
 *
 * @property lexical The string representation of the value
 * @property datatype The IRI identifying the data type
 * @see [Literal]
 */
data class TypedLiteral(override val lexical: String, override val datatype: Iri) : Literal {
    // Equal to TrueLiteral/FalseLiteral for "true"/"false"^^xsd:boolean, so equality does not depend on
    // which constructor produced the term.
    override fun equals(other: Any?): Boolean = datatypedLiteralEquals(this, other)
    override fun hashCode(): Int = datatypedLiteralHash(lexical, datatype)
    override fun toString(): String = "\"${escapeLiteralLexical(lexical)}\"^^${datatype}"
}

private fun datatypedLiteralEquals(self: Literal, other: Any?): Boolean =
    other is Literal && other !is LangString && self.lexical == other.lexical && self.datatype == other.datatype

private fun datatypedLiteralHash(lexical: String, datatype: Iri): Int = lexical.hashCode() * 31 + datatype.hashCode()

/**
 * Interprets an `xsd:boolean` literal's value: `"true"`/`"1"` -> true, `"false"`/`"0"` -> false.
 * Returns null for other datatypes and for ill-typed lexical forms.
 */
fun Literal.booleanValue(): Boolean? {
    if (datatype != XSD.boolean) return null
    return when (lexical) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}

/**
 * Represents the boolean literal `true`.
 *
 * This is implemented as a singleton object to avoid unnecessary allocations
 * and ensure canonical representation. The datatype is automatically set to
 * `xsd:boolean`.
 *
 * ## Usage
 * ```kotlin
 * val trueLiteral = TrueLiteral
 * 
 * // Or use the Literal factory
 * val true2 = Literal("true", XSD.boolean)
 * 
 * // Or use the extension function
 * val true3 = true.toLiteral()
 * ```
 *
 * @see [Literal]
 * @see [XSD.boolean]
 */
object TrueLiteral : Literal {
    override val lexical: String = "true"
    override val datatype: Iri = XSD.boolean
    override fun equals(other: Any?): Boolean = datatypedLiteralEquals(this, other)
    override fun hashCode(): Int = datatypedLiteralHash(lexical, datatype)
    override fun toString(): String = "\"true\"^^${XSD.boolean}"
}

/**
 * Represents the boolean literal `false`.
 *
 * This is implemented as a singleton object to avoid unnecessary allocations
 * and ensure canonical representation. The datatype is automatically set to
 * `xsd:boolean`.
 *
 * ## Usage
 * ```kotlin
 * val falseLiteral = FalseLiteral
 * 
 * // Or use the Literal factory
 * val false2 = Literal("false", XSD.boolean)
 * 
 * // Or use the extension function
 * val false3 = false.toLiteral()
 * ```
 *
 * @see [Literal]
 * @see [XSD.boolean]
 */
object FalseLiteral : Literal {
    override val lexical: String = "false"
    override val datatype: Iri = XSD.boolean
    override fun equals(other: Any?): Boolean = datatypedLiteralEquals(this, other)
    override fun hashCode(): Int = datatypedLiteralHash(lexical, datatype)
    override fun toString(): String = "\"false\"^^${XSD.boolean}"
}

/**
 * Represents an RDF triple (statement) with type-safe components.
 *
 * An RDF triple consists of three components:
 * - [subject]: The resource being described (must be a [RdfResource])
 * - [predicate]: The property/relationship (must be an [Iri])
 * - [obj]: The value of the property (can be any [RdfTerm])
 *
 * The type system enforces that subjects can only be resources, while objects
 * can be any RDF term including literals.
 *
 * ## Usage
 * ```kotlin
 * val subject = Iri("http://example.org/person")
 * val predicate = Iri("http://example.org/name")
 * val obj = string("John Doe")
 * 
 * val triple = RdfTriple(subject, predicate, obj)
 * 
 * // Or use the top-level function
 * val triple2 = triple(subject, predicate, obj)
 * 
 * // Access components
 * println(triple.subject)    // The person being described
 * println(triple.predicate)  // The property (name)
 * println(triple.obj)        // The value (John Doe)
 * ```
 *
 * @property subject The resource being described
 * @property predicate The property/relationship
 * @property obj The value of the property
 * @see [RdfResource]
 * @see [Iri]
 * @see [RdfTerm]
 */
data class RdfTriple(val subject: RdfResource, val predicate: Iri, val obj: RdfTerm) {
    override fun toString(): String = "$subject $predicate $obj ."
}

/**
 * An RDF 1.2 triple term (`<<( s p o )>>`).
 *
 * In RDF 1.2 a triple term is a *kind of RDF term* that names a triple without
 * asserting it. It can appear only as the **object** of another triple - it is
 * not a resource, so it cannot occupy subject or predicate position. To attach
 * metadata to a triple, use the [`rdf:reifies`][RDF.reifies] pattern: an IRI or
 * blank node (the *reifier*) names the triple term, and metadata properties hang
 * off the reifier.
 *
 * ## Usage
 * ```kotlin
 * val claim = triple(
 *     Iri("http://example.org/alice"),
 *     Iri("http://example.org/age"),
 *     30.toLiteral()
 * )
 *
 * val tt = TripleTerm(claim)            // <<( :alice :age 30 )>>
 * val tt2 = quoted(claim)               // same thing, helper form
 *
 * // RDF 1.2 reifier pattern: an IRI/bnode names the triple term, metadata
 * // hangs off the reifier.
 * val reifier = bnode("r1")
 * val reifies   = triple(reifier, RDF.reifies, tt)
 * val certainty = triple(reifier, Iri("http://example.org/certainty"), 0.9.toLiteral())
 * ```
 *
 * @property triple The RDF triple this triple term names. The triple is *not*
 *   asserted; it is merely referenced.
 * @see [RDF.reifies]
 * @see [RdfTriple]
 * @see [RdfTerm]
 */
data class TripleTerm(val triple: RdfTriple) : RdfTerm {
    override fun toString(): String = "<<( ${triple.subject} ${triple.predicate} ${triple.obj} )>>"
}


// ---- Core Factory Functions ----



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
 * @param value The float value
 * @return A new [Literal] with `xsd:decimal` datatype
 */
fun decimal(value: Float): Literal = decimal(BigDecimal.valueOf(value.toDouble()))

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















// ---- Community Interoperability Aliases ----

/**
 * Type alias for [Iri] to support community naming conventions.
 *
 * Many RDF libraries and documentation use "IRI" (all caps) instead of "Iri".
 * This alias provides compatibility with such conventions.
 *
 * ## Usage
 * ```kotlin
 * val iri = Iri("http://example.org/resource")
 * 
 * // Equivalent to:
 * val iri2 = IRI("http://example.org/resource")
 * ```
 *
 * @see [Iri]
 */
typealias IRI = Iri

/**
 * Type alias for [BlankNode] to support community naming conventions.
 *
 * Many RDF libraries and documentation use "BNode" instead of "BlankNode".
 * This alias provides compatibility with such conventions.
 *
 * ## Usage
 * ```kotlin
 * val bnode = BNode("b1")
 * 
 * // Equivalent to:
 * val bnode2 = BlankNode("b1")
 * ```
 *
 * @see [BlankNode]
 */
typealias BNode = BlankNode

/**
 * Interface for RDF graphs - collections of RDF triples.
 * 
 * A graph is a set of RDF triples. This interface provides operations for
 * managing triples within a graph.
 */
interface RdfGraph {
    /** Match a pattern. Indexed providers override this to avoid scanning the graph. */
    fun find(subject: RdfResource? = null, predicate: Iri? = null, obj: RdfTerm? = null): List<RdfTriple> =
        getTriplesSequence().filter {
            (subject == null || it.subject == subject) && (predicate == null || it.predicate == predicate) &&
                (obj == null || it.obj == obj)
        }.toList()

    /**
     * Checks if a triple exists in the graph.
     * 
     * **Performance:** O(1) for most implementations (hash-based lookup).
     * 
     * @param triple The triple to check
     * @return true if the triple exists, false otherwise
     */
    fun hasTriple(triple: RdfTriple): Boolean
    
    /**
     * Gets all triples in the graph.
     * 
     * **Performance:** O(n) where n is the number of triples.
     * For large graphs, consider using [getTriplesSequence] for lazy evaluation
     * or SPARQL queries with filters instead.
     * 
     * @return List of all triples (defensive copy)
     */
    fun getTriples(): List<RdfTriple>
    
    /**
     * Gets all triples in the graph as a lazy sequence.
     * 
     * **Performance:** O(1) to create the sequence, O(n) to iterate.
     * This method provides lazy evaluation, avoiding intermediate list creation.
     * Use this for large graphs where you don't need all triples at once.
     * 
     * **Example:**
     * ```kotlin
     * // Process triples lazily without loading all into memory
     * graph.getTriplesSequence()
     *     .filter { it.predicate == FOAF.name }
     *     .take(100)
     *     .forEach { println(it) }
     * ```
     * 
     * @return Sequence of all triples (lazy evaluation)
     */
    fun getTriplesSequence(): Sequence<RdfTriple> = getTriples().asSequence()
    
    /**
     * Get the number of triples in this graph.
     */
    fun size(): Int
}

/**
 * Mutable RDF graph operations.
 * 
 * Provides both read and write operations for RDF graphs.
 */
/** Batch size of the default streaming `addTriples`/`removeTriples` overloads of [MutableRdfGraph]. */
internal const val STREAMING_WRITE_BATCH_SIZE: Int = 10_000

interface MutableRdfGraph : RdfGraph {
    /**
     * Adds a single triple to the graph.
     * 
     * **Performance:** O(1) for most implementations.
     * For adding multiple triples, prefer [addTriples] for better performance.
     * 
     * @param triple The triple to add
     */
    fun addTriple(triple: RdfTriple)

    /**
     * Adds multiple triples to the graph.
     * 
     * **Performance:** This method is optimized for batch operations.
     * For adding multiple triples, prefer this over multiple [addTriple] calls.
     * 
     * @param triples The triples to add
     */
    fun addTriples(triples: Collection<RdfTriple>)

    /**
     * Remove a triple from this graph.
     * @return true if the triple was removed, false if it wasn't present
     */
    fun removeTriple(triple: RdfTriple): Boolean

    /**
     * Remove multiple triples from this graph.
     * @return true if any triples were removed
     */
    fun removeTriples(triples: Collection<RdfTriple>): Boolean

    /**
     * Adds triples from an [Iterable] that need not be a [Collection].
     *
     * Collections are delegated to the `Collection` overload; other iterables are streamed like a
     * [Sequence]. Transactional providers override this to write in a single transaction.
     */
    fun addTriples(triples: Iterable<RdfTriple>) {
        if (triples is Collection<RdfTriple>) addTriples(triples) else addTriples(triples.asSequence())
    }

    /**
     * Adds triples from a [Sequence] while streaming: the sequence is consumed once and never fully
     * materialised. The default writes bounded batches through the `Collection` overload; transactional
     * providers (Jena, RDF4J) override it to stream into a single transaction.
     */
    fun addTriples(triples: Sequence<RdfTriple>) {
        triples.chunked(STREAMING_WRITE_BATCH_SIZE).forEach { batch -> addTriples(batch) }
    }

    /**
     * Removes triples from an [Iterable]; delegation as for the `Iterable` [addTriples] overload.
     * @return true if any triples were removed
     */
    fun removeTriples(triples: Iterable<RdfTriple>): Boolean =
        if (triples is Collection<RdfTriple>) removeTriples(triples) else removeTriples(triples.asSequence())

    /**
     * Removes triples from a [Sequence] while streaming (bounded batches by default).
     * @return true if any triples were removed
     */
    fun removeTriples(triples: Sequence<RdfTriple>): Boolean {
        var changed = false
        triples.chunked(STREAMING_WRITE_BATCH_SIZE).forEach { batch -> if (removeTriples(batch)) changed = true }
        return changed
    }

    /**
     * Clear all triples from this graph.
     * @return true if any triples were removed
     */
    fun clear(): Boolean
}

/**
 * Interface for graphs that know their source repository and graph name.
 * 
 * This follows the industry pattern of tracking graph provenance for optimization.
 * When graphs implement this interface, datasets can optimize query execution
 * by using FROM clauses instead of materializing unions.
 * 
 * **Implementation Note:**
 * Graph implementations (like SparqlGraph) should implement this interface
 * to enable dataset optimization.
 */
interface SourceTrackedGraph : RdfGraph {
    /**
     * The repository this graph comes from, if known.
     * 
     * @return The source repository, or null if not tracked
     */
    val sourceRepository: RdfRepository?
    
    /**
     * The name of this graph in the source repository, if known.
     * 
     * null means this is the default graph of the repository.
     * 
     * @return The graph name in the source repository, or null for default graph
     */
    val sourceGraphName: Iri?
}









