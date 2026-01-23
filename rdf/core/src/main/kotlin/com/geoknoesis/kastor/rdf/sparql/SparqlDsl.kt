package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Kotlin-idiomatic SPARQL DSL inspired by Jena SSE.
 * 
 * This DSL provides a type-safe, composable way to build SPARQL queries and updates.
 * It uses Kotlin's DSL features (lambda receivers, infix functions) while maintaining
 * an explicit AST structure similar to Jena SSE.
 * 
 * ## Design Principles
 * - **Type Safety**: @DslMarker prevents scope leaks
 * - **Composability**: Build complex queries from simple components
 * - **Explicit Structure**: Every construct maps to an AST node
 * - **Kotlin Idioms**: Use infix functions, extension functions, and lambda receivers
 * 
 * ## Usage Examples
 * ```kotlin
 * // Simple SELECT query
 * val query = select("name", "age") {
 *     where {
 *         triple(`var`("person"), FOAF.name, `var`("name"))
 *         triple(`var`("person"), FOAF.age, `var`("age"))
 *         filter { `var`("age") gt 18 }
 *     }
 *     orderBy(`var`("age"), OrderDirection.DESC)
 *     limit(10)
 * }
 * 
 * // Complex query with property paths
 * val query2 = select("friend") {
 *     where {
 *         propertyPath(`var`("person"), path(FOAF.knows).oneOrMore(), `var`("friend"))
 *     }
 * }
 * ```
 */

// ============================================================================
// DSL MARKER
// ============================================================================

@DslMarker
annotation class SparqlDslMarker

// ============================================================================
// QUERY BUILDERS
// ============================================================================

/**
 * Creates a SELECT query (returns SparqlSelect for compatibility).
 */
fun select(vararg variables: String, block: SelectBuilder.() -> Unit = {}): SparqlSelect {
    val builder = SelectBuilder(variables.map { VariableSelectItemAst(`var`(it)) })
    builder.apply(block)
    return builder.build().toSparqlSelect()
}

/**
 * Creates a SELECT query with Var objects (returns SparqlSelect for compatibility).
 */
fun select(vararg variables: Var, block: SelectBuilder.() -> Unit = {}): SparqlSelect {
    val builder = SelectBuilder(variables.map { VariableSelectItemAst(it) })
    builder.apply(block)
    return builder.build().toSparqlSelect()
}

/**
 * Creates a SELECT query with no initial variables (returns SparqlSelect for compatibility).
 */
fun select(block: SelectBuilder.() -> Unit): SparqlSelect {
    val builder = SelectBuilder(emptyList())
    builder.apply(block)
    return builder.build().toSparqlSelect()
}

/**
 * Creates an ASK query (returns SparqlAsk for compatibility).
 */
fun ask(block: AskBuilder.() -> Unit = {}): SparqlAsk {
    val builder = AskBuilder()
    builder.apply(block)
    return builder.build().toSparqlAsk()
}

/**
 * Creates a CONSTRUCT query (returns SparqlConstruct for compatibility).
 */
fun construct(block: ConstructBuilder.() -> Unit): SparqlConstruct {
    val builder = ConstructBuilder()
    builder.apply(block)
    return builder.build().toSparqlConstruct()
}

/**
 * Creates a DESCRIBE query (returns SparqlDescribe for compatibility).
 */
fun describe(vararg terms: RdfTerm, block: DescribeBuilder.() -> Unit = {}): SparqlDescribe {
    val builder = DescribeBuilder(terms.toList())
    builder.apply(block)
    return builder.build().toSparqlDescribe()
}

/**
 * Creates a DESCRIBE * query (returns SparqlDescribe for compatibility).
 */
fun describeAll(block: DescribeBuilder.() -> Unit = {}): SparqlDescribe {
    val builder = DescribeBuilder(emptyList())
    builder.apply(block)
    return builder.build().toSparqlDescribe()
}

// ============================================================================
// SELECT BUILDER
// ============================================================================

@SparqlDslMarker
class SelectBuilder(initialItems: List<SelectItemAst>) {
    private val items = initialItems.toMutableList()
    private var version: String? = null
    private val prefixes = mutableListOf<PrefixDeclaration>()
    private var where: GraphPatternAst? = null
    private val from = mutableListOf<Iri>()
    private val fromNamed = mutableListOf<Iri>()
    private val groupBy = mutableListOf<Var>()
    private val having = mutableListOf<FilterExpressionAst>()
    private val orderBy = mutableListOf<OrderClauseAst>()
    private var limit: Int? = null
    private var offset: Int? = null
    private var distinct = false
    private var reduced = false
    
