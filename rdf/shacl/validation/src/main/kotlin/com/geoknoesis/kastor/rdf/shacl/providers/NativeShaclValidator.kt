package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Dataset
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.FalseLiteral
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.ShapesGraphNotFoundException
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ShaclShape
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationReport
import com.geoknoesis.kastor.rdf.shacl.ValidationStatistics
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ValidationWarning
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import java.time.Duration
import com.geoknoesis.kastor.rdf.shacl.native.ClosedMode
import com.geoknoesis.kastor.rdf.shacl.native.CompiledNodeShape
import com.geoknoesis.kastor.rdf.shacl.native.CompiledPropertyShape
import com.geoknoesis.kastor.rdf.shacl.native.CompiledShapeGraph
import com.geoknoesis.kastor.rdf.shacl.native.DataGraphIndex
import com.geoknoesis.kastor.rdf.shacl.native.NativeCompileCache
import com.geoknoesis.kastor.rdf.shacl.native.NodeLogicalPart
import com.geoknoesis.kastor.rdf.shacl.native.OwlImportsExpander
import com.geoknoesis.kastor.rdf.shacl.native.PathEvaluator
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint
import com.geoknoesis.kastor.rdf.shacl.native.graphFromTriples
import com.geoknoesis.kastor.rdf.shacl.native.literalLess
import com.geoknoesis.kastor.rdf.shacl.native.literalLessOrEqual
import com.geoknoesis.kastor.rdf.shacl.native.typedLiteralLexicallyValidForShaclDatatype
import com.geoknoesis.kastor.rdf.shacl.native.mergeGraphs
import com.geoknoesis.kastor.rdf.shacl.native.ShaclPath
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler
import com.geoknoesis.kastor.rdf.shacl.native.ShapesGraphTriplesCollector
import com.geoknoesis.kastor.rdf.shacl.native.ShapesStructuralDigest
import com.geoknoesis.kastor.rdf.shacl.native.ShapeGraphIndex
import com.geoknoesis.kastor.rdf.shacl.native.SparqlConstraintEvaluator
import com.geoknoesis.kastor.rdf.shacl.native.isLexicallyTrue
import com.geoknoesis.kastor.rdf.shacl.native.constraintStub
import com.geoknoesis.kastor.rdf.shacl.native.literalLexicalString
import com.geoknoesis.kastor.rdf.shacl.native.satisfiesMaxExclusive
import com.geoknoesis.kastor.rdf.shacl.native.satisfiesMaxInclusive
import com.geoknoesis.kastor.rdf.shacl.native.satisfiesMinExclusive
import com.geoknoesis.kastor.rdf.shacl.native.satisfiesMinInclusive
import com.geoknoesis.kastor.rdf.shacl.native.shaclRdfTermEquals
import com.geoknoesis.kastor.rdf.shacl.native.distinctShaclTerms

/**
 * Kastor native SHACL Core validator (compile → plan → execute → report).
 */
internal class NativeShaclValidator(private val config: ValidationConfig) : ShaclValidator {

    private companion object {
        val singleLineBreakRegex = Regex("[\\f\\r\\n\\u000B]")
    }

    private data class ValidationContext(
        val compiled: CompiledShapeGraph,
        val data: DataGraphIndex,
        val mergedDataAndShapes: RdfGraph,
        val shapesIndex: ShapeGraphIndex,
    )

    override fun validate(graph: RdfGraph, shapes: RdfGraph): ValidationReport =
        runValidation(graph, shapes, config.dataset.validationDataset)

    override fun validateDataset(dataset: Dataset, shapes: RdfGraph?): ValidationReport =
        runValidation(dataset.defaultGraph, shapes ?: Rdf.graph { }, dataset)

    private fun runValidation(graph: RdfGraph, shapes: RdfGraph, datasetForDiscovery: Dataset?): ValidationReport {
        val start = System.currentTimeMillis()
        val combinedEstimate = graph.size().toLong() + shapes.size().toLong()
        if (combinedEstimate > config.maxCombinedGraphTriples) {
            throw ShaclValidationException(
                "Combined data + shapes triple count ($combinedEstimate) exceeds ValidationConfig.maxCombinedGraphTriples (${config.maxCombinedGraphTriples})",
            )
        }
        val mergedShapesTriples =
            try {
                prepareMergedShapesTriples(graph, shapes, datasetForDiscovery)
            } catch (e: ShapesGraphNotFoundException) {
                throw ShaclValidationException(e.message ?: "Referenced shapes graph not found", e)
            }
        val digest =
            try {
                ShapesStructuralDigest.digest(mergedShapesTriples, config)
            } catch (e: ShapeCompileException) {
                throw ShaclValidationException("SHACL shapes digest failed: ${e.message}", e)
            }
        config.cache.shapesGraphVersion?.let { NativeCompileCache.assertTagOrRecord(it, digest) }
        val cacheKey = ShapesStructuralDigest.compileCacheKey(digest, config)
        val compiled =
            NativeCompileCache.getCompiled(cacheKey)
                ?: try {
                    ShapesCompiler.compile(mergedShapesTriples, config).also { NativeCompileCache.putCompiled(cacheKey, it) }
                } catch (e: ShapeCompileException) {
                    throw ShaclValidationException("SHACL shape graph compile failed: ${e.message}", e)
                }
        val shapesGraph = graphFromTriples(mergedShapesTriples)
        val mergedForSparql = mergeGraphs(graph, shapesGraph)
        val shapesIndex = ShapeGraphIndex(mergedShapesTriples)
        val ctx = ValidationContext(compiled, DataGraphIndex(graph), mergedForSparql, shapesIndex)

        val violations = mutableListOf<ValidationViolation>()
        val warnings = mutableListOf<ValidationWarning>()
        var hitViolationCap = false

        for (shape in compiled.orderedNodeShapes) {
            if (shape.uniqueValuesForProps.isNotEmpty()) {
                violations.addAll(validateUniqueValuesForShape(shape, ctx))
                if (violations.size >= config.maxViolations) {
                    hitViolationCap = true
                    break
                }
            }
            val focusNodes = computeFocusNodes(shape, ctx)
            for (focus in focusNodes) {
                violations.addAll(
                    validateNodeShape(focus, shape, ctx, DepthState(0, emptyList())),
                )
                if (violations.size >= config.maxViolations) {
                    hitViolationCap = true
                    break
                }
            }
            if (hitViolationCap) break
        }

        val cap = config.maxViolations.coerceAtLeast(1)
        val violationsTruncated = hitViolationCap || violations.size > cap
        val cappedViolations = violations.take(cap)

        val elapsed = Duration.ofMillis(System.currentTimeMillis() - start)
        val validatedConstraintSlots = countConstraintEvaluationSlots(compiled, ctx)
        val statistics = buildStatistics(graph, shapesGraph, cappedViolations, warnings, compiled, validatedConstraintSlots)

        return ValidationReport(
            isValid = cappedViolations.none {
                it.severity == ViolationSeverity.VIOLATION || it.severity == ViolationSeverity.ERROR
            },
            violations = cappedViolations,
            warnings = warnings,
            statistics = statistics,
            validationTime = elapsed,
            validatedResources = graph.getTriples().map { it.subject }.distinct().size,
            validatedConstraints = validatedConstraintSlots.coerceAtLeast(cappedViolations.size),
            shapeViolations = cappedViolations.groupBy { it.shapeUri ?: "unknown" },
            constraintViolations = cappedViolations.groupBy { it.constraint.constraintType.name },
            violationsTruncated = violationsTruncated,
        )
    }

