package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*

/**
 * SPARQL Abstract Syntax Tree (AST) model.
 * 
 * This module provides a complete AST representation of SPARQL queries and updates,
 * inspired by Jena SSE but designed to be Kotlin-idiomatic. The AST is separate
 * from rendering logic, allowing for multiple renderers or query optimizations.
 * 
 * ## Design Principles
 * - **Explicit Structure**: Every SPARQL construct has a corresponding AST node
 * - **Type Safety**: Sealed interfaces ensure exhaustive pattern matching
 * - **Composability**: Complex queries built from simple components
 * - **Separation of Concerns**: AST separate from rendering/execution
 */

// ============================================================================
// QUERY FORMS
// ============================================================================

/**
 * Base interface for all SPARQL query forms.
 */
sealed interface SparqlQueryAst {
    val version: String?
    val prefixes: List<PrefixDeclaration>
}

/**
 * SELECT query AST.
 */
data class SelectQueryAst(
    val selectItems: List<SelectItemAst>,
    override val version: String? = null,
    override val prefixes: List<PrefixDeclaration> = emptyList(),
    val where: GraphPatternAst? = null,
    val from: List<Iri> = emptyList(),
    val fromNamed: List<Iri> = emptyList(),
    val groupBy: List<Var> = emptyList(),
    val having: List<FilterExpressionAst> = emptyList(),
    val orderBy: List<OrderClauseAst> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null,
    val distinct: Boolean = false,
    val reduced: Boolean = false
) : SparqlQueryAst {
    init {
        require(limit == null || limit >= 0) { "LIMIT must not be negative: $limit" }
        require(offset == null || offset >= 0) { "OFFSET must not be negative: $offset" }
        require(WildcardSelectItemAst !in selectItems || selectItems.size == 1) {
            "SELECT * cannot be combined with other projection items"
        }
        require((groupBy.isEmpty() && having.isEmpty()) || (selectItems.isNotEmpty() && WildcardSelectItemAst !in selectItems)) {
            "SELECT * (or an empty projection) is not legal with GROUP BY or HAVING; project the grouped variables and aggregates explicitly"
        }
    }
}

/**
 * ASK query AST.
 */
data class AskQueryAst(
    override val version: String? = null,
    override val prefixes: List<PrefixDeclaration> = emptyList(),
    val where: GraphPatternAst? = null,
    val from: List<Iri> = emptyList(),
    val fromNamed: List<Iri> = emptyList()
) : SparqlQueryAst

/**
 * CONSTRUCT query AST.
 */
data class ConstructQueryAst(
    val template: List<TriplePatternAst>,
    override val version: String? = null,
    override val prefixes: List<PrefixDeclaration> = emptyList(),
    val where: GraphPatternAst? = null,
    val from: List<Iri> = emptyList(),
    val fromNamed: List<Iri> = emptyList()
) : SparqlQueryAst

/**
 * DESCRIBE query AST.
 */
data class DescribeQueryAst(
    val describeTerms: List<RdfTerm>,
    override val version: String? = null,
    override val prefixes: List<PrefixDeclaration> = emptyList(),
    val where: GraphPatternAst? = null,
    val from: List<Iri> = emptyList(),
    val fromNamed: List<Iri> = emptyList()
) : SparqlQueryAst

// ============================================================================
// SELECT ITEMS
// ============================================================================

/**
 * Represents an item in a SELECT clause.
 */
sealed interface SelectItemAst

/**
 * Simple variable in SELECT.
 */
data class VariableSelectItemAst(val variable: Var) : SelectItemAst

/**
 * Expression with alias in SELECT.
 */
data class AliasedSelectItemAst(
    val expression: ExpressionAst,
    val alias: String
) : SelectItemAst

/**
 * Wildcard SELECT *.
 */
object WildcardSelectItemAst : SelectItemAst

// ============================================================================
// GRAPH PATTERNS
// ============================================================================

/**
 * Base interface for all SPARQL graph patterns.
 */
sealed interface GraphPatternAst

/**
 * Triple pattern: subject predicate object .
 */
