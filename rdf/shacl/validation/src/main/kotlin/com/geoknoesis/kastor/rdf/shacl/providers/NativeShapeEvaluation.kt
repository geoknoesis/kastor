package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.native.ClosedMode
import com.geoknoesis.kastor.rdf.shacl.native.CompiledNodeShape
import com.geoknoesis.kastor.rdf.shacl.native.CompiledPropertyShape
import com.geoknoesis.kastor.rdf.shacl.native.DataGraphIndex
import com.geoknoesis.kastor.rdf.shacl.native.NodeLogicalPart
import com.geoknoesis.kastor.rdf.shacl.native.PathEvaluator
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint
import com.geoknoesis.kastor.rdf.shacl.native.ShaclPath
import com.geoknoesis.kastor.rdf.shacl.native.constraintStub
import com.geoknoesis.kastor.rdf.shacl.native.distinctShaclTerms

private fun NodeLogicalPart.constraintType(): ConstraintType =
    when (this) {
        is NodeLogicalPart.And -> ConstraintType.AND
        is NodeLogicalPart.Or -> ConstraintType.OR
        is NodeLogicalPart.Xone -> ConstraintType.XONE
        is NodeLogicalPart.Not -> ConstraintType.NOT
    }

// --- shape evaluation ---------------------------------------------------------------------------------------------

internal fun NativeShaclValidator.validateNodeShape(
    focus: RdfTerm,
    shape: CompiledNodeShape,
    ctx: NativeValidationContext,
    state: DepthState,
    sink: Sink,
) {
    ctx.checkDeadline()
    if (shape.deactivated) return
    ctx.counters.shapeEvaluations++
    val tpl = ResultTemplate(shape.shapeNode, shape.severity, shape.severityCustomIri, shape.messages, null)

    for (nr in shape.nodeRefs) {
        sink.unit { s ->
            s.outcome(ctx, conformance(focus, nr, ctx, state), focus, tpl, ConstraintType.NODE, focus, focus, nr) {
                violation(focus, tpl, constraintStub(ConstraintType.NODE), "sh:node constraint failed for ${nr.displayId()}", value = focus)
            }
        }
        if (sink.stop) return
    }

    for (exprRef in shape.nodeByExpressionRefs) {
        sink.unit { s ->
            s.outcome(ctx, conformance(focus, exprRef, ctx, state), focus, tpl, ConstraintType.NODE_BY_EXPRESSION, focus, focus, exprRef) {
                violation(
                    focus, tpl, constraintStub(ConstraintType.NODE_BY_EXPRESSION),
                    "sh:nodeByExpression constraint failed", value = focus, sourceConstraint = exprRef,
                )
            }
        }
        if (sink.stop) return
    }

    if (shape.nodeConstraints.isNotEmpty()) {
        evaluateConstraintsForValues(focus, tpl, listOf(focus), shape.nodeConstraints, ctx, state, shape.shapeNode, null, sink)
        if (sink.stop) return
    }

    for (ps in shape.propertyShapes) {
        validatePropertyShape(focus, ps, ctx, state, sink)
        if (sink.stop) return
    }

    for (part in shape.logicalParts) {
        sink.unit { s -> evalLogical(focus, focus, tpl, part, ctx, state, s) }
        if (sink.stop) return
    }

    if (config.validateClosedShapes && shape.closed != ClosedMode.NONE && focus is RdfResource) {
        sink.failAll(validateClosed(focus, shape, tpl, ctx, firstOnly = !sink.reports))
    }
}