    private fun prepareMergedShapesTriples(
        data: RdfGraph,
        shapesArg: RdfGraph,
        datasetForDiscovery: Dataset?,
    ): List<RdfTriple> {
        val aux = config.dataset.auxiliaryGraphs
        val ds = datasetForDiscovery ?: config.dataset.validationDataset
        val primary: List<RdfTriple> =
            when (val name = config.dataset.shapesGraphNamedGraph) {
                null -> shapesArg.getTriples()
                else -> {
                    val g =
                        ds?.getNamedGraph(name)
                            ?: aux[name]
                            ?: throw ShapesGraphNotFoundException(
                                "dataset.shapesGraphNamedGraph <$name> not found in validationDataset or auxiliaryGraphs",
                            )
                    g.getTriples()
                }
            }
        val expanded = OwlImportsExpander.expand(graphFromTriples(primary), config.imports, aux)
        val extra =
            if (config.dataset.discoverShapesGraphFromData) {
                ShapesGraphTriplesCollector.collectFromData(data, ds, aux)
            } else {
                emptyList()
            }
        return (expanded.getTriples() + extra).distinct()
    }

    private fun multisetTermsEqual(a: List<RdfTerm>, b: List<RdfTerm>): Boolean {
        if (a.size != b.size) return false
        val rest = b.toMutableList()
        for (x in a) {
            val i = rest.indexOfFirst { shaclRdfTermEquals(it, x) }
            if (i < 0) return false
            rest.removeAt(i)
        }
        return true
    }

    /** Symmetric multiset difference: unmatched terms from [left] ∪ leftovers of [right] after greedy pairing. */
    private fun multisetUnmatchedTerms(left: List<RdfTerm>, right: List<RdfTerm>): List<RdfTerm> {
        val rest = right.toMutableList()
        val out = mutableListOf<RdfTerm>()
        for (x in left) {
            val i = rest.indexOfFirst { shaclRdfTermEquals(it, x) }
            if (i >= 0) rest.removeAt(i)
            else out.add(x)
        }
        out.addAll(rest)
        return out
    }

    override fun validate(graph: RdfGraph, shapes: List<ShaclShape>): ValidationReport {
        if (shapes.isNotEmpty()) {
            throw UnsupportedOperationException(
                "Native SHACL validator does not support validate(graph, shapes: List<ShaclShape>). " +
                    "Pass shapes as an RdfGraph via validate(graph, shapesGraph).",
            )
        }
        return validate(graph, Rdf.graph { })
    }

    override fun validateResource(graph: RdfGraph, shapes: RdfGraph, resource: RdfResource): ValidationReport {
        val triples = graph.getTriples().filter { it.subject == resource || it.obj == resource }
        val filtered = Rdf.graph {
            triples.forEach { t ->
                t.subject - t.predicate - t.obj
            }
        }
        return validate(filtered, shapes)
    }

    override fun validateConstraints(graph: RdfGraph, constraints: List<com.geoknoesis.kastor.rdf.shacl.ShaclConstraint>): ValidationReport {
        if (constraints.isNotEmpty()) {
            throw UnsupportedOperationException(
                "Native SHACL validator does not support validateConstraints; pass constraints as part of a shapes RdfGraph.",
            )
        }
        return validate(graph, Rdf.graph { })
    }

    override fun conforms(graph: RdfGraph, shapes: RdfGraph): Boolean = validate(graph, shapes).isValid

    override fun getValidationStatistics(graph: RdfGraph, shapes: RdfGraph): ValidationStatistics =
        validate(graph, shapes).statistics

    private data class DepthState(val depth: Int, val stack: List<RdfResource>) {
        fun push(shapeNode: RdfResource): DepthState? {
            if (shapeNode in stack) return null
            return DepthState(depth + 1, stack + shapeNode)
        }
    }

    private fun computeFocusNodes(shape: CompiledNodeShape, ctx: ValidationContext): List<RdfTerm> {
        val data = ctx.data
        val out = linkedSetOf<RdfTerm>()
        shape.targets.targetClasses.forEach { c -> data.instancesMatchingTargetClass(c).forEach { out.add(it) } }
        shape.targets.targetNodes.forEach { out.add(it) }
        shape.targets.targetSubjectsOf.forEach { p -> data.subjectsWithPredicate(p).forEach { out.add(it) } }
        shape.targets.targetObjectsOf.forEach { p ->
            data.objectsWithPredicate(p).forEach { o -> out.add(o) }
        }
        val shapeIri = shape.shapeNode as? Iri
        if (shapeIri != null) {
            data.subjectsWith(SHACL.shape, shapeIri).forEach { out.add(it) }
        }
        val twBase = DepthState(0, emptyList())
        for (tw in shape.targetWhereCompiled) {
            for (cand in targetWhereCandidateResources(tw, data)) {
                if (validateNodeShape(cand, tw, ctx, twBase).isEmpty()) {
                    out.add(cand)
                }
            }
        }
        return out.toList()
    }

    private fun targetWhereCandidateResources(tw: CompiledNodeShape, data: DataGraphIndex): List<RdfResource> {
        val cls = tw.nodeConstraints.firstNotNullOfOrNull { c -> (c as? PropertyConstraint.Class)?.iri }
        return if (cls != null) {
            data.instancesMatchingTargetClass(cls).distinct().toList()
        } else {
            data.distinctResourceSubjects().toList()
        }
    }

    /** Approximates constraint checks: focus count × (property constraints + logical + node refs + optional closed). */
    private fun countConstraintEvaluationSlots(compiled: CompiledShapeGraph, ctx: ValidationContext): Int {
        var total = 0
        for (shape in compiled.orderedNodeShapes) {
            val f = computeFocusNodes(shape, ctx).size
            if (f == 0) continue
            val perFocus =
                shape.nodeConstraints.size +
                    shape.propertyShapes.sumOf { it.constraints.size + it.logicalParts.size } +
                    shape.logicalParts.size +
                    shape.nodeRefs.size +
                    shape.nodeByExpressionRefs.size +
                    if (config.validateClosedShapes && shape.closed != ClosedMode.NONE) 1 else 0
            var slots = f * perFocus
            if (shape.uniqueValuesForProps.isNotEmpty() && f > 0) slots += 1
            total += slots
        }
        return total
    }