data class TriplePatternAst(
    val subject: RdfTerm,
    val predicate: RdfTerm,
    val obj: RdfTerm
) : GraphPatternAst

/**
 * Group of graph patterns: { pattern1 . pattern2 . ... }
 */
data class GroupPatternAst(
    val patterns: List<GraphPatternAst>
) : GraphPatternAst

/**
 * OPTIONAL pattern: OPTIONAL { pattern }
 */
data class OptionalPatternAst(
    val pattern: GraphPatternAst
) : GraphPatternAst

/**
 * UNION pattern: { pattern1 } UNION { pattern2 }
 */
data class UnionPatternAst(
    val left: GraphPatternAst,
    val right: GraphPatternAst
) : GraphPatternAst

/**
 * MINUS pattern: `{ left MINUS { right } }`.
 *
 * [right] is subtracted from [left] only. A non-empty [left] is rendered as its own group so the
 * MINUS never applies to patterns that precede it in the enclosing group; an empty [left] renders a
 * bare `MINUS { right }`, which (as in SPARQL) applies to everything before it in the current group.
 */
data class MinusPatternAst(
    val left: GraphPatternAst,
    val right: GraphPatternAst
) : GraphPatternAst

/**
 * GRAPH pattern: GRAPH graphName { pattern }
 */
data class GraphPatternAstImpl(
    val graphName: RdfTerm,
    val pattern: GraphPatternAst
) : GraphPatternAst

/**
 * SERVICE pattern: SERVICE endpoint { pattern }
 */
data class ServicePatternAst(
    val endpoint: RdfTerm,
    val pattern: GraphPatternAst
) : GraphPatternAst

/**
 * VALUES clause: VALUES ?var { value1 value2 ... }
 *
 * Each row must have one entry per variable. A `null` entry renders as `UNDEF`.
 */
data class ValuesPatternAst(
    val variables: List<Var>,
    val values: List<List<RdfTerm?>>
) : GraphPatternAst {
    init {
        require(values.all { it.size == variables.size }) {
            "Every VALUES row must have exactly ${variables.size} entries"
        }
    }
}

/**
 * Property path pattern: subject path object
 */
data class PropertyPathPatternAst(
    val subject: RdfTerm,
    val path: PropertyPathAst,
    val obj: RdfTerm
) : GraphPatternAst

/**
 * RDF 1.2 triple-term pattern: `<<( subject predicate object )>>`.
 *
 * Triple terms in RDF 1.2 are object-position only. This AST node is consumed
 * by [TripleTermObjectPatternAst] and [ReifierPatternAst] which place it in a
 * legal position; the renderer rejects it when used as a standalone pattern.
 */
data class TripleTermPatternAst(
    val subject: RdfTerm,
    val predicate: RdfTerm,
    val obj: RdfTerm
) : GraphPatternAst

/**
 * Triple pattern with a triple term in the object position:
 * `subject predicate <<( s p o )>>` (RDF 1.2).
 */
data class TripleTermObjectPatternAst(
    val subject: RdfTerm,
    val predicate: RdfTerm,
    val tripleTerm: TripleTermPatternAst,
) : GraphPatternAst

/**
 * RDF 1.2 reifier pattern: `subject rdf:reifies <<( s p o )>>` (rendered with the
 * full `rdf:reifies` IRI, so no prefix declaration is needed).
 *
 * `subject` is the reifier (an IRI, blank node, or variable) and the triple
 * term is the triple it names. Other graph patterns can attach metadata to the
 * same reifier alongside this one.
 */
data class ReifierPatternAst(
    val reifier: RdfTerm,
    val tripleTerm: TripleTermPatternAst,
) : GraphPatternAst

/**
 * Legacy RDF-star quoted triple pattern: `<< subject predicate object >>`.
 * Renders as the SPARQL 1.2 reified-triple block `<< s p o >> .`, which matches
 * any reifier of the triple. New code should use [ReifierPatternAst] or
 * [TripleTermObjectPatternAst].
 */