/** Logical constraints under Kleene logic; operands of `sh:xone` and `sh:not` are negative dependencies. */
private fun NativeShaclValidator.evalLogical(
    reportFocus: RdfTerm,
    logicalTarget: RdfTerm,
    tpl: ResultTemplate,
    part: NodeLogicalPart,
    ctx: NativeValidationContext,
    state: DepthState,
    sink: Sink,
) {
    val type = part.constraintType()
    val stub = constraintStub(type, tpl.path?.predicate)
    when (part) {
        is NodeLogicalPart.And -> {
            var failing: RdfResource? = null
            var undecided: Pair<RdfTerm, RdfResource>? = null
            for (op in part.operands) {
                when (conformance(logicalTarget, op, ctx, state)) {
                    Conformance.FAILS -> { if (failing == null) failing = op; if (!sink.exhaustive) break }
                    Conformance.UNDEFINED -> if (prefersUndefined(ctx, undecided, logicalTarget, op)) undecided = logicalTarget to op
                    Conformance.CONFORMS -> Unit
                }
            }
            val f = failing
            val u = undecided
            if (f != null) {
                sink.fail { violation(reportFocus, tpl, stub, "sh:and failed: value does not conform to ${f.displayId()}", value = logicalTarget) }
            } else if (u != null) {
                sink.undefinedAnswer(ctx, reportFocus, tpl, type, logicalTarget, logicalTarget, u.second)
            }
        }
        is NodeLogicalPart.Or -> {
            var matched = false
            var undecided: Pair<RdfTerm, RdfResource>? = null
            for (op in part.operands) {
                when (conformance(logicalTarget, op, ctx, state)) {
                    Conformance.CONFORMS -> { matched = true; if (!sink.exhaustive) break }
                    Conformance.UNDEFINED -> if (prefersUndefined(ctx, undecided, logicalTarget, op)) undecided = logicalTarget to op
                    Conformance.FAILS -> Unit
                }
            }
            val u = undecided
            if (!matched) {
                if (u != null) {
                    sink.undefinedAnswer(ctx, reportFocus, tpl, type, logicalTarget, logicalTarget, u.second)
                } else {
                    sink.fail { violation(reportFocus, tpl, stub, "sh:or requires at least one matching shape", value = logicalTarget) }
                }
            }
        }
        is NodeLogicalPart.Xone -> {
            var matches = 0
            var undecided: Pair<RdfTerm, RdfResource>? = null
            for (op in part.operands) {
                when (conformance(logicalTarget, op, ctx, state, negative = true)) {
                    Conformance.CONFORMS -> matches++
                    Conformance.UNDEFINED -> if (prefersUndefined(ctx, undecided, logicalTarget, op)) undecided = logicalTarget to op
                    Conformance.FAILS -> Unit
                }
                if (matches > 1 && !sink.exhaustive) break
            }
            val u = undecided
            when {
                matches > 1 -> sink.fail {
                    violation(reportFocus, tpl, stub, "sh:xone requires exactly one matching shape (found more than one)", value = logicalTarget)
                }
                u != null -> sink.undefinedAnswer(ctx, reportFocus, tpl, type, logicalTarget, logicalTarget, u.second)
                matches == 0 -> sink.fail {
                    violation(reportFocus, tpl, stub, "sh:xone requires exactly one matching shape (found none)", value = logicalTarget)
                }
            }
        }
        is NodeLogicalPart.Not ->
            sink.outcome(ctx, invert(conformance(logicalTarget, part.operand, ctx, state, negative = true)), reportFocus, tpl, type, logicalTarget, logicalTarget, part.operand) {
                violation(reportFocus, tpl, stub, "sh:not violated: value conforms to ${part.operand.displayId()}", value = logicalTarget)
            }
    }
}

private fun invert(answer: Conformance): Conformance =
    when (answer) {
        Conformance.CONFORMS -> Conformance.FAILS
        Conformance.FAILS -> Conformance.CONFORMS
        Conformance.UNDEFINED -> Conformance.UNDEFINED
    }

private fun NativeShaclValidator.evaluateConstraintsForValues(
    focus: RdfTerm,
    tpl: ResultTemplate,
    values: List<RdfTerm>,
    constraints: List<PropertyConstraint>,
    ctx: NativeValidationContext,
    state: DepthState,
    currentShape: RdfResource,
    path: ShaclPath?,
    sink: Sink,
) {
    for (c in constraints) {
        if (sink.stop) return
        sink.unit { s -> evaluateConstraint(focus, tpl, values, c, ctx, state, currentShape, path, s) }
    }
}

private fun reportPath(ps: CompiledPropertyShape, ctx: NativeValidationContext): ReportPath =
    ctx.reportPaths.getOrPut(ps.shapeNode) {
        ReportPath(pathToTerms(ps.path), ps.pathNode.takeIf { it is BlankNode }, ps.pathTriples, (ps.path as? ShaclPath.Predicate)?.iri)
    }

internal fun NativeShaclValidator.validatePropertyShape(
    focus: RdfTerm,
    ps: CompiledPropertyShape,
    ctx: NativeValidationContext,
    state: DepthState,
    sink: Sink,
) {
    if (ps.deactivated) return
    ctx.counters.shapeEvaluations++
    val values = propertyValueNodes(focus, ps, ctx)
    val tpl = ResultTemplate(ps.shapeNode, ps.severity, ps.severityCustomIri, ps.messages, reportPath(ps, ctx))
    evaluateConstraintsForValues(focus, tpl, values, ps.constraints, ctx, state, ps.shapeNode, ps.path, sink)
    if (sink.stop) return
    for (part in ps.logicalParts) {
        for (v in values) {
            sink.unit { s -> evalLogical(focus, v, tpl, part, ctx, state, s) }
            if (sink.stop) return
        }
    }
    for (nested in ps.nestedPropertyShapes) {
        for (v in values) {
            validatePropertyShape(v, nested, ctx, state, sink)
            if (sink.stop) return
        }
    }
    if (focus is RdfResource &&
        ps.constraints.any { it is PropertyConstraint.ReifierShape || it is PropertyConstraint.ReificationRequired }
    ) {
        sink.unit { s ->
            validateReifierPropertyConstraints(
                focus = focus,
                tpl = tpl,
                claims = tripleClaimsMatchingSimplePath(focus, ps.path, ctx.data),
                constraints = ps.constraints,
                ctx = ctx,
                state = state,
                sink = s,
            )
        }
    }
}

