@file:JvmName("RdfTerms")
@file:JvmMultifileClass

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

    /**
     * The N-Triples/Turtle/SPARQL form `_:label`.
     *
     * Ids are not restricted (stores such as Virtuoso report ids like `nodeID://b1`), so an id that is not a
     * valid `BLANK_NODE_LABEL` is written as `ux_` followed by four lower-case hex digits per UTF-16 code unit.
     * The encoding covers unpaired surrogates, so distinct invalid ids always get distinct labels.
     *
     * Other valid ids are written unchanged, except those that start with `ux_`: they get one extra `_` after the
     * prefix (`ux_00610020` is written `ux__00610020`), so they can never equal an encoded label (hex digits never
     * include `_`). The mapping is therefore injective: distinct ids always get distinct labels, so two blank nodes
     * are never merged when written. The price is that a label starting with `ux_`, once parsed back as an id,
     * is written one character longer on the next serialization (blank node labels are document-scoped, so this
     * does not change the meaning of the data).
     *
     * Use this (not `"_:" + id`) whenever a blank node is written as N-Triples, Turtle or SPARQL text.
     */
    override fun toString(): String = "_:${blankNodeLabel(id)}"
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
         * @throws IllegalArgumentException for the datatypes `rdf:langString` and `rdf:dirLangString`: a literal of
         *   those datatypes needs a language tag, use `Literal(lexical, lang)` or [LangString]
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
        operator fun invoke(value: BigDecimal): Literal = Literal(com.geoknoesis.kastor.rdf.xsd.XsdDecimal.canonicalLexical(value), XSD.decimal)
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
 * Language tags must match the Turtle/SPARQL `LANGTAG` grammar
 * (`[a-zA-Z]+ ('-' [a-zA-Z0-9]+)*`) with subtags of at most 8 characters (BCP 47). The tag is kept exactly as
 * given - `LangString("x", "en-GB").lang` is `"en-GB"` - and serialised that way. Because RDF
 * compares language tags case-insensitively, [equals] and [hashCode] use [normalizedLang], so
 * `LangString("x", "en-GB") == LangString("x", "en-gb")`. The lexical form and the direction
 * are still compared exactly.
 *
 * @property lexical The string content of the literal
 * @property lang The language tag exactly as given (e.g., "en", "en-US")
 * @property normalizedLang The language tag in lower case, as used for comparison (e.g., "en-us")
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
    val lang: String = lang.also {
        require(LiteralValidation.isWellFormedLanguageTag(it)) { "Invalid language tag: '$it'" }
    }

    val normalizedLang: String = lang.lowercase(java.util.Locale.ROOT)

    override val datatype: Iri
        get() = if (direction == null) RDF.langString else RDF.dirLangString

    operator fun component1(): String = lexical
    operator fun component2(): String = lang
    operator fun component3(): Direction? = direction

    fun copy(lexical: String = this.lexical, lang: String = this.lang, direction: Direction? = this.direction): LangString =
        LangString(lexical, lang, direction)

    override fun equals(other: Any?): Boolean =
        other is LangString && lexical == other.lexical && normalizedLang == other.normalizedLang && direction == other.direction

    override fun hashCode(): Int = (lexical.hashCode() * 31 + normalizedLang.hashCode()) * 31 + (direction?.hashCode() ?: 0)

    override fun toString(): String = when (direction) {
        null -> "\"${escapeLiteralLexical(lexical)}\"@$lang"
        else -> "\"${escapeLiteralLexical(lexical)}\"@$lang--${direction.token}"
    }
}

/**
 * Validates a language tag against the Turtle/SPARQL `LANGTAG` grammar
 * (`[a-zA-Z]+ ('-' [a-zA-Z0-9]+)*`) and returns it in lower case, the form RDF uses to compare tags.
 *
 * [LangString] does not apply this to its tag (it keeps the tag as given); use it, or
 * [LangString.normalizedLang], when a canonical key is needed.
 *
 * @throws IllegalArgumentException if the tag is empty or malformed
 */
fun normalizeLanguageTag(tag: String): String {
    require(LiteralValidation.isWellFormedLanguageTag(tag)) { "Invalid language tag: '$tag'" }
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
 * @property datatype The IRI identifying the data type; not `rdf:langString` or `rdf:dirLangString`, which need a
 *   language tag (see [LangString])
 * @throws IllegalArgumentException for the datatypes `rdf:langString` and `rdf:dirLangString`
 * @see [Literal]
 */
data class TypedLiteral(override val lexical: String, override val datatype: Iri) : Literal {
    init {
        // rdf:langString and rdf:dirLangString are the datatypes of language-tagged strings: a literal of such a
        // datatype without a language tag is not an RDF term. (Other ill-typed literals are legal RDF.)
        LiteralValidation.problem(datatype.value, null, false)?.let { problem ->
            throw IllegalArgumentException("$problem; use LangString(lexical, lang) or Literal(lexical, lang)")
        }
    }

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
