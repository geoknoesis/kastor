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
import com.geoknoesis.kastor.rdf.shacl.native.DynamicComponent
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
    val condition = shape.deactivatedWhen
    if (condition == null) {
        validateNodeShapeConstraints(focus, shape, tpl, ctx, state, sink)
    } else {
        val activation = Activation.of { deactivates(condition, focus, ctx, state.depth) }
        unlessDeactivated(activation, sink, ctx, focus, tpl, ConstraintType.NODE) { s -> validateNodeShapeConstraints(focus, shape, tpl, ctx, state, s) }
    }
}

/** The constraints of the node shape [shape] for [focus], once the shape is known to be active for it. */
private fun NativeShaclValidator.validateNodeShapeConstraints(
    focus: RdfTerm,
    shape: CompiledNodeShape,
    tpl: ResultTemplate,
    ctx: NativeValidationContext,
    state: DepthState,
    sink: Sink,
) {
    val reifiers = shape.reifierConditions
    for (nr in shape.nodeRefs) {
        sink.unit { s ->
            unlessReifierDeactivated(reifiers.link(SHACL.node, nr), focus, tpl, ConstraintType.NODE, ctx, state, s) { t ->
                t.outcome(ctx, conformance(focus, nr, ctx, state), focus, tpl, ConstraintType.NODE, focus, focus, nr) {
                    violation(focus, tpl, constraintStub(ConstraintType.NODE), "sh:node constraint failed for ${nr.displayId()}", value = focus)
                }
            }
        }
        if (sink.stop) return
    }

    for (exprRef in shape.nodeByExpressionRefs) {
        sink.unit { s ->
            unlessReifierDeactivated(reifiers.link(SHACL.nodeByExpression, exprRef), focus, tpl, ConstraintType.NODE_BY_EXPRESSION, ctx, state, s) { t ->
                t.outcome(ctx, conformance(focus, exprRef, ctx, state), focus, tpl, ConstraintType.NODE_BY_EXPRESSION, focus, focus, exprRef) {
                    violation(
                        focus, tpl, constraintStub(ConstraintType.NODE_BY_EXPRESSION),
                        "sh:nodeByExpression constraint failed", value = focus, sourceConstraint = exprRef,
                    )
                }
            }
        }
        if (sink.stop) return
    }

    if (shape.nodeConstraints.isNotEmpty()) {
        evaluateConstraintsForValues(focus, tpl, listOf(focus), shape.nodeConstraints, ctx, state, shape.shapeNode, null, sink)
        if (sink.stop) return
    }

    for (ps in shape.propertyShapes) {
        unlessReifierDeactivated(reifiers.link(SHACL.`property`, ps.shapeNode), focus, tpl, ConstraintType.PROPERTY_SHAPE, ctx, state, sink) { s ->
            validatePropertyShape(focus, ps, ctx, state, s)
        }
        if (sink.stop) return
    }

    for (part in shape.logicalParts) {
        sink.unit { s ->
            unlessReifierDeactivated(part.deactivatedWhen, focus, tpl, part.constraintType(), ctx, state, s) { t -> evalLogical(focus, focus, tpl, part, ctx, state, t) }
        }
        if (sink.stop) return
    }

    // The reifiers' deactivation first, then the computed parameters (SHACL 1.2 Node Expressions §8), if any. These are
    // evaluated for a literal focus node too, so that a failed evaluation fails the run (§3.4), though a literal has no
    // properties to close.
    val closedComputed = shape.closedParameter != null || shape.ignoredParameter != null
    if (config.validateClosedShapes && (shape.closed != ClosedMode.NONE || shape.closedParameter != null) && (focus is RdfResource || closedComputed)) {
        unlessReifierDeactivated(reifiers.closed, focus, tpl, ConstraintType.CLOSED, ctx, state, sink) { s -> validateClosedConstraint(focus, shape, tpl, ctx, state, s) }
    }
}

/** `sh:closed` of [shape] for [focus], with its computed parameters resolved for [focus]. */
private fun NativeShaclValidator.validateClosedConstraint(
    focus: RdfTerm,
    shape: CompiledNodeShape,
    tpl: ResultTemplate,
    ctx: NativeValidationContext,
    state: DepthState,
    sink: Sink,
) {
    val resolved = try {
        resolveClosed(focus, shape, ctx, state.depth)
    } catch (e: ProvisionalUndefined) {
        sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.CLOSED, null, e.node, e.shape)
        null
    }
    if (resolved != null && resolved.first != ClosedMode.NONE && focus is RdfResource) {
        sink.failAll(validateClosed(focus, shape, resolved.first, resolved.second, tpl, ctx, firstOnly = !sink.reports))
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
        sink.unit { s ->
            try {
                evaluateConstraint(focus, tpl, values, c, ctx, state, currentShape, path, s)
            } catch (e: ProvisionalUndefined) {
                // A node expression of the constraint (or a function body its SPARQL query calls) read a provisional
                // answer of the recursion solver: the constraint is undefined for now.
                s.undefinedAnswer(ctx, focus, tpl, provisionalType(c), null, e.node, e.shape)
            }
        }
    }
}

