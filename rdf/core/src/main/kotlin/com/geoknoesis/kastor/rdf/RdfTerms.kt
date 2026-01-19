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
 * In RDF, only certain types of values can be subjects:
 * - [Iri]: Internationalized Resource Identifiers
 * - [BlankNode]: Anonymous resources
 * - [TripleTerm]: Quoted triples (RDF-star)
 *
 * Note that [Literal] values cannot be subjects in standard RDF.
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
 * ## Usage
 * ```kotlin
 * val iri = Iri("http://example.org/resource")
 * val predicate = Iri("http://example.org/name")
 * 
 * // Or use the top-level function
 * val iri2 = Iri("http://example.org/resource")
 * 
 * // Or use the extension function
 * val iri3 = "http://example.org/resource".toIri()
 * ```
 *
 * @property value The string representation of the IRI
 * @see [RdfResource]
 */
@JvmInline
value class Iri(val value: String) : RdfResource {
    override fun toString(): String = "<$value>" // debugging only
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
    override fun toString(): String = "_:$id" // debugging only
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
         * For boolean datatypes, this method validates the lexical form and returns
         * the appropriate singleton ([TrueLiteral] or [FalseLiteral]) when possible.
         * Invalid boolean forms will throw an exception.
         *
         * ## Examples
         * ```kotlin
         * // Plain string literals (default)
         * Literal("Hello, World!")  // "Hello, World!"^^xsd:string
         * Literal("John Doe")       // "John Doe"^^xsd:string
         * 
         * // Boolean literals
         * Literal("true", XSD.boolean)  // Returns TrueLiteral
         * Literal("1", XSD.boolean)     // Returns TrueLiteral
         * Literal("false", XSD.boolean) // Returns FalseLiteral
         * Literal("maybe", XSD.boolean) // Throws IllegalArgumentException
         * 
         * // Typed literals
         * Literal("42", XSD.integer)      // "42"^^xsd:integer
         * Literal("2025-01-15", XSD.date) // "2025-01-15"^^xsd:date
         * ```
         *
         * @param lexical The string representation of the value
         * @param datatype The IRI identifying the data type (defaults to xsd:string)
         * @return A new [Literal] with the specified datatype
         * @throws IllegalArgumentException if the lexical form is invalid for the datatype
         * @see [TypedLiteral]
         * @see [TrueLiteral]
         * @see [FalseLiteral]
         */
        operator fun invoke(lexical: String, datatype: Iri = XSD.string): Literal {
            return when (datatype) {
                XSD.boolean -> when (lexical) {
                    "true", "1" -> TrueLiteral
                    "false", "0" -> FalseLiteral
                    else -> throw IllegalArgumentException(
                        "Lexical value '$lexical' is not valid for xsd:boolean. Use 'true', 'false', '1', or '0'."
                    )
                }
                else -> TypedLiteral(lexical, datatype)
            }
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
        operator fun invoke(value: Double): Literal = Literal(value.toString(), XSD.double)
        operator fun invoke(value: Float): Literal = Literal(value.toString(), XSD.float)
        operator fun invoke(value: Boolean): Literal = if (value) TrueLiteral else FalseLiteral
        operator fun invoke(value: BigDecimal): Literal = Literal(value.stripTrailingZeros().toPlainString(), XSD.decimal)
        operator fun invoke(value: BigInteger): Literal = Literal(value.toString(), XSD.integer)
        operator fun invoke(value: LocalDate): Literal = Literal(value.toString(), XSD.date)
        operator fun invoke(value: LocalTime): Literal = Literal(value.toString(), XSD.time)
        operator fun invoke(value: LocalDateTime): Literal = Literal(value.toString(), XSD.dateTime)
        operator fun invoke(value: OffsetDateTime): Literal = Literal(value.toString(), XSD.dateTime)
        operator fun invoke(value: Instant): Literal = Literal(value.toString(), XSD.dateTimeStamp)
        operator fun invoke(value: Year): Literal = Literal(value.toString(), XSD.gYear)
        operator fun invoke(value: YearMonth): Literal = Literal(value.toString(), XSD.gYearMonth)
        operator fun invoke(value: ByteArray): Literal = Literal(Base64.getEncoder().encodeToString(value), XSD.base64Binary)

        /**
         * Creates a literal with automatic type inference from any value.
         * This is a catch-all function that delegates to the appropriate
         * typed overload or falls back to string representation.
         *
         * ## Examples
         * ```kotlin
         * // Supported types (use specific overloads)
         * 42.toLiteral()                    // "42"^^xsd:integer
         * Literal(3.14)                  // "3.14"^^xsd:double
         * true.toLiteral()                  // Returns TrueLiteral
         * 
         * // Unsupported types (fallback to string)
         * Literal(SomeCustomClass())     // "SomeCustomClass@123456"^^xsd:string
         * Literal(Any())                 // "kotlin.Any@123456"^^xsd:string
         * ```
         *
         * @param value The value to convert to a literal
         * @return A new [Literal] with the appropriate datatype
         * @see [TypedLiteral]
         */
        operator fun invoke(value: Any): Literal = when (value) {
            is Int -> invoke(value)
            is Long -> invoke(value)
            is Double -> invoke(value)
            is Float -> invoke(value)
            is Boolean -> invoke(value)
            is BigDecimal -> invoke(value)
            is BigInteger -> invoke(value)
            is LocalDate -> invoke(value)
            is LocalTime -> invoke(value)
            is LocalDateTime -> invoke(value)
            is OffsetDateTime -> invoke(value)
            is Instant -> invoke(value)
            is Year -> invoke(value)
            is YearMonth -> invoke(value)
            is ByteArray -> invoke(value)
            is String -> invoke(value)
            else -> Literal(value.toString(), XSD.string)
        }

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

    }
}