    fun version(version: String) {
        this.version = version
    }
    
    fun prefix(prefix: String, namespace: String) {
        prefixes.add(PrefixDeclaration(prefix, namespace))
    }
    
    fun from(graph: Iri) {
        from.add(graph)
    }
    
    fun fromNamed(graph: Iri) {
        fromNamed.add(graph)
    }
    
    fun variable(name: String) {
        items.add(VariableSelectItemAst(`var`(name)))
    }
    
    fun variable(variable: Var) {
        items.add(VariableSelectItemAst(variable))
    }
    
    fun expression(expr: ExpressionAst, alias: String) {
        items.add(AliasedSelectItemAst(expr, alias))
    }
    
    fun aggregate(function: AggregateFunction, expr: ExpressionAst, alias: String, distinct: Boolean = false) {
        items.add(AliasedSelectItemAst(AggregateExpressionAst(function, expr, distinct), alias))
    }
    
    fun distinct() {
        distinct = true
    }
    
    fun reduced() {
        reduced = true
    }
    
    fun where(block: PatternBuilder.() -> Unit) {
        val builder = PatternBuilder()
        builder.apply(block)
        where = builder.build()
    }
    
    fun groupBy(vararg variables: Var) {
        groupBy.addAll(variables)
    }
    
    fun having(block: HavingBuilder.() -> Unit) {
        val builder = HavingBuilder()
        builder.apply(block)
        having.addAll(builder.filters)
    }
    
    fun orderBy(expr: ExpressionAst, direction: OrderDirection = OrderDirection.ASC) {
        orderBy.add(OrderClauseAst(expr, direction))
    }
    
    fun orderBy(variable: Var, direction: OrderDirection = OrderDirection.ASC) {
        orderBy.add(OrderClauseAst(TermExpressionAst(variable), direction))
    }
    
    fun limit(value: Int) {
        limit = value
    }
    
    fun offset(value: Int) {
        offset = value
    }
    
    fun build(): SelectQueryAst = SelectQueryAst(
        selectItems = items,
        version = version,
        prefixes = prefixes,
        where = where,
        from = from,
        fromNamed = fromNamed,
        groupBy = groupBy,
        having = having,
        orderBy = orderBy,
        limit = limit,
        offset = offset,
        distinct = distinct,
        reduced = reduced
    )
}

// ============================================================================
// ASK BUILDER
// ============================================================================

@SparqlDslMarker
class AskBuilder {
    private var version: String? = null
    private val prefixes = mutableListOf<PrefixDeclaration>()
    private var where: GraphPatternAst? = null
    private val from = mutableListOf<Iri>()
    private val fromNamed = mutableListOf<Iri>()
    
    fun version(version: String) {
        this.version = version
    }
    
    fun prefix(prefix: String, namespace: String) {
        prefixes.add(PrefixDeclaration(prefix, namespace))
    }
    
    fun from(graph: Iri) {
        from.add(graph)
    }
    
    fun fromNamed(graph: Iri) {
        fromNamed.add(graph)
    }
    
    fun where(block: PatternBuilder.() -> Unit) {
        val builder = PatternBuilder()
        builder.apply(block)
        where = builder.build()
    }
    
    fun build(): AskQueryAst = AskQueryAst(
        version = version,
        prefixes = prefixes,
        where = where,
        from = from,
        fromNamed = fromNamed
    )
}

// ============================================================================
// CONSTRUCT BUILDER
// ============================================================================

@SparqlDslMarker
class ConstructBuilder {
    private var version: String? = null
    private val prefixes = mutableListOf<PrefixDeclaration>()
    private val template = mutableListOf<TriplePatternAst>()
    private var where: GraphPatternAst? = null
    private val from = mutableListOf<Iri>()
    private val fromNamed = mutableListOf<Iri>()
    
    fun version(version: String) {
        this.version = version
    }
    
    fun prefix(prefix: String, namespace: String) {
        prefixes.add(PrefixDeclaration(prefix, namespace))
    }
    
    fun from(graph: Iri) {
        from.add(graph)
    }
    
    fun fromNamed(graph: Iri) {
        fromNamed.add(graph)
    }
    
    fun template(block: PatternBuilder.() -> Unit) {
        val builder = PatternBuilder()
        builder.apply(block)
        // Extract triple patterns from the pattern builder
        extractTriplePatterns(builder.build(), template)
    }
    
    fun where(block: PatternBuilder.() -> Unit) {
        val builder = PatternBuilder()
        builder.apply(block)
        where = builder.build()
    }
    