/**
 * True for a `sh:reifierShape` or `sh:reificationRequired` constraint, constant or with a computed
 * `sh:reificationRequired` (SHACL 1.2 Node Expressions §8): [validatePropertyShape] evaluates them, once per focus node,
 * with the triples they constrain.
 */
internal fun PropertyConstraint.isReifierConstraint(): Boolean =
    this is PropertyConstraint.ReifierShape || this is PropertyConstraint.ReificationRequired ||
        (this is PropertyConstraint.Dynamic && component == DynamicComponent.REIFICATION)

/** The constraint type an undefined [ProvisionalUndefined] answer of [c] is reported with. */
internal fun provisionalType(c: PropertyConstraint): ConstraintType =
    when (c) {
        is PropertyConstraint.Deactivatable -> provisionalType(c.constraint)
        is PropertyConstraint.ReifierShape, is PropertyConstraint.ReificationRequired -> ConstraintType.REIFIER_SHAPE
        is PropertyConstraint.Expression -> ConstraintType.EXPRESSION
        is PropertyConstraint.NodeByExpressionComputed -> ConstraintType.NODE_BY_EXPRESSION
        is PropertyConstraint.Sparql -> ConstraintType.SPARQL_CONSTRAINT
        is PropertyConstraint.SparqlComponent -> ConstraintType.SPARQL_CONSTRAINT_COMPONENT
        is PropertyConstraint.Dynamic -> c.component.type
        else -> ConstraintType.PROPERTY_SHAPE
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
    val tpl = ResultTemplate(ps.shapeNode, ps.severity, ps.severityCustomIri, ps.messages, reportPath(ps, ctx))
    val condition = ps.deactivatedWhen
    if (condition == null) {
        validatePropertyShapeConstraints(focus, ps, tpl, ctx, state, sink)
    } else {
        val activation = Activation.of { deactivates(condition, focus, ctx, state.depth) }
        unlessDeactivated(activation, sink, ctx, focus, tpl, ConstraintType.PROPERTY_SHAPE) { s -> validatePropertyShapeConstraints(focus, ps, tpl, ctx, state, s) }
    }
}

/** The constraints of the property shape [ps] for [focus], once the shape is known to be active for it. */
private fun NativeShaclValidator.validatePropertyShapeConstraints(
    focus: RdfTerm,
    ps: CompiledPropertyShape,
    tpl: ResultTemplate,
    ctx: NativeValidationContext,
    state: DepthState,
    sink: Sink,
) {
    val values = try {
        propertyValueNodes(focus, ps, ctx, state)
    } catch (e: ProvisionalUndefined) {
        // sh:values / sh:defaultValue read a provisional answer of the recursion solver: the value nodes, and so
        // the whole property shape, are undefined for now.
        sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.PROPERTY_SHAPE, null, e.node, e.shape)
        return
    }
    evaluateConstraintsForValues(focus, tpl, values, ps.constraints, ctx, state, ps.shapeNode, ps.path, sink)
    if (sink.stop) return
    for (part in ps.logicalParts) {
        if (part.deactivatedWhen.isEmpty()) {
            for (v in values) {
                sink.unit { s -> evalLogical(focus, v, tpl, part, ctx, state, s) }
                if (sink.stop) return
            }
        } else if (values.isNotEmpty()) {
            // The focus node of a constraint a reifier deactivates is the property shape's, not a value node: the
            // condition is evaluated once per (focus node, constraint), in one unit with every value node, so that a
            // re-run unit re-checks it.
            sink.unit { s ->
                unlessReifierDeactivated(part.deactivatedWhen, focus, tpl, part.constraintType(), ctx, state, s) { t ->
                    for (v in values) {
                        evalLogical(focus, v, tpl, part, ctx, state, t)
                        if (t.stop) break
                    }
                }
            }
            if (sink.stop) return
        }
    }
    for (nested in ps.nestedPropertyShapes) {
        if (values.isEmpty()) continue
        unlessReifierDeactivated(ps.reifierConditions.link(SHACL.`property`, nested.shapeNode), focus, tpl, ConstraintType.PROPERTY_SHAPE, ctx, state, sink) { s ->
            for (v in values) {
                validatePropertyShape(v, nested, ctx, state, s)
                if (s.stop) break
            }
        }
        if (sink.stop) return
    }
    validateReifierConstraints(focus, ps, tpl, ctx, state, sink)
}

