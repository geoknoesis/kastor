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
) : SparqlQueryAst

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
 * MINUS pattern: pattern1 MINUS { pattern2 }
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
 */
data class ValuesPatternAst(
    val variables: List<Var>,
    val values: List<List<RdfTerm>>
) : GraphPatternAst

/**
 * Property path pattern: subject path object
 */
data class PropertyPathPatternAst(
    val subject: RdfTerm,
    val path: PropertyPathAst,
    val obj: RdfTerm
) : GraphPatternAst

/**
 * RDF-star quoted triple pattern: << subject predicate object >>
 */
data class QuotedTriplePatternAst(
    val subject: RdfTerm,
    val predicate: RdfTerm,
    val obj: RdfTerm
) : GraphPatternAst

/**
 * RDF-star triple pattern with quoted triple as subject/object.
 */
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
 */
data class AggregateExpressionAst(
    val function: AggregateFunction,
    val expression: ExpressionAst,
    val distinct: Boolean = false
) : ExpressionAst

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
 * Base interface for SPARQL UPDATE operations.
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
    override val with: Iri? = null
) : UpdateOperationAst

/**
 * DELETE DATA operation.
 */
data class DeleteDataOperationAst(
    val data: List<TriplePatternAst>,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null
) : UpdateOperationAst

/**
 * DELETE/INSERT operation (MODIFY).
 */
data class ModifyOperationAst(
    val delete: List<TriplePatternAst> = emptyList(),
    val insert: List<TriplePatternAst> = emptyList(),
    val where: GraphPatternAst? = null,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null
) : UpdateOperationAst

/**
 * DELETE WHERE operation.
 */
data class DeleteWhereOperationAst(
    val where: GraphPatternAst,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null
) : UpdateOperationAst

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
) : UpdateOperationAst

/**
 * CLEAR operation.
 */
data class ClearOperationAst(
    val graph: Iri? = null,
    val silent: Boolean = false,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null
) : UpdateOperationAst

/**
 * CREATE operation.
 */
data class CreateOperationAst(
    val graph: Iri,
    val silent: Boolean = false,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null
) : UpdateOperationAst

/**
 * DROP operation.
 */
data class DropOperationAst(
    val graph: Iri? = null,
    val silent: Boolean = false,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null
) : UpdateOperationAst

/**
 * COPY operation.
 */
data class CopyOperationAst(
    val source: Iri,
    val destination: Iri,
    val silent: Boolean = false,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null
) : UpdateOperationAst

/**
 * MOVE operation.
 */
data class MoveOperationAst(
    val source: Iri,
    val destination: Iri,
    val silent: Boolean = false,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null
) : UpdateOperationAst

/**
 * ADD operation.
 */
data class AddOperationAst(
    val source: Iri,
    val destination: Iri,
    val silent: Boolean = false,
    override val using: List<Iri> = emptyList(),
    override val usingNamed: List<Iri> = emptyList(),
    override val with: Iri? = null
) : UpdateOperationAst

/**
 * Complete UPDATE request (can contain multiple operations).
 */
data class UpdateRequestAst(
    val version: String? = null,
    val prefixes: List<PrefixDeclaration> = emptyList(),
    val operations: List<UpdateOperationAst>
)