    private fun extractTriplePatterns(pattern: GraphPatternAst, result: MutableList<TriplePatternAst>) {
        when (pattern) {
            is TriplePatternAst -> result.add(pattern)
            is GroupPatternAst -> pattern.patterns.forEach { extractTriplePatterns(it, result) }
            else -> {} // Other patterns not valid in CONSTRUCT template
        }
    }
    
    fun build(): ConstructQueryAst = ConstructQueryAst(
        template = template,
        version = version,
        prefixes = prefixes,
        where = where,
        from = from,
        fromNamed = fromNamed
    )
}

// ============================================================================
// DESCRIBE BUILDER
// ============================================================================

@SparqlDslMarker
class DescribeBuilder(private val initialTerms: List<RdfTerm>) {
    private var version: String? = null
    private val prefixes = mutableListOf<PrefixDeclaration>()
    private var where: GraphPatternAst? = null
    private val from = mutableListOf<Iri>()
    private val fromNamed = mutableListOf<Iri>()
    
    fun version(version: String) {
        this.version = version
    }
    
    fun prefix(prefix: String, namespace: String) {
        prefixes.add(PrefixDeclaration(prefix, namespace))
    }
    
    fun from(graph: Iri) {
        from.add(graph)
    }
    
    fun fromNamed(graph: Iri) {
        fromNamed.add(graph)
    }
    
    fun where(block: PatternBuilder.() -> Unit) {
        val builder = PatternBuilder()
        builder.apply(block)
        where = builder.build()
    }
    
    fun build(): DescribeQueryAst = DescribeQueryAst(
        describeTerms = initialTerms,
        version = version,
        prefixes = prefixes,
        where = where,
        from = from,
        fromNamed = fromNamed
    )
}

// ============================================================================
// PATTERN BUILDER
// ============================================================================

@SparqlDslMarker
class PatternBuilder {
    private val patterns = mutableListOf<GraphPatternAst>()
    
    fun triple(subject: RdfTerm, predicate: RdfTerm, obj: RdfTerm) {
        patterns.add(TriplePatternAst(subject, predicate, obj))
    }
    
    fun optional(block: PatternBuilder.() -> Unit) {
        val builder = PatternBuilder()
        builder.apply(block)
        patterns.add(OptionalPatternAst(builder.build()))
    }
    
    fun union(block: PatternBuilder.() -> Unit) {
        if (patterns.isEmpty()) {
            val builder = PatternBuilder()
            builder.apply(block)
            patterns.add(builder.build())
        } else {
            val builder = PatternBuilder()
            builder.apply(block)
            val last = patterns.removeAt(patterns.size - 1)
            patterns.add(UnionPatternAst(last, builder.build()))
        }
    }
    
    fun minus(block: PatternBuilder.() -> Unit) {
        if (patterns.isEmpty()) {
            val builder = PatternBuilder()
            builder.apply(block)
            patterns.add(builder.build())
        } else {
            val builder = PatternBuilder()
            builder.apply(block)
            val last = patterns.removeAt(patterns.size - 1)
            patterns.add(MinusPatternAst(last, builder.build()))
        }
    }
    
    fun graph(graphName: RdfTerm, block: PatternBuilder.() -> Unit) {
        val builder = PatternBuilder()
        builder.apply(block)
        patterns.add(GraphPatternAstImpl(graphName, builder.build()))
    }
    
    fun service(endpoint: RdfTerm, block: PatternBuilder.() -> Unit) {
        val builder = PatternBuilder()
        builder.apply(block)
        patterns.add(ServicePatternAst(endpoint, builder.build()))
    }
    
    fun values(variable: Var, vararg values: RdfTerm) {
        patterns.add(ValuesPatternAst(listOf(variable), values.map { listOf(it) }))
    }
    
    fun values(variables: List<Var>, values: List<List<RdfTerm>>) {
        patterns.add(ValuesPatternAst(variables, values))
    }
    
    fun propertyPath(subject: RdfTerm, path: PropertyPathAst, obj: RdfTerm) {
        patterns.add(PropertyPathPatternAst(subject, path, obj))
    }
    
    fun quotedTriple(subject: RdfTerm, predicate: RdfTerm, obj: RdfTerm) {
        patterns.add(QuotedTriplePatternAst(subject, predicate, obj))
    }
    
    fun bind(variable: Var, expression: ExpressionAst) {
        patterns.add(BindPatternAst(variable, expression))
    }
    
    // Convenience: bind with RdfTerm (converts to ExpressionAst)
    fun bind(variable: Var, term: RdfTerm) {
        patterns.add(BindPatternAst(variable, TermExpressionAst(term)))
    }
    