/**
 * The `sh:reifierShape` / `sh:reificationRequired` constraints of [ps] for [focus], with the triples they constrain.
 * The constraints a reifier deactivates for the focus node are left out (SHACL 1.2 Core §3.1.6); one whose
 * deactivation is undefined for now is checked on its own ([unlessDeactivated]). A computed `sh:reificationRequired`
 * (SHACL 1.2 Node Expressions §8) is resolved for the focus node once the constraint is known to be active. A literal
 * has no triples to reify, but the computed parameters are evaluated, so that a failed evaluation fails the run (§3.4).
 */
private fun NativeShaclValidator.validateReifierConstraints(
    focus: RdfTerm,
    ps: CompiledPropertyShape,
    tpl: ResultTemplate,
    ctx: NativeValidationContext,
    state: DepthState,
    sink: Sink,
) {
    val reifierConstraints = ps.constraints.filter { it.unwrapped().isReifierConstraint() }
    val checked = if (focus is RdfResource) reifierConstraints else reifierConstraints.filter { it.unwrapped() is PropertyConstraint.Dynamic }
    if (checked.isEmpty()) return
    val claims = if (focus is RdfResource) tripleClaimsMatchingSimplePath(focus, ps.path, ctx.data) else emptyList()
    fun resolved(c: PropertyConstraint, s: Sink): PropertyConstraint? =
        if (c is PropertyConstraint.Dynamic) resolveReification(c, focus, ps, tpl, ctx, state, s) else c
    fun check(constraints: List<PropertyConstraint>, s: Sink) {
        if (focus is RdfResource) validateReifierPropertyConstraints(focus, tpl, claims, constraints, ctx, state, s)
    }
    sink.unit { s ->
        val active = ArrayList<PropertyConstraint>()
        for (c in checked) {
            val activation = if (c is PropertyConstraint.Deactivatable) reifierActivation(c.deactivatedWhen, focus, ctx, state.depth) else Activation.ACTIVE
            if (activation.active) {
                resolved(c.unwrapped(), s)?.let { active.add(it) }
            } else {
                unlessDeactivated(activation, s, ctx, focus, tpl, ConstraintType.REIFIER_SHAPE) { t -> resolved(c.unwrapped(), t)?.let { check(listOf(it), t) } }
            }
        }
        check(active, s)
    }
}

/**
 * The `sh:reifierShape` / `sh:reificationRequired` constraint the computed [c] stands for at [focus] ([resolveDynamic]).
 * Null when it constrains nothing: on a path that is not a predicate IRI (an unsupported feature, see
 * `UnsupportedShaclFeature.REIFIER_CONSTRAINT_ON_COMPLEX_PATH`, here ignored), whose expression is not evaluated, or
 * when the expression reads a provisional answer of the recursion solver, which is reported as undefined.
 */
private fun NativeShaclValidator.resolveReification(
    c: PropertyConstraint.Dynamic,
    focus: RdfTerm,
    ps: CompiledPropertyShape,
    tpl: ResultTemplate,
    ctx: NativeValidationContext,
    state: DepthState,
    sink: Sink,
): PropertyConstraint? {
    if (ps.path !is ShaclPath.Predicate) return null
    return try {
        resolveDynamic(c, focus, ctx, state.depth, ps.shapeNode)
    } catch (e: ProvisionalUndefined) {
        sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.REIFIER_SHAPE, null, e.node, e.shape)
        null
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
    // sh:reificationRequired: on its own, or as a parameter of an active sh:reifierShape constraint.
    val reifReq = constraints.any { (it is PropertyConstraint.ReificationRequired && it.required) || (it is PropertyConstraint.ReifierShape && it.required) }
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
private fun NativeShaclValidator.propertyValueNodes(focus: RdfTerm, ps: CompiledPropertyShape, ctx: NativeValidationContext, state: DepthState): List<RdfTerm> {
    val pathValues = PathEvaluator.evaluate(focus, ps.path, ctx.data)
    val valuesExpr = ps.values
    var values = if (valuesExpr == null) pathValues else distinctShaclTerms(pathValues + evalNodeExpression(valuesExpr, ctx, focus, shapeDepth = state.depth))
    checkNodeExpressionSize(values.size, ctx, "value node computation")
    val defaultExpr = ps.defaultValue
    if (values.isEmpty() && defaultExpr != null) values = evalNodeExpression(defaultExpr, ctx, focus, shapeDepth = state.depth)
    return values
}