@Deprecated(
    message = "Use TripleTermPatternAst (RDF 1.2). The renderer still emits valid RDF 1.2 syntax.",
    replaceWith = ReplaceWith("TripleTermPatternAst(subject, predicate, obj)"),
)
data class QuotedTriplePatternAst(
    val subject: RdfTerm,
    val predicate: RdfTerm,
    val obj: RdfTerm
) : GraphPatternAst

/**
 * Legacy RDF-star triple pattern with a quoted triple as subject. Deprecated
 * because RDF 1.2 forbids subject-position triple terms; use
 * [ReifierPatternAst] (with `rdf:reifies`) instead.
 */
@Deprecated(
    message = "Subject-position triple terms are forbidden in RDF 1.2. Use ReifierPatternAst.",
    replaceWith = ReplaceWith("ReifierPatternAst(reifier, tripleTerm)"),
)
data class RdfStarTriplePatternAst(
    val quotedTriple: QuotedTriplePatternAst,
    val predicate: RdfTerm,
    val obj: RdfTerm
) : GraphPatternAst

/**
 * BIND clause: BIND(expression AS ?var)
 */
data class BindPatternAst(
    val variable: Var,
    val expression: ExpressionAst
) : GraphPatternAst

/**
 * FILTER clause: FILTER(expression)
 */
data class FilterPatternAst(
    val expression: FilterExpressionAst
) : GraphPatternAst

/**
 * Sub-select pattern: { SELECT ... WHERE ... }
 */
data class SubSelectPatternAst(
    val query: SelectQueryAst
) : GraphPatternAst

// ============================================================================
// PROPERTY PATHS
// ============================================================================

/**
 * Base interface for SPARQL property paths.
 */
sealed interface PropertyPathAst

/**
 * Basic property path (IRI or variable).
 */
data class BasicPathAst(val term: RdfTerm) : PropertyPathAst

/**
 * One or more: path+
 */
data class OneOrMorePathAst(val path: PropertyPathAst) : PropertyPathAst

/**
 * Zero or more: path*
 */
data class ZeroOrMorePathAst(val path: PropertyPathAst) : PropertyPathAst

/**
 * Zero or one: path?
 */
data class ZeroOrOnePathAst(val path: PropertyPathAst) : PropertyPathAst

/**
 * Inverse: ^path
 */
data class InversePathAst(val path: PropertyPathAst) : PropertyPathAst

/**
 * Negation: !path
 */
data class NegationPathAst(val path: PropertyPathAst) : PropertyPathAst

/**
 * Alternative: path1 | path2
 */
data class AlternativePathAst(
    val left: PropertyPathAst,
    val right: PropertyPathAst
) : PropertyPathAst

/**
 * Sequence: path1 / path2
 */
data class SequencePathAst(
    val left: PropertyPathAst,
    val right: PropertyPathAst
) : PropertyPathAst

/**
 * Range: path{n}, path{n,}, path{,m}, path{n,m}
 *
 * Not part of SPARQL 1.1/1.2 (it was dropped from the final grammar); the
 * renderer rejects it.
 */
data class RangePathAst(
    val path: PropertyPathAst,
    val min: Int,
    val max: Int?
) : PropertyPathAst

// ============================================================================
// EXPRESSIONS
// ============================================================================

/**
 * Base interface for all SPARQL expressions (used in SELECT, BIND, FILTER).
 */
sealed interface ExpressionAst

/**
 * Base interface for filter expressions (boolean-valued).
 */
sealed interface FilterExpressionAst : ExpressionAst

/**
 * RDF term as expression (variable, IRI, literal).
 */
data class TermExpressionAst(val term: RdfTerm) : ExpressionAst

/**
 * Comparison operators: =, !=, <, <=, >, >=
 */
data class ComparisonExpressionAst(
    val left: ExpressionAst,
    val operator: ComparisonOperator,
    val right: ExpressionAst
) : FilterExpressionAst

enum class ComparisonOperator(val symbol: String) {
    EQ("="),
    NE("!="),
    LT("<"),
    LTE("<="),
    GT(">"),
    GTE(">=")
}