    private fun validateNodeShape(
        focus: RdfTerm,
        shape: CompiledNodeShape,
        ctx: ValidationContext,
        state: DepthState,
    ): List<ValidationViolation> {
        val vs = mutableListOf<ValidationViolation>()
        val compiled = ctx.compiled

        if (state.depth > config.maxRecursionDepth) {
            vs.add(
                violation(
                    focus = focus,
                    shape = shape.shapeNode,
                    severity = ViolationSeverity.VIOLATION,
                    severityCustomIri = null,
                    constraint = constraintStub(ConstraintType.NODE),
                    message = "Exceeded SHACL recursion guard maxRecursionDepth=${config.maxRecursionDepth} at shape ${shape.shapeNode.displayId()}",
                    pathTerms = null,
                ),
            )
            return vs
        }

        for (nr in shape.nodeRefs) {
            val nested =
                compiled.shapesByNode[nr]
                    ?: ShapesCompiler.tryCompileInlineNodeShape(nr, ctx.shapesIndex, config)
            if (nested == null) {
                vs.add(
                    violation(
                        focus = focus,
                        shape = shape.shapeNode,
                        severity = shape.severity,
                        severityCustomIri = shape.severityCustomIri,
                        constraint = constraintStub(ConstraintType.NODE),
                        message = "sh:node references undefined NodeShape $nr",
                        pathTerms = null,
                    ),
                )
                continue
            }
            val st = state.push(nested.shapeNode)
            if (st == null) {
                vs.add(
                    violation(
                        focus = focus,
                        shape = shape.shapeNode,
                        severity = ViolationSeverity.VIOLATION,
                        severityCustomIri = null,
                        constraint = constraintStub(ConstraintType.NODE),
                        message = "Shape dependency cycle detected involving ${nested.shapeNode.displayId()}",
                        pathTerms = null,
                    ),
                )
                continue
            }
            val nestedVs = validateNodeShape(focus, nested, ctx, st)
            if (nestedVs.isNotEmpty()) {
                vs.add(
                    violation(
                        focus = focus,
                        shape = shape.shapeNode,
                        severity = shape.severity,
                        severityCustomIri = shape.severityCustomIri,
                        constraint = constraintStub(ConstraintType.NODE),
                        message = "sh:node constraint failed",
                        pathTerms = null,
                        value = focus,
                    ),
                )
            }
        }

        for (exprRef in shape.nodeByExpressionRefs) {
            val nested =
                compiled.shapesByNode[exprRef]
                    ?: ShapesCompiler.tryCompileInlineNodeShape(exprRef, ctx.shapesIndex, config)
            if (nested == null) {
                vs.add(
                    violation(
                        focus = focus,
                        shape = shape.shapeNode,
                        severity = shape.severity,
                        severityCustomIri = shape.severityCustomIri,
                        constraint = constraintStub(ConstraintType.NODE_BY_EXPRESSION),
                        message = "sh:nodeByExpression references missing shape $exprRef",
                        pathTerms = null,
                    ),
                )
                continue
            }
            val st = state.push(nested.shapeNode)
            if (st == null) {
                vs.add(
                    violation(
                        focus = focus,
                        shape = shape.shapeNode,
                        severity = ViolationSeverity.VIOLATION,
                        severityCustomIri = null,
                        constraint = constraintStub(ConstraintType.NODE_BY_EXPRESSION),
                        message = "Shape dependency cycle detected involving ${nested.shapeNode.displayId()}",
                        pathTerms = null,
                    ),
                )
                continue
            }
            val nestedVs = validateNodeShape(focus, nested, ctx, st)
            if (nestedVs.isNotEmpty()) {
                vs.add(
                    violation(
                        focus = focus,
                        shape = shape.shapeNode,
                        severity = shape.severity,
                        severityCustomIri = shape.severityCustomIri,
                        constraint = constraintStub(ConstraintType.NODE_BY_EXPRESSION),
                        message = "sh:nodeByExpression constraint failed",
                        pathTerms = null,
                        value = focus,
                    ),
                )
            }
        }

        val siblingQualifiedShapes =
            shape.propertyShapes.flatMap { ps ->
                ps.constraints.filterIsInstance<PropertyConstraint.Qualified>().map { it.shape }
            }.distinct()

        if (shape.nodeConstraints.isNotEmpty()) {
            vs.addAll(
                evaluateConstraintsForValues(
                    focus = focus,
                    violationShape = shape.shapeNode,
                    severity = shape.severity,
                    severityCustomIri = shape.severityCustomIri,
                    pathTerms = null,
                    pathPredicate = null,
                    values = listOf(focus),
                    constraints = shape.nodeConstraints,
                    ctx = ctx,
                    state = state,
                    siblingQualifiedShapes = siblingQualifiedShapes,
                ),
            )
        }

        for (ps in shape.propertyShapes) {
            vs.addAll(validatePropertyShape(focus, ps, ctx, state, siblingQualifiedShapes))
        }

        for (part in shape.logicalParts) {
            vs.addAll(evalLogical(focus, focus, shape.shapeNode, shape.severity, shape.severityCustomIri, part, ctx, state, null))
        }

        if (config.validateClosedShapes && shape.closed != ClosedMode.NONE && focus is RdfResource) {
            vs.addAll(validateClosed(focus, shape, ctx))
        }

        return vs
    }

    private fun evalLogical(
        reportFocus: RdfTerm,
        logicalTarget: RdfTerm,
        violationShape: RdfResource,
        severity: ViolationSeverity,
        severityCustomIri: Iri?,
        part: NodeLogicalPart,
        ctx: ValidationContext,
        state: DepthState,
        logicalPathTerms: List<RdfTerm>?,
    ): List<ValidationViolation> {
        val compiled = ctx.compiled
        fun conforms(ref: RdfResource): Boolean {
            val idx = ctx.shapesIndex
            if (idx.objects(ref, SHACL.deactivated).any { isLexicallyTrue(it) }) return true
            compiled.shapesByNode[ref]?.let { nested ->
                val st = state.push(nested.shapeNode) ?: return false
                return validateNodeShape(logicalTarget, nested, ctx, st).isEmpty()
            }
            ShapesCompiler.tryCompilePropertyShape(ref, idx, config)?.let { ps ->
                val sibs = ps.constraints.filterIsInstance<PropertyConstraint.Qualified>().map { it.shape }.distinct()
                return validatePropertyShape(logicalTarget, ps, ctx, state, sibs).isEmpty()
            }
            ShapesCompiler.tryCompileInlineNodeShape(ref, idx, config)?.let { nested ->
                val st = state.push(nested.shapeNode) ?: return false
                return validateNodeShape(logicalTarget, nested, ctx, st).isEmpty()
            }
            return false
        }
        return when (part) {
            is NodeLogicalPart.And -> {
                val fails = part.operands.filter { !conforms(it) }
                if (fails.isNotEmpty()) {
                    listOf(
                        violation(
                            reportFocus,
                            violationShape,
                            severity,
                            severityCustomIri,
                            constraintStub(ConstraintType.AND),
                            "sh:and failed for operands ${fails.map { it.displayId() }}",
                            logicalPathTerms,
                        ),
                    )
                } else emptyList()
            }
            is NodeLogicalPart.Or -> {
                val ok = part.operands.any { conforms(it) }
                if (!ok) {
                    listOf(
                        violation(
                            reportFocus,
                            violationShape,
                            severity,
                            severityCustomIri,
                            constraintStub(ConstraintType.OR),
                            "sh:or requires at least one matching shape",
                            logicalPathTerms,
                            value = logicalTarget,
                        ),
                    )
                } else emptyList()
            }
            is NodeLogicalPart.Xone -> {
                val matches = part.operands.count { conforms(it) }
                if (matches != 1) {
                    listOf(
                        violation(
                            reportFocus,
                            violationShape,
                            severity,
                            severityCustomIri,
                            constraintStub(ConstraintType.XONE),
                            "sh:xone requires exactly one matching shape (found $matches)",
                            logicalPathTerms,
                        ),
                    )
                } else emptyList()
            }
            is NodeLogicalPart.Not -> {
                if (conforms(part.operand)) {
                    listOf(
                        violation(
                            reportFocus,
                            violationShape,
                            severity,
                            severityCustomIri,
                            constraintStub(ConstraintType.NOT),
                            "sh:not violated: nested shape matched",
                            logicalPathTerms,
                        ),
                    )
                } else emptyList()
            }
        }
    }

