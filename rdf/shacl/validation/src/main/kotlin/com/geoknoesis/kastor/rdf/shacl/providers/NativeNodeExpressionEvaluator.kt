package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.FalseLiteral
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.booleanValue
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpression
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpressionOrdering
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpressions
import com.geoknoesis.kastor.rdf.shacl.native.PathEvaluator
import com.geoknoesis.kastor.rdf.shacl.native.ShaclPath
import com.geoknoesis.kastor.rdf.shacl.native.SparqlQueryTemplate
import com.geoknoesis.kastor.rdf.shacl.native.shaclRdfTermFingerprint
import com.geoknoesis.kastor.rdf.vocab.XSD

/** Maximum nesting of node expression evaluation (including, later, custom function bodies); deeper is a failure. */
internal const val MAX_NODE_EXPRESSION_DEPTH = 128

/** The outcome of `evalExpr` (Node Expressions §3): a list of output nodes, or an evaluation failure (§3.3). */
internal sealed interface NodeExprOutcome {
    data class Nodes(val nodes: List<RdfTerm>) : NodeExprOutcome
    data class Failure(val reason: String) : NodeExprOutcome
}

/**
 * The evaluation context of a node expression besides the focus graph: the focus node (null when there is none), the
 * scope variables, the arguments of the custom function being evaluated, and the evaluation nesting [depth].
 */
internal class NodeExprScope(val focus: RdfTerm?, val variables: Map<String, RdfTerm>, val args: List<RdfTerm?>, val depth: Int) {
    fun nested(focus: RdfTerm? = this.focus): NodeExprScope = NodeExprScope(focus, variables, args, depth + 1)
}

/**
 * `evalExpr(expr, focus graph, scope.focus, scope)` (Node Expressions §3, §4) over the data graph of [ctx]. Output
 * nodes are a list: order and duplicates are kept unless the function says otherwise. A nested failure fails the
 * whole expression (§3.3), `shnex:if` evaluates only the selected branch, and `shnex:distinct`,
 * `shnex:intersection` and `shnex:remove` compare by RDF term equality ([RdfTerm.equals]: lexical form and datatype,
 * language tags case-insensitively).
 *
 * Bounds: every output node ticks the run budget, every list larger than
 * [com.geoknoesis.kastor.rdf.shacl.ValidationConfig.maxPathValueNodes] is a failure, and so is evaluation nested
 * deeper than [MAX_NODE_EXPRESSION_DEPTH]. Run aborts (timeout, cancellation) and SPARQL errors of
 * [NodeExpression.Select] are exceptions, not failures. Functions that are not evaluated yet produce a failure whose
 * reason starts with `"unsupported:"`.
 */
internal fun NativeShaclValidator.evaluate(expr: NodeExpression, ctx: NativeValidationContext, scope: NodeExprScope): NodeExprOutcome =
    try {
        NodeExprOutcome.Nodes(NodeExpressionEvaluator(ctx).eval(expr, scope))
    } catch (f: NodeExprFailure) {
        NodeExprOutcome.Failure(f.reason)
    }

/**
 * Evaluates [expr] at a validation position (SHACL 1.2 Core: `sh:values`, `sh:defaultValue`, `sh:targetNode`) for
 * [focus] with an empty scope. An evaluation failure is a validation failure ([ShaclValidationException]). For a
 * [NodeExpression.Select], `this` is pre-bound to [focus] when it is not null, solutions in which the expression
 * variable is unbound are skipped, and the output nodes are distinct, in solution order.
 */
internal fun NativeShaclValidator.evalNodeExpression(expr: NodeExpression, ctx: NativeValidationContext, focus: RdfTerm?): List<RdfTerm> =
    when (val outcome = evaluate(expr, ctx, NodeExprScope(focus, emptyMap(), emptyList(), 0))) {
        is NodeExprOutcome.Nodes -> outcome.nodes
        is NodeExprOutcome.Failure -> throw ShaclValidationException(outcome.reason)
    }

