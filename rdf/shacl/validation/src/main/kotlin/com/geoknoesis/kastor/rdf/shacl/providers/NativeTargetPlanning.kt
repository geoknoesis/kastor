package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.UnsupportedFeatureHandling
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeature
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeatureException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.native.ClosedMode
import com.geoknoesis.kastor.rdf.shacl.native.CompiledNodeShape
import com.geoknoesis.kastor.rdf.shacl.native.CompiledShapeGraph
import com.geoknoesis.kastor.rdf.shacl.native.DataGraphIndex
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint

/**
 * Blank node `sh:targetNode` values. One that is a node of the data graph (the graphs share it: a graph validated
 * against itself, shapes discovered from the data) is an ordinary focus node, whatever its triples look like. One
 * that is not can never be a focus node, and validating it anyway would check the shape against a node without
 * triples (bogus `sh:minCount` results, everything else conforming): in SHACL 1.2 it is a node expression, which
 * the engine does not evaluate. Every such target — plain, list-shaped, described with `sh:` vocabulary or
 * call-shaped — is an unsupported feature, failing or warning per [ValidationConfig.unsupportedFeatures]. Returns
 * the warnings to add to the report.
 */
internal fun NativeShaclValidator.checkBlankNodeTargets(compiled: CompiledShapeGraph, data: DataGraphIndex): List<String> {
    val unsupported = LinkedHashSet<String>()
    for (shape in compiled.orderedNodeShapes) {
        for (target in shape.targets.targetNodes) {
            if (target !is BlankNode || data.containsNode(target)) continue
            val call = shape.targets.callShapedTargets[target]
            val detail =
                if (call != null) {
                    "a call of <${call.value}>, which is not declared as a function in the shapes graph; the blank node is " +
                        "not a node of the data graph"
                } else {
                    "the blank node $target is not a node of the data graph, so it can never be a focus node; a blank node " +
                        "sh:targetNode is a target only when the shapes graph and the data graph share it"
                }
            unsupported.add("sh:targetNode with a node expression on ${shape.shapeNode} ($detail)")
        }
    }
    if (unsupported.isNotEmpty() && config.unsupportedFeatures == UnsupportedFeatureHandling.FAIL) {
        val cause = UnsupportedShaclFeatureException(
            "Unsupported SHACL feature(s) for the native engine: ${unsupported.joinToString("; ")}. " +
                "Set ValidationConfig.unsupportedFeatures = IGNORE_WITH_WARNING to skip these constructs.",
            setOf(UnsupportedShaclFeature.NODE_EXPRESSION),
        )
        throw ShaclValidationException("SHACL validation failed: ${cause.message}", cause)
    }
    return unsupported.map { "Unsupported SHACL feature ignored: $it" }
}

internal fun NativeShaclValidator.computeFocusNodes(shape: CompiledNodeShape, ctx: NativeValidationContext, undecidedTargets: MutableList<ValidationViolation>): List<RdfTerm> {
    val data = ctx.data
    val out = linkedSetOf<RdfTerm>()
    shape.targets.targetClasses.forEach { c -> data.instancesMatchingTargetClass(c).forEach { out.add(it) } }
    // An IRI or literal target need not occur in the data graph (SHACL §2.1.3.1); a blank node of the shapes graph
    // that is not a data node denotes nothing there (see checkBlankNodeTargets) and is not validated.
    shape.targets.targetNodes.forEach { if (it !is BlankNode || data.containsNode(it)) out.add(it) }
    shape.targets.targetSubjectsOf.forEach { p -> data.subjectsWithPredicate(p).forEach { out.add(it) } }
    shape.targets.targetObjectsOf.forEach { p ->
        data.objectsWithPredicate(p).forEach { o -> out.add(o) }
    }
    // SHACL 1.2 Core §3.1.3.1: the outputs of evalExpr(expr, data graph, shape, {}) are targets.
    shape.targets.targetNodeExpressions.forEach { out.addAll(evalNodeExpression(it, ctx, shape.shapeNode)) }
    val shapeIri = shape.shapeNode as? Iri
    if (shapeIri != null) {
        data.subjectsWith(SHACL.shape, shapeIri).forEach { out.add(it) }
    }
    for (tw in shape.targetWhereRefs) {
        for (candidate in targetWhereCandidates(tw, ctx)) {
            ctx.budget.tick("targetWhere")
            when (conformance(candidate, tw, ctx, DepthState(0, Mode.CONFORMS))) {
                Conformance.CONFORMS -> out.add(candidate)
                Conformance.FAILS -> Unit
                Conformance.UNDEFINED -> undecidedTargets.add(undecidableTargetResult(candidate, shape, tw, ctx))
            }
        }
    }
    return out.toList()
}

/**
 * `sh:targetWhere` candidates: every node of the data graph (subjects and objects), narrowed to class instances
 * when the membership shape requires a class, and without the nodes its `sh:nodeKind` / `sh:datatype` exclude.
 */
private fun targetWhereCandidates(tw: RdfResource, ctx: NativeValidationContext): Collection<RdfTerm> {
    val constraints =
        if (tw in ctx.compiled.referencedPropertyShapes) emptyList() else ctx.compiled.referencedNodeShapes[tw]?.nodeConstraints.orEmpty()
    constraints.firstNotNullOfOrNull { c -> (c as? PropertyConstraint.Class)?.iri }?.let { return ctx.data.instancesMatchingTargetClass(it).toList() }
    val kinds = constraints.filterIsInstance<PropertyConstraint.NodeKind>()
    val literalKinds = setOf(SHACL.Literal, SHACL.BlankNodeOrLiteral, SHACL.IRIOrLiteral)
    val noLiterals = kinds.any { nk -> nk.kinds.none { it in literalKinds } }
    val onlyLiterals = constraints.any { it is PropertyConstraint.Datatype } || kinds.any { nk -> nk.kinds.all { it == SHACL.Literal } }
    val nodes = ctx.data.allNodes()
    return when {
        noLiterals -> nodes.filter { it !is Literal }
        onlyLiterals -> nodes.filter { it is Literal }
        else -> nodes
    }
}

/** Approximates constraint checks: focus count × (property constraints + logical + node refs + optional closed). */
internal fun NativeShaclValidator.countConstraintEvaluationSlots(shape: CompiledNodeShape, focusCount: Int): Long {
    if (focusCount == 0) return 0
    val perFocus = shape.nodeConstraints.size.toLong() +
        shape.propertyShapes.sumOf { it.constraints.size.toLong() + it.logicalParts.size } +
        shape.logicalParts.size + shape.nodeRefs.size + shape.nodeByExpressionRefs.size +
        if (config.validateClosedShapes && shape.closed != ClosedMode.NONE) 1 else 0
    var slots = focusCount * perFocus
    if (shape.uniqueValuesForProps.isNotEmpty()) slots += 1
    return slots
}
