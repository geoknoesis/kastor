package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpression
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpressions
import com.geoknoesis.kastor.rdf.shacl.native.ShapeGraphIndex
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler

// The entry points of node expression evaluation: at a validation position, and outside validation (the W3C harness).
// The evaluator itself is NativeNodeExpressionEvaluator.kt.

/**
 * Evaluates [expr] at a validation position (SHACL 1.2 Core: `sh:values`, `sh:defaultValue`, `sh:targetNode`; Node
 * Expressions §7: `sh:expression`, `sh:nodeByExpression`) for [focus] with the scope [variables]. An evaluation
 * failure is a validation failure ([ShaclValidationException], caused by the failure's cause); a provisional
 * conformance answer is a [ProvisionalUndefined]. A failure of an undecided conformance check is an
 * [UndecidedConformanceException], whatever function surrounds the check. [shapeDepth] is the nesting depth of the shape evaluation the
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
    positiveReads: Boolean = false,
): List<RdfTerm> {
    val saved = ctx.expressionShapeDepth
    ctx.expressionShapeDepth = shapeDepth
    val outcome = try {
        evaluate(expr, ctx, NodeExprScope(focus, variables, emptyList(), 0), positiveReads)
    } finally {
        ctx.expressionShapeDepth = saved
    }
    return when (outcome) {
        is NodeExprOutcome.Nodes -> outcome.nodes
        is NodeExprOutcome.Failure ->
            throw if (outcome.undecided) UndecidedConformanceException(outcome.reason, outcome.cause) else ShaclValidationException(outcome.reason, outcome.cause)
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