/**
 * Logical operators: &&, ||, !
 */
data class AndExpressionAst(
    val left: FilterExpressionAst,
    val right: FilterExpressionAst
) : FilterExpressionAst

data class OrExpressionAst(
    val left: FilterExpressionAst,
    val right: FilterExpressionAst
) : FilterExpressionAst

data class NotExpressionAst(
    val expression: FilterExpressionAst
) : FilterExpressionAst

/**
 * Built-in function call.
 */
data class FunctionCallAst(
    val name: String,
    val arguments: List<ExpressionAst>
) : ExpressionAst, FilterExpressionAst

/**
 * Conditional expression: IF(condition, thenValue, elseValue)
 */
data class ConditionalExpressionAst(
    val condition: FilterExpressionAst,
    val thenValue: ExpressionAst,
    val elseValue: ExpressionAst
) : ExpressionAst

/**
 * Aggregate function: COUNT(?var), SUM(?var), etc.
 *
 * A `null` [expression] renders `*` and is only valid for `COUNT`. [separator]
 * renders `; SEPARATOR="..."` and is only valid for `GROUP_CONCAT`.
 */
data class AggregateExpressionAst(
    val function: AggregateFunction,
    val expression: ExpressionAst?,
    val distinct: Boolean = false,
    val separator: String? = null
) : ExpressionAst {
    init {
        require(expression != null || function == AggregateFunction.COUNT) { "Only COUNT accepts '*'" }
        require(separator == null || function == AggregateFunction.GROUP_CONCAT) { "SEPARATOR is only valid for GROUP_CONCAT" }
    }

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    constructor(function: AggregateFunction, expression: ExpressionAst, distinct: Boolean = false) :
        this(function, expression, distinct, null)

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("copy")
    fun copyWithoutSeparator(
        function: AggregateFunction = this.function,
        expression: ExpressionAst = compatNotNull(this.expression, "expression"),
        distinct: Boolean = this.distinct,
    ): AggregateExpressionAst =
        AggregateExpressionAst(function, expression, distinct, separator.takeIf { function == AggregateFunction.GROUP_CONCAT })
}

enum class AggregateFunction(val functionName: String) {
    COUNT("COUNT"),
    SUM("SUM"),
    AVG("AVG"),
    MIN("MIN"),
    MAX("MAX"),
    GROUP_CONCAT("GROUP_CONCAT"),
    SAMPLE("SAMPLE")
}

/**
 * Arithmetic operators: +, -, *, /
 */
data class ArithmeticExpressionAst(
    val left: ExpressionAst,
    val operator: ArithmeticOperator,
    val right: ExpressionAst
) : ExpressionAst

enum class ArithmeticOperator(val symbol: String) {
    ADD("+"),
    SUBTRACT("-"),
    MULTIPLY("*"),
    DIVIDE("/")
}

// ============================================================================
// ORDER BY
// ============================================================================

/**
 * ORDER BY clause item.
 */
data class OrderClauseAst(
    val expression: ExpressionAst,
    val direction: OrderDirection = OrderDirection.ASC
)

enum class OrderDirection {
    ASC, DESC
}

// ============================================================================
// UPDATE OPERATIONS
// ============================================================================

/**
 * A `GRAPH <g> { triples }` block inside INSERT DATA / DELETE DATA or a
 * DELETE/INSERT template. In DATA blocks [graph] must be an IRI; in templates it
 * may also be a variable.
 */
data class QuadBlockAst(
    val graph: RdfTerm,
    val triples: List<TriplePatternAst>
)

/**
 * Base interface for SPARQL UPDATE operations.
 *
 * [using], [usingNamed] and [with] are only meaningful for [ModifyOperationAst];
 * every other operation rejects them at construction.
 */
sealed interface UpdateOperationAst {
    val using: List<Iri>
    val usingNamed: List<Iri>
    val with: Iri?
}

/**
 * INSERT DATA operation.
 */