    private fun validateClosed(focus: RdfResource, shape: CompiledNodeShape, ctx: ValidationContext): List<ValidationViolation> {
        val allowed = mutableSetOf<Iri>()
        allowed.addAll(shape.ignoredProperties)
        if (shape.closed == ClosedMode.BY_TYPES || RDF.type in shape.ignoredProperties) {
            allowed.add(RDF.type)
        }
        when (shape.closed) {
            ClosedMode.TRUE -> {
                for (ps in shape.propertyShapes) {
                    forwardPathPredicates(ps.path).forEach { allowed.add(it) }
                }
            }
            ClosedMode.BY_TYPES -> {
                allowed.addAll(collectClosedByTypesProperties(focus, ctx))
            }
            ClosedMode.NONE -> Unit
        }
        val violations = mutableListOf<ValidationViolation>()
        val data = ctx.data
        for (p in data.predicatesFor(focus)) {
            if (p !in allowed) {
                val witness = data.objects(focus, p).firstOrNull()
                violations.add(
                    violation(
                        focus,
                        shape.shapeNode,
                        shape.severity,
                        shape.severityCustomIri,
                        constraintStub(ConstraintType.CLOSED, p),
                        "Closed shape disallows predicate $p",
                        listOf(p),
                        value = witness,
                    ),
                )
            }
        }
        return violations
    }

    private fun forwardPathPredicates(path: ShaclPath): Set<Iri> =
        when (path) {
            is ShaclPath.Predicate -> setOf(path.iri)
            is ShaclPath.Inverse -> emptySet()
            is ShaclPath.Sequence -> path.segments.flatMap { forwardPathPredicates(it) }.toSet()
            is ShaclPath.Alternative -> path.options.flatMap { forwardPathPredicates(it) }.toSet()
            is ShaclPath.ZeroOrMore -> forwardPathPredicates(path.child)
            is ShaclPath.OneOrMore -> forwardPathPredicates(path.child)
            is ShaclPath.ZeroOrOne -> forwardPathPredicates(path.child)
        }

    /** SHACL 1.2 `sh:closed sh:ByTypes` property collection (shapes graph walk). */
    private fun collectClosedByTypesProperties(focus: RdfResource, ctx: ValidationContext): Set<Iri> {
        val out = mutableSetOf<Iri>()
        val compiled = ctx.compiled
        val shapesIdx = ctx.shapesIndex
        val visited = mutableSetOf<RdfResource>()

        fun collectFromShapeNode(shapeNode: RdfResource) {
            if (!visited.add(shapeNode)) return
            val cn = compiled.shapesByNode[shapeNode] ?: return
            for (ps in cn.propertyShapes) {
                forwardPathPredicates(ps.path).forEach { out.add(it) }
            }
        }

        val visitedShapeWalk = mutableSetOf<RdfResource>()

        fun collectProperties(s: RdfResource) {
            if (!visitedShapeWalk.add(s)) return
            collectFromShapeNode(s)
            val types = shapesIdx.objects(s, RDF.type)
            if (types.contains(RDFS.Class) && s is Iri) {
                for (sup in shapesIdx.objects(s, RDFS.subClassOf).filterIsInstance<Iri>()) {
                    collectProperties(sup)
                }
                for (cn in compiled.orderedNodeShapes) {
                    if (s in cn.targets.targetClasses) {
                        collectProperties(cn.shapeNode)
                    }
                }
            }
            if (types.contains(SHACL.NodeShape)) {
                for (nr in shapesIdx.objects(s, SHACL.node).filterIsInstance<RdfResource>()) {
                    collectProperties(nr)
                }
            }
        }

        for (t in ctx.data.typesOf(focus)) {
            collectProperties(t)
        }
        return out
    }

    private fun validateUniqueValuesForShape(shape: CompiledNodeShape, ctx: ValidationContext): List<ValidationViolation> {
        val props = shape.uniqueValuesForProps
        if (props.isEmpty()) return emptyList()
        val data = ctx.data
        val targets = computeFocusNodes(shape, ctx).filterIsInstance<RdfResource>()
        if (targets.size < 2) return emptyList()

        fun projectionKey(node: RdfResource): List<List<RdfTerm>> =
            props.map { p -> data.objects(node, p) }

        fun skip(node: RdfResource): Boolean = props.all { data.objects(node, it).isEmpty() }

        fun projectionsMatch(a: RdfResource, b: RdfResource): Boolean {
            val ka = projectionKey(a)
            val kb = projectionKey(b)
            if (ka.size != kb.size) return false
            for (i in ka.indices) {
                if (!multisetTermsEqual(ka[i], kb[i])) return false
            }
            return true
        }

        val clusters = mutableListOf<MutableList<RdfResource>>()
        for (t in targets) {
            if (skip(t)) continue
            val found =
                clusters.firstOrNull { c ->
                    projectionsMatch(t, c.first())
                }
            if (found != null) {
                found.add(t)
            } else {
                clusters.add(mutableListOf(t))
            }
        }

        val vs = mutableListOf<ValidationViolation>()
        for (nodes in clusters) {
            if (nodes.size < 2) continue
            for (a in nodes) {
                for (b in nodes) {
                    if (a == b) continue
                    vs.add(
                        violation(
                            a,
                            shape.shapeNode,
                            shape.severity,
                            shape.severityCustomIri,
                            constraintStub(ConstraintType.UNIQUE_VALUES_FOR),
                            "sh:uniqueValuesFor duplicate composite key",
                            pathTerms = null,
                            value = b,
                        ),
                    )
                }
            }
        }
        return vs
    }