/**
 * Evaluates the node expression [exprNode] of [graph], which is both the focus graph and the shapes graph (§3.4), with
 * [focus] and the scope [variables] (the W3C `sht:EvalNodeExpr` harness). Ill-formed expressions and shapes throw
 * [ShapeCompileException].
 */
internal fun NativeShaclValidator.evaluateNodeExpression(graph: RdfGraph, exprNode: RdfTerm, focus: RdfTerm?, variables: Map<String, RdfTerm>): NodeExprOutcome =
    withNodeExpressionContext(graph) { ctx ->
        val expr = NodeExpressions.compile(exprNode, ctx.compiled.index)
            ?: throw ShapeCompileException("$exprNode is not a node expression")
        evaluate(expr, ctx, NodeExprScope(focus, variables, emptyList(), 0))
    }

/** As [evaluateNodeExpression], for an expression tree that is already compiled. */
internal fun NativeShaclValidator.evaluateNodeExpression(graph: RdfGraph, expr: NodeExpression, focus: RdfTerm?, variables: Map<String, RdfTerm>): NodeExprOutcome =
    withNodeExpressionContext(graph) { ctx -> evaluate(expr, ctx, NodeExprScope(focus, variables, emptyList(), 0)) }

/** Fails when [size] output nodes exceed [com.geoknoesis.kastor.rdf.shacl.ValidationConfig.maxPathValueNodes], as path evaluation does. */
internal fun checkNodeExpressionSize(size: Int, ctx: NativeValidationContext, what: String) {
    if (size > ctx.data.maxPathValueNodes) throw ShaclValidationException(sizeMessage(ctx, what))
}

private fun sizeMessage(ctx: NativeValidationContext, what: String) =
    "SHACL $what exceeded ValidationConfig.maxPathValueNodes=${ctx.data.maxPathValueNodes}"

/** An evaluation failure (§3.3) on its way to [evaluate]; no stack trace, as it is an outcome, not an error. */
private class NodeExprFailure(val reason: String) : RuntimeException(reason, null, false, false)

private class NodeExpressionEvaluator(private val ctx: NativeValidationContext) {
    private fun fail(reason: String): Nothing = throw NodeExprFailure(reason)

    private fun unsupported(function: String): Nothing = fail("unsupported: $function is not evaluated by the native engine yet")

    /** [nodes] as an output list: capped, and ticking the budget per node. */
    private fun output(nodes: List<RdfTerm>): List<RdfTerm> {
        capped(nodes.size)
        for (i in nodes.indices) ctx.budget.tick("node expression")
        return nodes
    }

    private fun capped(size: Int) {
        if (size > ctx.data.maxPathValueNodes) fail(sizeMessage(ctx, "node expression evaluation"))
    }

