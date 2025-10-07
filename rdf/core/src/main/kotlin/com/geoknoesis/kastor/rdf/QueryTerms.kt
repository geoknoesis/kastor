package com.geoknoesis.kastor.rdf

import java.time.LocalDate
import java.time.LocalDateTime

// ---- Prefix Declarations ----

/**
 * Represents a SPARQL prefix declaration.
 *
 * Prefix declarations allow using abbreviated IRIs in SPARQL queries,
 * making them more readable and concise.
 *
 * ## Usage
 * ```kotlin
 * val foafPrefix = PrefixDeclaration("foaf", "http://xmlns.com/foaf/0.1/")
 * val xsdPrefix = PrefixDeclaration("xsd", "http://www.w3.org/2001/XMLSchema#")
 * ```
 *
 * @property prefix The prefix name (e.g., "foaf", "xsd")
 * @property namespace The namespace IRI
 */
data class PrefixDeclaration(val prefix: String, val namespace: String) {
    init {
        require(prefix.isNotBlank()) { "Prefix must not be blank" }
        require(namespace.isNotBlank()) { "Namespace must not be blank" }
    }
    
    override fun toString(): String = "PREFIX $prefix: <$namespace>"
}

/**
 * Represents a SPARQL 1.2 VERSION declaration.
 *
 * VERSION declarations specify the SPARQL version to use for the query.
 * This is a new feature in SPARQL 1.2.
 *
 * ## Usage
 * ```kotlin
 * val version = VersionDeclaration("1.2")
 * ```
 *
 * @property version The SPARQL version (e.g., "1.2")
 */
data class VersionDeclaration(val version: String) {
    init {
        require(version.isNotBlank()) { "Version must not be blank" }
        require(version.matches(Regex("\\d+\\.\\d+"))) { "Version must be in format X.Y" }
    }
    
    override fun toString(): String = "VERSION $version"
}

/**
 * Common SPARQL prefixes for popular vocabularies.
 *
 * This object provides constants for commonly used prefixes,
 * making it easy to add standard vocabularies to queries.
 *
 * ## Usage
 * ```kotlin
 * select("name") {
 *     addCommonPrefixes("foaf", "rdf", "rdfs")
 *     where {
 *         person has iri("foaf:name") with var("name")
 *     }
 * }
 * ```
 */
object CommonPrefixes {
    const val FOAF = "http://xmlns.com/foaf/0.1/"
    const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    const val RDFS = "http://www.w3.org/2000/01/rdf-schema#"
    const val OWL = "http://www.w3.org/2002/07/owl#"
    const val XSD = "http://www.w3.org/2001/XMLSchema#"
    const val DC = "http://purl.org/dc/elements/1.1/"
    const val DCTERMS = "http://purl.org/dc/terms/"
    const val SCHEMA = "https://schema.org/"
    const val DBPEDIA = "http://dbpedia.org/ontology/"
    const val WIKIDATA = "http://www.wikidata.org/entity/"
    const val SKOS = "http://www.w3.org/2004/02/skos/core#"
}

/**
 * QueryTerms API for building SPARQL queries in a type-safe and idiomatic way.
 *
 * This module provides a clean, focused API for constructing SPARQL queries
 * using Kotlin's type system and DSL features. It follows the same simplified
 * approach as RdfTerms - essential functionality without overwhelming complexity.
 *
 * ## Key Features
 * - **Type Safety**: Compile-time validation of query structure
 * - **DSL Support**: Natural language-like query construction
 * - **Pattern Matching**: Intuitive triple pattern creation
 * - **Filtering**: Type-safe filter expressions
 * - **Aggregation**: Built-in aggregate functions
 *
 * ## Usage Examples
 * ```kotlin
 * // Basic query with variables
 * val query = select("name", "age") {
 *     where {
 *         person has namePred with var("name")
 *         person has agePred with var("age")
 *     }
 * }
 * 
 * // Query with filters
 * val adultQuery = select("name") {
 *     where {
 *         person has namePred with var("name")
 *         person has agePred with var("age")
 *     }
 *     filter { var("age") greaterThan 18 }
 * }
 * ```
 *
 * @see [RdfTerms] for the core RDF term model
 */

// ---- SPARQL Variables ----

/**
 * Represents a SPARQL variable.
 *
 * Variables are placeholders in SPARQL queries that can be bound to values
 * during query execution. They are used in SELECT clauses, WHERE patterns,
 * and other query components.
 *
 * ## Usage
 * ```kotlin
 * val nameVar = var("name")
 * val ageVar = var("age")
 * 
 * // In patterns
 * person has namePred with nameVar
 * person has agePred with ageVar
 * ```
 *
 * @property name The variable name (without the ? prefix)
 * @see [RdfTerm]
 */
data class Var(val name: String) : RdfTerm {
    init { 
        require(name.isNotBlank()) { "Variable name must not be blank" }
        require(name[0].isLetterOrDigit()) { "Variable name must start with letter or digit" }
    }
    override fun toString(): String = "?$name"
}

/**
 * Creates a SPARQL variable.
 *
 * This is a convenience function that provides a clean API for creating variables.
 *
 * ## Usage
 * ```kotlin
 * val nameVar = var_("name")
 * val ageVar = var_("age")
 * ```
 *
 * @param name The variable name (without the ? prefix)
 * @return A new [Var] instance
 * @throws IllegalArgumentException if the name is blank or invalid
 * @see [Var]
 */
fun var_(name: String) = Var(name)

/**
 * Creates a SPARQL variable (shorter alias).
 *
 * This is a shorter alias for `var_()` for convenience.
 *
 * ## Usage
 * ```kotlin
 * val nameVar = var("name")
 * val ageVar = var("age")
 * ```
 *
 * @param name The variable name (without the ? prefix)
 * @return A new [Var] instance
 * @throws IllegalArgumentException if the name is blank or invalid
 * @see [Var]
 */
fun `var`(name: String) = Var(name)

// ---- SPARQL Patterns ----

/**
 * Represents a SPARQL triple pattern.
 *
 * Triple patterns are the basic building blocks of SPARQL WHERE clauses.
 * They can contain variables, IRIs, literals, and blank nodes.
 *
 * ## Usage
 * ```kotlin
 * val pattern = TriplePattern(
 *     subject = var("person"),
 *     predicate = iri("http://example.org/name"),
 *     obj = var("name")
 * )
 * ```
 *
 * @property subject The subject of the triple pattern
 * @property predicate The predicate of the triple pattern
 * @property obj The object of the triple pattern
 * @see [RdfTriple]
 */
data class TriplePattern(
    val subject: RdfTerm,
    val predicate: RdfTerm,
    val obj: RdfTerm
) : SparqlGraphPattern {
    override fun toString(): String = "$subject $predicate $obj ."
}

/**
 * Represents a SPARQL graph pattern.
 *
 * Graph patterns can contain triple patterns, filters, binds, and other
 * graph patterns. They are the building blocks of complex SPARQL queries.
 */
sealed interface SparqlGraphPattern {
    fun and(other: SparqlGraphPattern): GroupPattern = GroupPattern(listOf(this, other))
    fun optional(): OptionalPattern = OptionalPattern(this)
    fun minus(other: SparqlGraphPattern): MinusPattern = MinusPattern(this, other)
}

