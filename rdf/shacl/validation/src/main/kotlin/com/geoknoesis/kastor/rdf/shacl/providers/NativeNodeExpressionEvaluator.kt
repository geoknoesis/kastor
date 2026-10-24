package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.FalseLiteral
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpression
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpressionOrdering
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpressions
import com.geoknoesis.kastor.rdf.shacl.native.PathEvaluator
import com.geoknoesis.kastor.rdf.shacl.native.ShapeGraphIndex
import com.geoknoesis.kastor.rdf.shacl.native.ShaclPath
import com.geoknoesis.kastor.rdf.shacl.native.SparqlQueryTemplate
import com.geoknoesis.kastor.rdf.shacl.native.shaclRdfTermFingerprint
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * Maximum nesting of node expression evaluation (including, later, custom function bodies): the root expression is
 * level 1, so evaluation depths 0 to 127 are allowed and depth 128 (the 129th level) is a failure.
 */
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
 *
 * The shape-based functions validate against the compiled shapes of [ctx] (§3.4): conformance that the engine cannot
 * decide (undefined recursion, an undecided `sh:pattern`), a validation error, and a shape that is not compiled are
 * failures.
 */
internal fun NativeShaclValidator.evaluate(expr: NodeExpression, ctx: NativeValidationContext, scope: NodeExprScope): NodeExprOutcome =
    try {
        NodeExprOutcome.Nodes(NodeExpressionEvaluator(this, ctx).eval(expr, scope))
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
internal fun NativeShaclValidator.evaluateNodeExpression(graph: RdfGraph, exprNode: RdfTerm, focus: RdfTerm?, variables: Map<String, RdfTerm>): NodeExprOutcome {
    // Compiled over the shapes index, so that the shapes it references are compiled with the shapes graph.
    var expr: NodeExpression? = null
    val shapes = { index: ShapeGraphIndex ->
        val compiled = NodeExpressions.compile(exprNode, index) ?: throw ShapeCompileException("$exprNode is not a node expression")
        expr = compiled
        NodeExpressions.shapeRefs(compiled)
    }
    return withNodeExpressionContext(graph, shapes) { ctx -> evaluate(checkNotNull(expr), ctx, NodeExprScope(focus, variables, emptyList(), 0)) }
}

/** As [evaluateNodeExpression], for an expression tree that is already compiled. */
internal fun NativeShaclValidator.evaluateNodeExpression(graph: RdfGraph, expr: NodeExpression, focus: RdfTerm?, variables: Map<String, RdfTerm>): NodeExprOutcome =
    withNodeExpressionContext(graph, { NodeExpressions.shapeRefs(expr) }) { ctx -> evaluate(expr, ctx, NodeExprScope(focus, variables, emptyList(), 0)) }

/** Fails when [size] output nodes exceed [com.geoknoesis.kastor.rdf.shacl.ValidationConfig.maxPathValueNodes], as path evaluation does. */
internal fun checkNodeExpressionSize(size: Int, ctx: NativeValidationContext, what: String) {
    if (size > ctx.data.maxPathValueNodes) throw ShaclValidationException(sizeMessage(ctx, what))
}

private fun sizeMessage(ctx: NativeValidationContext, what: String) =
    "SHACL $what exceeded ValidationConfig.maxPathValueNodes=${ctx.data.maxPathValueNodes}"

/** An evaluation failure (§3.3) on its way to [evaluate]; no stack trace, as it is an outcome, not an error. */
private class NodeExprFailure(val reason: String) : RuntimeException(reason, null, false, false)

private class NodeExpressionEvaluator(private val validator: NativeShaclValidator, private val ctx: NativeValidationContext) {
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
        if (scope.depth >= MAX_NODE_EXPRESSION_DEPTH) {
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
            is NodeExpression.ListExpr -> concat(expr.members.asSequence().map { sub(it) })
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
            is NodeExpression.FilterShape -> output(sub(expr.nodes).filter { conforms(it, expr.shape, "shnex:filterShape") })
            is NodeExpression.FindFirst -> {
                val inputs = expr.nodes?.let { sub(it) } ?: listOfNotNull(scope.focus)
                output(listOfNotNull(inputs.firstOrNull { conforms(it, expr.shape, "shnex:findFirst") }))
            }
            is NodeExpression.MatchAll -> {
                // Stops at the first node that does not conform; ( true ) for no input nodes.
                val inputs = expr.nodes?.let { sub(it) } ?: listOfNotNull(scope.focus)
                output(listOf(if (inputs.all { conforms(it, expr.shape, "shnex:matchAll") }) TrueLiteral else FalseLiteral))
            }
            is NodeExpression.NodesMatching -> nodesMatching(expr.shape)
            is NodeExpression.ConformsToShape -> {
                // A list parameter function (§3.2.2): both arguments are evaluated first, so that a failure takes
                // precedence over an argument without output nodes.
                val node = argument(sub(expr.node), "node")
                val shape = argument(sub(expr.shape), "shape")
                when {
                    node == null || shape == null -> emptyList()
                    shape !is Iri -> fail("the shape argument $shape of shnex:conformsToShape is not an IRI")
                    else -> output(listOf(if (conforms(node, shape, "shnex:conformsToShape")) TrueLiteral else FalseLiteral))
                }
            }
            // SPARQL functions (§5) and custom functions (§6).
            is NodeExpression.SparqlCall -> unsupported("sparql:${expr.name}")
            is NodeExpression.CustomNamed -> unsupported("custom function <${expr.function.value}>")
            is NodeExpression.CustomList -> unsupported("custom function <${expr.function.value}>")
            is NodeExpression.Arg -> unsupported("shnex:arg ${expr.index}")
            is NodeExpression.NamedArg -> unsupported("shnex:arg <${expr.parameter.value}>")
        }
    }

    /** §4.1.6: the condition holds when its output nodes are exactly the term `"true"^^xsd:boolean` (not `"1"`). */
    private fun isTrue(nodes: List<RdfTerm>): Boolean = nodes.size == 1 && TrueLiteral == nodes.single()

    /** The value of an argument of a list parameter function (§3.2.2): null for none, a failure for several. */
    private fun argument(nodes: List<RdfTerm>, name: String): RdfTerm? =
        when (nodes.size) {
            0 -> null
            1 -> nodes.single()
            else -> fail("the $name argument of shnex:conformsToShape produced more than one node (${nodes.size})")
        }

    /**
     * Whether [node] conforms to [shape] (§3.4), which must be a compiled shape. Conformance the engine cannot decide
     * and a validation error are failures of [function]; a timeout or cancellation stays a run abort.
     */
    private fun conforms(node: RdfTerm, shape: RdfResource, function: String): Boolean {
        val compiled = ctx.compiled
        if (shape !in compiled.referencedNodeShapes && shape !in compiled.referencedPropertyShapes) {
            fail("$function: $shape is not a shape of the shapes graph")
        }
        val answer = try {
            validator.conformance(node, shape, ctx, DepthState(0, Mode.CONFORMS))
        } catch (e: ShaclValidationException) {
            ctx.checkDeadline()
            fail("$function: validating $node against $shape failed: ${e.message}")
        }
        return when (answer) {
            Conformance.CONFORMS -> true
            Conformance.FAILS -> false
            Conformance.UNDEFINED -> fail("$function: the conformance of $node to $shape is undefined")
        }
    }

    /**
     * §4.5.2: the nodes of the focus graph (subjects and objects) that conform to [shape], in SPARQL `ORDER BY` order
     * ([NodeExpressionOrdering]) so that the output does not depend on the triple order of the provider.
     */
    private fun nodesMatching(shape: RdfResource): List<RdfTerm> {
        val matches = ArrayList<RdfTerm>()
        for (node in ctx.data.allNodes()) {
            ctx.budget.tick("node expression")
            if (conforms(node, shape, "shnex:nodesMatching")) {
                matches.add(node)
                capped(matches.size)
            }
        }
        val sorted = NodeExpressionOrdering.stableSort(matches) { a, b ->
            ctx.budget.tick("node expression ordering")
            NodeExpressionOrdering.compare(a, b)
        }
        return output(sorted)
    }

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