    fun filter(block: FilterBuilder.() -> FilterExpressionAst) {
        val builder = FilterBuilder()
        patterns.add(FilterPatternAst(builder.block()))
    }
    
    fun filter(expression: FilterExpressionAst) {
        patterns.add(FilterPatternAst(expression))
    }
    
    // Convenience: filter with direct expression
    fun filter(expr: () -> FilterExpressionAst) {
        patterns.add(FilterPatternAst(expr()))
    }
    
    fun subSelect(block: SelectBuilder.() -> Unit) {
        val builder = SelectBuilder(emptyList())
        builder.apply(block)
        patterns.add(SubSelectPatternAst(builder.build()))
    }
    
    fun build(): GraphPatternAst = GroupPatternAst(patterns)
}

// ============================================================================
// FILTER BUILDER
// ============================================================================

@SparqlDslMarker
class FilterBuilder {
    infix fun ExpressionAst.eq(other: ExpressionAst): FilterExpressionAst =
        ComparisonExpressionAst(this, ComparisonOperator.EQ, other)
    
    infix fun ExpressionAst.ne(other: ExpressionAst): FilterExpressionAst =
        ComparisonExpressionAst(this, ComparisonOperator.NE, other)
    
    infix fun ExpressionAst.lt(other: ExpressionAst): FilterExpressionAst =
        ComparisonExpressionAst(this, ComparisonOperator.LT, other)
    
    infix fun ExpressionAst.lte(other: ExpressionAst): FilterExpressionAst =
        ComparisonExpressionAst(this, ComparisonOperator.LTE, other)
    
    infix fun ExpressionAst.gt(other: ExpressionAst): FilterExpressionAst =
        ComparisonExpressionAst(this, ComparisonOperator.GT, other)
    
    infix fun ExpressionAst.gte(other: ExpressionAst): FilterExpressionAst =
        ComparisonExpressionAst(this, ComparisonOperator.GTE, other)
    
    infix fun FilterExpressionAst.and(other: FilterExpressionAst): FilterExpressionAst =
        AndExpressionAst(this, other)
    
    infix fun FilterExpressionAst.or(other: FilterExpressionAst): FilterExpressionAst =
        OrExpressionAst(this, other)
    
    fun not(expr: FilterExpressionAst): FilterExpressionAst = NotExpressionAst(expr)
}

// ============================================================================
// HAVING BUILDER
// ============================================================================

@SparqlDslMarker
class HavingBuilder {
    val filters = mutableListOf<FilterExpressionAst>()
    
    fun filter(expression: FilterExpressionAst) {
        filters.add(expression)
    }
}

// ============================================================================
// PROPERTY PATH BUILDERS
// ============================================================================

fun path(term: RdfTerm): PropertyPathAst = BasicPathAst(term)

fun PropertyPathAst.oneOrMore(): PropertyPathAst = OneOrMorePathAst(this)
fun PropertyPathAst.zeroOrMore(): PropertyPathAst = ZeroOrMorePathAst(this)
fun PropertyPathAst.zeroOrOne(): PropertyPathAst = ZeroOrOnePathAst(this)
fun PropertyPathAst.inverse(): PropertyPathAst = InversePathAst(this)
fun PropertyPathAst.negation(): PropertyPathAst = NegationPathAst(this)

infix fun PropertyPathAst.alternative(other: PropertyPathAst): PropertyPathAst =
    AlternativePathAst(this, other)

infix fun PropertyPathAst.sequence(other: PropertyPathAst): PropertyPathAst =
    SequencePathAst(this, other)

fun PropertyPathAst.exactly(n: Int): PropertyPathAst = RangePathAst(this, n, n)
fun PropertyPathAst.atLeast(n: Int): PropertyPathAst = RangePathAst(this, n, null)
fun PropertyPathAst.atMost(m: Int): PropertyPathAst = RangePathAst(this, 0, m)
fun PropertyPathAst.between(n: Int, m: Int): PropertyPathAst = RangePathAst(this, n, m)

// ============================================================================
// EXPRESSION BUILDERS
// ============================================================================

// Term expressions - use extension function RdfTerm.expr() instead

// Comparison operators (infix)
infix fun ExpressionAst.eq(other: ExpressionAst): FilterExpressionAst =
    ComparisonExpressionAst(this, ComparisonOperator.EQ, other)

infix fun ExpressionAst.ne(other: ExpressionAst): FilterExpressionAst =
    ComparisonExpressionAst(this, ComparisonOperator.NE, other)

infix fun ExpressionAst.lt(other: ExpressionAst): FilterExpressionAst =
    ComparisonExpressionAst(this, ComparisonOperator.LT, other)