/**
 * Represents a group of graph patterns.
 *
 * Groups are used to combine multiple patterns and ensure proper precedence.
 * They are equivalent to using braces `{ }` in SPARQL.
 *
 * ## Usage
 * ```kotlin
 * val group = GroupPattern(listOf(
 *     TriplePattern(personVar, namePred, nameVar),
 *     TriplePattern(personVar, agePred, ageVar)
 * ))
 * ```
 */
data class GroupPattern(
    val patterns: List<SparqlGraphPattern>
) : SparqlGraphPattern {
    override fun toString(): String = buildString {
        append("{\n")
        patterns.forEach { append("  $it\n") }
        append("}")
    }
}

/**
 * Represents a UNION of two graph patterns.
 *
 * UNION allows matching either of two patterns. It's equivalent to
 * the `UNION` keyword in SPARQL.
 *
 * ## Usage
 * ```kotlin
 * val union = UnionPattern(
 *     TriplePattern(personVar, namePred, nameVar),
 *     TriplePattern(personVar, emailPred, emailVar)
 * )
 * ```
 */
data class UnionPattern(
    val left: SparqlGraphPattern,
    val right: SparqlGraphPattern
) : SparqlGraphPattern {
    override fun toString(): String = "$left\nUNION\n$right"
}

/**
 * Represents an OPTIONAL graph pattern.
 *
 * OPTIONAL patterns are matched if possible, but don't fail the query
 * if they don't match. They are equivalent to `OPTIONAL { }` in SPARQL.
 *
 * ## Usage
 * ```kotlin
 * val optional = OptionalPattern(
 *     TriplePattern(personVar, emailPred, emailVar)
 * )
 * ```
 */
data class OptionalPattern(
    val pattern: SparqlGraphPattern
) : SparqlGraphPattern {
    override fun toString(): String = "OPTIONAL $pattern"
}

/**
 * Represents a MINUS graph pattern.
 *
 * MINUS excludes solutions that match the pattern. It's equivalent to
 * `MINUS { }` in SPARQL.
 *
 * ## Usage
 * ```kotlin
 * val minus = MinusPattern(
 *     TriplePattern(personVar, namePred, nameVar),
 *     TriplePattern(personVar, emailPred, emailVar)
 * )
 * ```
 */
data class MinusPattern(
    val left: SparqlGraphPattern,
    val right: SparqlGraphPattern
) : SparqlGraphPattern {
    override fun toString(): String = "$left\nMINUS $right"
}

/**
 * Represents a VALUES clause in SPARQL.
 *
 * VALUES provides a way to specify a set of values for variables.
 * It's equivalent to `VALUES ?var { value1 value2 ... }` in SPARQL.
 *
 * ## Usage
 * ```kotlin
 * val values = ValuesPattern(
 *     listOf(var("name")),
 *     listOf(listOf(string("John")), listOf(string("Jane")))
 * )
 * ```
 */
data class ValuesPattern(
    val variables: List<Var>,
    val values: List<List<RdfTerm>>
) : SparqlGraphPattern {
    override fun toString(): String = buildString {
        append("VALUES ")
        if (variables.size == 1) {
            append(variables.first())
        } else {
            append("(")
            append(variables.joinToString(" "))
            append(")")
        }
        append(" {\n")
        values.forEach { valueList ->
            append("  ")
            if (valueList.size == 1) {
                append(valueList.first())
            } else {
                append("(")
                append(valueList.joinToString(" "))
                append(")")
            }
            append("\n")
        }
        append("}")
    }
}

/**
 * Represents a GRAPH pattern in SPARQL.
 *
 * GRAPH restricts the scope of a pattern to a specific named graph.
 * It's equivalent to `GRAPH ?graph { ... }` in SPARQL.
 *
 * ## Usage
 * ```kotlin
 * val graph = GraphPattern(
 *     var("graph"),
 *     TriplePattern(personVar, namePred, nameVar)
 * )
 * ```
 */
data class NamedGraphPattern(
    val graphName: RdfTerm,
    val pattern: SparqlGraphPattern
) : SparqlGraphPattern {
    override fun toString(): String = "GRAPH $graphName $pattern"
}

/**
 * Represents a SERVICE pattern in SPARQL for federated queries.
 *
 * SERVICE allows querying remote SPARQL endpoints within a query.
 * It's equivalent to `SERVICE <endpoint> { ... }` in SPARQL.
 *
 * ## Usage
 * ```kotlin
 * val service = ServicePattern(
 *     iri("http://dbpedia.org/sparql"),
 *     TriplePattern(personVar, namePred, nameVar)
 * )
 * ```
 */
data class ServicePattern(
    val serviceEndpoint: RdfTerm,
    val pattern: SparqlGraphPattern
) : SparqlGraphPattern {
    override fun toString(): String = "SERVICE $serviceEndpoint $pattern"
}

/**
 * Represents a SubSelect (nested query) in SPARQL.
 *
 * SubSelect allows using a SELECT query as part of a larger query.
 * It's equivalent to `{ SELECT ... WHERE ... }` in SPARQL.
 *
 * ## Usage
 * ```kotlin
 * val subSelect = SubSelectPattern(
 *     SelectQuery(listOf(var("name")), wherePattern)
 * )
 * ```
 */
data class SubSelectPattern(
    val query: SelectQuery
) : SparqlGraphPattern {
    override fun toString(): String = query.toString()
}

/**
 * Represents an RDF-star quoted triple pattern.
 *
 * RDF-star allows using triples as subjects or objects.
 * It's equivalent to `<< ?s ?p ?o >>` in SPARQL-star.
 *
 * ## Usage
 * ```kotlin
 * val quotedTriple = QuotedTriplePattern(
 *     subject = var("s"),
 *     predicate = var("p"),
 *     obj = var("o")
 * )
 * ```
 */
data class QuotedTriplePattern(
    val subject: RdfTerm,
    val predicate: RdfTerm,
    val obj: RdfTerm
) : RdfTerm {
    override fun toString(): String = "<< $subject $predicate $obj >>"
}

/**
 * Represents an RDF-star triple pattern where the subject or object is a quoted triple.
 *
 * This allows using quoted triples in triple patterns for SPARQL 1.2 RDF-star support.
 *
 * ## Usage
 * ```kotlin
 * val rdfStarPattern = RdfStarTriplePattern(
 *     quotedTriple = QuotedTriplePattern(var("s"), var("p"), var("o")),
 *     predicate = var("pred"),
 *     obj = var("obj")
 * )
 * ```
 */
data class RdfStarTriplePattern(
    val quotedTriple: QuotedTriplePattern,
    val predicate: RdfTerm,
    val obj: RdfTerm
) : SparqlGraphPattern {
    override fun toString(): String = "$quotedTriple $predicate $obj ."
}

// ---- Property Paths (SPARQL 1.2) ----

/**
 * Represents a SPARQL property path.
 *
 * Property paths allow complex navigation through RDF graphs using
 * operators like +, *, ?, {n}, |, ^, and !.
 *
 * ## Usage
 * ```kotlin
 * // Basic path
 * val path = PropertyPath(iri("http://example.org/friend"))
 * 
 * // One or more
 * val oneOrMore = OneOrMore(path)
 * 
 * // Zero or more
 * val zeroOrMore = ZeroOrMore(path)
 * 
 * // Alternatives
 * val alternatives = Alternative(path1, path2)
 * ```
 */
