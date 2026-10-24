package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.FalseLiteral
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfProviderException
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpression
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpressionOrdering
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpressions
import com.geoknoesis.kastor.rdf.shacl.native.PathEvaluator
import com.geoknoesis.kastor.rdf.shacl.native.ShapeGraphIndex
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler
import com.geoknoesis.kastor.rdf.shacl.native.ShaclPath
import com.geoknoesis.kastor.rdf.shacl.native.SparqlFunctionExpressions
import com.geoknoesis.kastor.rdf.shacl.native.SparqlQueryTemplate
import com.geoknoesis.kastor.rdf.shacl.native.shaclRdfTermFingerprint
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * Maximum nesting of node expression evaluation, custom function bodies included (each body is one level deeper than
 * its call): the root expression is level 1, so evaluation depths 0 to 127 are allowed and depth 128 (the 129th
 * level) is a failure. Recursive custom functions therefore end in a failure.
 */
internal const val MAX_NODE_EXPRESSION_DEPTH = 128

/**
 * A shape-based function read a provisional "undefined" answer of the recursion solver
 * ([NativeValidationContext.isProvisional]): the expression has no value yet. It propagates out of the evaluation (it
 * is no failure) to the constraint that evaluates the expression, which reports it as undefined
 * ([Sink.undefinedAnswer]) so that the solver evaluates the question again.
 */
internal class ProvisionalUndefined(val node: RdfTerm, val shape: RdfResource) : RuntimeException(null, null, false, false)

/**
 * The [ProvisionalUndefined] answers that function bodies read during a SPARQL query, which cannot carry them, and
 * the validation failures of bodies that failed on a conformance check the engine could not decide ([stashFailure]):
 * each query runs in its own [scoped] block, so that nested queries keep their own, a query that returns rethrows
 * the first one (a failure before a provisional answer), and a query that throws discards it (it never reaches a
 * later, unrelated query).
 */
internal class ProvisionalStash {
    private var pending: RuntimeException? = null

    /** Records [e] for the query under way (the first one wins). */
    fun stash(e: ProvisionalUndefined) {
        if (pending == null) pending = e
    }

    /**
     * Records [e], the validation failure of a function body that failed on a conformance check the engine could not
     * decide (a final undefined answer, R16; an unsupported or ill-formed candidate shape, R8), for the query under
     * way: the query's answer cannot be decided either, whatever SPARQL makes of the body's error. It replaces a
     * provisional answer, which the solver would only evaluate again.
     */
    fun stashFailure(e: ShaclValidationException) {
        if (pending !is ShaclValidationException) pending = e
    }

    inline fun <T> scoped(query: () -> T): T {
        val outer = swap(null)
        try {
            val result = query()
            swap(null)?.let { throw it }
            return result
        } finally {
            swap(outer)
        }
    }

    /** Replaces the pending answer with [value], returning the previous one. */
    fun swap(value: RuntimeException?): RuntimeException? = pending.also { pending = value }
}

/** The outcome of `evalExpr` (Node Expressions §3): a list of output nodes, or an evaluation failure (§3.3). */
internal sealed interface NodeExprOutcome {
    data class Nodes(val nodes: List<RdfTerm>) : NodeExprOutcome
    /** [cause] is the error behind the failure, such as the provider's SPARQL error, when there is one. */
    data class Failure(val reason: String, val cause: Throwable? = null) : NodeExprOutcome
}

/**
 * The evaluation context of a node expression besides the focus graph: the focus node (null when there is none), the
 * scope variables, the arguments of the custom function being evaluated, and the evaluation nesting [depth]. The
 * arguments of a custom list parameter function (§6.2) are nodes by position ([args]; null for an unbound optional
 * argument of a SPARQL call); those of a custom named parameter function (§6.1) are the argument node expressions by
 * parameter `sh:path` ([namedArgs]).
 */