infix fun ExpressionAst.lte(other: ExpressionAst): FilterExpressionAst =
    ComparisonExpressionAst(this, ComparisonOperator.LTE, other)

infix fun ExpressionAst.gt(other: ExpressionAst): FilterExpressionAst =
    ComparisonExpressionAst(this, ComparisonOperator.GT, other)

infix fun ExpressionAst.gte(other: ExpressionAst): FilterExpressionAst =
    ComparisonExpressionAst(this, ComparisonOperator.GTE, other)

// Convenience overloads for common types
infix fun Var.eq(value: String): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.EQ, TermExpressionAst(string(value)))

infix fun Var.eq(value: Int): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.EQ, TermExpressionAst(value.toLiteral()))

infix fun Var.eq(value: Long): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.EQ, TermExpressionAst(value.toLiteral()))

infix fun Var.eq(value: Double): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.EQ, TermExpressionAst(value.toLiteral()))

infix fun Var.eq(value: Boolean): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.EQ, TermExpressionAst(boolean(value)))

// Convenience: eq with Literal
infix fun Var.eq(literal: Literal): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.EQ, TermExpressionAst(literal))

infix fun Var.gt(value: Int): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.GT, TermExpressionAst(value.toLiteral()))

infix fun Var.gt(value: Long): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.GT, TermExpressionAst(value.toLiteral()))

infix fun Var.gt(value: Double): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.GT, TermExpressionAst(value.toLiteral()))

infix fun Var.lt(value: Int): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.LT, TermExpressionAst(value.toLiteral()))

infix fun Var.lt(value: Long): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.LT, TermExpressionAst(value.toLiteral()))

infix fun Var.lt(value: Double): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.LT, TermExpressionAst(value.toLiteral()))

infix fun Var.gte(value: Int): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.GTE, TermExpressionAst(value.toLiteral()))

infix fun Var.lte(value: Int): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.LTE, TermExpressionAst(value.toLiteral()))

infix fun Var.ne(value: String): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.NE, TermExpressionAst(string(value)))

infix fun Var.ne(value: Int): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.NE, TermExpressionAst(value.toLiteral()))

infix fun Var.ne(value: Long): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.NE, TermExpressionAst(value.toLiteral()))

infix fun Var.ne(value: Double): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.NE, TermExpressionAst(value.toLiteral()))

infix fun Var.ne(value: Boolean): FilterExpressionAst =
    ComparisonExpressionAst(TermExpressionAst(this), ComparisonOperator.NE, TermExpressionAst(boolean(value)))

// Logical operators
infix fun FilterExpressionAst.and(other: FilterExpressionAst): FilterExpressionAst =
    AndExpressionAst(this, other)

infix fun FilterExpressionAst.or(other: FilterExpressionAst): FilterExpressionAst =
    OrExpressionAst(this, other)

fun not(expr: FilterExpressionAst): FilterExpressionAst = NotExpressionAst(expr)

// Built-in functions
fun function(name: String, vararg args: ExpressionAst): ExpressionAst =
    FunctionCallAst(name, args.toList())

// Common SPARQL functions
fun bound(variable: Var): FilterExpressionAst = FunctionCallAst("BOUND", listOf(TermExpressionAst(variable)))
fun isIRI(expr: ExpressionAst): FilterExpressionAst = FunctionCallAst("isIRI", listOf(expr))
fun isBlank(expr: ExpressionAst): FilterExpressionAst = FunctionCallAst("isBLANK", listOf(expr))
fun isLiteral(expr: ExpressionAst): FilterExpressionAst = FunctionCallAst("isLITERAL", listOf(expr))
fun isNumeric(expr: ExpressionAst): FilterExpressionAst = FunctionCallAst("isNUMERIC", listOf(expr))
fun regex(variable: Var, pattern: String, flags: String? = null): FilterExpressionAst {
    val args = mutableListOf<ExpressionAst>(TermExpressionAst(variable), TermExpressionAst(string(pattern)))
    flags?.let { args.add(TermExpressionAst(string(it))) }
    return FunctionCallAst("REGEX", args)
}

fun concat(vararg terms: ExpressionAst): ExpressionAst = FunctionCallAst("CONCAT", terms.toList())
fun strlen(expr: ExpressionAst): ExpressionAst = FunctionCallAst("STRLEN", listOf(expr))
fun ucase(expr: ExpressionAst): ExpressionAst = FunctionCallAst("UCASE", listOf(expr))
fun lcase(expr: ExpressionAst): ExpressionAst = FunctionCallAst("LCASE", listOf(expr))
fun substr(expr: ExpressionAst, start: Int, length: Int? = null): ExpressionAst {
    val args = mutableListOf<ExpressionAst>(expr, TermExpressionAst(start.toLiteral()))
    length?.let { args.add(TermExpressionAst(it.toLiteral())) }
    return FunctionCallAst("SUBSTR", args)
}