sealed interface PropertyPath {
    fun oneOrMore(): OneOrMore = OneOrMore(this)
    fun zeroOrMore(): ZeroOrMore = ZeroOrMore(this)
    fun zeroOrOne(): ZeroOrOne = ZeroOrOne(this)
    fun inverse(): Inverse = Inverse(this)
    fun negation(): Negation = Negation(this)
    fun alternative(other: PropertyPath): Alternative = Alternative(this, other)
    fun sequence(other: PropertyPath): PathSequence = PathSequence(this, other)
    fun range(min: Int, max: Int? = null): Range = Range(this, min, max)
}

/**
 * Represents a basic property path (IRI or variable).
 */
data class BasicPath(val predicate: RdfTerm) : PropertyPath {
    override fun toString(): String = predicate.toString()
}

/**
 * Represents a one-or-more path (+).
 */
data class OneOrMore(val path: PropertyPath) : PropertyPath {
    override fun toString(): String = "$path+"
}

/**
 * Represents a zero-or-more path (*).
 */
data class ZeroOrMore(val path: PropertyPath) : PropertyPath {
    override fun toString(): String = "$path*"
}

/**
 * Represents a zero-or-one path (?).
 */
data class ZeroOrOne(val path: PropertyPath) : PropertyPath {
    override fun toString(): String = "$path?"
}

/**
 * Represents an inverse path (^).
 */
data class Inverse(val path: PropertyPath) : PropertyPath {
    override fun toString(): String = "^$path"
}

/**
 * Represents a negation path (!).
 */
data class Negation(val path: PropertyPath) : PropertyPath {
    override fun toString(): String = "!$path"
}

/**
 * Represents an alternative path (|).
 */
data class Alternative(val left: PropertyPath, val right: PropertyPath) : PropertyPath {
    override fun toString(): String = "$left|$right"
}

/**
 * Represents a sequence path (concatenation).
 */
data class PathSequence(val left: PropertyPath, val right: PropertyPath) : PropertyPath {
    override fun toString(): String = "$left/$right"
}

/**
 * Represents a range path {n}, {n,}, {,m}, {n,m}.
 */
data class Range(val path: PropertyPath, val min: Int, val max: Int?) : PropertyPath {
    override fun toString(): String = when {
        max == null -> "$path{$min,}"
        min == 0 -> "$path{,$max}"
        min == max -> "$path{$min}"
        else -> "$path{$min,$max}"
    }
}

/**
 * Represents a property path triple pattern.
 */
data class PropertyPathPattern(
    val subject: RdfTerm,
    val path: PropertyPath,
    val obj: RdfTerm
) : SparqlGraphPattern {
    override fun toString(): String = "$subject $path $obj ."
}

// Property path convenience functions
fun path(predicate: RdfTerm): PropertyPath = BasicPath(predicate)
fun path(predicate: String): PropertyPath = BasicPath(iri(predicate))

// Range convenience functions
fun PropertyPath.exactly(n: Int): Range = Range(this, n, n)
fun PropertyPath.atLeast(n: Int): Range = Range(this, n, null)
fun PropertyPath.atMost(m: Int): Range = Range(this, 0, m)
fun PropertyPath.between(n: Int, m: Int): Range = Range(this, n, m)

/**
 * Represents a SPARQL filter expression.
 *
 * Filters are used to restrict the results of a query based on conditions.
 * They can be applied to variables, literals, and other RDF terms.
 *
 * ## Usage
 * ```kotlin
 * val filter = Filter(var("age") greaterThan 18)
 * val nameFilter = Filter(var("name") like "John*")
 * ```
 *
 * @property expression The filter expression
 * @see [FilterExpression]
 */
data class Filter(val expression: FilterExpression) : SparqlGraphPattern {
    override fun toString(): String = "FILTER($expression)"
}

/**
 * Represents a SPARQL BIND expression.
 *
 * BIND is used to assign values to variables using expressions.
 * It's useful for creating computed values, type conversions, and
 * conditional assignments.
 *
 * ## Usage
 * ```kotlin
 * val bind = Bind(var("fullName"), concat(var("firstName"), " ", var("lastName")))
 * val ageBind = Bind(var("ageGroup"), when(var("age")) { gt 65 -> "senior" })
 * ```
 *
 * @property variable The variable to bind the value to
 * @property expression The expression to evaluate
 * @see [FilterExpression]
 */
data class Bind(
    val variable: Var,
    val expression: RdfTerm
) : SparqlGraphPattern {
    override fun toString(): String = "BIND($expression AS $variable)"
}

/**
 * Base interface for SPARQL filter expressions.
 *
 * Filter expressions define conditions that can be used in FILTER clauses.
 * They support comparison operators, logical operators, and built-in functions.
 *
 * ## Usage
 * ```kotlin
 * // Comparison
 * var("age") greaterThan 18
 * var("name") equalTo "John"
 * 
 * // Logical
 * var("age") greaterThan 18 and var("age") lessThan 65
 * var("name") equalTo "John" or var("name") equalTo "Jane"
 * 
 * // Built-in functions
 * var("name") like "John*"
 * var("age") isNotNull
 * ```
 */
sealed interface FilterExpression {
    fun and(other: FilterExpression): FilterExpression = AndFilter(this, other)
    fun or(other: FilterExpression): FilterExpression = OrFilter(this, other)
    fun not(): FilterExpression = NotFilter(this)
}

// ---- Comparison Operators ----

/**
 * Represents a comparison between two values.
 */
data class ComparisonFilter(
    val left: RdfTerm,
    val operator: String,
    val right: RdfTerm
) : FilterExpression {
    override fun toString(): String = "$left $operator $right"
}

// Extension functions for comparison operators
infix fun RdfTerm.eq(other: RdfTerm): FilterExpression = ComparisonFilter(this, "=", other)
infix fun RdfTerm.ne(other: RdfTerm): FilterExpression = ComparisonFilter(this, "!=", other)
infix fun RdfTerm.lt(other: RdfTerm): FilterExpression = ComparisonFilter(this, "<", other)
infix fun RdfTerm.lte(other: RdfTerm): FilterExpression = ComparisonFilter(this, "<=", other)
infix fun RdfTerm.gt(other: RdfTerm): FilterExpression = ComparisonFilter(this, ">", other)
infix fun RdfTerm.gte(other: RdfTerm): FilterExpression = ComparisonFilter(this, ">=", other)

// Convenience overloads for common types with short operators
infix fun Var.eq(value: String): FilterExpression = ComparisonFilter(this, "=", string(value))
infix fun Var.eq(value: Int): FilterExpression = ComparisonFilter(this, "=", Literal(value))
infix fun Var.eq(value: Long): FilterExpression = ComparisonFilter(this, "=", Literal(value))
infix fun Var.eq(value: Double): FilterExpression = ComparisonFilter(this, "=", Literal(value))
infix fun Var.eq(value: Boolean): FilterExpression = ComparisonFilter(this, "=", Literal(value))
infix fun Var.eq(value: LocalDate): FilterExpression = ComparisonFilter(this, "=", Literal(value))
infix fun Var.eq(value: LocalDateTime): FilterExpression = ComparisonFilter(this, "=", Literal(value))

