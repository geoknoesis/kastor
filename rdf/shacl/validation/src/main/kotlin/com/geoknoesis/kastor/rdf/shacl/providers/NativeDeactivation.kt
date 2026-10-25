package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpression

// Per focus node deactivation (SHACL 1.2 Core §3.1.6): a non-constant `sh:deactivated` of a shape, of a SPARQL-based
// constraint, or of a reifier of a constraint's parameter triple.

/**
 * Whether a shape or a constraint is active for a focus node: [ACTIVE], [DEACTIVATED], or undefined for now because
 * its `sh:deactivated` read a conformance answer the recursion solver has not settled ([provisional]).
 */
internal class Activation private constructor(val active: Boolean, val provisional: ProvisionalUndefined?) {
    companion object {
        val ACTIVE = Activation(true, null)
        val DEACTIVATED = Activation(false, null)

        fun provisional(e: ProvisionalUndefined) = Activation(false, e)

        /** The activation [deactivated] decides; a failure is thrown, a provisional answer it reads is [provisional]. */
        inline fun of(deactivated: () -> Boolean): Activation =
            try {
                if (deactivated()) DEACTIVATED else ACTIVE
            } catch (e: ProvisionalUndefined) {
                provisional(e)
            }
    }
}

/**
 * Evaluates [body], the constraints [activation] guards for [focus], into [sink] when they are active, and not at
 * all when they are deactivated.
 *
 * When the deactivation is undefined for now, "deactivated, or the constraints hold" is a Kleene disjunction: it holds
 * when the constraints hold, whatever the deactivation turns out to be, and is undefined otherwise (deactivated
 * means conforming, so failing constraints cannot make it fail). So [body] is evaluated into a sink of its own: when
 * it conforms, nothing is reported; otherwise the constraints are reported as undefined ([type]), for the answer the
 * deactivation read, and the solver evaluates them again once that answer is settled. Discarding a definite
 * "conforms" here would leave a recursive component that the solver can settle undefined (and so abort a run that
 * fails closed on undecided checks).
 */
internal inline fun NativeShaclValidator.unlessDeactivated(
    activation: Activation,
    sink: Sink,
    ctx: NativeValidationContext,
    focus: RdfTerm,
    tpl: ResultTemplate,
    type: ConstraintType,
    body: (Sink) -> Unit,
) {
    if (activation.active) {
        body(sink)
        return
    }
    val provisional = activation.provisional ?: return
    // Exhaustive when the caller is, so that the questions the evaluation reads do not depend on the answers.
    val trial = Sink(if (sink.exhaustive) Mode.EXHAUSTIVE else Mode.CONFORMS)
    body(trial)
    if (trial.conformance() != Conformance.CONFORMS) sink.undefinedAnswer(ctx, focus, tpl, type, null, provisional.node, provisional.shape)
}

/** The activation of a constraint by the non-constant `sh:deactivated` [conditions] of the reifiers of its triples. */
internal fun NativeShaclValidator.reifierActivation(conditions: List<NodeExpression>, focus: RdfTerm, ctx: NativeValidationContext, depth: Int): Activation =
    if (conditions.isEmpty()) Activation.ACTIVE else Activation.of { anyDeactivates(conditions, focus, ctx, depth) }

/** [unlessDeactivated] for the constraint whose parameter triples have the reifier [conditions]. */
internal inline fun NativeShaclValidator.unlessReifierDeactivated(
    conditions: List<NodeExpression>,
    focus: RdfTerm,
    tpl: ResultTemplate,
    type: ConstraintType,
    ctx: NativeValidationContext,
    state: DepthState,
    sink: Sink,
    body: (Sink) -> Unit,
) {
    if (conditions.isEmpty()) {
        body(sink)
        return
    }
    unlessDeactivated(reifierActivation(conditions, focus, ctx, state.depth), sink, ctx, focus, tpl, type, body)
}

/**
 * The disjunction, under Kleene logic, of whether each of [conditions] (the non-constant `sh:deactivated` of the
 * reifiers of one constraint's parameter triples) deactivates the constraint for [focus]: every condition is
 * evaluated until one is definitely true, so a definite `true` wins over an answer that is undefined for now. When
 * none is true and one read a provisional answer of the recursion solver, the first such [ProvisionalUndefined] is
 * thrown. A failure is thrown at once.
 */
internal fun NativeShaclValidator.anyDeactivates(conditions: List<NodeExpression>, focus: RdfTerm, ctx: NativeValidationContext, depth: Int): Boolean {
    var provisional: ProvisionalUndefined? = null
    for (expr in conditions) {
        try {
            if (deactivates(expr, focus, ctx, depth)) return true
        } catch (e: ProvisionalUndefined) {
            if (provisional == null) provisional = e
        }
    }
    provisional?.let { throw it }
    return false
}

/**
 * Whether the non-constant `sh:deactivated` [expr] (of a shape or a SPARQL-based constraint) deactivates it for
 * [focus]: `evalExpr(expr, data graph, focus node, {})` at shape nesting [depth] produces exactly the term
 * `"true"^^xsd:boolean` (R5) as its only output node. Any other output, the empty list included, leaves it active.
 * An evaluation failure is a validation failure (Node Expressions §3.4), thrown by [evalNodeExpression]; so is a
 * [ProvisionalUndefined] answer.
 */
internal fun NativeShaclValidator.deactivates(expr: NodeExpression, focus: RdfTerm, ctx: NativeValidationContext, depth: Int): Boolean {
    ctx.counters.deactivationEvaluations++
    val out = evalNodeExpression(expr, ctx, focus, shapeDepth = depth)
    return out.size == 1 && out.single() == TrueLiteral
}