// SPARQL 1.2 functions
fun triple(s: RdfTerm, p: RdfTerm, o: RdfTerm): ExpressionAst =
    FunctionCallAst("TRIPLE", listOf(TermExpressionAst(s), TermExpressionAst(p), TermExpressionAst(o)))

fun isTriple(expr: ExpressionAst): FilterExpressionAst = FunctionCallAst("isTRIPLE", listOf(expr))
fun subject(expr: ExpressionAst): ExpressionAst = FunctionCallAst("SUBJECT", listOf(expr))
fun predicate(expr: ExpressionAst): ExpressionAst = FunctionCallAst("PREDICATE", listOf(expr))
fun `object`(expr: ExpressionAst): ExpressionAst = FunctionCallAst("OBJECT", listOf(expr))

fun langdir(expr: ExpressionAst): ExpressionAst = FunctionCallAst("LANGDIR", listOf(expr))
fun hasLang(expr: ExpressionAst, lang: String): FilterExpressionAst =
    FunctionCallAst("hasLANG", listOf(expr, TermExpressionAst(string(lang))))

fun hasLangdir(expr: ExpressionAst, dir: String): FilterExpressionAst =
    FunctionCallAst("hasLANGDIR", listOf(expr, TermExpressionAst(string(dir))))

fun strlangdir(expr: ExpressionAst, lang: String, dir: String): ExpressionAst =
    FunctionCallAst("STRLANGDIR", listOf(expr, TermExpressionAst(string(lang)), TermExpressionAst(string(dir))))

fun replace(expr: ExpressionAst, pattern: String, replacement: String): ExpressionAst =
    FunctionCallAst("REPLACE", listOf(expr, TermExpressionAst(string(pattern)), TermExpressionAst(string(replacement))))

fun replaceAll(expr: ExpressionAst, pattern: String, replacement: String): ExpressionAst =
    FunctionCallAst("REPLACE_ALL", listOf(expr, TermExpressionAst(string(pattern)), TermExpressionAst(string(replacement))))

fun encodeForUri(expr: ExpressionAst): ExpressionAst = FunctionCallAst("ENCODE_FOR_URI", listOf(expr))
fun decodeForUri(expr: ExpressionAst): ExpressionAst = FunctionCallAst("DECODE_FOR_URI", listOf(expr))
fun contains(expr: ExpressionAst, substring: String): FilterExpressionAst =
    FunctionCallAst("CONTAINS", listOf(expr, TermExpressionAst(string(substring))))

fun startsWith(expr: ExpressionAst, prefix: String): FilterExpressionAst =
    FunctionCallAst("STARTS_WITH", listOf(expr, TermExpressionAst(string(prefix))))

fun endsWith(expr: ExpressionAst, suffix: String): FilterExpressionAst =
    FunctionCallAst("ENDS_WITH", listOf(expr, TermExpressionAst(string(suffix))))

fun strBefore(expr: ExpressionAst, substring: String): ExpressionAst =
    FunctionCallAst("STRBEFORE", listOf(expr, TermExpressionAst(string(substring))))

fun strAfter(expr: ExpressionAst, substring: String): ExpressionAst =
    FunctionCallAst("STRAFTER", listOf(expr, TermExpressionAst(string(substring))))

fun rand(): ExpressionAst = FunctionCallAst("RAND", emptyList())
fun random(): ExpressionAst = FunctionCallAst("RANDOM", emptyList())
fun now(): ExpressionAst = FunctionCallAst("NOW", emptyList())
fun timezone(): ExpressionAst = FunctionCallAst("TIMEZONE", emptyList())
fun tz(expr: ExpressionAst): ExpressionAst = FunctionCallAst("TZ", listOf(expr))
fun dateTime(expr: ExpressionAst): ExpressionAst = FunctionCallAst("DATETIME", listOf(expr))
fun date(expr: ExpressionAst): ExpressionAst = FunctionCallAst("DATE", listOf(expr))
fun time(expr: ExpressionAst): ExpressionAst = FunctionCallAst("TIME", listOf(expr))

// Conditional expression
fun if_(condition: FilterExpressionAst, thenValue: ExpressionAst, elseValue: ExpressionAst): ExpressionAst =
    ConditionalExpressionAst(condition, thenValue, elseValue)