infix fun Var.gt(value: Int): FilterExpression = ComparisonFilter(this, ">", Literal(value))
infix fun Var.gt(value: Long): FilterExpression = ComparisonFilter(this, ">", Literal(value))
infix fun Var.gt(value: Double): FilterExpression = ComparisonFilter(this, ">", Literal(value))
infix fun Var.gt(value: LocalDate): FilterExpression = ComparisonFilter(this, ">", Literal(value))

infix fun Var.lt(value: Int): FilterExpression = ComparisonFilter(this, "<", Literal(value))
infix fun Var.lt(value: Long): FilterExpression = ComparisonFilter(this, "<", Literal(value))
infix fun Var.lt(value: Double): FilterExpression = ComparisonFilter(this, "<", Literal(value))
infix fun Var.lt(value: LocalDate): FilterExpression = ComparisonFilter(this, "<", Literal(value))

infix fun Var.gte(value: Int): FilterExpression = ComparisonFilter(this, ">=", Literal(value))
infix fun Var.gte(value: Long): FilterExpression = ComparisonFilter(this, ">=", Literal(value))
infix fun Var.gte(value: Double): FilterExpression = ComparisonFilter(this, ">=", Literal(value))
infix fun Var.gte(value: LocalDate): FilterExpression = ComparisonFilter(this, ">=", Literal(value))

infix fun Var.lte(value: Int): FilterExpression = ComparisonFilter(this, "<=", Literal(value))
infix fun Var.lte(value: Long): FilterExpression = ComparisonFilter(this, "<=", Literal(value))
infix fun Var.lte(value: Double): FilterExpression = ComparisonFilter(this, "<=", Literal(value))
infix fun Var.lte(value: LocalDate): FilterExpression = ComparisonFilter(this, "<=", Literal(value))



// ---- Logical Operators ----

/**
 * Represents a logical AND operation between two filter expressions.
 */
data class AndFilter(
    val left: FilterExpression,
    val right: FilterExpression
) : FilterExpression {
    override fun toString(): String = "($left && $right)"
}

/**
 * Represents a logical OR operation between two filter expressions.
 */
data class OrFilter(
    val left: FilterExpression,
    val right: FilterExpression
) : FilterExpression {
    override fun toString(): String = "($left || $right)"
}

/**
 * Represents a logical NOT operation on a filter expression.
 */
data class NotFilter(
    val expression: FilterExpression
) : FilterExpression {
    override fun toString(): String = "!($expression)"
}

/**
 * Represents a conditional expression (IF-THEN-ELSE).
 */
data class ConditionalExpression(
    val condition: FilterExpression,
    val thenValue: RdfTerm,
    val elseValue: RdfTerm
) : RdfTerm {
    override fun toString(): String = "IF($condition, $thenValue, $elseValue)"
}

// ---- Built-in Functions ----

/**
 * Represents a SPARQL built-in function call.
 */
data class BuiltInFunction(
    val name: String,
    val arguments: List<RdfTerm>
) : FilterExpression, RdfTerm {
    override fun toString(): String = "$name(${arguments.joinToString(", ")})"
}

// Built-in function constructors
fun regex(variable: Var, pattern: String): BuiltInFunction = BuiltInFunction("REGEX", listOf(variable, string(pattern)))
fun regex(variable: Var, pattern: String, flags: String): BuiltInFunction = BuiltInFunction("REGEX", listOf(variable, string(pattern), string(flags)))

fun lang(variable: Var): BuiltInFunction = BuiltInFunction("LANG", listOf(variable))
fun datatype(variable: Var): BuiltInFunction = BuiltInFunction("DATATYPE", listOf(variable))
fun bound(variable: Var): BuiltInFunction = BuiltInFunction("BOUND", listOf(variable))
fun isIRI(variable: Var): BuiltInFunction = BuiltInFunction("isIRI", listOf(variable))
fun isBlank(variable: Var): BuiltInFunction = BuiltInFunction("isBLANK", listOf(variable))
fun isLiteral(variable: Var): BuiltInFunction = BuiltInFunction("isLITERAL", listOf(variable))
fun isNumeric(variable: Var): BuiltInFunction = BuiltInFunction("isNUMERIC", listOf(variable))

// String functions for BIND
fun concat(vararg terms: RdfTerm): BuiltInFunction = BuiltInFunction("CONCAT", terms.toList())
fun concat(vararg terms: String): BuiltInFunction = BuiltInFunction("CONCAT", terms.map { string(it) })
fun concat(variable: Var, vararg terms: String): BuiltInFunction = BuiltInFunction("CONCAT", listOf(variable) + terms.map { string(it) })

fun substr(variable: Var, start: Int, length: Int? = null): BuiltInFunction = 
    if (length != null) BuiltInFunction("SUBSTR", listOf(variable, Literal(start), Literal(length)))
    else BuiltInFunction("SUBSTR", listOf(variable, Literal(start)))

fun strlen(variable: Var): BuiltInFunction = BuiltInFunction("STRLEN", listOf(variable))
fun ucase(variable: Var): BuiltInFunction = BuiltInFunction("UCASE", listOf(variable))
fun lcase(variable: Var): BuiltInFunction = BuiltInFunction("LCASE", listOf(variable))

// Numeric functions for BIND
fun abs(variable: Var): BuiltInFunction = BuiltInFunction("ABS", listOf(variable))
fun round(variable: Var): BuiltInFunction = BuiltInFunction("ROUND", listOf(variable))
fun ceil(variable: Var): BuiltInFunction = BuiltInFunction("CEIL", listOf(variable))
fun floor(variable: Var): BuiltInFunction = BuiltInFunction("FLOOR", listOf(variable))

// Date/Time functions for BIND
fun year(variable: Var): BuiltInFunction = BuiltInFunction("YEAR", listOf(variable))
fun month(variable: Var): BuiltInFunction = BuiltInFunction("MONTH", listOf(variable))
fun day(variable: Var): BuiltInFunction = BuiltInFunction("DAY", listOf(variable))
fun hours(variable: Var): BuiltInFunction = BuiltInFunction("HOURS", listOf(variable))
fun minutes(variable: Var): BuiltInFunction = BuiltInFunction("MINUTES", listOf(variable))
fun seconds(variable: Var): BuiltInFunction = BuiltInFunction("SECONDS", listOf(variable))

// SPARQL 1.2 Enhanced String Functions
fun replace(variable: Var, pattern: String, replacement: String): BuiltInFunction = 
    BuiltInFunction("REPLACE", listOf(variable, string(pattern), string(replacement)))

fun replaceAll(variable: Var, pattern: String, replacement: String): BuiltInFunction = 
    BuiltInFunction("REPLACE_ALL", listOf(variable, string(pattern), string(replacement)))

fun encodeForUri(variable: Var): BuiltInFunction = BuiltInFunction("ENCODE_FOR_URI", listOf(variable))
fun decodeForUri(variable: Var): BuiltInFunction = BuiltInFunction("DECODE_FOR_URI", listOf(variable))

fun contains(variable: Var, substring: String): BuiltInFunction = 
    BuiltInFunction("CONTAINS", listOf(variable, string(substring)))

fun startsWith(variable: Var, prefix: String): BuiltInFunction = 
    BuiltInFunction("STARTS_WITH", listOf(variable, string(prefix)))