    fun eval(expr: NodeExpression, scope: NodeExprScope): List<RdfTerm> {
        if (scope.depth > MAX_NODE_EXPRESSION_DEPTH) {
            fail("node expression evaluation is nested more than $MAX_NODE_EXPRESSION_DEPTH levels deep")
        }
        val inner = scope.nested()
        fun sub(e: NodeExpression) = eval(e, inner)
        fun at(e: NodeExpression, focus: RdfTerm) = eval(e, scope.nested(focus))
        return when (expr) {
            is NodeExpression.Constant -> output(listOf(expr.term))
            NodeExpression.Empty -> emptyList()
            // §4.1.2, in order: the focus node, a scope variable, nothing.
            is NodeExpression.Var -> output(listOfNotNull(if (expr.name == "focusNode") scope.focus else scope.variables[expr.name]))
            is NodeExpression.ListExpr -> output(expr.members.flatMap { sub(it) })
            is NodeExpression.PathValues -> {
                val start = expr.focusNode?.let { sub(it) } ?: listOfNotNull(scope.focus)
                when (start.size) {
                    0 -> emptyList()
                    1 -> pathValues(start.single(), expr.path)
                    else -> fail("the shnex:focusNode of a path values expression produced more than one node (${start.size})")
                }
            }
            is NodeExpression.Exists -> output(listOf(if (sub(expr.e).isNotEmpty()) TrueLiteral else FalseLiteral))
            is NodeExpression.If -> {
                val branch = if (isTrue(sub(expr.cond))) expr.then else expr.otherwise
                branch?.let { sub(it) } ?: emptyList()
            }
            is NodeExpression.Distinct -> output(LinkedHashSet(sub(expr.e)).toList())
            is NodeExpression.Intersection -> {
                val lists = expr.members.map { sub(it) }
                if (lists.isEmpty()) {
                    emptyList()
                } else {
                    val others = lists.drop(1).map { it.toHashSet() }
                    output(LinkedHashSet(lists.first()).filter { node -> others.all { node in it } })
                }
            }
            is NodeExpression.Concat -> concat(expr.members.asSequence().map { sub(it) })
            is NodeExpression.Remove -> {
                val removed = sub(expr.remove).toHashSet()
                output(sub(expr.nodes).filter { it !in removed })
            }
            is NodeExpression.Limit -> output(sub(expr.nodes).take(expr.n))
            is NodeExpression.Offset -> output(sub(expr.nodes).drop(expr.n))
            is NodeExpression.OrderBy -> {
                // §4.2.8: c(n) is the first output node of the key for focus n; unbound keys are the smallest.
                val keyed = sub(expr.nodes).map { n -> n to at(expr.key, n).firstOrNull() }
                val sorted = NodeExpressionOrdering.stableSort(keyed) { a, b ->
                    ctx.budget.tick("node expression ordering")
                    NodeExpressionOrdering.compare(a.second, b.second)
                }.map { it.first }
                output(if (expr.desc) sorted.asReversed() else sorted)
            }
            is NodeExpression.FlatMap -> {
                val inputs = expr.nodes?.let { sub(it) } ?: listOfNotNull(scope.focus)
                concat(inputs.asSequence().map { at(expr.e, it) })
            }
            is NodeExpression.Count -> output(listOf(TypedLiteral(sub(expr.e).size.toString(), XSD.integer)))
            is NodeExpression.Min -> extreme(sub(expr.e), sign = -1)
            is NodeExpression.Max -> extreme(sub(expr.e), sign = 1)
            is NodeExpression.Sum -> output(listOf(sum(sub(expr.e))))
            is NodeExpression.InstancesOf -> instancesOf(sub(expr.e))
            is NodeExpression.Select -> select(expr, scope.focus)
            // Shape-based functions (§4.2.5, §4.3.2, §4.3.3, §4.5.2, §4.5.3).
            is NodeExpression.FilterShape -> unsupported("shnex:filterShape")
            is NodeExpression.FindFirst -> unsupported("shnex:findFirst")
            is NodeExpression.MatchAll -> unsupported("shnex:matchAll")
            is NodeExpression.NodesMatching -> unsupported("shnex:nodesMatching")
            is NodeExpression.ConformsToShape -> unsupported("shnex:conformsToShape")
            // SPARQL functions (§5) and custom functions (§6).
            is NodeExpression.SparqlCall -> unsupported("sparql:${expr.name}")
            is NodeExpression.CustomNamed -> unsupported("custom function <${expr.function.value}>")
            is NodeExpression.CustomList -> unsupported("custom function <${expr.function.value}>")
            is NodeExpression.Arg -> unsupported("shnex:arg ${expr.index}")
            is NodeExpression.NamedArg -> unsupported("shnex:arg <${expr.parameter.value}>")
        }
    }

    /** §4.1.6: the condition holds when its output nodes are exactly the boolean true (`"true"` or `"1"`). */
    private fun isTrue(nodes: List<RdfTerm>): Boolean = (nodes.singleOrNull() as? Literal)?.booleanValue() == true

    private fun concat(parts: Sequence<List<RdfTerm>>): List<RdfTerm> {
        val out = ArrayList<RdfTerm>()
        for (part in parts) {
            capped(out.size + part.size)
            out.addAll(part)
        }
        return output(out)
    }