// Aggregate functions
fun count(expr: ExpressionAst, distinct: Boolean = false): AggregateExpressionAst =
    AggregateExpressionAst(AggregateFunction.COUNT, expr, distinct)

fun sum(expr: ExpressionAst, distinct: Boolean = false): AggregateExpressionAst =
    AggregateExpressionAst(AggregateFunction.SUM, expr, distinct)

fun avg(expr: ExpressionAst, distinct: Boolean = false): AggregateExpressionAst =
    AggregateExpressionAst(AggregateFunction.AVG, expr, distinct)

fun min(expr: ExpressionAst): AggregateExpressionAst =
    AggregateExpressionAst(AggregateFunction.MIN, expr, false)

fun max(expr: ExpressionAst): AggregateExpressionAst =
    AggregateExpressionAst(AggregateFunction.MAX, expr, false)

fun groupConcat(expr: ExpressionAst, distinct: Boolean = false): AggregateExpressionAst =
    AggregateExpressionAst(AggregateFunction.GROUP_CONCAT, expr, distinct)

fun sample(expr: ExpressionAst): AggregateExpressionAst =
    AggregateExpressionAst(AggregateFunction.SAMPLE, expr, false)

// Arithmetic operators
infix fun ExpressionAst.plus(other: ExpressionAst): ExpressionAst =
    ArithmeticExpressionAst(this, ArithmeticOperator.ADD, other)

infix fun ExpressionAst.minus(other: ExpressionAst): ExpressionAst =
    ArithmeticExpressionAst(this, ArithmeticOperator.SUBTRACT, other)

infix fun ExpressionAst.times(other: ExpressionAst): ExpressionAst =
    ArithmeticExpressionAst(this, ArithmeticOperator.MULTIPLY, other)

infix fun ExpressionAst.div(other: ExpressionAst): ExpressionAst =
    ArithmeticExpressionAst(this, ArithmeticOperator.DIVIDE, other)

// Convenience: Var as ExpressionAst
fun Var.expr(): ExpressionAst = TermExpressionAst(this)

// Convenience: RdfTerm as ExpressionAst
fun RdfTerm.expr(): ExpressionAst = TermExpressionAst(this)

// ============================================================================
// UPDATE BUILDERS
// ============================================================================

/**
 * Creates a SPARQL UPDATE request (returns UpdateQuery for compatibility).
 */
fun update(block: UpdateBuilder.() -> Unit): UpdateQuery {
    val builder = UpdateBuilder()
    builder.apply(block)
    return builder.build().toUpdateQuery()
}

@SparqlDslMarker
class UpdateBuilder {
    private var version: String? = null
    private val prefixes = mutableListOf<PrefixDeclaration>()
    private val operations = mutableListOf<UpdateOperationAst>()
    
    fun version(version: String) {
        this.version = version
    }
    
    fun prefix(prefix: String, namespace: String) {
        prefixes.add(PrefixDeclaration(prefix, namespace))
    }
    
    fun insertData(block: InsertDataBuilder.() -> Unit) {
        val builder = InsertDataBuilder()
        builder.apply(block)
        operations.add(builder.build())
    }
    
    fun deleteData(block: DeleteDataBuilder.() -> Unit) {
        val builder = DeleteDataBuilder()
        builder.apply(block)
        operations.add(builder.build())
    }
    
    fun modify(block: ModifyBuilder.() -> Unit) {
        val builder = ModifyBuilder()
        builder.apply(block)
        operations.add(builder.build())
    }
    
    fun deleteWhere(block: DeleteWhereBuilder.() -> Unit) {
        val builder = DeleteWhereBuilder()
        builder.apply(block)
        operations.add(builder.build())
    }
    
    fun load(source: Iri, into: Iri? = null, silent: Boolean = false) {
        operations.add(LoadOperationAst(source, into, silent, emptyList(), emptyList(), null))
    }
    
    fun clear(graph: Iri? = null, silent: Boolean = false) {
        operations.add(ClearOperationAst(graph, silent, emptyList(), emptyList(), null))
    }
    
    fun create(graph: Iri, silent: Boolean = false) {
        operations.add(CreateOperationAst(graph, silent, emptyList(), emptyList(), null))
    }
    
    fun drop(graph: Iri? = null, silent: Boolean = false) {
        operations.add(DropOperationAst(graph, silent, emptyList(), emptyList(), null))
    }
    
    fun copy(source: Iri, destination: Iri, silent: Boolean = false) {
        operations.add(CopyOperationAst(source, destination, silent, emptyList(), emptyList(), null))
    }
    