fun endsWith(variable: Var, suffix: String): BuiltInFunction = 
    BuiltInFunction("ENDS_WITH", listOf(variable, string(suffix)))

fun strBefore(variable: Var, substring: String): BuiltInFunction = 
    BuiltInFunction("STRBEFORE", listOf(variable, string(substring)))

fun strAfter(variable: Var, substring: String): BuiltInFunction = 
    BuiltInFunction("STRAFTER", listOf(variable, string(substring)))

// SPARQL 1.2 Enhanced Numeric Functions
fun rand(): BuiltInFunction = BuiltInFunction("RAND", emptyList())
fun random(): BuiltInFunction = BuiltInFunction("RANDOM", emptyList())
fun now(): BuiltInFunction = BuiltInFunction("NOW", emptyList())
fun timezone(): BuiltInFunction = BuiltInFunction("TIMEZONE", emptyList())
fun tz(variable: Var): BuiltInFunction = BuiltInFunction("TZ", listOf(variable))

// SPARQL 1.2 Enhanced Date/Time Functions
fun dateTime(variable: Var): BuiltInFunction = BuiltInFunction("DATETIME", listOf(variable))
fun date(variable: Var): BuiltInFunction = BuiltInFunction("DATE", listOf(variable))
fun time(variable: Var): BuiltInFunction = BuiltInFunction("TIME", listOf(variable))

// SPARQL 1.2 RDF-star Functions
fun triple(subject: RdfTerm, predicate: RdfTerm, obj: RdfTerm): BuiltInFunction = 
    BuiltInFunction("TRIPLE", listOf(subject, predicate, obj))

fun isTriple(variable: Var): BuiltInFunction = BuiltInFunction("isTRIPLE", listOf(variable))

fun subject(variable: Var): BuiltInFunction = BuiltInFunction("SUBJECT", listOf(variable))

fun predicate(variable: Var): BuiltInFunction = BuiltInFunction("PREDICATE", listOf(variable))

fun `object`(variable: Var): BuiltInFunction = BuiltInFunction("OBJECT", listOf(variable))

// SPARQL 1.2 Literal Base Direction Functions
fun langdir(variable: Var): BuiltInFunction = BuiltInFunction("LANGDIR", listOf(variable))

fun hasLang(variable: Var, languageTag: String): BuiltInFunction = 
    BuiltInFunction("hasLANG", listOf(variable, string(languageTag)))

fun hasLangdir(variable: Var, direction: String): BuiltInFunction = 
    BuiltInFunction("hasLANGDIR", listOf(variable, string(direction)))

fun strlangdir(variable: Var, languageTag: String, direction: String): BuiltInFunction = 
    BuiltInFunction("STRLANGDIR", listOf(variable, string(languageTag), string(direction)))

// Extension functions for built-in functions
fun Var.like(pattern: String): BuiltInFunction = regex(this, pattern.replace("*", ".*"))
fun Var.isNotNull(): BuiltInFunction = bound(this)
fun Var.isNull(): FilterExpression = not(bound(this))

// ---- Aggregation Functions ----

/**
 * Represents a SPARQL aggregate function.
 */
data class AggregateFunction(
    val name: String,
    val variable: Var,
    val alias: String? = null
) : RdfTerm {
    override fun toString(): String = when {
        alias != null && name.startsWith("COUNT(DISTINCT") -> "$name $variable) AS ?$alias"
        alias == null && name.startsWith("COUNT(DISTINCT") -> "$name $variable)"
        alias != null -> "$name($variable) AS ?$alias"
        else -> "$name($variable)"
    }
}

// Aggregate function constructors
fun count(variable: Var, alias: String? = null): AggregateFunction = AggregateFunction("COUNT", variable, alias)
fun countDistinct(variable: Var, alias: String? = null): AggregateFunction = AggregateFunction("COUNT(DISTINCT", variable, alias)
fun sum(variable: Var, alias: String? = null): AggregateFunction = AggregateFunction("SUM", variable, alias)
fun avg(variable: Var, alias: String? = null): AggregateFunction = AggregateFunction("AVG", variable, alias)
fun min(variable: Var, alias: String? = null): AggregateFunction = AggregateFunction("MIN", variable, alias)
fun max(variable: Var, alias: String? = null): AggregateFunction = AggregateFunction("MAX", variable, alias)
fun groupConcat(variable: Var, alias: String? = null): AggregateFunction = AggregateFunction("GROUP_CONCAT", variable, alias)

// ---- Query Structure ----

/**
 * Represents a SELECT item in a SPARQL query.
 * Can be either a variable or an expression with an alias.
 */
sealed interface SelectItem {
    fun as_(alias: String): AliasedSelectItem
}

/**
 * Represents an aggregate function in SELECT.
 */
data class AggregateSelectItem(val aggregateFunction: AggregateFunction) : SelectItem {
    override fun toString(): String = aggregateFunction.toString()
    override fun as_(alias: String): AliasedSelectItem = AliasedSelectItem(aggregateFunction, alias)
}

/**
 * Represents a simple variable in SELECT.
 */
data class VariableSelectItem(val variable: Var) : SelectItem {
    override fun toString(): String = variable.toString()
    override fun as_(alias: String): AliasedSelectItem = AliasedSelectItem(variable, alias)
}

/**
 * Represents an expression with an alias in SELECT.
 */
data class AliasedSelectItem(
    val expression: RdfTerm,
    val alias: String
) : SelectItem {
    init {
        require(alias.isNotBlank()) { "Alias must not be blank" }
        require(alias[0].isLetterOrDigit()) { "Alias must start with letter or digit" }
    }
    
    override fun toString(): String = "$expression AS ?$alias"
    override fun as_(alias: String): AliasedSelectItem = AliasedSelectItem(expression, alias)
}

/**
 * Represents a SPARQL SELECT query.
 */
data class SelectQuery(
    val selectItems: List<SelectItem>,
    val version: VersionDeclaration? = null,
    val prefixes: List<PrefixDeclaration> = emptyList(),
    val wherePattern: SparqlGraphPattern? = null,
    val groupBy: List<Var> = emptyList(),
    val having: List<Filter> = emptyList(),
    val orderBy: List<OrderClause> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null
) {
    override fun toString(): String = buildString {
        // Add version declaration
        version?.let { ver ->
            append(ver)
            append("\n\n")
        }
        
        // Add prefix declarations
        if (prefixes.isNotEmpty()) {
            prefixes.forEach { prefix ->
                append(prefix)
                append("\n")
            }
            append("\n")
        }
        
        append("SELECT ")
        append(selectItems.joinToString(" ") { it.toString() })
        append("\n")
        
        wherePattern?.let { pattern ->
            append("WHERE $pattern")
        }
        
        if (groupBy.isNotEmpty()) {
            append("\nGROUP BY ")
            append(groupBy.joinToString(" ") { it.toString() })
        }
        
        if (having.isNotEmpty()) {
            append("\nHAVING ")
            append(having.joinToString(" ") { it.toString() })
        }
        
        if (orderBy.isNotEmpty()) {
            append("\nORDER BY ")
            append(orderBy.joinToString(" ") { it.toString() })
        }
        
        limit?.let { append("\nLIMIT $it") }
        offset?.let { append("\nOFFSET $it") }
    }
}

