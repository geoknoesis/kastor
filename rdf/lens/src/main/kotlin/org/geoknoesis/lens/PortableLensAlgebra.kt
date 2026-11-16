package org.geoknoesis.lens

/**
 * Portable subset of the Semantic Lens v0.23 node algebra.
 * Does not use OWL open-world negation: Difference is scoped to the left input.
 */
sealed interface Lens {
    data class Type(val classIri: String) : Lens
    data class Equals(val input: Lens, val property: String, val value: Value) : Lens
    data class Exists(val input: Lens, val property: String, val target: Lens) : Lens
    data class Intersect(val parts: List<Lens>) : Lens
    data class Union(val parts: List<Lens>) : Lens
    data class Difference(val left: Lens, val right: Lens) : Lens
    data class Filter(val input: Lens, val condition: BooleanPredicate) : Lens
}
sealed interface Value {
    data class Iri(val value: String): Value
    data class Literal(val lexical: String): Value
    data class TypedLiteral(val lexical: String, val datatype: String): Value
}
object LensDsl {
    fun type(iri: String): Lens = Lens.Type(iri)
    fun eq(input: Lens, property: String, value: Value): Lens = Lens.Equals(input, property, value)
    fun exists(input: Lens, property: String, target: Lens): Lens = Lens.Exists(input, property, target)
    fun and(vararg parts: Lens): Lens = Lens.Intersect(parts.toList())
    fun or(vararg parts: Lens): Lens = Lens.Union(parts.toList())
    fun minus(left: Lens, right: Lens): Lens = Lens.Difference(left, right)
    fun filter(input: Lens, condition: BooleanPredicate): Lens = Lens.Filter(input, condition)
}
object LensNormalizer {
    fun normalized(lens: Lens): Lens = when(lens) {
        is Lens.Type -> lens
        is Lens.Equals -> lens.copy(input = normalized(lens.input))
        is Lens.Exists -> lens.copy(input = normalized(lens.input), target = normalized(lens.target))
        is Lens.Difference -> lens.copy(left = normalized(lens.left), right = normalized(lens.right))
        is Lens.Filter -> lens.copy(input = normalized(lens.input))
        is Lens.Intersect -> {
            val items = lens.parts.flatMap { p ->
                val n = normalized(p)
                if (n is Lens.Intersect) n.parts else listOf(n)
            }.distinct()
            require(items.isNotEmpty()) { "Empty intersection requires a universe" }
            if (items.size == 1) items.single() else Lens.Intersect(items)
        }
        is Lens.Union -> {
            val items = lens.parts.flatMap { p ->
                val n = normalized(p)
                if (n is Lens.Union) n.parts else listOf(n)
            }.distinct()
            require(items.isNotEmpty()) { "Empty union is undefined" }
            if (items.size == 1) items.single() else Lens.Union(items)
        }
    }
}
class SparqlCompiler {
    private class State {
        private var serial = 0
        private val conditionCompiler = BooleanPredicateCompiler()
        private fun next() = "?lens" + serial++
        private fun iri(raw: String): String {
            require(raw.startsWith("urn:") || raw.startsWith("http://") || raw.startsWith("https://")) {
                "Absolute IRI required"
            }
            require(raw.none { it.isWhitespace() || it == '<' || it == '>' || it == '"' ||
                it == '{' || it == '}' || it == '\\' || it == '|' || it == '^' }) { "Unsafe IRI" }
            return "<" + raw + ">"
        }
        private fun term(v: Value): String = when(v) {
            is Value.Iri -> iri(v.value)
            is Value.TypedLiteral -> {
                val safe = v.lexical.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r")
                "\"" + safe + "\"^^" + iri(v.datatype)
            }
            is Value.Literal -> {
                val safe = v.lexical.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r")
                "\"" + safe + "\""
            }
        }
        fun pattern(q: Lens, node: String, out: StringBuilder) {
            when(q) {
                is Lens.Type -> out.append(node).append(" a ").append(iri(q.classIri)).append(" .")
                is Lens.Equals -> {
                    pattern(q.input, node, out)
                    out.append('\n').append(node).append(' ').append(iri(q.property))
                        .append(' ').append(term(q.value)).append(" .")
                }
                is Lens.Exists -> {
                    val target = next()
                    pattern(q.input, node, out)
                    // The target test is nested so that the only pattern beside the
                    // link is evaluated with the target bound: a provider that plans
                    // an EXISTS body without the outer bindings cannot start from
                    // the whole target class.
                    out.append("\nFILTER EXISTS { ").append(node).append(' ').append(iri(q.property))
                        .append(' ').append(target).append(" . FILTER EXISTS { ")
                    pattern(q.target, target, out)
                    out.append(" } }")
                }
                // One flat group: every restriction only references the node and
                // variables confined to its own EXISTS, so the provider can plan
                // all triple patterns of the intersection as a single BGP.
                is Lens.Intersect -> q.parts.forEachIndexed { i, part ->
                    if (i > 0) out.append('\n')
                    pattern(part, node, out)
                }
                is Lens.Union -> {
                    out.append("{ ")
                    q.parts.forEachIndexed { i, part ->
                        if (i > 0) out.append(" UNION ")
                        out.append("{ ")
                        pattern(part, node, out)
                        out.append(" }")
                    }
                    out.append(" }")
                }
                is Lens.Difference -> {
                    pattern(q.left, node, out)
                    out.append("\nFILTER NOT EXISTS { ")
                    pattern(q.right, node, out)
                    out.append(" }")
                }
                is Lens.Filter -> {
                    pattern(q.input, node, out)
                    out.append("\nFILTER(").append(conditionCompiler.expression(q.condition, node)).append(')')
                }
            }
        }
    }
    private companion object {
        val VARIABLE = Regex("\\?[A-Za-z_][A-Za-z0-9_]*")
    }

    /** Apply the logical optimizer once; pair with [patternOfOptimized] to reuse the result. */
    internal fun optimized(lens: Lens): Lens = LensOptimizer().optimize(lens).lens

    /** Serialize a lens already returned by [optimized], without optimizing it again. */
    internal fun patternOfOptimized(lens: Lens, nodeVariable: String): String {
        require(VARIABLE.matches(nodeVariable)) { "Invalid SPARQL variable" }
        return StringBuilder().also { State().pattern(lens, nodeVariable, it) }.toString()
    }

    /** Reuse the typed node selector in guarded network traversal expressions. */
    fun graphPattern(lens: Lens, nodeVariable: String): String =
        patternOfOptimized(optimized(lens), nodeVariable)

    /** Exposes applied algebra rules and the exact query without inventing cost estimates. */
    data class ExplainedSelect(val plan: LensOptimizer.Plan, val sparql: String)

    fun explain(q: Lens): ExplainedSelect {
        val plan = LensOptimizer().optimize(q)
        val sparql = "SELECT DISTINCT ?node WHERE {\n" +
            patternOfOptimized(plan.lens, "?node") + "\n} ORDER BY ?node"
        return ExplainedSelect(plan, sparql)
    }

    /** One SELECT: all joins, anti-joins and unions stay with the RDF provider. */
    fun select(q: Lens): String = explain(q).sparql
}