private fun tripleClaimsMatchingSimplePath(focus: RdfResource, path: ShaclPath, data: DataGraphIndex): List<RdfTriple> =
    when (path) {
        is ShaclPath.Predicate ->
            data.objects(focus, path.iri).map { obj -> RdfTriple(focus, path.iri, obj) }
        else -> emptyList()
    }

private fun NativeShaclValidator.validateReifierPropertyConstraints(
    focus: RdfResource,
    tpl: ResultTemplate,
    claims: List<RdfTriple>,
    constraints: List<PropertyConstraint>,
    ctx: NativeValidationContext,
    state: DepthState,
    sink: Sink,
) {
    ctx.checkDeadline()
    val shapeRefs = constraints.filterIsInstance<PropertyConstraint.ReifierShape>().map { it.nestedShape }
    val reifReq = constraints.filterIsInstance<PropertyConstraint.ReificationRequired>().any { it.required }
    val pathPredicate = tpl.path?.predicate
    val reported = HashSet<ValidationViolation>()
    for (claim in claims) {
        val reifiers = ctx.data.reifiersForClaim(claim)
        if (reifiers.isEmpty()) {
            // SHACL 1.2: sh:reifierShape only constrains existing reifiers; a missing reifier is a violation only
            // under sh:reificationRequired true, reported by sh:ReifierShapeConstraintComponent when the property
            // shape has a sh:reifierShape (sh:reificationRequired is one of its parameters).
            if (!reifReq) continue
            if (shapeRefs.isNotEmpty()) {
                sink.fail {
                    violation(focus, tpl, constraintStub(ConstraintType.REIFIER_SHAPE, pathPredicate), "sh:reificationRequired: missing reifier for triple $claim", value = claim.obj)
                }
            } else {
                sink.fail {
                    violation(focus, tpl, constraintStub(ConstraintType.REIFICATION_REQUIRED, pathPredicate), "sh:reificationRequired: missing reifier for triple $claim", value = claim.obj)
                }
            }
            if (sink.stop) return
            continue
        }
        for (ref in shapeRefs) {
            for (r in reifiers) {
                when (conformance(r, ref, ctx, state)) {
                    Conformance.CONFORMS -> Unit
                    // Like a failure, an undecided answer names its reifier (exported as ksh:reifier).
                    Conformance.UNDEFINED -> sink.undefinedAnswer(
                        ctx, focus, tpl, ConstraintType.REIFIER_SHAPE, claim.obj, r, ref,
                        context = mapOf(ValidationViolation.REIFIER_CONTEXT_KEY to r),
                    )
                    Conformance.FAILS -> {
                        sink.failed = true
                        if (sink.reports) {
                            // sh:value is the object of the reified triple (W3C reifierShape-001), so the failing
                            // reifier is carried by the message and the result context (exported as ksh:reifier).
                            val result = violation(
                                focus, tpl, constraintStub(ConstraintType.REIFIER_SHAPE, pathPredicate),
                                "sh:reifierShape ${ref.displayId()} failed for reifier ${displayTerm(r)} of triple $claim",
                                value = claim.obj,
                            ).copy(context = mapOf(ValidationViolation.REIFIER_CONTEXT_KEY to r))
                            // A reifier failing several reifier shapes yields identical results when the shape's
                            // sh:message replaces the message: report such a result once.
                            if (reported.add(result)) sink.results.add(result)
                        }
                    }
                }
                if (sink.stop) return
            }
        }
    }
}

/**
 * The value nodes of [ps] for [focus] (SHACL 1.2 Core §6.9.2): the nodes reached by the path, then the outputs of
 * `sh:values`, and, only if that set is still empty, the outputs of `sh:defaultValue`. Value nodes are a set
 * (SHACL §2.3.2), so the result is distinct, path values first.
 */
private fun propertyValueNodes(focus: RdfTerm, ps: CompiledPropertyShape, ctx: NativeValidationContext): List<RdfTerm> {
    val pathValues = PathEvaluator.evaluate(focus, ps.path, ctx.data)
    val valuesExpr = ps.values
    var values = if (valuesExpr == null) pathValues else distinctShaclTerms(pathValues + evalNodeExpression(valuesExpr, ctx, focus))
    val defaultExpr = ps.defaultValue
    if (values.isEmpty() && defaultExpr != null) values = evalNodeExpression(defaultExpr, ctx, focus)
    return values
}