/**
 * Represents an ORDER BY clause in a SPARQL query.
 */
data class OrderClause(
    val variable: Var,
    val direction: OrderDirection = OrderDirection.ASC
) {
    override fun toString(): String = when (direction) {
        OrderDirection.ASC -> "$variable ASC"
        OrderDirection.DESC -> "$variable DESC"
    }
}

enum class OrderDirection {
    ASC, DESC
}

// ---- Query DSL ----

/**
 * Creates a SELECT query with the specified variables.
 */
fun select(vararg variables: String, block: SelectQueryBuilder.() -> Unit): SelectQuery {
    val vars = variables.map { var_(it) }
    return SelectQueryBuilder(vars.map { VariableSelectItem(it) }).apply(block).build()
}

/**
 * Creates a SELECT query with the specified variables.
 */
fun select(vararg variables: Var, block: SelectQueryBuilder.() -> Unit): SelectQuery {
    return SelectQueryBuilder(variables.map { VariableSelectItem(it) }).apply(block).build()
}

/**
 * Creates a SELECT query with expressions and aliases.
 */
fun select(block: SelectQueryBuilder.() -> Unit): SelectQuery {
    return SelectQueryBuilder(emptyList()).apply(block).build()
}

// ---- SPARQL 1.2 Query Forms ----

/**
 * Represents a SPARQL ASK query.
 */
data class AskQuery(
    val version: VersionDeclaration? = null,
    val prefixes: List<PrefixDeclaration> = emptyList(),
    val wherePattern: SparqlGraphPattern? = null
) {
    override fun toString(): String = buildString {
        // Add version declaration
        version?.let { ver ->
            append(ver)
            append("\n\n")
        }
        
        // Add prefix declarations
        if (prefixes.isNotEmpty()) {
            prefixes.forEach { prefix ->
                append(prefix)
                append("\n")
            }
            append("\n")
        }
        
        append("ASK")
        wherePattern?.let { pattern ->
            append(" $pattern")
        }
    }
}

/**
 * Represents a SPARQL CONSTRUCT query.
 */
data class ConstructQuery(
    val version: VersionDeclaration? = null,
    val prefixes: List<PrefixDeclaration> = emptyList(),
    val constructTemplate: List<TriplePattern> = emptyList(),
    val wherePattern: SparqlGraphPattern? = null
) {
    override fun toString(): String = buildString {
        // Add version declaration
        version?.let { ver ->
            append(ver)
            append("\n\n")
        }
        
        // Add prefix declarations
        if (prefixes.isNotEmpty()) {
            prefixes.forEach { prefix ->
                append(prefix)
                append("\n")
            }
            append("\n")
        }
        
        append("CONSTRUCT {\n")
        constructTemplate.forEach { triple ->
            append("  $triple\n")
        }
        append("}")
        
        wherePattern?.let { pattern ->
            append(" WHERE $pattern")
        }
    }
}

/**
 * Represents a SPARQL DESCRIBE query.
 */
data class DescribeQuery(
    val version: VersionDeclaration? = null,
    val prefixes: List<PrefixDeclaration> = emptyList(),
    val describeTerms: List<RdfTerm> = emptyList(),
    val wherePattern: SparqlGraphPattern? = null
) {
    override fun toString(): String = buildString {
        // Add version declaration
        version?.let { ver ->
            append(ver)
            append("\n\n")
        }
        
        // Add prefix declarations
        if (prefixes.isNotEmpty()) {
            prefixes.forEach { prefix ->
                append(prefix)
                append("\n")
            }
            append("\n")
        }
        
        append("DESCRIBE ")
        if (describeTerms.isEmpty()) {
            append("*")
        } else {
            append(describeTerms.joinToString(" "))
        }
        
        wherePattern?.let { pattern ->
            append(" WHERE $pattern")
        }
    }
}

/**
 * Builder class for constructing SELECT queries.
 */
class SelectQueryBuilder(private val selectItems: List<SelectItem>) {
    private val items = selectItems.toMutableList()
    private var version: VersionDeclaration? = null
    private val prefixes = mutableListOf<PrefixDeclaration>()
    private var wherePattern: SparqlGraphPattern? = null
    private val groupBy = mutableListOf<Var>()
    private val having = mutableListOf<Filter>()
    private val orderBy = mutableListOf<OrderClause>()
    private var limit: Int? = null
    private var offset: Int? = null

    /**
     * Adds a variable to the SELECT clause.
     *
     * ## Usage
     * ```kotlin
     * val query = select {
     *     variable("name")
     *     variable("age")
     *     where {
     *         // patterns
     *     }
     * }
     * ```
     *
     * @param name The variable name
     */
    fun variable(name: String) {
        items.add(VariableSelectItem(var_(name)))
    }

    /**
     * Adds a variable to the SELECT clause.
     *
     * ## Usage
     * ```kotlin
     * val query = select {
     *     variable(var_("name"))
     *     variable(var_("age"))
     *     where {
     *         // patterns
     *     }
     * }
     * ```
     *
     * @param variable The variable to add
     */
    fun variable(variable: Var) {
        items.add(VariableSelectItem(variable))
    }

    /**
     * Adds an expression with an alias to the SELECT clause.
     *
     * ## Usage
     * ```kotlin
     * val query = select {
     *     expression(concat(var_("firstName"), string(" "), var_("lastName")), "fullName")
     *     expression(strlen(var_("name")), "nameLength")
     *     where {
     *         // patterns
     *     }
     * }
     * ```
     *
     * @param expression The expression to evaluate
     * @param alias The alias for the expression
     */
    fun expression(expression: RdfTerm, alias: String) {
        items.add(AliasedSelectItem(expression, alias))
    }

    /**
     * Adds an aggregate function to the SELECT clause.
     *
     * ## Usage
     * ```kotlin
     * val query = select {
     *     variable("department")
     *     aggregate(count(var_("employee")), "employeeCount")
     *     aggregate(avg(var_("salary")), "avgSalary")
     *     where {
     *         // patterns
     *     }
     *     groupBy(var_("department"))
     * }
     * ```
     *
     * @param aggregateFunction The aggregate function to add
     * @param alias The alias for the aggregate function
     */
    fun aggregate(aggregateFunction: AggregateFunction, alias: String) {
        items.add(AggregateSelectItem(aggregateFunction.copy(alias = alias)))
    }

    /**
     * Sets the SPARQL version for the query.
     *
     * ## Usage
     * ```kotlin
     * val query = select("name") {
     *     version("1.2")
     *     prefix("foaf", "http://xmlns.com/foaf/0.1/")
     *     where {
     *         // Use SPARQL 1.2 features
     *     }
     * }
     * ```
     *
     * @param version The SPARQL version (e.g., "1.2")
     */
    fun version(version: String) {
        this.version = VersionDeclaration(version)
    }

    /**
     * Adds a prefix declaration to the query.
     *
     * ## Usage
     * ```kotlin
     * val query = select("name") {
     *     prefix("foaf", "http://xmlns.com/foaf/0.1/")
     *     prefix("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
     *     where {
     *         // Use prefixed names in patterns
     *     }
     * }
     * ```
     *
     * @param prefix The prefix name (without the colon)
     * @param namespace The namespace IRI
     */
    fun prefix(prefix: String, namespace: String) {
        prefixes.add(PrefixDeclaration(prefix, namespace))
    }