data class InsertDataOperationAst(
    val data: List<TriplePatternAst>,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null,
    /** Named-graph data: `GRAPH <g> { ... }` blocks. */
    val graphData: List<QuadBlockAst> = emptyList()
) : UpdateOperationAst {
    init { requireNoDatasetClauses(using, usingNamed, with) }

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    constructor(
        data: List<TriplePatternAst>,
        using: List<Iri> = emptyList(),
        usingNamed: List<Iri> = emptyList(),
        with: Iri? = null,
    ) : this(data, using, usingNamed, with, emptyList())

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("copy-HMI5rLE")
    fun copyWithoutGraphData(
        data: List<TriplePatternAst> = this.data,
        using: List<Iri> = this.using,
        usingNamed: List<Iri> = this.usingNamed,
        with: Iri? = this.with,
    ): InsertDataOperationAst = InsertDataOperationAst(data, using, usingNamed, with, graphData)
}

/**
 * DELETE DATA operation.
 */
data class DeleteDataOperationAst(
    val data: List<TriplePatternAst>,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null,
    /** Named-graph data: `GRAPH <g> { ... }` blocks. */
    val graphData: List<QuadBlockAst> = emptyList()
) : UpdateOperationAst {
    init { requireNoDatasetClauses(using, usingNamed, with) }

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    constructor(
        data: List<TriplePatternAst>,
        using: List<Iri> = emptyList(),
        usingNamed: List<Iri> = emptyList(),
        with: Iri? = null,
    ) : this(data, using, usingNamed, with, emptyList())

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("copy-HMI5rLE")
    fun copyWithoutGraphData(
        data: List<TriplePatternAst> = this.data,
        using: List<Iri> = this.using,
        usingNamed: List<Iri> = this.usingNamed,
        with: Iri? = this.with,
    ): DeleteDataOperationAst = DeleteDataOperationAst(data, using, usingNamed, with, graphData)
}

/**
 * DELETE/INSERT operation (MODIFY).
 */
data class ModifyOperationAst(
    val delete: List<TriplePatternAst> = emptyList(),
    val insert: List<TriplePatternAst> = emptyList(),
    /** WHERE clause; `null` renders `WHERE {}`. */
    val where: GraphPatternAst? = null,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null,
    /** `GRAPH ?g { ... }` blocks of the DELETE template. */
    val deleteGraphs: List<QuadBlockAst> = emptyList(),
    /** `GRAPH ?g { ... }` blocks of the INSERT template. */
    val insertGraphs: List<QuadBlockAst> = emptyList()
) : UpdateOperationAst {

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    constructor(
        delete: List<TriplePatternAst> = emptyList(),
        insert: List<TriplePatternAst> = emptyList(),
        where: GraphPatternAst? = null,
        using: List<Iri> = emptyList(),
        usingNamed: List<Iri> = emptyList(),
        with: Iri? = null,
    ) : this(delete, insert, where, using, usingNamed, with, emptyList(), emptyList())

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("copy-wt97nKI")
    fun copyWithoutGraphs(
        delete: List<TriplePatternAst> = this.delete,
        insert: List<TriplePatternAst> = this.insert,
        where: GraphPatternAst? = this.where,
        using: List<Iri> = this.using,
        usingNamed: List<Iri> = this.usingNamed,
        with: Iri? = this.with,
    ): ModifyOperationAst = ModifyOperationAst(delete, insert, where, using, usingNamed, with, deleteGraphs, insertGraphs)
}

/**
 * DELETE WHERE operation.
 */
data class DeleteWhereOperationAst(
    val where: GraphPatternAst,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null
) : UpdateOperationAst {
    init { requireNoDatasetClauses(using, usingNamed, with) }
}

/**
 * LOAD operation.
 */
data class LoadOperationAst(
    val source: Iri,
    val into: Iri? = null,
    val silent: Boolean = false,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null
) : UpdateOperationAst {
    init { requireNoDatasetClauses(using, usingNamed, with) }
}

/**
 * CLEAR operation: `CLEAR GRAPH <g>` when [graph] is set, otherwise `CLEAR DEFAULT`, `CLEAR NAMED`
 * or `CLEAR ALL` according to [scope].
 */
