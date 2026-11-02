package org.geoknoesis.lens

/**
 * Portable, typed filter expressions for an RDF node binding.
 *
 * Every path is a single RDF predicate (not a concatenated SPARQL fragment).
 * A path/constant comparison has existential semantics over the node's property
 * values. Missing values make a positive comparison false; NOT negates that
 * Boolean, so missing values make NOT(comparison) true.
 */
sealed interface ValueExpression {
    data class Path(val predicate: String) : ValueExpression
    data class SequencePath(val predicates: List<String>) : ValueExpression
    data class InversePath(val predicate: String) : ValueExpression
    data class AlternativePath(val predicates: List<String>) : ValueExpression
    data class Constant(val value: Value) : ValueExpression
}
enum class CompareOperator(val sparql: String) {
    EQ("="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">=")
}
sealed interface BooleanPredicate {
    data class Compare(
        val left: ValueExpression,
        val operator: CompareOperator,
        val right: ValueExpression
    ) : BooleanPredicate
    data class All(val clauses: List<BooleanPredicate>) : BooleanPredicate
    data class Any(val clauses: List<BooleanPredicate>) : BooleanPredicate
    data class Not(val inner: BooleanPredicate) : BooleanPredicate
}
object Filters {
    fun path(iri: String): ValueExpression = ValueExpression.Path(iri)
    fun sequence(vararg predicates: String): ValueExpression = ValueExpression.SequencePath(predicates.toList())
    fun inverse(predicate: String): ValueExpression = ValueExpression.InversePath(predicate)
    fun alternative(vararg predicates: String): ValueExpression = ValueExpression.AlternativePath(predicates.toList())
    fun constant(value: Value): ValueExpression = ValueExpression.Constant(value)
    fun compare(a: ValueExpression, op: CompareOperator, b: ValueExpression): BooleanPredicate =
        BooleanPredicate.Compare(a, op, b)
    fun all(vararg parts: BooleanPredicate): BooleanPredicate = BooleanPredicate.All(parts.toList())
    fun any(vararg parts: BooleanPredicate): BooleanPredicate = BooleanPredicate.Any(parts.toList())
    fun not(part: BooleanPredicate): BooleanPredicate = BooleanPredicate.Not(part)
}

/** Generates correlated SPARQL EXISTS expressions without evaluating joins in Kotlin. */
class BooleanPredicateCompiler {
    private var variable = 0
    private fun iri(raw: String): String {
        require(raw.startsWith("urn:") || raw.startsWith("http://") || raw.startsWith("https://")) {
            "Only absolute IRIs are supported"
        }
        require(raw.none { it.isWhitespace() || it == '<' || it == '>' ||
            it == '"' || it == '{' || it == '}' || it == '|' || it == '^' ||
            it == '\\' || it.code == 96 }) { "Unsafe IRI" }
        return "<" + raw + ">"
    }
    private fun literal(raw: String): String =
        "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private fun value(v: Value): String = when(v) {
        is Value.Iri -> iri(v.value)
        is Value.Literal -> literal(v.lexical)
        is Value.TypedLiteral -> literal(v.lexical) + "^^" + iri(v.datatype)
    }

    private fun path(predicates: List<String>, separator: String): String {
        require(predicates.size in 1..8) { "Property paths must contain 1..8 predicates" }
        return "(" + predicates.joinToString(separator) { iri(it) } + ")"
    }

    private fun bind(expr: ValueExpression, node: String, triples: MutableList<String>): String {
        if (expr is ValueExpression.Constant) return value(expr.value)
        val propertyPath = when(expr) {
            is ValueExpression.Path -> iri(expr.predicate)
            is ValueExpression.SequencePath -> path(expr.predicates, "/")
            is ValueExpression.InversePath -> "^" + iri(expr.predicate)
            is ValueExpression.AlternativePath -> path(expr.predicates, "|")
            is ValueExpression.Constant -> error("Already handled")
        }
        val target = "?lensfilter" + variable++
        triples.add(node + " " + propertyPath + " " + target + " .")
        return target
    }

    fun expression(p: BooleanPredicate, node: String): String = when(p) {
        is BooleanPredicate.Compare -> {
            require(node.matches(Regex("\\?[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid binding" }
            if (p.operator !in setOf(CompareOperator.EQ, CompareOperator.NE)) {
                for (e in listOf(p.left, p.right)) {
                    if (e is ValueExpression.Constant) require(e.value !is Value.Iri) {
                        "Ordered comparisons cannot use IRI constants"
                    }
                }
            }
            val triples = ArrayList<String>()
            val left = bind(p.left, node, triples)
            val right = bind(p.right, node, triples)
            val comparison = "(" + left + " " + p.operator.sparql + " " + right + ")"
            if (triples.isEmpty()) comparison else
                "EXISTS { " + triples.joinToString(" ") + " FILTER" + comparison + " }"
        }
        is BooleanPredicate.All -> {
            require(p.clauses.isNotEmpty()) { "Empty conjunction requires an explicit true expression" }
            "(" + p.clauses.joinToString(" && ") { expression(it,node) } + ")"
        }
        is BooleanPredicate.Any -> {
            require(p.clauses.isNotEmpty()) { "Empty disjunction requires an explicit false expression" }
            "(" + p.clauses.joinToString(" || ") { expression(it,node) } + ")"
        }
        is BooleanPredicate.Not -> "(!(" + expression(p.inner,node) + "))"
    }
}