    fun where(block: WhereBuilder.() -> Unit) {
        val whereBuilder = WhereBuilder()
        whereBuilder.apply(block)
        wherePattern = whereBuilder.build()
    }

    fun groupBy(vararg variables: Var) {
        groupBy.addAll(variables)
    }

    fun having(block: HavingBuilder.() -> Unit) {
        val havingBuilder = HavingBuilder()
        havingBuilder.apply(block)
        having.addAll(havingBuilder.filters)
    }

    fun orderBy(variable: Var, direction: OrderDirection = OrderDirection.ASC) {
        orderBy.add(OrderClause(variable, direction))
    }

    fun limit(value: Int) {
        limit = value
    }

    fun offset(value: Int) {
        offset = value
    }

    fun build(): SelectQuery = SelectQuery(
        selectItems = items,
        version = version,
        prefixes = prefixes,
        wherePattern = wherePattern,
        groupBy = groupBy,
        having = having,
        orderBy = orderBy,
        limit = limit,
        offset = offset
    )
}

/**
 * Builder class for WHERE clauses.
 */
class WhereBuilder {
    private val patterns = mutableListOf<SparqlGraphPattern>()

    fun pattern(subject: RdfTerm, predicate: RdfTerm, obj: RdfTerm) {
        patterns.add(TriplePattern(subject, predicate, obj))
    }

    fun filter(expression: FilterExpression) {
        patterns.add(Filter(expression))
    }

    fun bind(variable: Var, expression: RdfTerm) {
        patterns.add(Bind(variable, expression))
    }

    fun optional(block: WhereBuilder.() -> Unit) {
        val optionalBuilder = WhereBuilder()
        optionalBuilder.apply(block)
        patterns.add(OptionalPattern(optionalBuilder.build()))
    }

    fun union(block: WhereBuilder.() -> Unit) {
        if (patterns.isNotEmpty()) {
            val unionBuilder = WhereBuilder()
            unionBuilder.apply(block)
            val lastPattern = patterns.removeAt(patterns.size - 1)
            patterns.add(UnionPattern(lastPattern, unionBuilder.build()))
        }
    }

    fun minus(block: WhereBuilder.() -> Unit) {
        if (patterns.isNotEmpty()) {
            val minusBuilder = WhereBuilder()
            minusBuilder.apply(block)
            val lastPattern = patterns.removeAt(patterns.size - 1)
            patterns.add(MinusPattern(lastPattern, minusBuilder.build()))
        }
    }

    fun values(variable: Var, vararg values: RdfTerm) {
        patterns.add(ValuesPattern(listOf(variable), values.map { listOf(it) }))
    }

    fun values(variables: List<Var>, values: List<List<RdfTerm>>) {
        patterns.add(ValuesPattern(variables, values))
    }

    fun graph(graphName: RdfTerm, block: WhereBuilder.() -> Unit) {
        val graphBuilder = WhereBuilder()
        graphBuilder.apply(block)
        patterns.add(NamedGraphPattern(graphName, graphBuilder.build()))
    }

    fun service(endpoint: RdfTerm, block: WhereBuilder.() -> Unit) {
        val serviceBuilder = WhereBuilder()
        serviceBuilder.apply(block)
        patterns.add(ServicePattern(endpoint, serviceBuilder.build()))
    }

    fun propertyPath(subject: RdfTerm, path: PropertyPath, obj: RdfTerm) {
        patterns.add(PropertyPathPattern(subject, path, obj))
    }

    fun quotedTriple(subject: RdfTerm, predicate: RdfTerm, obj: RdfTerm) {
        patterns.add(RdfStarTriplePattern(QuotedTriplePattern(subject, predicate, obj), var_("dummy"), var_("dummy")))
    }

    fun build(): SparqlGraphPattern = when {
        patterns.isEmpty() -> GroupPattern(emptyList())
        patterns.size == 1 -> patterns.first()
        else -> GroupPattern(patterns)
    }
}

/**
 * Builder class for HAVING clauses.
 */
class HavingBuilder {
    val filters = mutableListOf<Filter>()

    fun filter(expression: FilterExpression) {
        filters.add(Filter(expression))
    }
}

// ---- Convenience Functions ----

/**
 * Creates a triple pattern using the DSL syntax.
 */
infix fun RdfTerm.has(predicate: RdfTerm): SubjectPredicatePair = SubjectPredicatePair(this, predicate)

data class SubjectPredicatePair(val subject: RdfTerm, val predicate: RdfTerm) {
    infix fun with(obj: RdfTerm): TriplePattern = TriplePattern(subject, predicate, obj)
}

/**
 * Creates a NOT filter expression.
 */
fun not(expression: FilterExpression): FilterExpression = expression.not()

/**
 * Creates a BIND expression.
 */
fun bind(variable: Var, expression: RdfTerm): Bind = Bind(variable, expression)

/**
 * Creates a conditional expression.
 */
fun if_(condition: FilterExpression, thenValue: RdfTerm, elseValue: RdfTerm): ConditionalExpression = 
    ConditionalExpression(condition, thenValue, elseValue)

/**
 * Creates a UNION pattern.
 */
fun union(left: SparqlGraphPattern, right: SparqlGraphPattern): UnionPattern = UnionPattern(left, right)

/**
 * Creates an OPTIONAL pattern.
 */
fun optional(pattern: SparqlGraphPattern): OptionalPattern = OptionalPattern(pattern)

/**
 * Creates a MINUS pattern.
 */
fun minus(left: SparqlGraphPattern, right: SparqlGraphPattern): MinusPattern = MinusPattern(left, right)

/**
 * Creates a GROUP pattern.
 */
fun group(vararg patterns: SparqlGraphPattern): GroupPattern = GroupPattern(patterns.toList())

/**
 * Creates a VALUES pattern.
 */
fun values(variable: Var, vararg values: RdfTerm): ValuesPattern = 
    ValuesPattern(listOf(variable), values.map { listOf(it) })

/**
 * Creates a VALUES pattern with multiple variables.
 */
fun values(variables: List<Var>, values: List<List<RdfTerm>>): ValuesPattern = 
    ValuesPattern(variables, values)

/**
 * Creates a GRAPH pattern.
 */
fun graph(graphName: RdfTerm, pattern: SparqlGraphPattern): NamedGraphPattern = 
    NamedGraphPattern(graphName, pattern)

/**
 * Creates a SERVICE pattern for federated queries.
 */
fun service(endpoint: RdfTerm, pattern: SparqlGraphPattern): ServicePattern = 
    ServicePattern(endpoint, pattern)

/**
 * Creates an ORDER BY clause for ascending order.
 */
fun asc(variable: Var): OrderClause = OrderClause(variable, OrderDirection.ASC)

/**
 * Creates an ORDER BY clause for descending order.
 */
fun desc(variable: Var): OrderClause = OrderClause(variable, OrderDirection.DESC)

/**
 * Creates a SubSelect pattern.
 */
fun subSelect(query: SelectQuery): SubSelectPattern = SubSelectPattern(query)

/**
 * Creates an RDF-star quoted triple.
 */
fun quotedTriple(subject: RdfTerm, predicate: RdfTerm, obj: RdfTerm): QuotedTriplePattern = 
    QuotedTriplePattern(subject, predicate, obj)