data class ClearOperationAst(
    val graph: Iri? = null,
    val silent: Boolean = false,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null,
    val scope: GraphScope = GraphScope.DEFAULT,
) : UpdateOperationAst {
    init {
        requireNoDatasetClauses(using, usingNamed, with)
        require(graph == null || scope == GraphScope.DEFAULT) { "CLEAR takes either a graph IRI or a scope, not both" }
    }

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    constructor(
        graph: Iri? = null,
        silent: Boolean = false,
        using: List<Iri> = emptyList(),
        usingNamed: List<Iri> = emptyList(),
        with: Iri? = null,
    ) : this(graph, silent, using, usingNamed, with, GraphScope.DEFAULT)

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("copy-z3iZ69M")
    fun copyWithoutScope(
        graph: Iri? = this.graph,
        silent: Boolean = this.silent,
        using: List<Iri> = this.using,
        usingNamed: List<Iri> = this.usingNamed,
        with: Iri? = this.with,
    ): ClearOperationAst = ClearOperationAst(graph, silent, using, usingNamed, with, if (graph == null) scope else GraphScope.DEFAULT)
}

/**
 * CREATE operation.
 */
data class CreateOperationAst(
    val graph: Iri,
    val silent: Boolean = false,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null
) : UpdateOperationAst {
    init { requireNoDatasetClauses(using, usingNamed, with) }
}

/**
 * DROP operation: `DROP GRAPH <g>` when [graph] is set, otherwise `DROP DEFAULT`, `DROP NAMED`
 * or `DROP ALL` according to [scope].
 */
data class DropOperationAst(
    val graph: Iri? = null,
    val silent: Boolean = false,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null,
    val scope: GraphScope = GraphScope.DEFAULT,
) : UpdateOperationAst {
    init {
        requireNoDatasetClauses(using, usingNamed, with)
        require(graph == null || scope == GraphScope.DEFAULT) { "DROP takes either a graph IRI or a scope, not both" }
    }

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    constructor(
        graph: Iri? = null,
        silent: Boolean = false,
        using: List<Iri> = emptyList(),
        usingNamed: List<Iri> = emptyList(),
        with: Iri? = null,
    ) : this(graph, silent, using, usingNamed, with, GraphScope.DEFAULT)

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("copy-z3iZ69M")
    fun copyWithoutScope(
        graph: Iri? = this.graph,
        silent: Boolean = this.silent,
        using: List<Iri> = this.using,
        usingNamed: List<Iri> = this.usingNamed,
        with: Iri? = this.with,
    ): DropOperationAst = DropOperationAst(graph, silent, using, usingNamed, with, if (graph == null) scope else GraphScope.DEFAULT)
}

/**
 * COPY operation. A `null` [source] or [destination] means the default graph (`DEFAULT`).
 */
data class CopyOperationAst(
    val source: Iri?,
    val destination: Iri?,
    val silent: Boolean = false,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null
) : UpdateOperationAst {
    init { requireNoDatasetClauses(using, usingNamed, with) }

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("getSource-tqZU9bw")
    fun sourceNotNull(): Iri = compatNotNull(source, "source")

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("getDestination-tqZU9bw")
    fun destinationNotNull(): Iri = compatNotNull(destination, "destination")

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("component1-tqZU9bw")
    fun component1NotNull(): Iri = compatNotNull(source, "source")

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("component2-tqZU9bw")
    fun component2NotNull(): Iri = compatNotNull(destination, "destination")

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("copy-VXt0FlY")
    fun copyNotNull(
        source: Iri = compatNotNull(this.source, "source"),
        destination: Iri = compatNotNull(this.destination, "destination"),
        silent: Boolean = this.silent,
        using: List<Iri> = this.using,
        usingNamed: List<Iri> = this.usingNamed,
        with: Iri? = this.with,
    ): CopyOperationAst = CopyOperationAst(source, destination, silent, using, usingNamed, with)
}