    fun move(source: Iri, destination: Iri, silent: Boolean = false) {
        operations.add(MoveOperationAst(source, destination, silent, emptyList(), emptyList(), null))
    }
    
    fun add(source: Iri, destination: Iri, silent: Boolean = false) {
        operations.add(AddOperationAst(source, destination, silent, emptyList(), emptyList(), null))
    }
    
    fun build(): UpdateRequestAst = UpdateRequestAst(
        version = version,
        prefixes = prefixes,
        operations = operations
    )
}

@SparqlDslMarker
class InsertDataBuilder {
    private val data = mutableListOf<TriplePatternAst>()
    private val using = mutableListOf<Iri>()
    private val usingNamed = mutableListOf<Iri>()
    private var with: Iri? = null
    
    fun using(graph: Iri) {
        using.add(graph)
    }
    
    fun usingNamed(graph: Iri) {
        usingNamed.add(graph)
    }
    
    fun with(graph: Iri) {
        with = graph
    }
    
    fun triple(subject: RdfTerm, predicate: RdfTerm, obj: RdfTerm) {
        data.add(TriplePatternAst(subject, predicate, obj))
    }
    
    fun build(): InsertDataOperationAst = InsertDataOperationAst(
        data = data,
        using = using,
        usingNamed = usingNamed,
        with = with
    )
}

@SparqlDslMarker
class DeleteDataBuilder {
    private val data = mutableListOf<TriplePatternAst>()
    private val using = mutableListOf<Iri>()
    private val usingNamed = mutableListOf<Iri>()
    private var with: Iri? = null
    
    fun using(graph: Iri) {
        using.add(graph)
    }
    
    fun usingNamed(graph: Iri) {
        usingNamed.add(graph)
    }
    
    fun with(graph: Iri) {
        with = graph
    }
    
    fun triple(subject: RdfTerm, predicate: RdfTerm, obj: RdfTerm) {
        data.add(TriplePatternAst(subject, predicate, obj))
    }
    
    fun build(): DeleteDataOperationAst = DeleteDataOperationAst(
        data = data,
        using = using,
        usingNamed = usingNamed,
        with = with
    )
}

@SparqlDslMarker
class ModifyBuilder {
    private val delete = mutableListOf<TriplePatternAst>()
    private val insert = mutableListOf<TriplePatternAst>()
    private var where: GraphPatternAst? = null
    private val using = mutableListOf<Iri>()
    private val usingNamed = mutableListOf<Iri>()
    private var with: Iri? = null
    
    fun using(graph: Iri) {
        using.add(graph)
    }
    
    fun usingNamed(graph: Iri) {
        usingNamed.add(graph)
    }
    
    fun with(graph: Iri) {
        with = graph
    }
    
    fun delete(block: PatternBuilder.() -> Unit) {
        val builder = PatternBuilder()
        builder.apply(block)
        extractTriplePatterns(builder.build(), delete)
    }
    
    fun insert(block: PatternBuilder.() -> Unit) {
        val builder = PatternBuilder()
        builder.apply(block)
        extractTriplePatterns(builder.build(), insert)
    }
    
    fun where(block: PatternBuilder.() -> Unit) {
        val builder = PatternBuilder()
        builder.apply(block)
        where = builder.build()
    }
    
    private fun extractTriplePatterns(pattern: GraphPatternAst, result: MutableList<TriplePatternAst>) {
        when (pattern) {
            is TriplePatternAst -> result.add(pattern)
            is GroupPatternAst -> pattern.patterns.forEach { extractTriplePatterns(it, result) }
            else -> {} // Other patterns not valid in DELETE/INSERT
        }
    }
    
    fun build(): ModifyOperationAst = ModifyOperationAst(
        delete = delete,
        insert = insert,
        where = where,
        using = using,
        usingNamed = usingNamed,
        with = with
    )
}

@SparqlDslMarker
class DeleteWhereBuilder {
    private var where: GraphPatternAst? = null
    private val using = mutableListOf<Iri>()
    private val usingNamed = mutableListOf<Iri>()
    private var with: Iri? = null
    
    fun using(graph: Iri) {
        using.add(graph)
    }
    
    fun usingNamed(graph: Iri) {
        usingNamed.add(graph)
    }
    
    fun with(graph: Iri) {
        with = graph
    }
    
    fun where(block: PatternBuilder.() -> Unit) {
        val builder = PatternBuilder()
        builder.apply(block)
        where = builder.build()
    }
    
    fun build(): DeleteWhereOperationAst = DeleteWhereOperationAst(
        where = where ?: GroupPatternAst(emptyList()),
        using = using,
        usingNamed = usingNamed,
        with = with
    )
}