    private fun pathValues(focus: RdfTerm, path: ShaclPath): List<RdfTerm> =
        try {
            output(PathEvaluator.evaluate(focus, path, ctx.data))
        } catch (e: PathEvaluator.PathValueLimitExceededException) {
            fail(e.message ?: sizeMessage(ctx, "property path evaluation"))
        }

    /** §4.4.2 / §4.4.3: SPARQL MIN ([sign] -1) / MAX ([sign] 1), i.e. the first extreme node in SPARQL ORDER BY order; nothing for no input. */
    private fun extreme(nodes: List<RdfTerm>, sign: Int): List<RdfTerm> {
        if (nodes.isEmpty()) return emptyList()
        var best = nodes.first()
        for (node in nodes) {
            ctx.budget.tick("node expression ordering")
            if (NodeExpressionOrdering.compare(node, best) * sign > 0) best = node
        }
        return output(listOf(best))
    }

    /**
     * §4.4.4: SPARQL SUM, computed by the provider (`SELECT (SUM(?v) AS ?r) WHERE { VALUES ?v { ... } }`) so that
     * numeric type promotion and errors are SPARQL's. SUM of nothing is `0`. A non-literal input, a literal the query
     * cannot carry, or a SPARQL error (no `?r`) is a failure.
     */
    private fun sum(nodes: List<RdfTerm>): RdfTerm {
        if (nodes.isEmpty()) return TypedLiteral("0", XSD.integer)
        val rendered = nodes.map { node ->
            (node as? Literal)?.let { SparqlQueryTemplate.renderTerm(it) } ?: fail("shnex:sum input $node is not a literal SPARQL can sum")
        }
        val query = "SELECT (SUM(?v) AS ?r) WHERE { VALUES ?v { ${rendered.joinToString(" ")} } }"
        val result = try {
            ctx.select(query, emptyMap()) { rows -> rows.firstOrNull()?.get("r") }
        } catch (e: ShaclValidationException) {
            ctx.checkDeadline() // A timeout is a run abort, not a failure of the sum.
            fail("shnex:sum failed: ${e.message}")
        }
        return result ?: fail("shnex:sum of ${nodes.size} nodes has no value (a SPARQL SUM error)")
    }

    /** §4.5.1: the distinct SHACL instances (including instances of subclasses) of every type, which must be IRIs. */
    private fun instancesOf(types: List<RdfTerm>): List<RdfTerm> {
        types.firstOrNull { it !is Iri }?.let { fail("shnex:instancesOf type $it is not an IRI") }
        val out = LinkedHashSet<RdfTerm>()
        for (type in types) {
            for (instance in ctx.data.instancesMatchingTargetClass(type as Iri)) {
                if (out.add(instance)) capped(out.size)
            }
        }
        return output(out.toList())
    }

    /**
     * A SPARQL node expression (SHACL 1.2 SPARQL §6): `this` is pre-bound to [focus] when it is not null; solutions
     * in which the expression variable is unbound are skipped, and the output nodes are distinct, in solution order.
     */
    private fun select(expr: NodeExpression.Select, focus: RdfTerm?): List<RdfTerm> {
        val (query, bindings) = expr.template.bind(if (focus == null) emptyMap() else mapOf("this" to focus))
        val nodes = ctx.select(query, bindings) { rows ->
            // Distinct under the same equality as distinctShaclTerms, capped while streaming.
            val out = LinkedHashMap<String, RdfTerm>()
            for (row in rows) {
                ctx.budget.tick("node expression")
                val term = row.get(expr.variable) ?: continue
                if (out.putIfAbsent(shaclRdfTermFingerprint(term), term) == null && out.size > ctx.data.maxPathValueNodes) {
                    return@select null
                }
            }
            out.values.toList()
        }
        return nodes ?: fail(sizeMessage(ctx, "node expression evaluation"))
    }
}