/**
 * MOVE operation. A `null` [source] or [destination] means the default graph (`DEFAULT`).
 */
data class MoveOperationAst(
    val source: Iri?,
    val destination: Iri?,
    val silent: Boolean = false,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null
) : UpdateOperationAst {
    init { requireNoDatasetClauses(using, usingNamed, with) }

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("getSource-tqZU9bw")
    fun sourceNotNull(): Iri = compatNotNull(source, "source")

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("getDestination-tqZU9bw")
    fun destinationNotNull(): Iri = compatNotNull(destination, "destination")

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("component1-tqZU9bw")
    fun component1NotNull(): Iri = compatNotNull(source, "source")

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("component2-tqZU9bw")
    fun component2NotNull(): Iri = compatNotNull(destination, "destination")

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("copy-VXt0FlY")
    fun copyNotNull(
        source: Iri = compatNotNull(this.source, "source"),
        destination: Iri = compatNotNull(this.destination, "destination"),
        silent: Boolean = this.silent,
        using: List<Iri> = this.using,
        usingNamed: List<Iri> = this.usingNamed,
        with: Iri? = this.with,
    ): MoveOperationAst = MoveOperationAst(source, destination, silent, using, usingNamed, with)
}

/**
 * ADD operation. A `null` [source] or [destination] means the default graph (`DEFAULT`).
 */
data class AddOperationAst(
    val source: Iri?,
    val destination: Iri?,
    val silent: Boolean = false,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null
) : UpdateOperationAst {
    init { requireNoDatasetClauses(using, usingNamed, with) }

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("getSource-tqZU9bw")
    fun sourceNotNull(): Iri = compatNotNull(source, "source")

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("getDestination-tqZU9bw")
    fun destinationNotNull(): Iri = compatNotNull(destination, "destination")

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("component1-tqZU9bw")
    fun component1NotNull(): Iri = compatNotNull(source, "source")

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("component2-tqZU9bw")
    fun component2NotNull(): Iri = compatNotNull(destination, "destination")

    @Deprecated(BINARY_COMPATIBILITY, level = DeprecationLevel.HIDDEN)
    @JvmName("copy-VXt0FlY")
    fun copyNotNull(
        source: Iri = compatNotNull(this.source, "source"),
        destination: Iri = compatNotNull(this.destination, "destination"),
        silent: Boolean = this.silent,
        using: List<Iri> = this.using,
        usingNamed: List<Iri> = this.usingNamed,
        with: Iri? = this.with,
    ): AddOperationAst = AddOperationAst(source, destination, silent, using, usingNamed, with)
}

/**
 * Message of the hidden bridges that keep the JVM signatures published in Kastor 0.2.1 (non-null
 * COPY/MOVE/ADD graphs, constructors without scope, graph blocks or separator) linkable.
 */
internal const val BINARY_COMPATIBILITY = "Binary compatibility with Kastor 0.2.1 only"

/** Value for a 0.2.1 bridge that cannot represent the default graph or `COUNT(*)`. */
internal fun <T : Any> compatNotNull(value: T?, name: String): T =
    value ?: throw IllegalStateException("$name is null (DEFAULT graph or '*'), which the 0.2.1 API cannot represent")

/** Target of `CLEAR`/`DROP` without a graph IRI (SPARQL 1.1 Update `GraphRefAll`). */
enum class GraphScope(val keyword: String) {
    DEFAULT("DEFAULT"),
    NAMED("NAMED"),
    ALL("ALL"),
}

/**
 * Complete UPDATE request (can contain multiple operations).
 */
data class UpdateRequestAst(
    val version: String? = null,
    val prefixes: List<PrefixDeclaration> = emptyList(),
    val operations: List<UpdateOperationAst>
)

private fun requireNoDatasetClauses(using: List<Iri>, usingNamed: List<Iri>, with: Iri?) {
    require(using.isEmpty() && usingNamed.isEmpty() && with == null) {
        "USING/USING NAMED/WITH are only valid on DELETE/INSERT ... WHERE operations"
    }
}