internal class NodeExprScope(
    val focus: RdfTerm?,
    val variables: Map<String, RdfTerm>,
    val args: List<RdfTerm?>,
    val depth: Int,
    val namedArgs: Map<Iri, NodeExpression> = emptyMap(),
) {
    fun nested(focus: RdfTerm? = this.focus): NodeExprScope = NodeExprScope(focus, variables, args, depth + 1, namedArgs)
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
 * deeper than [MAX_NODE_EXPRESSION_DEPTH]. Run aborts (timeout, cancellation) are exceptions, not failures; a SPARQL
 * error of the provider ([NodeExpression.Select], [NodeExpression.SparqlCall], `shnex:sum`) is a failure, with the
 * provider's error as its cause.
 *
 * Custom functions (§6) evaluate the body compiled in the shapes index of [ctx]
 * ([com.geoknoesis.kastor.rdf.shacl.native.NodeExpressionFunctions]) one level deeper, with a scope of their
 * arguments only: the scope variables of the call are not visible in the body.
 *
 * The shape-based functions validate against the compiled shapes of [ctx] (§3.4): conformance that the engine cannot
 * decide (undefined recursion, an undecided `sh:pattern`), a validation error, and a shape that is not compiled are
 * failures.
 */
internal fun NativeShaclValidator.evaluate(expr: NodeExpression, ctx: NativeValidationContext, scope: NodeExprScope): NodeExprOutcome =
    try {
        NodeExprOutcome.Nodes(NodeExpressionEvaluator(this, ctx).eval(expr, scope))
    } catch (f: NodeExprFailure) {
        NodeExprOutcome.Failure(f.reason, f.cause)
    }

/**
 * Evaluates [expr] at a validation position (SHACL 1.2 Core: `sh:values`, `sh:defaultValue`, `sh:targetNode`; Node
 * Expressions §7: `sh:expression`, `sh:nodeByExpression`) for [focus] with the scope [variables]. An evaluation
 * failure is a validation failure ([ShaclValidationException], caused by the failure's cause); a provisional
 * conformance answer is a [ProvisionalUndefined]. [shapeDepth] is the nesting depth of the shape evaluation the
 * expression belongs to ([NativeValidationContext.expressionShapeDepth]). For a
 * [NodeExpression.Select], `this` is pre-bound to [focus] when it is not null, solutions in which the expression
 * variable is unbound are skipped, and the output nodes are distinct, in solution order.
 */
internal fun NativeShaclValidator.evalNodeExpression(
    expr: NodeExpression,
    ctx: NativeValidationContext,
    focus: RdfTerm?,
    variables: Map<String, RdfTerm> = emptyMap(),
    shapeDepth: Int = ctx.expressionShapeDepth,
): List<RdfTerm> {
    val saved = ctx.expressionShapeDepth
    ctx.expressionShapeDepth = shapeDepth
    val outcome = try {
        evaluate(expr, ctx, NodeExprScope(focus, variables, emptyList(), 0))
    } finally {
        ctx.expressionShapeDepth = saved
    }
    return when (outcome) {
        is NodeExprOutcome.Nodes -> outcome.nodes
        is NodeExprOutcome.Failure -> throw ShaclValidationException(outcome.reason, outcome.cause)
    }
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
        ShapesCompiler.expressionShapes(compiled, index)
    }
    return withNodeExpressionContext(graph, shapes) { ctx -> evaluate(checkNotNull(expr), ctx, NodeExprScope(focus, variables, emptyList(), 0)) }
}

/** As [evaluateNodeExpression], for an expression tree that is already compiled. */
internal fun NativeShaclValidator.evaluateNodeExpression(graph: RdfGraph, expr: NodeExpression, focus: RdfTerm?, variables: Map<String, RdfTerm>): NodeExprOutcome =
    withNodeExpressionContext(graph, { index -> ShapesCompiler.expressionShapes(expr, index) }) { ctx -> evaluate(expr, ctx, NodeExprScope(focus, variables, emptyList(), 0)) }

