package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.native.CompiledNodeShape
import com.geoknoesis.kastor.rdf.shacl.native.constraintStub

private const val UNDEFINED_RECURSION =
    "Recursive shape dependency through a non-monotone operator (sh:not, sh:xone, sh:qualifiedMaxCount, disjoint qualified value shapes) is undefined"

/**
 * Marks this sink failed, building a validation row only in reporting mode.
 * Conformance-only checks retain the failure without allocating a report row.
 * The caller supplies the row through `build`.
 */
// Failure rows are lazy in non-reporting modes.

internal inline fun Sink.fail(build: () -> ValidationViolation) {
    failed = true
    if (reports) results.add(build())
}

internal fun Sink.failAll(list: List<ValidationViolation>) {
    if (list.isEmpty()) return
    failed = true
    if (reports) results.addAll(list)
}

/**
 * An outcome that depends on the undefined answer "does [node] conform to [shape]". The answer is undefined
 * because of undefined recursion, because of an undecided pattern, or both ([NativeValidationContext.undefined]). The
 * result has the status of **one** cause, chosen deterministically: undefined recursion when it is a cause
 * (`ksh:UndefinedRecursion`; its message also names the pattern that could not be evaluated, if any), otherwise
 * the pattern's status. [context] is added to the result's [ValidationViolation.context].
 */
internal fun Sink.undefinedAnswer(
    ctx: NativeValidationContext,
    focus: RdfTerm,
    tpl: ResultTemplate,
    type: ConstraintType,
    value: RdfTerm?,
    node: RdfTerm,
    shape: RdfResource,
    context: Map<String, Any> = emptyMap(),
) {
    undefined = true
    val why = if (ctx.undefined.isEmpty()) null else ctx.undefined[AtomKey(TermKey(node), shape)]
    val because = why?.pattern
    if (because != null && cause == null) cause = because
    if (why == null || why.recursion) recursion = true
    if (!reports) return
    val result =
        if (why == null || why.recursion) {
            undefinedRecursionResult(focus, tpl, type, value, node, shape, because)
        } else {
            val message = "Whether ${displayTerm(node)} conforms to ${shape.displayId()} cannot be decided, so this " +
                "${type.name} constraint could not be evaluated: ${because!!.reason}"
            violation(focus, tpl, constraintStub(type, tpl.path?.predicate), message, value, messages = emptyList())
                .copy(violationCode = because.code)
        }
    results.add(if (context.isEmpty()) result else result.copy(context = context))
}

/** The outcome of a `sh:pattern` constraint on [value] that could not be decided: see [NativeShaclValidator.matchPattern]. */
internal fun Sink.undecidedPattern(focus: RdfTerm, tpl: ResultTemplate, value: RdfTerm, because: Undecided) {
    undefined = true
    if (cause == null) cause = because
    if (!reports) return
    // The engine's explanation, never the shape's sh:message: the constraint was not found violated.
    results.add(
        violation(focus, tpl, constraintStub(ConstraintType.PATTERN, tpl.path?.predicate), because.reason, value, messages = emptyList())
            .copy(violationCode = because.code),
    )
}

internal inline fun Sink.outcome(
    ctx: NativeValidationContext,
    answer: Conformance,
    focus: RdfTerm,
    tpl: ResultTemplate,
    type: ConstraintType,
    value: RdfTerm?,
    node: RdfTerm,
    shape: RdfResource,
    failure: () -> ValidationViolation,
) {
    when (answer) {
        Conformance.CONFORMS -> Unit
        Conformance.FAILS -> fail(failure)
        Conformance.UNDEFINED -> undefinedAnswer(ctx, focus, tpl, type, value, node, shape)
    }
}

private fun undefinedRecursionResult(
    focus: RdfTerm,
    tpl: ResultTemplate,
    type: ConstraintType,
    value: RdfTerm?,
    node: RdfTerm,
    shape: RdfResource,
    pattern: Undecided? = null,
): ValidationViolation {
    val alsoPattern = pattern?.let { ". The answer is also undefined for another reason: ${it.reason}" }.orEmpty()
    val message = "$UNDEFINED_RECURSION (SHACL does not define recursive shapes): whether ${displayTerm(node)} " +
        "conforms to ${shape.displayId()} cannot be decided, so this ${type.name} constraint could not be evaluated$alsoPattern"
    // In strict mode the result fails the validation when it is reported (see runValidation), not here: a
    // recorded evaluation of the solver may build it under answers that are not final.
    // The declared severity of the source shape: the result blocks conformance exactly when a failure would.
    return violation(focus, tpl, constraintStub(type, tpl.path?.predicate), message, value, messages = emptyList())
        .copy(violationCode = ValidationViolation.UNDEFINED_RECURSION_CODE)
}

/**
 * Result for a node whose `sh:targetWhere` membership is undefined (strict mode: failure). It has the target
 * shape's declared severity, so it blocks conformance exactly when a failure of that shape would.
 */
internal fun NativeShaclValidator.undecidableTargetResult(
    candidate: RdfTerm,
    shape: CompiledNodeShape,
    targetWhere: RdfResource,
    ctx: NativeValidationContext,
): ValidationViolation {
    // Undefined because of an undecided pattern (strict mode already failed), because of undefined recursion, or
    // both: recursion is reported when it is a cause (see Sink.undefinedAnswer).
    val why = ctx.undefined[AtomKey(TermKey(candidate), targetWhere)]
    val because = why?.pattern?.takeIf { !why.recursion }
    val membership = "sh:targetWhere membership of ${displayTerm(candidate)} in ${targetWhere.displayId()} cannot be decided, " +
        "so ${shape.shapeNode.displayId()} could not be validated for it"
    val message =
        if (because == null) "$UNDEFINED_RECURSION (SHACL does not define recursive shapes): $membership" else "$membership: ${because.reason}"
    if (because == null && config.strictMode) throw ShaclValidationException(message)
    val tpl = ResultTemplate(shape.shapeNode, shape.severity, shape.severityCustomIri, emptyList(), null)
    return violation(
        candidate, tpl, constraintStub(ConstraintType.NODE), message, value = candidate,
        messages = emptyList(), sourceConstraint = targetWhere,
    ).copy(violationCode = because?.code ?: ValidationViolation.UNDEFINED_RECURSION_CODE)
}