    private fun evaluateConstraintsForValues(
        focus: RdfTerm,
        violationShape: RdfResource,
        severity: ViolationSeverity,
        severityCustomIri: Iri? = null,
        pathTerms: List<RdfTerm>?,
        pathPredicate: Iri?,
        values: List<RdfTerm>,
        constraints: List<PropertyConstraint>,
        ctx: ValidationContext,
        state: DepthState,
        siblingQualifiedShapes: List<RdfResource>,
    ): List<ValidationViolation> {
        val compiled = ctx.compiled
        val data = ctx.data
        val vs = mutableListOf<ValidationViolation>()

        for (c in constraints) {
            when (c) {
                is PropertyConstraint.MinCount -> {
                    // Cardinality counts distinct RDF terms under native SHACL term equality (see distinctShaclTerms), not raw multiset size.
                    val card = distinctShaclTerms(values).size
                    if (card < c.n) {
                        vs.add(
                            violation(
                                focus,
                                violationShape,
                                severity,
                                severityCustomIri,
                                constraintStub(ConstraintType.MIN_COUNT, pathPredicate, mapOf("min" to c.n, "actual" to card)),
                                "Minimum cardinality $c.n required, found $card",
                                pathTerms,
                                value = values.firstOrNull(),
                            ),
                        )
                    }
                }
                is PropertyConstraint.MaxCount -> {
                    // Same distinct-value cardinality as minCount.
                    val card = distinctShaclTerms(values).size
                    if (card > c.n) {
                        val witness =
                            distinctShaclTerms(values).getOrNull(c.n)
                                ?: values.getOrNull(c.n)
                        vs.add(
                            violation(
                                focus,
                                violationShape,
                                severity,
                                severityCustomIri,
                                constraintStub(ConstraintType.MAX_COUNT, pathPredicate, mapOf("max" to c.n, "actual" to card)),
                                "Maximum cardinality $c.n allowed, found $card",
                                pathTerms,
                                value = witness,
                            ),
                        )
                    }
                }
                is PropertyConstraint.Datatype ->
                    values.forEach { v ->
                        val useSeverity = c.severityOverride ?: severity
                        if (!literalMatchesShaclDatatypes(v, c.allowed)) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    useSeverity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.DATATYPE, pathPredicate),
                                    "Value $v does not match allowed datatype(s) ${c.allowed.joinToString { it.value }}",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                is PropertyConstraint.Class ->
                    values.forEach { v ->
                        val ok =
                            v is RdfResource &&
                                data.typesOf(v).any { t -> c.iri in data.superclassCone(t) }
                        if (!ok) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.CLASS, pathPredicate),
                                    "Expected rdf:type (subclass of) ${c.iri.value} for value $v",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                is PropertyConstraint.ClassAnyOf ->
                    values.forEach { v ->
                        val ok =
                            v is RdfResource &&
                                c.options.any { req ->
                                    data.typesOf(v).any { t -> req in data.superclassCone(t) }
                                }
                        if (!ok) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.CLASS, pathPredicate),
                                    "Expected rdf:type matching one of ${c.options.joinToString { it.value }} for value $v",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                is PropertyConstraint.NodeKind ->
                    // One violation per distinct value after SHACL-term dedup (multiset duplicates ignored).
                    distinctShaclTerms(values).forEach { v ->
                        if (!c.kinds.any { matchesNodeKind(v, it) }) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.NODE_KIND, pathPredicate),
                                    "Node kind ${c.kinds.joinToString { it.value }} required for $v",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                is PropertyConstraint.Pattern -> {
                    val rx = compileRegex(c.pattern, c.flags)
                    values.forEach { v ->
                        val lex = literalLexicalString(v)
                        if (lex == null || !rx.containsMatchIn(lex)) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.PATTERN, pathPredicate),
                                    "Pattern violation for value $v",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                }
                is PropertyConstraint.MinLength ->
                    values.forEach { v ->
                        val lex = literalLexicalString(v)
                        if (lex == null || lex.length < c.n) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.MIN_LENGTH, pathPredicate),
                                    "minLength ${c.n} violated for $v",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                is PropertyConstraint.MaxLength ->
                    values.forEach { v ->
                        val lex = literalLexicalString(v)
                        if (lex == null || lex.length > c.n) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.MAX_LENGTH, pathPredicate),
                                    "maxLength ${c.n} violated for $v",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                is PropertyConstraint.In ->
                    values.forEach { v ->
                        if (!c.allowed.any { shaclRdfTermEquals(it, v) }) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.IN, pathPredicate),
                                    "Value $v not in sh:in",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                is PropertyConstraint.HasValue -> {
                    val ok = values.any { shaclRdfTermEquals(it, c.value) }
                    if (!ok) {
                        vs.add(
                            violation(
                                focus,
                                violationShape,
                                severity,
                                severityCustomIri,
                                constraintStub(ConstraintType.HAS_VALUE, pathPredicate),
                                "sh:hasValue missing ${c.value}",
                                pathTerms,
                                value = values.firstOrNull(),
                            ),
                        )
                    }
                }
                is PropertyConstraint.LanguageIn ->
                    values.forEach { v ->
                        val lang = (v as? LangString)?.lang?.takeIf { it.isNotEmpty() }
                        val ok =
                            lang != null &&
                                c.langs.any { allowed ->
                                    languageTagMatchesLanguageRange(lang, allowed)
                                }
                        if (!ok) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.LANGUAGE_IN, pathPredicate),
                                    "languageIn violated for $v",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                is PropertyConstraint.UniqueLang -> {
                    if (c.enabled) {
                        val langKeys =
                            values.mapNotNull { v ->
                                when (v) {
                                    is LangString ->
                                        "${v.lang.lowercase()}\u0000${v.direction?.token ?: ""}"
                                    else -> null
                                }
                            }
                        val duplicatedLanguages =
                            langKeys.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
                        for (_dup in duplicatedLanguages) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.UNIQUE_LANG, pathPredicate),
                                    "sh:uniqueLang violated",
                                    pathTerms,
                                ),
                            )
                        }
                    }
                }
                is PropertyConstraint.EqualsPath -> {
                    val otherVals = PathEvaluator.evaluate(focus, c.otherPath, data)
                    if (!multisetTermsEqual(values, otherVals)) {
                        for (witness in multisetUnmatchedTerms(values, otherVals)) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.EQUALS, pathPredicate),
                                    "sh:equals multiset mismatch between path and referenced path",
                                    pathTerms,
                                    value = witness,
                                ),
                            )
                        }
                    }
                }
                is PropertyConstraint.DisjointPath -> {
                    val otherVals = PathEvaluator.evaluate(focus, c.otherPath, data)
                    val offending = values.firstOrNull { v -> otherVals.any { o -> shaclRdfTermEquals(v, o) } }
                    if (offending != null) {
                        vs.add(
                            violation(
                                focus,
                                violationShape,
                                severity,
                                severityCustomIri,
                                constraintStub(ConstraintType.DISJOINT, pathPredicate),
                                "sh:disjoint violated: paths share a value",
                                pathTerms,
                                value = offending,
                            ),
                        )
                    }
                }
                is PropertyConstraint.LessThanPath -> {
                    val otherVals = PathEvaluator.evaluate(focus, c.otherPath, data)
                    values.forEach { v ->
                        otherVals.forEach { w ->
                            if (!literalLess(v, w)) {
                                vs.add(
                                    violation(
                                        focus,
                                        violationShape,
                                        severity,
                                        severityCustomIri,
                                        constraintStub(ConstraintType.LESS_THAN, pathPredicate),
                                        "sh:lessThan violated comparing $v and $w",
                                        pathTerms,
                                        value = v,
                                    ),
                                )
                            }
                        }
                    }
                }
                is PropertyConstraint.LessThanOrEqualsPath -> {
                    val otherVals = PathEvaluator.evaluate(focus, c.otherPath, data)
                    values.forEach { v ->
                        otherVals.forEach { w ->
                            if (!literalLessOrEqual(v, w)) {
                                vs.add(
                                    violation(
                                        focus,
                                        violationShape,
                                        severity,
                                        severityCustomIri,
                                        constraintStub(ConstraintType.LESS_THAN_OR_EQUALS, pathPredicate),
                                        "sh:lessThanOrEquals violated comparing $v and $w",
                                        pathTerms,
                                        value = v,
                                    ),
                                )
                            }
                        }
                    }
                }
                is PropertyConstraint.MinInclusive ->
                    values.forEach { v ->
                        if (!satisfiesMinInclusive(v, c.bound)) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.MIN_INCLUSIVE, pathPredicate),
                                    "minInclusive violated for $v vs bound ${c.bound}",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                is PropertyConstraint.MaxInclusive ->
                    values.forEach { v ->
                        if (!satisfiesMaxInclusive(v, c.bound)) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.MAX_INCLUSIVE, pathPredicate),
                                    "maxInclusive violated for $v vs bound ${c.bound}",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                is PropertyConstraint.MinExclusive ->
                    values.forEach { v ->
                        if (!satisfiesMinExclusive(v, c.bound)) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.MIN_EXCLUSIVE, pathPredicate),
                                    "minExclusive violated for $v vs bound ${c.bound}",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                is PropertyConstraint.MaxExclusive ->
                    values.forEach { v ->
                        if (!satisfiesMaxExclusive(v, c.bound)) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.MAX_EXCLUSIVE, pathPredicate),
                                    "maxExclusive violated for $v vs bound ${c.bound}",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                is PropertyConstraint.Qualified -> {
                    val nested =
                        compiled.shapesByNode[c.shape]
                            ?: ShapesCompiler.tryCompileInlineNodeShape(c.shape, ctx.shapesIndex, config)
                    if (nested == null) {
                        vs.add(
                            violation(
                                focus,
                                violationShape,
                                severity,
                                severityCustomIri,
                                constraintStub(ConstraintType.QUALIFIED_VALUE_SHAPE, pathPredicate),
                                "Undefined qualifiedValueShape ${c.shape.displayId()}",
                                pathTerms,
                            ),
                        )
                    } else {
                        val conforming =
                            values.filterIsInstance<RdfResource>().filter { v ->
                                val st = state.push(nested.shapeNode) ?: return@filter false
                                validateNodeShape(v, nested, ctx, st).isEmpty()
                            }
                        val count = conforming.size
                        val minOk = c.min == null || count >= c.min!!
                        val maxOk = c.max == null || count <= c.max!!
                        if (!minOk) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.QUALIFIED_MIN_COUNT, pathPredicate, mapOf("min" to (c.min ?: 0), "actual" to count)),
                                    "qualifiedMinCount violated (required ${c.min}, found $count)",
                                    pathTerms,
                                ),
                            )
                        }
                        if (!maxOk) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.QUALIFIED_MAX_COUNT, pathPredicate, mapOf("max" to (c.max ?: 0), "actual" to count)),
                                    "qualifiedMaxCount violated (max ${c.max}, found $count)",
                                    pathTerms,
                                ),
                            )
                        }
                        if (c.disjoint && c.min != null) {
                            val others = siblingQualifiedShapes.filter { it != c.shape }
                            for (v in conforming) {
                                siblingShapes@
                                for (oref in others) {
                                    val otherShape =
                                        compiled.shapesByNode[oref]
                                            ?: ShapesCompiler.tryCompileInlineNodeShape(oref, ctx.shapesIndex, config)
                                            ?: continue
                                    val st2 = state.push(otherShape.shapeNode) ?: continue
                                    if (validateNodeShape(v, otherShape, ctx, st2).isEmpty()) {
                                        vs.add(
                                            violation(
                                                focus,
                                                violationShape,
                                                severity,
                                                severityCustomIri,
                                                constraintStub(ConstraintType.QUALIFIED_MIN_COUNT, pathPredicate),
                                                "qualifiedValueShapesDisjoint violated: value conforms to another sibling qualified shape",
                                                pathTerms,
                                                value = v,
                                            ),
                                        )
                                        break@siblingShapes
                                    }
                                }
                            }
                        }
                    }
                }
                is PropertyConstraint.MinListLength -> {
                    values.forEach { v ->
                        val members = data.expandDataList(v)
                        if (members == null || members.size < c.n) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.MIN_LIST_LENGTH, pathPredicate),
                                    "sh:minListLength requires at least ${c.n} list members",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                }
                is PropertyConstraint.MaxListLength -> {
                    values.forEach { v ->
                        val members = data.expandDataList(v)
                        if (members == null || members.size > c.n) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.MAX_LIST_LENGTH, pathPredicate),
                                    "sh:maxListLength allows at most ${c.n} list members",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                }
                is PropertyConstraint.MemberShape -> {
                    val nested = compiled.shapesByNode[c.nestedShape]
                    values.forEach { v ->
                        val members = data.expandDataList(v)
                        if (members == null) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.MEMBER_SHAPE, pathPredicate),
                                    "Value is not a valid SHACL RDF list",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                            return@forEach
                        }
                        if (nested == null) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.MEMBER_SHAPE, pathPredicate),
                                    "Undefined memberShape ${c.nestedShape.displayId()}",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                            return@forEach
                        }
                        var okAll = true
                        for (m in members) {
                            if (m !is RdfResource) {
                                okAll = false
                                break
                            }
                            val st = state.push(nested.shapeNode)
                            if (st == null || validateNodeShape(m, nested, ctx, st).isNotEmpty()) {
                                okAll = false
                                break
                            }
                        }
                        if (!okAll) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.MEMBER_SHAPE, pathPredicate),
                                    "sh:memberShape violated for list value",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                }
                is PropertyConstraint.UniqueMembers -> {
                    if (!c.enabled) Unit
                    else {
                        values.forEach { v ->
                            val members = data.expandDataList(v)
                            if (members == null) {
                                vs.add(
                                    violation(
                                        focus,
                                        violationShape,
                                        severity,
                                        severityCustomIri,
                                        constraintStub(ConstraintType.UNIQUE_MEMBERS, pathPredicate),
                                        "Value is not a valid SHACL RDF list",
                                        pathTerms,
                                        value = v,
                                    ),
                                )
                            } else {
                                val seen = mutableListOf<RdfTerm>()
                                var dup = false
                                for (m in members) {
                                    if (seen.any { shaclRdfTermEquals(it, m) }) {
                                        dup = true
                                        break
                                    }
                                    seen.add(m)
                                }
                                if (dup) {
                                    vs.add(
                                        violation(
                                            focus,
                                            violationShape,
                                            severity,
                                            severityCustomIri,
                                            constraintStub(ConstraintType.UNIQUE_MEMBERS, pathPredicate),
                                            "sh:uniqueMembers violated",
                                            pathTerms,
                                            value = v,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                is PropertyConstraint.SubsetOfPath -> {
                    val otherVals = PathEvaluator.evaluate(focus, c.otherPath, data)
                    values.forEach { v ->
                        if (!otherVals.any { shaclRdfTermEquals(it, v) }) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.SUBSET_OF, pathPredicate),
                                    "sh:subsetOf violated: value not reachable via referenced path",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                }
                is PropertyConstraint.SingleLine -> {
                    if (c.enabled) {
                        val rx = singleLineBreakRegex
                        values.forEach { v ->
                            val lex = literalLexicalString(v)
                            if (lex != null && rx.containsMatchIn(lex)) {
                                vs.add(
                                    violation(
                                        focus,
                                        violationShape,
                                        severity,
                                        severityCustomIri,
                                        constraintStub(ConstraintType.SINGLE_LINE, pathPredicate),
                                        "sh:singleLine violated",
                                        pathTerms,
                                        value = v,
                                    ),
                                )
                            }
                        }
                    }
                }
                is PropertyConstraint.SomeValue -> {
                    val nested =
                        compiled.shapesByNode[c.nestedShape]
                            ?: ShapesCompiler.tryCompileInlineNodeShape(c.nestedShape, ctx.shapesIndex, config)
                    if (nested == null) {
                        vs.add(
                            violation(
                                focus,
                                violationShape,
                                severity,
                                severityCustomIri,
                                constraintStub(ConstraintType.SOME_VALUE, pathPredicate),
                                "Undefined sh:someValue shape ${c.nestedShape.displayId()}",
                                pathTerms,
                            ),
                        )
                    } else {
                        val anyOk =
                            values.filterIsInstance<RdfResource>().any { v ->
                                val st = state.push(nested.shapeNode) ?: return@any false
                                validateNodeShape(v, nested, ctx, st).isEmpty()
                            }
                        if (!anyOk) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.SOME_VALUE, pathPredicate),
                                    "sh:someValue requires at least one conforming value",
                                    pathTerms,
                                    value = values.firstOrNull(),
                                ),
                            )
                        }
                    }
                }
                is PropertyConstraint.RootClass -> {
                    values.forEach { v ->
                        val cls = v as? Iri
                        if (cls == null) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.ROOT_CLASS, pathPredicate),
                                    "sh:rootClass expects an IRI class value",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        } else {
                            val cone = data.superclassCone(cls)
                            val ok = c.roots.any { it in cone }
                            if (!ok) {
                                vs.add(
                                    violation(
                                        focus,
                                        violationShape,
                                        severity,
                                        severityCustomIri,
                                        constraintStub(ConstraintType.ROOT_CLASS, pathPredicate),
                                        "sh:rootClass violated for $cls",
                                        pathTerms,
                                        value = v,
                                    ),
                                )
                            }
                        }
                    }
                }
                is PropertyConstraint.Shape ->
                    values.forEach { v ->
                        if (v !is RdfResource) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.SHAPE, pathPredicate),
                                    "sh:shape requires resource value, got $v",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                            return@forEach
                        }
                        val nestedNs =
                            compiled.shapesByNode[c.nestedShape]
                                ?: ShapesCompiler.tryCompileInlineNodeShape(c.nestedShape, ctx.shapesIndex, config)
                        if (nestedNs != null) {
                            val st = state.push(nestedNs.shapeNode)
                            if (st == null) {
                                vs.add(
                                    violation(
                                        focus,
                                        violationShape,
                                        severity,
                                        severityCustomIri,
                                        constraintStub(ConstraintType.SHAPE, pathPredicate),
                                        "Shape dependency cycle detected at nested shape ${nestedNs.shapeNode.displayId()}",
                                        pathTerms,
                                        value = v,
                                    ),
                                )
                                return@forEach
                            }
                            val nestedVs = validateNodeShape(v, nestedNs, ctx, st)
                            if (nestedVs.isNotEmpty()) {
                                vs.add(
                                    violation(
                                        focus,
                                        violationShape,
                                        severity,
                                        severityCustomIri,
                                        constraintStub(ConstraintType.SHAPE, pathPredicate),
                                        "sh:shape constraint failed",
                                        pathTerms,
                                        value = v,
                                    ),
                                )
                            }
                            return@forEach
                        }
                        val nestedPs = ShapesCompiler.tryCompilePropertyShape(c.nestedShape, ctx.shapesIndex, config)
                        if (nestedPs == null) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.SHAPE, pathPredicate),
                                    "Undefined nested shape ${c.nestedShape.displayId()}",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                            return@forEach
                        }
                        val sibs =
                            nestedPs.constraints.filterIsInstance<PropertyConstraint.Qualified>().map { it.shape }.distinct()
                        val nestedVs = validatePropertyShape(v, nestedPs, ctx, state, sibs)
                        if (nestedVs.isNotEmpty()) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.SHAPE, pathPredicate),
                                    "sh:shape constraint failed",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                is PropertyConstraint.Sparql -> {
                    val raw = ctx.shapesIndex.objects(c.constraintNode, SHACL.select).singleOrNull()
                    val queryText = (raw as? Literal)?.lexical
                    if (queryText.isNullOrBlank()) {
                        vs.add(
                            violation(
                                focus,
                                violationShape,
                                severity,
                                severityCustomIri,
                                constraintStub(ConstraintType.SPARQL_CONSTRAINT, pathPredicate),
                                "sh:sparql missing a single sh:select literal",
                                pathTerms,
                            ),
                        )
                    } else if (SparqlConstraintEvaluator.selectReturnsRows(queryText, ctx.mergedDataAndShapes, focus)) {
                        vs.add(
                            violation(
                                focus,
                                violationShape,
                                severity,
                                severityCustomIri,
                                constraintStub(ConstraintType.SPARQL_CONSTRAINT, pathPredicate),
                                "SPARQL constraint SELECT returned bindings",
                                pathTerms,
                            ),
                        )
                    }
                }
                is PropertyConstraint.Node ->
                    values.forEach { v ->
                        if (v !is RdfResource) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.NODE, pathPredicate),
                                    "sh:node requires resource value, got $v",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                            return@forEach
                        }
                        val nested =
                            compiled.shapesByNode[c.nestedShape]
                                ?: ShapesCompiler.tryCompileInlineNodeShape(c.nestedShape, ctx.shapesIndex, config)
                        if (nested == null) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.NODE, pathPredicate),
                                    "Undefined nested NodeShape ${c.nestedShape.displayId()}",
                                    pathTerms,
                                ),
                            )
                            return@forEach
                        }
                        val st = state.push(nested.shapeNode)
                        if (st == null) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.NODE, pathPredicate),
                                    "Shape dependency cycle detected at nested shape ${nested.shapeNode.displayId()}",
                                    pathTerms,
                                ),
                            )
                            return@forEach
                        }
                        val nestedVs = validateNodeShape(v, nested, ctx, st)
                        if (nestedVs.isNotEmpty()) {
                            vs.add(
                                violation(
                                    focus,
                                    violationShape,
                                    severity,
                                    severityCustomIri,
                                    constraintStub(ConstraintType.NODE, pathPredicate),
                                    "sh:node constraint failed",
                                    pathTerms,
                                    value = v,
                                ),
                            )
                        }
                    }
                is PropertyConstraint.ReifierShape,
                is PropertyConstraint.ReificationRequired,
                -> Unit
            }
        }
        return vs
    }

    private fun validatePropertyShape(
        focus: RdfTerm,
        ps: CompiledPropertyShape,
        ctx: ValidationContext,
        state: DepthState,
        siblingQualifiedShapes: List<RdfResource>,
    ): List<ValidationViolation> {
        val values = PathEvaluator.evaluate(focus, ps.path, ctx.data)
        val vs =
            evaluateConstraintsForValues(
                focus = focus,
                violationShape = ps.shapeNode,
                severity = ps.severity,
                severityCustomIri = ps.severityCustomIri,
                pathTerms = pathToTerms(ps.path),
                pathPredicate = simplePathPredicate(ps.path),
                values = values,
                constraints = ps.constraints,
                ctx = ctx,
                state = state,
                siblingQualifiedShapes = siblingQualifiedShapes,
            ).toMutableList()
        for (part in ps.logicalParts) {
            for (v in values) {
                vs.addAll(evalLogical(focus, v, ps.shapeNode, ps.severity, ps.severityCustomIri, part, ctx, state, pathToTerms(ps.path)))
            }
        }
        for (nested in ps.nestedPropertyShapes) {
            for (v in values) {
                val vr = v as? RdfResource ?: continue
                vs.addAll(validatePropertyShape(vr, nested, ctx, state, siblingQualifiedShapes))
            }
        }
        if (focus is RdfResource &&
            ps.constraints.any { it is PropertyConstraint.ReifierShape || it is PropertyConstraint.ReificationRequired }
        ) {
            vs.addAll(
                validateReifierPropertyConstraints(
                    focus = focus,
                    violationShape = ps.shapeNode,
                    severity = ps.severity,
                    severityCustomIri = ps.severityCustomIri,
                    pathTerms = pathToTerms(ps.path),
                    pathPredicate = simplePathPredicate(ps.path),
                    claims = tripleClaimsMatchingSimplePath(focus, ps.path, ctx.data),
                    constraints = ps.constraints,
                    ctx = ctx,
                    state = state,
                ),
            )
        }
        return vs
    }

    private fun tripleClaimsMatchingSimplePath(focus: RdfResource, path: ShaclPath, data: DataGraphIndex): List<RdfTriple> =
        when (path) {
            is ShaclPath.Predicate ->
                data.objects(focus, path.iri).map { obj -> RdfTriple(focus, path.iri, obj) }
            else -> emptyList()
        }

    private fun validateReifierPropertyConstraints(
        focus: RdfResource,
        violationShape: RdfResource,
        severity: ViolationSeverity,
        severityCustomIri: Iri?,
        pathTerms: List<RdfTerm>?,
        pathPredicate: Iri?,
        claims: List<RdfTriple>,
        constraints: List<PropertyConstraint>,
        ctx: ValidationContext,
        state: DepthState,
    ): List<ValidationViolation> {
        val vs = mutableListOf<ValidationViolation>()
        val shapeRef = constraints.filterIsInstance<PropertyConstraint.ReifierShape>().singleOrNull()
        val reifReq = constraints.filterIsInstance<PropertyConstraint.ReificationRequired>().singleOrNull()?.required == true
        val compiledNested =
            shapeRef?.let { sr ->
                ctx.compiled.shapesByNode[sr.nestedShape]
                    ?: ShapesCompiler.tryCompileInlineNodeShape(sr.nestedShape, ctx.shapesIndex, config)
            }
        if (shapeRef != null && compiledNested == null) {
            return listOf(
                violation(
                    focus,
                    violationShape,
                    severity,
                    severityCustomIri,
                    constraintStub(ConstraintType.REIFIER_SHAPE, pathPredicate),
                    "Undefined reifier shape ${shapeRef.nestedShape.displayId()}",
                    pathTerms,
                ),
            )
        }
        for (claim in claims) {
            val reifiers = ctx.data.reifiersForClaim(claim)
            val nested = compiledNested
            if (reifiers.isEmpty()) {
                when {
                    nested != null ->
                        vs.add(
                            violation(
                                focus,
                                violationShape,
                                severity,
                                severityCustomIri,
                                constraintStub(ConstraintType.REIFIER_SHAPE, pathPredicate),
                                "sh:reifierShape: no reifier for triple $claim",
                                pathTerms,
                                value = claim.obj,
                            ),
                        )
                    reifReq ->
                        vs.add(
                            violation(
                                focus,
                                violationShape,
                                severity,
                                severityCustomIri,
                                constraintStub(ConstraintType.REIFICATION_REQUIRED, pathPredicate),
                                "sh:reificationRequired: missing reifier for triple $claim",
                                pathTerms,
                                value = claim.obj,
                            ),
                        )
                }
                continue
            }
            if (nested != null) {
                for (r in reifiers) {
                    val st = state.push(nested.shapeNode)
                    if (st == null) {
                        vs.add(
                            violation(
                                focus,
                                violationShape,
                                severity,
                                severityCustomIri,
                                constraintStub(ConstraintType.REIFIER_SHAPE, pathPredicate),
                                "Shape dependency cycle detected at reifier shape ${nested.shapeNode.displayId()}",
                                pathTerms,
                                value = r,
                            ),
                        )
                        continue
                    }
                    if (validateNodeShape(r, nested, ctx, st).isNotEmpty()) {
                        vs.add(
                            violation(
                                focus,
                                violationShape,
                                severity,
                                severityCustomIri,
                                constraintStub(ConstraintType.REIFIER_SHAPE, pathPredicate),
                                "sh:reifierShape constraint failed",
                                pathTerms,
                                value = r,
                            ),
                        )
                    }
                }
            }
        }
        return vs
    }

    /** BCP47-style prefix match (`en` ⊇ `en-NZ`). Supports trailing `-*` wildcard ranges. */
    private fun languageTagMatchesLanguageRange(valueLang: String, range: String): Boolean {
        if (range == "*") return true
        val v = valueLang.lowercase()
        val r = range.lowercase()
        if (v == r) return true
        if (r.endsWith("-*")) {
            val prefix = r.dropLast(2)
            return prefix.isEmpty() || v == prefix || v.startsWith("$prefix-")
        }
        return v.startsWith("$r-")
    }

    private fun violation(
        focus: RdfTerm,
        shape: RdfResource,
        severity: ViolationSeverity,
        severityCustomIri: Iri? = null,
        constraint: ShaclConstraint,
        message: String,
        pathTerms: List<RdfTerm>?,
        value: RdfTerm? = null,
    ): ValidationViolation =
        ValidationViolation(
            severity = severity,
            constraint = constraint,
            focusNode = focus,
            message = message,
            path = pathTerms,
            shapeUri = shape.displayId(),
            value = value,
            resultSeverityIri = severityCustomIri?.value,
        )

    private fun buildStatistics(
        graph: RdfGraph,
        shapes: RdfGraph,
        violations: List<ValidationViolation>,
        warnings: List<ValidationWarning>,
        compiled: CompiledShapeGraph,
        validatedConstraintSlots: Int,
    ): ValidationStatistics {
        val triples = graph.getTriples()
        val constraintsByType = violations.groupingBy { it.constraint.constraintType }.eachCount()
        val violationsByType = constraintsByType
        val warningsByType = warnings.mapNotNull { it.constraint?.constraintType }.groupingBy { it }.eachCount()
        val propConstraints =
            compiled.orderedNodeShapes.sumOf { ns ->
                ns.nodeConstraints.size +
                    ns.logicalParts.size +
                    ns.propertyShapes.sumOf { ps -> ps.constraints.size + ps.logicalParts.size }
            }
        return ValidationStatistics(
            totalResources = triples.map { it.subject }.distinct().size,
            validatedResources = violations.map { it.focusNode }.distinct().size,
            totalConstraints = propConstraints,
            validatedConstraints = validatedConstraintSlots.coerceAtLeast(violations.size),
            shapesProcessed = compiled.orderedNodeShapes.size,
            constraintsByType = constraintsByType,
            violationsByType = violationsByType,
            warningsByType = warningsByType,
            averageValidationTimePerResource = Duration.ofMillis(1),
        )
    }

    private fun RdfResource.displayId(): String = when (this) {
        is Iri -> value
        is BlankNode -> "_:$id"
        else -> toString()
    }

    private fun simplePathPredicate(path: ShaclPath): Iri? = when (path) {
        is ShaclPath.Predicate -> path.iri
        else -> null
    }

    private fun pathToTerms(path: ShaclPath): List<RdfTerm>? =
        when (path) {
            is ShaclPath.Predicate -> listOf(path.iri)
            is ShaclPath.Sequence -> {
                val iris =
                    path.segments.map { seg ->
                        (seg as? ShaclPath.Predicate)?.iri ?: return null
                    }
                iris
            }
            else -> null
        }

    private fun literalMatchesShaclDatatypes(term: RdfTerm, allowed: List<Iri>): Boolean {
        val set = allowed.toSet()
        return when (term) {
            is LangString -> term.datatype in set
            is TrueLiteral,
            is FalseLiteral,
            -> XSD.boolean in set
            is TypedLiteral ->
                term.datatype in set && typedLiteralLexicallyValidForShaclDatatype(term)
            else -> false
        }
    }

    private fun matchesNodeKind(term: RdfTerm, kind: Iri): Boolean =
        when (kind) {
            SHACL.IRI -> term is Iri
            SHACL.BlankNode -> term is BlankNode
            SHACL.Literal -> term is Literal
            SHACL.BlankNodeOrIRI -> term is Iri || term is BlankNode
            SHACL.BlankNodeOrLiteral -> term is BlankNode || term is Literal
            SHACL.IRIOrLiteral -> term is Iri || term is Literal
            SHACL.TripleTerm -> term is TripleTerm
            else -> false
        }

    private fun compileRegex(pattern: String, flags: String?): Regex {
        val opts = mutableSetOf<RegexOption>()
        flags?.forEach { c ->
            when (c.lowercaseChar()) {
                'i' -> opts.add(RegexOption.IGNORE_CASE)
                'm' -> opts.add(RegexOption.MULTILINE)
                's' -> opts.add(RegexOption.DOT_MATCHES_ALL)
                else -> {}
            }
        }
        return if (opts.isEmpty()) Regex(pattern) else Regex(pattern, opts)
    }
}