/** Fails when [size] output nodes exceed [com.geoknoesis.kastor.rdf.shacl.ValidationConfig.maxPathValueNodes], as path evaluation does. */
internal fun checkNodeExpressionSize(size: Int, ctx: NativeValidationContext, what: String) {
    if (size > ctx.data.maxPathValueNodes) throw ShaclValidationException(sizeMessage(ctx, what))
}

private fun sizeMessage(ctx: NativeValidationContext, what: String) =
    "SHACL $what exceeded ValidationConfig.maxPathValueNodes=${ctx.data.maxPathValueNodes}"

/** An evaluation failure (§3.3) on its way to [evaluate]; no stack trace, as it is an outcome, not an error. */
private class NodeExprFailure(val reason: String, cause: Throwable? = null) : RuntimeException(reason, cause, false, false)

private class NodeExpressionEvaluator(private val validator: NativeShaclValidator, private val ctx: NativeValidationContext) {
    private fun fail(reason: String, cause: Throwable? = null): Nothing = throw NodeExprFailure(reason, cause)

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
        // Every evaluation step is work of the run, also one without output nodes (custom function recursion).
        ctx.budget.tick("node expression")
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
            is NodeExpression.Sum -> output(listOf(sum(sub(expr.e), scope.depth)))
            is NodeExpression.InstancesOf -> instancesOf(sub(expr.e))
            is NodeExpression.Select -> startedAt(scope.depth) { select(expr, scope.focus, scope.args) }
            // Shape-based functions (§4.2.5, §4.3.2, §4.3.3, §4.5.2, §4.5.3).
            is NodeExpression.FilterShape -> output(sub(expr.nodes).filter { conforms(it, expr.shape, "shnex:filterShape", scope.depth) })
            is NodeExpression.FindFirst -> {
                // R6: stops at the first conforming node; an undecided or failed check before it is a failure.
                val inputs = expr.nodes?.let { sub(it) } ?: listOfNotNull(scope.focus)
                output(listOfNotNull(inputs.firstOrNull { conforms(it, expr.shape, "shnex:findFirst", scope.depth) }))
            }
            is NodeExpression.MatchAll -> {
                // R6: every node is checked, so an undecided or failed check anywhere is a failure; ( true ) for no input.
                val inputs = expr.nodes?.let { sub(it) } ?: listOfNotNull(scope.focus)
                val answers = inputs.map { conforms(it, expr.shape, "shnex:matchAll", scope.depth) }
                output(listOf(if (answers.all { it }) TrueLiteral else FalseLiteral))
            }
            is NodeExpression.NodesMatching -> nodesMatching(expr.shape, scope.depth)
            is NodeExpression.ConformsToShape -> {
                // A list parameter function (§3.2.2): both arguments are evaluated first, so that a failure takes
                // precedence over an argument without output nodes.
                val node = argument(sub(expr.node), "node", "shnex:conformsToShape")
                val shape = argument(sub(expr.shape), "shape", "shnex:conformsToShape")
                when {
                    node == null || shape == null -> emptyList()
                    shape !is Iri -> fail("the shape argument $shape of shnex:conformsToShape is not an IRI")
                    else -> output(listOf(if (conforms(node, shape, "shnex:conformsToShape", scope.depth)) TrueLiteral else FalseLiteral))
                }
            }
            // SPARQL functions (§5) and custom functions (§6).
            is NodeExpression.SparqlCall -> sparqlCall(expr, inner)
            is NodeExpression.CustomNamed -> {
                // §6.1: the scope of the body maps each given parameter to its argument expression, unevaluated.
                eval(body(expr.function), NodeExprScope(scope.focus, emptyMap(), emptyList(), scope.depth + 1, expr.args))
            }
            is NodeExpression.CustomList -> customList(expr, scope, inner)
            // §6.3: a list argument is a node, which evaluates to itself; a named one is an expression, evaluated with
            // an empty scope for the focus node of the arg expression. No such argument: no output nodes.
            is NodeExpression.Arg -> output(listOfNotNull(scope.args.getOrNull(expr.index)))
            is NodeExpression.NamedArg -> scope.namedArgs[expr.parameter]?.let { eval(it, NodeExprScope(scope.focus, emptyMap(), emptyList(), scope.depth + 1)) }
                ?: emptyList()
        }
    }

    /**
     * Runs [block], a query or conformance check started at nesting [depth], so that the SHACL SPARQL functions it
     * calls evaluate their node expression bodies deeper than [depth] ([NativeValidationContext.nodeExpressionDepth]).
     */
    private inline fun <T> startedAt(depth: Int, block: () -> T): T {
        val saved = ctx.nodeExpressionDepth
        ctx.nodeExpressionDepth = depth
        try {
            return block()
        } finally {
            ctx.nodeExpressionDepth = saved
        }
    }

    /** §4.1.6: the condition holds when its output nodes are exactly the term `"true"^^xsd:boolean` (not `"1"`). */
    private fun isTrue(nodes: List<RdfTerm>): Boolean = nodes.size == 1 && TrueLiteral == nodes.single()

    /** The value of an argument of a list parameter function (§3.2.2): null for none, a failure for several. */
    private fun argument(nodes: List<RdfTerm>, name: String, function: String): RdfTerm? =
        when (nodes.size) {
            0 -> null
            1 -> nodes.single()
            else -> fail("the $name argument of $function produced more than one node (${nodes.size})")
        }

    /** The compiled body of the custom function [function] (compiled with the expression that calls it). */
    private fun body(function: Iri): NodeExpression =
        ctx.compiled.index.nodeExpressionFunctions.body(function) ?: fail("the custom function <${function.value}> has no compiled body")

    /**
     * §6.2: every argument is evaluated in the scope of the call ([argScope]) to at most one node (§3.2.2); a failing
     * argument takes precedence over one without output nodes, which makes the output empty. The body is evaluated
     * one level deeper with the argument nodes as its scope, and more than one output node is a failure.
     */
    private fun customList(expr: NodeExpression.CustomList, scope: NodeExprScope, argScope: NodeExprScope): List<RdfTerm> {
        val function = "<${expr.function.value}>"
        var failure: NodeExprFailure? = null
        var unbound = false
        val values = expr.args.mapIndexed { i, arg ->
            try {
                argument(eval(arg, argScope), "argument $i", function).also { if (it == null) unbound = true }
            } catch (f: NodeExprFailure) {
                if (failure == null) failure = f
                null
            }
        }
        failure?.let { throw it }
        if (unbound) return emptyList()
        val out = eval(body(expr.function), NodeExprScope(scope.focus, emptyMap(), values, scope.depth + 1))
        if (out.size > 1) fail("the custom function $function produced more than one node (${out.size})")
        return out
    }

    /**
     * Whether [node] conforms to [shape] (§3.4), which must be a compiled shape. The compiler compiles every shape an
     * expression can name, deactivated ones included (to which every node conforms), see
     * [ShapesCompiler.ExpressionShapes]; any other node is not a shape and a failure. A final answer the engine cannot
     * decide and a validation error are failures of [function]; a provisional answer of the recursion solver is a
     * [ProvisionalUndefined]; a timeout or cancellation stays a run abort.
     *
     * The check is one shape level deeper than the shape evaluation of the expression
     * ([NativeValidationContext.expressionShapeDepth]), so that recursion through expressions stays bounded. It is a
     * negative read for the recursion solver: an expression need not be monotone in the answer
     * (`sparql:logical-not ( [ shnex:conformsToShape ... ] )`).
     */
    private fun conforms(node: RdfTerm, shape: RdfResource, function: String, depth: Int): Boolean {
        val compiled = ctx.compiled
        // R8: a candidate of a computed shape reference that did not compile.
        compiled.candidateErrors[shape]?.let { ctx.undecidedReads++; fail("$function: $it") }
        if (shape !in compiled.referencedNodeShapes && shape !in compiled.referencedPropertyShapes) {
            fail("$function: $shape is not a shape of the shapes graph")
        }
        val answer = try {
            startedAt(depth) { validator.conformance(node, shape, ctx, DepthState(ctx.expressionShapeDepth, Mode.CONFORMS), negative = true) }
        } catch (e: ShaclValidationException) {
            ctx.checkDeadline()
            if (isRunAbort(e)) throw e
            ctx.undecidedReads++
            fail("$function: validating $node against $shape failed: ${e.message}", e)
        }
        return when (answer) {
            Conformance.CONFORMS -> true
            Conformance.FAILS -> false
            Conformance.UNDEFINED -> {
                if (ctx.isProvisional(node, shape)) throw ProvisionalUndefined(node, shape)
                ctx.undecidedReads++
                fail("$function: the conformance of $node to $shape is undefined")
            }
        }
    }

    /**
     * §4.5.2: the nodes of the focus graph (subjects and objects) that conform to [shape], in SPARQL `ORDER BY` order
     * ([NodeExpressionOrdering]) so that the output does not depend on the triple order of the provider.
     */
    private fun nodesMatching(shape: RdfResource, depth: Int): List<RdfTerm> {
        val matches = ArrayList<RdfTerm>()
        for (node in ctx.data.allNodes()) {
            ctx.budget.tick("node expression")
            if (conforms(node, shape, "shnex:nodesMatching", depth)) {
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
    private fun sum(nodes: List<RdfTerm>, depth: Int): RdfTerm {
        if (nodes.isEmpty()) return TypedLiteral("0", XSD.integer)
        val rendered = nodes.map { node ->
            (node as? Literal)?.let { SparqlQueryTemplate.renderTerm(it) } ?: fail("shnex:sum input $node is not a literal SPARQL can sum")
        }
        val query = "SELECT (SUM(?v) AS ?r) WHERE { VALUES ?v { ${rendered.joinToString(" ")} } }"
        val result = try {
            startedAt(depth) { ctx.select(query, emptyMap()) { rows -> rows.firstOrNull()?.get("r") } }
        } catch (e: ShaclValidationException) {
            sparqlError("shnex:sum", e)
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
    private fun select(expr: NodeExpression.Select, focus: RdfTerm?, args: List<RdfTerm?>): List<RdfTerm> {
        val scope = LinkedHashMap<String, RdfTerm>()
        if (focus != null) scope["this"] = focus
        // §6 of SHACL 1.2 SPARQL: a scope variable named by a number (an argument of a custom list parameter
        // function) is bound as "arg" + str(name).
        args.forEachIndexed { i, arg -> if (arg != null) scope["arg$i"] = arg }
        val (query, bindings) = expr.template.bind(scope)
        val nodes = try {
            selectNodes(expr, query, bindings)
        } catch (e: ShaclValidationException) {
            sparqlError("the SPARQL node expression", e)
        }
        return nodes ?: fail(sizeMessage(ctx, "node expression evaluation"))
    }

    /** The distinct values of the expression variable of [expr] over the solutions of [query], or null beyond the size cap. */
    private fun selectNodes(expr: NodeExpression.Select, query: String, bindings: Map<String, RdfTerm>): List<RdfTerm>? =
        ctx.select(query, bindings) { rows ->
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

    /**
     * A SHACL SPARQL function expression (§5, §3.2.2). Every argument is evaluated; one that fails or produces more
     * than one node is a SPARQL error, and one without output nodes is unbound. Without errors, an unbound argument
     * means no output nodes unless the function handles unbound values
     * ([SparqlFunctionExpressions.handlesUnbound]). The function itself is evaluated by the provider, as
     * `SELECT (<call> AS ?r) WHERE {}` with the argument values pre-bound ([SparqlQueryTemplate]: IRIs and
     * literals in the text, other terms as initial bindings), so that its semantics, unbound and error handling
     * included, are SPARQL's.
     *
     * An argument in error must reach the function as an error, not as an unbound value (`COALESCE` skips both, but
     * `BOUND` is false only for unbound and `IF` fails on a selected error): its variable is replaced by
     * [SPARQL_ERROR], an expression that is an error on every provider. `BOUND` only takes a variable, so `BOUND` of an
     * error is a failure without a query. No `?r` (a SPARQL error) or a query error is a failure; a timeout or
     * cancellation stays a run abort.
     */
    private fun sparqlCall(expr: NodeExpression.SparqlCall, scope: NodeExprScope): List<RdfTerm> {
        val function = "sparql:${expr.name}"
        val values = arrayOfNulls<RdfTerm>(expr.args.size)
        val errors = BooleanArray(expr.args.size)
        var unbound = false
        expr.args.forEachIndexed { i, arg ->
            val out = try {
                eval(arg, scope)
            } catch (f: NodeExprFailure) {
                null
            }
            when {
                out == null || out.size > 1 -> errors[i] = true
                out.isEmpty() -> unbound = true
                else -> values[i] = out.single()
            }
        }
        val errored = errors.any { it }
        if (!errored && unbound && expr.name !in SparqlFunctionExpressions.handlesUnbound) return emptyList()
        if (errored && expr.name == "bound") fail("$function: its argument is a SPARQL error")
        val call = SparqlFunctionExpressions.render(expr.name, List(expr.args.size) { if (errors[it]) SPARQL_ERROR else "?a$it" })
            ?: fail("$function cannot take ${expr.args.size} argument(s)") // Rejected by the parser already.
        val bound = LinkedHashMap<String, RdfTerm>()
        values.forEachIndexed { i, value -> if (value != null) bound["a$i"] = value }
        val (query, bindings) = SparqlQueryTemplate.compile("SELECT ($call AS ?r) WHERE {}", bound.keys).bind(bound)
        val result = try {
            startedAt(scope.depth) { ctx.select(query, bindings, SparqlFunctionLibrary.EMPTY) { rows -> rows.firstOrNull()?.get("r") } }
        } catch (e: ShaclValidationException) {
            sparqlError(function, e)
        }
        return output(listOf(result ?: fail("$function produced a SPARQL error")))
    }

    /**
     * Turns the SPARQL error [e] of [function] into a failure caused by [e], unless it is a run abort: the run budget
     * is used up, the provider cancelled or interrupted the query, or no SPARQL engine is available; [e] is then
     * rethrown.
     */
    private fun sparqlError(function: String, e: ShaclValidationException): Nothing {
        ctx.checkDeadline()
        if (isRunAbort(e)) throw e
        fail("$function failed: ${e.message}", e)
    }

    /** Whether [e] aborts the run: the provider cancelled or interrupted a query, or no SPARQL engine is available. */
    private fun isRunAbort(e: ShaclValidationException): Boolean =
        // A missing SPARQL engine is a configuration error of the run, not a SPARQL error.
        isCancellation(e) || e.cause is RdfProviderException

    private companion object {
        /** A SPARQL expression that is an error on every provider (integer division by zero). */
        const val SPARQL_ERROR = "(1/0)"

        /** Provider exceptions of a cancelled or timed-out query, matched by name: the providers are runtime dependencies. */
        val CANCELLATIONS = setOf(
            "org.apache.jena.query.QueryCancelledException",
            "org.eclipse.rdf4j.query.QueryInterruptedException",
        )

        fun isCancellation(e: Throwable): Boolean =
            generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }
                .take(32)
                .any { t -> t is InterruptedException || generateSequence<Class<*>>(t.javaClass) { it.superclass }.any { it.name in CANCELLATIONS } }
    }
}