/**
 * Represents a language-tagged string literal in RDF.
 *
 * Language-tagged strings are used when the literal has a specific language
 * (e.g., "Hello"@en, "Bonjour"@fr). The datatype is automatically set to
 * `rdf:langString` as per the RDF specification.
 *
 * ## Usage
 * ```kotlin
 * val english = LangString("Hello", "en")  // "Hello"@en
 * val french = LangString("Bonjour", "fr") // "Bonjour"@fr
 * 
 * // Or use the Literal factory
 * val german = Literal("Hallo", "de")      // "Hallo"@de
 * 
 * // Or use the top-level function
 * val spanish = lang("Hola", "es")         // "Hola"@es
 * ```
 *
 * @property lexical The string content of the literal
 * @property lang The language tag (e.g., "en", "fr", "de")
 * @see [Literal]
 * @see [RDF.langString]
 */
data class LangString(override val lexical: String, val lang: String) : Literal {
    override val datatype: Iri get() = RDF.langString
    override fun toString(): String = "\"$lexical\"@$lang" // debugging only
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
    override fun toString(): String = "\"$lexical\"^^${datatype}" // debugging only
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
data object TrueLiteral : Literal {
    override val lexical: String = "true"
    override val datatype: Iri = XSD.boolean
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
data object FalseLiteral : Literal {
    override val lexical: String = "false"
    override val datatype: Iri = XSD.boolean
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
    override fun toString(): String = "$subject $predicate $obj ." // debugging only
}

/**
 * Represents a quoted triple as a resource, enabling RDF-star functionality.
 *
 * RDF-star allows triples to be quoted and used as subjects or objects in other
 * triples. This enables representing statements about statements (metadata).
 *
 * A TripleTerm wraps an [RdfTriple] and makes it usable as a [RdfResource],
 * allowing it to appear as a subject in other triples.
 *
 * ## Usage
 * ```kotlin
 * val baseTriple = triple(
 *     Iri("http://example.org/person"),
 *     Iri("http://example.org/name"),
 *     string("John Doe")
 * )
 * 
 * val quotedTriple = TripleTerm(baseTriple)
 * 
 * // Or use the top-level function
 * val quoted2 = quoted(baseTriple)
 * 
 * // Now you can use the quoted triple as a subject
 * val metadataTriple = triple(
 *     quotedTriple,  // Subject: the quoted triple
 *     Iri("http://example.org/source"),  // Predicate: source
 *     string("Wikipedia")  // Object: source value
 * )
 * ```
 *
 * @property triple The RDF triple being quoted
 * @see [RdfTriple]
 * @see [RdfResource]
 */
data class TripleTerm(val triple: RdfTriple) : RdfResource {
    override fun toString(): String = "<<${triple.subject} ${triple.predicate} ${triple.obj}>>"
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
fun Double.toLiteral(): Literal = Literal(this.toString(), XSD.double)
fun Float.toLiteral(): Literal = Literal(this.toString(), XSD.float)
fun BigDecimal.toLiteral(): Literal = Literal(this.stripTrailingZeros().toPlainString(), XSD.decimal)
fun BigInteger.toLiteral(): Literal = Literal(this.toString(), XSD.integer)

// Essential date/time extensions
fun LocalDate.toLiteral(): Literal = Literal(this.toString(), XSD.date)
fun LocalTime.toLiteral(): Literal = Literal(this.toString(), XSD.time)
fun LocalDateTime.toLiteral(): Literal = Literal(this.toString(), XSD.dateTime)
fun Instant.toLiteral(): Literal = Literal(this.toString(), XSD.dateTimeStamp)
fun Year.toLiteral(): Literal = Literal(this.toString(), XSD.gYear)
fun YearMonth.toLiteral(): Literal = Literal(this.toString(), XSD.gYearMonth)

// Essential binary extension
fun ByteArray.toLiteral(): Literal = Literal(Base64.getEncoder().encodeToString(this), XSD.base64Binary)















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
 * val iri2 = Iri("http://example.org/resource")
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
    /**
     * Check if a triple exists in this graph.
     */
    fun hasTriple(triple: RdfTriple): Boolean
    
    /**
     * Get all triples in this graph.
     */
    fun getTriples(): List<RdfTriple>
    
    /**
     * Get the number of triples in this graph.
     */
    fun size(): Int
}

/**
 * Mutable RDF graph operations.
 */
interface MutableRdfGraph : RdfGraph {

    /**
     * Add a triple to this graph.
     */
    fun addTriple(triple: RdfTriple)

    /**
     * Add multiple triples to this graph.
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
     * Clear all triples from this graph.
     * @return true if any triples were removed
     */
    fun clear(): Boolean
}