/**
 * Creates a property path pattern using the DSL syntax.
 */
infix fun RdfTerm.has(path: PropertyPath): PropertyPathSubjectPair = PropertyPathSubjectPair(this, path)

data class PropertyPathSubjectPair(val subject: RdfTerm, val path: PropertyPath) {
    infix fun with(obj: RdfTerm): PropertyPathPattern = PropertyPathPattern(subject, path, obj)
}

/**
 * Creates an RDF-star quoted triple using the DSL syntax.
 */
infix fun RdfTerm.quoted(predicate: RdfTerm): QuotedSubjectPredicatePair = QuotedSubjectPredicatePair(this, predicate)

data class QuotedSubjectPredicatePair(val subject: RdfTerm, val predicate: RdfTerm) {
    infix fun with(obj: RdfTerm): QuotedTriplePattern = QuotedTriplePattern(subject, predicate, obj)
}

// ---- SPARQL 1.2 Query Form DSL Functions ----

/**
 * Creates an ASK query.
 */
fun ask(block: AskQueryBuilder.() -> Unit): AskQuery {
    return AskQueryBuilder().apply(block).build()
}

/**
 * Creates a CONSTRUCT query.
 */
fun construct(block: ConstructQueryBuilder.() -> Unit): ConstructQuery {
    return ConstructQueryBuilder().apply(block).build()
}

/**
 * Creates a DESCRIBE query.
 */
fun describe(vararg terms: RdfTerm, block: DescribeQueryBuilder.() -> Unit): DescribeQuery {
    return DescribeQueryBuilder(terms.toList()).apply(block).build()
}

/**
 * Creates a DESCRIBE * query (describes all resources).
 */
fun describeAll(block: DescribeQueryBuilder.() -> Unit): DescribeQuery {
    return DescribeQueryBuilder(emptyList()).apply(block).build()
}

/**
 * Builder class for constructing ASK queries.
 */
class AskQueryBuilder {
    private var version: VersionDeclaration? = null
    private val prefixes = mutableListOf<PrefixDeclaration>()
    private var wherePattern: SparqlGraphPattern? = null

    fun version(version: String) {
        this.version = VersionDeclaration(version)
    }

    fun prefix(prefix: String, namespace: String) {
        prefixes.add(PrefixDeclaration(prefix, namespace))
    }

    fun where(block: WhereBuilder.() -> Unit) {
        val whereBuilder = WhereBuilder()
        whereBuilder.apply(block)
        wherePattern = whereBuilder.build()
    }

    fun build(): AskQuery = AskQuery(
        version = version,
        prefixes = prefixes,
        wherePattern = wherePattern
    )
}

/**
 * Builder class for constructing CONSTRUCT queries.
 */
class ConstructQueryBuilder {
    private var version: VersionDeclaration? = null
    private val prefixes = mutableListOf<PrefixDeclaration>()
    private val constructTemplate = mutableListOf<TriplePattern>()
    private var wherePattern: SparqlGraphPattern? = null

    fun version(version: String) {
        this.version = VersionDeclaration(version)
    }

    fun prefix(prefix: String, namespace: String) {
        prefixes.add(PrefixDeclaration(prefix, namespace))
    }

    fun construct(block: WhereBuilder.() -> Unit) {
        val constructBuilder = WhereBuilder()
        constructBuilder.apply(block)
        // Extract triple patterns from the construct block
        // This is a simplified implementation - in practice you'd need more sophisticated pattern extraction
    }

    fun template(subject: RdfTerm, predicate: RdfTerm, obj: RdfTerm) {
        constructTemplate.add(TriplePattern(subject, predicate, obj))
    }

    fun where(block: WhereBuilder.() -> Unit) {
        val whereBuilder = WhereBuilder()
        whereBuilder.apply(block)
        wherePattern = whereBuilder.build()
    }

    fun build(): ConstructQuery = ConstructQuery(
        version = version,
        prefixes = prefixes,
        constructTemplate = constructTemplate,
        wherePattern = wherePattern
    )
}

/**
 * Builder class for constructing DESCRIBE queries.
 */
class DescribeQueryBuilder(private val describeTerms: List<RdfTerm>) {
    private var version: VersionDeclaration? = null
    private val prefixes = mutableListOf<PrefixDeclaration>()
    private var wherePattern: SparqlGraphPattern? = null

    fun version(version: String) {
        this.version = VersionDeclaration(version)
    }

    fun prefix(prefix: String, namespace: String) {
        prefixes.add(PrefixDeclaration(prefix, namespace))
    }

    fun where(block: WhereBuilder.() -> Unit) {
        val whereBuilder = WhereBuilder()
        whereBuilder.apply(block)
        wherePattern = whereBuilder.build()
    }

    fun build(): DescribeQuery = DescribeQuery(
        version = version,
        prefixes = prefixes,
        describeTerms = describeTerms,
        wherePattern = wherePattern
    )
}

// ---- SELECT Expression Convenience Functions ----

/**
 * Creates an aliased expression for SELECT clauses.
 *
 * ## Usage
 * ```kotlin
 * val query = select {
 *     variable("name")
 *     concat(var_("firstName"), string(" "), var_("lastName")) as_ "fullName"
 *     strlen(var_("name")) as_ "nameLength"
 *     where {
 *         // patterns
 *     }
 * }
 * ```
 *
 * @param alias The alias for the expression
 * @return An aliased select item
 */
infix fun RdfTerm.as_(alias: String): AliasedSelectItem = AliasedSelectItem(this, alias)

/**
 * Adds common prefixes to a SELECT query builder.
 *
 * This extension function provides a convenient way to add multiple
 * common prefixes to a query at once.
 *
 * ## Usage
 * ```kotlin
 * val query = select("name", "age") {
 *     addCommonPrefixes("foaf", "rdf", "rdfs")
 *     where {
 *         person has iri("foaf:name") with var("name")
 *         person has iri("rdf:type") with var("type")
 *     }
 * }
 * ```
 *
 * @param prefixes The names of common prefixes to add
 * @see [CommonPrefixes]
 */
fun SelectQueryBuilder.addCommonPrefixes(vararg prefixes: String) {
    prefixes.forEach { prefix ->
        when (prefix.lowercase()) {
            "foaf" -> this.prefix("foaf", CommonPrefixes.FOAF)
            "rdf" -> this.prefix("rdf", CommonPrefixes.RDF)
            "rdfs" -> this.prefix("rdfs", CommonPrefixes.RDFS)
            "owl" -> this.prefix("owl", CommonPrefixes.OWL)
            "xsd" -> this.prefix("xsd", CommonPrefixes.XSD)
            "dc" -> this.prefix("dc", CommonPrefixes.DC)
            "dcterms" -> this.prefix("dcterms", CommonPrefixes.DCTERMS)
            "schema" -> this.prefix("schema", CommonPrefixes.SCHEMA)
            "dbpedia" -> this.prefix("dbpedia", CommonPrefixes.DBPEDIA)
            "wikidata" -> this.prefix("wikidata", CommonPrefixes.WIKIDATA)
            "skos" -> this.prefix("skos", CommonPrefixes.SKOS)
            else -> throw IllegalArgumentException("Unknown common prefix: $prefix")
        }
    }
}
