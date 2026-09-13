package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Dataset
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
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
import com.geoknoesis.kastor.rdf.shacl.native.ValidationBudget
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
import com.geoknoesis.kastor.rdf.shacl.native.literalLexicallyValid
import com.geoknoesis.kastor.rdf.shacl.native.ShaclPath
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler
import com.geoknoesis.kastor.rdf.shacl.native.ShapesGraphTriplesCollector
import com.geoknoesis.kastor.rdf.shacl.native.ShapesStructuralDigest
import com.geoknoesis.kastor.rdf.shacl.native.SparqlConstraintEvaluator
import com.geoknoesis.kastor.rdf.shacl.native.isLexicallyTrue
import com.geoknoesis.kastor.rdf.shacl.native.constraintStub
import com.geoknoesis.kastor.rdf.shacl.native.literalLexicalString
import com.geoknoesis.kastor.rdf.shacl.native.satisfiesMaxExclusive
import com.geoknoesis.kastor.rdf.shacl.native.satisfiesMaxInclusive
import com.geoknoesis.kastor.rdf.shacl.native.satisfiesMinExclusive
import com.geoknoesis.kastor.rdf.shacl.native.satisfiesMinInclusive
import com.geoknoesis.kastor.rdf.shacl.native.shaclRdfTermEquals
import com.geoknoesis.kastor.rdf.shacl.native.shaclRdfTermFingerprint
import com.geoknoesis.kastor.rdf.shacl.native.distinctShaclTerms

/**
 * Kastor native SHACL Core validator (compile → plan → execute → report).
 *
 * Semantics notes:
 * - A report conforms (`isValid`) only when it has no result of severity sh:Violation, sh:Warning, sh:Info or a
 *   custom severity; SHACL 1.2 sh:Debug / sh:Trace results do not affect conformance.
 * - Value nodes are sets; nested shape checks (`sh:node`, logical constraints, qualified shapes, `sh:shape`,
 *   `sh:someValue`, `sh:memberShape`, `sh:reifierShape`, `sh:targetWhere`) are conformance checks memoized per
 *   (value node, shape) within a run and short-circuit on the first result.
 * - Recursion is detected on (focus node, shape) pairs. SHACL leaves recursive shapes undefined; a pair that is
 *   re-entered while being checked is assumed to conform, so finite acyclic data chains validate normally.
 */
internal class NativeShaclValidator(private val config: ValidationConfig) : ShaclValidator, com.geoknoesis.kastor.rdf.shacl.ShapeCacheControl {

    private companion object {
        val singleLineBreakRegex = Regex("[\\f\\r\\n\\u000B]")
        val messagePlaceholder = Regex("\\{[?$]([A-Za-z_][A-Za-z0-9_]*)\\}")
    }

    private val compileCache = NativeCompileCache()
    override val cacheStatistics get() = compileCache.statistics()
    override fun clearCache() = compileCache.clear()
    init {
        require(!config.parallelValidation) { "Native parallel validation is unsupported" }
        require(!config.streamingMode) { "Native streaming validation is unsupported" }
        require(config.maxViolations > 0 && !config.timeout.isNegative && !config.timeout.isZero)
    }

    /** `sh:resultPath` information for results of one property shape. */
    private class ReportPath(val terms: List<RdfTerm>?, val node: RdfTerm?, val triples: List<RdfTriple>, val predicate: Iri?)

    /** Source shape, severity, messages and path shared by the results of one shape. */
    private class ResultTemplate(
        val shape: RdfResource,
        val severity: ViolationSeverity,
        val severityCustomIri: Iri?,
        val messages: List<Literal>,
        val path: ReportPath?,
    )

    private data class DepthState(val depth: Int, val conformsOnly: Boolean) {
        fun nested() = DepthState(depth + 1, true)
    }

    /** Evaluates a CharSequence for regex matching while consulting the deadline every 1024 character reads. */
    private class DeadlineCharSequence(private val value: String, private val budget: ValidationBudget) : CharSequence {
        private var reads = 0
        override val length: Int get() = value.length
        override fun get(index: Int): Char {
            if ((++reads and 1023) == 0) budget.check("pattern matching")
            return value[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            DeadlineCharSequence(value.substring(startIndex, endIndex), budget)
        override fun toString(): String = value
    }

    private class ValidationContext(
        val compiled: CompiledShapeGraph,
        val data: DataGraphIndex,
        val budget: ValidationBudget,
    ) : AutoCloseable {
        val conformsMemo = HashMap<Pair<String, RdfResource>, Boolean>()
        val inProgress = HashSet<Pair<String, RdfResource>>()
        var recursionAssumptions = 0L
        val reportPaths = HashMap<RdfResource, ReportPath>()
        private val session = lazy {
            SparqlConstraintEvaluator.Session(data.graph, if (compiled.sparqlUsesShapesGraph) compiled.index.triples else emptyList())
        }
        fun checkDeadline() {
            budget.check()
        }
        fun select(query: String, bindings: Map<String, RdfTerm>) =
            session.value.select(query, bindings, Duration.ofNanos(budget.remainingNanos()))
        fun text(value: String): CharSequence = DeadlineCharSequence(value, budget)
        override fun close() { if (session.isInitialized()) session.value.close() }
    }

    override fun validate(graph: RdfGraph, shapes: RdfGraph): ValidationReport =
        runValidation(graph, shapes, config.dataset.validationDataset)

    override fun validateDataset(dataset: Dataset, shapes: RdfGraph?): ValidationReport =
        runValidation(dataset.defaultGraph, shapes ?: Rdf.graph { }, dataset)

    private fun runValidation(graph: RdfGraph, shapes: RdfGraph, datasetForDiscovery: Dataset?, focusOnly: RdfResource? = null): ValidationReport {
        val budget = ValidationBudget(config.timeout)
        budget.check("admission")
        val start = System.currentTimeMillis()
        val combinedEstimate = graph.size().toLong() + shapes.size().toLong()
        if (combinedEstimate > config.maxCombinedGraphTriples) {
            throw ShaclValidationException(
                "Combined data + shapes triple count ($combinedEstimate) exceeds ValidationConfig.maxCombinedGraphTriples (${config.maxCombinedGraphTriples})",
            )
        }
        val mergedShapesTriples =
            try {
                prepareMergedShapesTriples(graph, shapes, datasetForDiscovery, budget)
            } catch (e: ShapesGraphNotFoundException) {
                throw ShaclValidationException(e.message ?: "Referenced shapes graph not found", e)
            }
        val digest =
            try {
                ShapesStructuralDigest.digest(mergedShapesTriples, config, budget)
            } catch (e: ShapeCompileException) {
                throw ShaclValidationException("SHACL shapes digest failed: ${e.message}", e)
            }
        config.cache.shapesGraphVersion?.let { compileCache.assertTagOrRecord(it, digest, budget) }
        val cacheKey = ShapesStructuralDigest.compileCacheKey(digest, config)
        if (graph.size().toLong() + mergedShapesTriples.size > config.maxCombinedGraphTriples) {
            throw ShaclValidationException("Expanded data and shapes exceed maxCombinedGraphTriples")
        }
        val compiled = try {
            compileCache.getOrCompile(cacheKey, budget) { ShapesCompiler.compile(mergedShapesTriples, config, budget) }
        } catch (e: ShapeCompileException) { throw ShaclValidationException("SHACL compile failed: ${e.message}", e) }
        ValidationContext(compiled, DataGraphIndex(graph, budget), budget).use { ctx ->
            ctx.checkDeadline()

            val violations = mutableListOf<ValidationViolation>()
            val warnings = mutableListOf<ValidationWarning>()
            var totalResults = 0L
            // SHACL 1.2: sh:Debug and sh:Trace results do not affect conformance (default sh:conformanceDisallows).
            var blockingResults = 0L
            fun blocking(severity: ViolationSeverity) = severity != ViolationSeverity.DEBUG && severity != ViolationSeverity.TRACE
            var validatedConstraintSlots = 0L
            fun record(results: List<ValidationViolation>) {
                totalResults += results.size
                blockingResults += results.count { blocking(it.severity) }
                for (result in results) {
                    if (violations.size >= config.maxViolations) break
                    violations.add(result)
                }
            }

            for (shape in compiled.orderedNodeShapes) {
                ctx.checkDeadline()
                val allFocusNodes = computeFocusNodes(shape, ctx)
                if (shape.uniqueValuesForProps.isNotEmpty()) {
                    val (rows, count) = validateUniqueValuesForShape(shape, allFocusNodes, focusOnly, ctx, config.maxViolations - violations.size)
                    violations.addAll(rows)
                    totalResults += count
                    if (blocking(shape.severity)) blockingResults += count
                }
                val focusNodes = if (focusOnly == null) allFocusNodes else allFocusNodes.filter { it == focusOnly }
                validatedConstraintSlots += countConstraintEvaluationSlots(shape, focusNodes.size)
                for (focus in focusNodes) {
                    ctx.checkDeadline()
                    record(validateFocus(focus, shape, ctx))
                }
            }
            val violationsTruncated = totalResults > violations.size
            val slots = validatedConstraintSlots.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

            val elapsed = Duration.ofMillis(System.currentTimeMillis() - start)
            val statistics = buildStatistics(ctx.data.distinctResourceSubjects().size, violations, warnings, compiled, slots)

            return ValidationReport(
                // SHACL: sh:conforms is false as soon as a result has a conformance-disallowed severity
                // (sh:Violation, sh:Warning, sh:Info and custom severities; not the SHACL 1.2 sh:Debug / sh:Trace).
                isValid = blockingResults == 0L,
                violations = violations,
                warnings = warnings,
                statistics = statistics,
                validationTime = elapsed,
                validatedResources = if (focusOnly == null) ctx.data.distinctResourceSubjects().size else 1,
                validatedConstraints = slots.coerceAtLeast(violations.size),
                shapeViolations = violations.groupBy { it.shapeUri ?: "unknown" },
                constraintViolations = violations.groupBy { it.constraint.constraintType.name },
                violationsTruncated = violationsTruncated,
            )
        }
    }

    private fun prepareMergedShapesTriples(
        data: RdfGraph,
        shapesArg: RdfGraph,
        datasetForDiscovery: Dataset?,
        budget: ValidationBudget,
    ): List<RdfTriple> {
        val aux = config.dataset.auxiliaryGraphs
        val auxiliarySize = aux.values.fold(0L) { n, g -> budget.check("auxiliary admission"); Math.addExact(n, g.size().toLong()) }
        if (auxiliarySize > config.maxCombinedGraphTriples) throw ShaclValidationException("Auxiliary shapes exceed maxCombinedGraphTriples")
        val ds = datasetForDiscovery ?: config.dataset.validationDataset
        val primary: List<RdfTriple> =
            when (val name = config.dataset.shapesGraphNamedGraph) {
                null -> budget.snapshot(shapesArg, "primary shapes")
                else -> {
                    val g =
                        ds?.getNamedGraph(name)
                            ?: aux[name]
                            ?: throw ShapesGraphNotFoundException(
                                "dataset.shapesGraphNamedGraph <$name> not found in validationDataset or auxiliaryGraphs",
                            )
                    budget.snapshot(g, "named shapes")
                }
            }
        val resolveImports = config.imports.resolveOwlImports
        val expanded =
            if (resolveImports) {
                budget.snapshot(
                    OwlImportsExpander.expand(graphFromTriples(primary, budget), config.imports, aux, config.maxCombinedGraphTriples, budget),
                    "expanded shapes",
                )
            } else {
                primary
            }
        val extra =
            if (config.dataset.discoverShapesGraphFromData) {
                ShapesGraphTriplesCollector.collectFromData(data, ds, aux, config.maxCombinedGraphTriples, budget)
            } else {
                emptyList()
            }
        // Fast path: a single graph snapshot is already duplicate-free, so no merge copy is needed.
        if (extra.isEmpty()) return expanded
        val unique = linkedSetOf<RdfTriple>()
        for (t in expanded) { budget.tick("shape merge"); unique.add(t) }
        for (t in extra) { budget.tick("shape merge"); unique.add(t) }
        return unique.toList()
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

    override fun validateResource(graph: RdfGraph, shapes: RdfGraph, resource: RdfResource): ValidationReport =
        runValidation(graph, shapes, config.dataset.validationDataset, resource)

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
        for (tw in shape.targetWhereRefs) {
            for (cand in targetWhereCandidateResources(tw, ctx)) {
                if (conformsTo(cand, tw, ctx, DepthState(0, true))) {
                    out.add(cand)
                }
            }
        }
        return out.toList()
    }

    private fun targetWhereCandidateResources(tw: RdfResource, ctx: ValidationContext): List<RdfResource> {
        val cls = ctx.compiled.referencedNodeShapes[tw]?.nodeConstraints?.firstNotNullOfOrNull { c -> (c as? PropertyConstraint.Class)?.iri }
        return if (cls != null) {
            ctx.data.instancesMatchingTargetClass(cls).toList()
        } else {
            ctx.data.distinctResourceSubjects().toList()
        }
    }

    /** Approximates constraint checks: focus count × (property constraints + logical + node refs + optional closed). */
    private fun countConstraintEvaluationSlots(shape: CompiledNodeShape, focusCount: Int): Long {
        if (focusCount == 0) return 0
        val perFocus = shape.nodeConstraints.size.toLong() +
            shape.propertyShapes.sumOf { it.constraints.size.toLong() + it.logicalParts.size } +
            shape.logicalParts.size + shape.nodeRefs.size + shape.nodeByExpressionRefs.size +
            if (config.validateClosedShapes && shape.closed != ClosedMode.NONE) 1 else 0
        var slots = focusCount * perFocus
        if (shape.uniqueValuesForProps.isNotEmpty()) slots += 1
        return slots
    }

    private fun validateFocus(focus: RdfTerm, shape: CompiledNodeShape, ctx: ValidationContext): List<ValidationViolation> {
        val key = shaclRdfTermFingerprint(focus) to shape.shapeNode
        val added = ctx.inProgress.add(key)
        try {
            return validateNodeShape(focus, shape, ctx, DepthState(0, false))
        } finally {
            if (added) ctx.inProgress.remove(key)
        }
    }

    /**
     * Whether [value] conforms to the shape [ref] (no validation results of any severity). Deactivated and
     * constraint-free shapes are conformed to by every node.
     */
    private fun conformsTo(value: RdfTerm, ref: RdfResource, ctx: ValidationContext, state: DepthState): Boolean {
        ctx.checkDeadline()
        val propertyShape = ctx.compiled.referencedPropertyShapes[ref]
        val nodeShape = if (propertyShape == null) ctx.compiled.referencedNodeShapes[ref] else null
        if (propertyShape == null && nodeShape == null) return true
        if (propertyShape?.deactivated == true || nodeShape?.deactivated == true) return true
        val key = shaclRdfTermFingerprint(value) to ref
        ctx.conformsMemo[key]?.let { return it }
        if (!ctx.inProgress.add(key)) {
            ctx.recursionAssumptions++
            return true
        }
        val next = state.nested()
        if (next.depth > config.maxRecursionDepth) {
            ctx.inProgress.remove(key)
            throw ShaclValidationException(
                "SHACL shape nesting exceeded ValidationConfig.maxRecursionDepth=${config.maxRecursionDepth} at shape ${ref.displayId()}",
            )
        }
        val assumptionsBefore = ctx.recursionAssumptions
        val ok =
            try {
                if (propertyShape != null) {
                    validatePropertyShape(value, propertyShape, ctx, next).isEmpty()
                } else {
                    validateNodeShape(value, nodeShape!!, ctx, next).isEmpty()
                }
            } finally {
                ctx.inProgress.remove(key)
            }
        // Results that relied on a recursion assumption are not cached: they depend on the call stack.
        if (ctx.recursionAssumptions == assumptionsBefore) ctx.conformsMemo[key] = ok
        return ok
    }

    private fun validateNodeShape(
        focus: RdfTerm,
        shape: CompiledNodeShape,
        ctx: ValidationContext,
        state: DepthState,
    ): List<ValidationViolation> {
        ctx.checkDeadline()
        if (shape.deactivated) return emptyList()
        val vs = mutableListOf<ValidationViolation>()
        fun done() = state.conformsOnly && vs.isNotEmpty()
        val tpl = ResultTemplate(shape.shapeNode, shape.severity, shape.severityCustomIri, shape.messages, null)

        for (nr in shape.nodeRefs) {
            if (!conformsTo(focus, nr, ctx, state)) {
                vs.add(violation(focus, tpl, constraintStub(ConstraintType.NODE), "sh:node constraint failed for ${nr.displayId()}", value = focus))
                if (done()) return vs
            }
        }

        for (exprRef in shape.nodeByExpressionRefs) {
            if (!conformsTo(focus, exprRef, ctx, state)) {
                vs.add(
                    violation(
                        focus, tpl, constraintStub(ConstraintType.NODE_BY_EXPRESSION),
                        "sh:nodeByExpression constraint failed", value = focus, sourceConstraint = exprRef,
                    ),
                )
                if (done()) return vs
            }
        }

        if (shape.nodeConstraints.isNotEmpty()) {
            vs.addAll(evaluateConstraintsForValues(focus, tpl, listOf(focus), shape.nodeConstraints, ctx, state, shape.shapeNode, null))
            if (done()) return vs
        }

        for (ps in shape.propertyShapes) {
            vs.addAll(validatePropertyShape(focus, ps, ctx, state))
            if (done()) return vs
        }

        for (part in shape.logicalParts) {
            vs.addAll(evalLogical(focus, focus, tpl, part, ctx, state))
            if (done()) return vs
        }

        if (config.validateClosedShapes && shape.closed != ClosedMode.NONE && focus is RdfResource) {
            vs.addAll(validateClosed(focus, shape, tpl, ctx))
        }

        return vs
    }

    private fun evalLogical(
        reportFocus: RdfTerm,
        logicalTarget: RdfTerm,
        tpl: ResultTemplate,
        part: NodeLogicalPart,
        ctx: ValidationContext,
        state: DepthState,
    ): List<ValidationViolation> {
        fun conforms(ref: RdfResource) = conformsTo(logicalTarget, ref, ctx, state)
        return when (part) {
            is NodeLogicalPart.And -> {
                val failing = part.operands.firstOrNull { !conforms(it) }
                if (failing != null) {
                    listOf(violation(reportFocus, tpl, constraintStub(ConstraintType.AND, tpl.path?.predicate),
                        "sh:and failed: value does not conform to ${failing.displayId()}", value = logicalTarget))
                } else emptyList()
            }
            is NodeLogicalPart.Or ->
                if (part.operands.none { conforms(it) }) {
                    listOf(violation(reportFocus, tpl, constraintStub(ConstraintType.OR, tpl.path?.predicate),
                        "sh:or requires at least one matching shape", value = logicalTarget))
                } else emptyList()
            is NodeLogicalPart.Xone -> {
                var matches = 0
                for (op in part.operands) {
                    if (conforms(op)) matches++
                    if (matches > 1) break
                }
                if (matches != 1) {
                    listOf(violation(reportFocus, tpl, constraintStub(ConstraintType.XONE, tpl.path?.predicate),
                        "sh:xone requires exactly one matching shape (found ${if (matches > 1) "more than one" else "none"})", value = logicalTarget))
                } else emptyList()
            }
            is NodeLogicalPart.Not ->
                if (conforms(part.operand)) {
                    listOf(violation(reportFocus, tpl, constraintStub(ConstraintType.NOT, tpl.path?.predicate),
                        "sh:not violated: value conforms to ${part.operand.displayId()}", value = logicalTarget))
                } else emptyList()
        }
    }

    private fun validateClosed(focus: RdfResource, shape: CompiledNodeShape, tpl: ResultTemplate, ctx: ValidationContext): List<ValidationViolation> {
        val allowed = mutableSetOf<Iri>()
        allowed.addAll(shape.ignoredProperties)
        if (shape.closed == ClosedMode.BY_TYPES || RDF.type in shape.ignoredProperties) {
            allowed.add(RDF.type)
        }
        when (shape.closed) {
            // SHACL §4.8.1: only IRIs used directly as sh:path of the shape's sh:property values are allowed.
            ClosedMode.TRUE -> shape.propertyShapes.forEach { ps -> directPredicate(ps.path)?.let { allowed.add(it) } }
            ClosedMode.BY_TYPES -> allowed.addAll(collectClosedByTypesProperties(focus, ctx))
            ClosedMode.NONE -> Unit
        }
        val violations = mutableListOf<ValidationViolation>()
        val data = ctx.data
        for (p in data.predicatesFor(focus)) {
            if (p in allowed) continue
            val path = ReportPath(listOf(p), null, emptyList(), p)
            for (o in data.objects(focus, p)) {
                violations.add(
                    violation(focus, tpl, constraintStub(ConstraintType.CLOSED, p), "Closed shape disallows predicate $p", value = o, path = path),
                )
            }
        }
        return violations
    }

    private fun directPredicate(path: ShaclPath): Iri? = (path as? ShaclPath.Predicate)?.iri

    /** SHACL 1.2 `sh:closed sh:ByTypes` property collection (shapes graph walk). */
    private fun collectClosedByTypesProperties(focus: RdfResource, ctx: ValidationContext): Set<Iri> {
        val out = mutableSetOf<Iri>()
        val compiled = ctx.compiled
        val shapesIdx = compiled.index
        val visited = mutableSetOf<RdfResource>()

        fun collectFromShapeNode(shapeNode: RdfResource) {
            if (!visited.add(shapeNode)) return
            val cn = compiled.shapesByNode[shapeNode] ?: return
            for (ps in cn.propertyShapes) {
                directPredicate(ps.path)?.let { out.add(it) }
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

    /**
     * `sh:uniqueValuesFor`: hash-groups targets by their composite key (multisets of values per property). Returns
     * at most [capacity] result rows plus the total number of results (so truncation and conformance stay exact).
     */
    private fun validateUniqueValuesForShape(
        shape: CompiledNodeShape,
        focusNodes: List<RdfTerm>,
        focusOnly: RdfResource?,
        ctx: ValidationContext,
        capacity: Int,
    ): Pair<List<ValidationViolation>, Long> {
        val props = shape.uniqueValuesForProps
        val targets = focusNodes.filterIsInstance<RdfResource>()
        if (targets.size < 2) return emptyList<ValidationViolation>() to 0L
        val groups = LinkedHashMap<List<List<String>>, MutableList<RdfResource>>()
        for (t in targets) {
            ctx.budget.tick("uniqueValuesFor")
            val key = props.map { p -> ctx.data.objects(t, p).map { shaclRdfTermFingerprint(it) }.sorted() }
            if (key.all { it.isEmpty() }) continue
            groups.getOrPut(key) { mutableListOf() }.add(t)
        }
        val tpl = ResultTemplate(shape.shapeNode, shape.severity, shape.severityCustomIri, shape.messages, null)
        val rows = mutableListOf<ValidationViolation>()
        var count = 0L
        for (nodes in groups.values) {
            if (nodes.size < 2) continue
            for (a in nodes) {
                if (focusOnly != null && a != focusOnly) continue
                for (b in nodes) {
                    if (a == b) continue
                    count++
                    if (rows.size < capacity) {
                        rows.add(violation(a, tpl, constraintStub(ConstraintType.UNIQUE_VALUES_FOR), "sh:uniqueValuesFor duplicate composite key", value = b))
                    }
                }
            }
        }
        return rows to count
    }

    private fun evaluateConstraintsForValues(
        focus: RdfTerm,
        tpl: ResultTemplate,
        values: List<RdfTerm>,
        constraints: List<PropertyConstraint>,
        ctx: ValidationContext,
        state: DepthState,
        currentShape: RdfResource,
        path: ShaclPath?,
    ): List<ValidationViolation> {
        val data = ctx.data
        val vs = mutableListOf<ValidationViolation>()
        val pathPredicate = tpl.path?.predicate

        fun add(
            type: ConstraintType,
            message: String,
            value: RdfTerm? = null,
            params: Map<String, Any> = emptyMap(),
            severity: ViolationSeverity = tpl.severity,
            severityIri: Iri? = tpl.severityCustomIri,
            messages: List<Literal> = tpl.messages,
        ) {
            vs.add(violation(focus, tpl, constraintStub(type, pathPredicate, params), message, value, severity, severityIri, messages))
        }

        fun fingerprints(terms: List<RdfTerm>): Set<String> = terms.mapTo(HashSet()) { shaclRdfTermFingerprint(it) }

        for (c in constraints) {
            if (state.conformsOnly && vs.isNotEmpty()) return vs
            when (c) {
                // Cardinality results carry no sh:value (SHACL §4.2.1 / §4.2.2).
                is PropertyConstraint.MinCount ->
                    if (values.size < c.n) add(ConstraintType.MIN_COUNT, "Minimum cardinality ${c.n} required, found ${values.size}", params = mapOf("min" to c.n, "actual" to values.size))
                is PropertyConstraint.MaxCount ->
                    if (values.size > c.n) add(ConstraintType.MAX_COUNT, "Maximum cardinality ${c.n} allowed, found ${values.size}", params = mapOf("max" to c.n, "actual" to values.size))
                is PropertyConstraint.Datatype ->
                    values.forEach { v ->
                        if (!literalMatchesShaclDatatypes(v, c.allowed)) {
                            add(
                                ConstraintType.DATATYPE,
                                "Value $v does not match allowed datatype(s) ${c.allowed.joinToString { it.value }}",
                                v,
                                severity = c.severityOverride ?: tpl.severity,
                                severityIri = if (c.severityOverride != null) null else tpl.severityCustomIri,
                                messages = c.messages.ifEmpty { tpl.messages },
                            )
                        }
                    }
                is PropertyConstraint.Class ->
                    values.forEach { v ->
                        val ok = v is RdfResource && data.typesOf(v).any { t -> c.iri in data.superclassCone(t) }
                        if (!ok) add(ConstraintType.CLASS, "Expected rdf:type (subclass of) ${c.iri.value} for value $v", v)
                    }
                is PropertyConstraint.ClassAnyOf ->
                    values.forEach { v ->
                        val ok = v is RdfResource && c.options.any { req -> data.typesOf(v).any { t -> req in data.superclassCone(t) } }
                        if (!ok) add(ConstraintType.CLASS, "Expected rdf:type matching one of ${c.options.joinToString { it.value }} for value $v", v)
                    }
                is PropertyConstraint.NodeKind ->
                    values.forEach { v ->
                        if (!c.kinds.any { matchesNodeKind(v, it) }) add(ConstraintType.NODE_KIND, "Node kind ${c.kinds.joinToString { it.value }} required for $v", v)
                    }
                is PropertyConstraint.Pattern ->
                    values.forEach { v ->
                        val lex = literalLexicalString(v)
                        if (lex == null || !c.regex.containsMatchIn(ctx.text(lex))) add(ConstraintType.PATTERN, "Pattern ${c.pattern} violated for value $v", v)
                    }
                is PropertyConstraint.MinLength ->
                    values.forEach { v ->
                        val lex = literalLexicalString(v)
                        if (lex == null || lex.codePointCount(0, lex.length) < c.n) add(ConstraintType.MIN_LENGTH, "minLength ${c.n} violated for $v", v)
                    }
                is PropertyConstraint.MaxLength ->
                    values.forEach { v ->
                        val lex = literalLexicalString(v)
                        if (lex == null || lex.codePointCount(0, lex.length) > c.n) add(ConstraintType.MAX_LENGTH, "maxLength ${c.n} violated for $v", v)
                    }
                is PropertyConstraint.In ->
                    values.forEach { v ->
                        if (!c.allowed.any { shaclRdfTermEquals(it, v) }) add(ConstraintType.IN, "Value $v not in sh:in", v)
                    }
                is PropertyConstraint.HasValue ->
                    if (values.none { shaclRdfTermEquals(it, c.value) }) add(ConstraintType.HAS_VALUE, "sh:hasValue missing ${c.value}")
                is PropertyConstraint.LanguageIn ->
                    values.forEach { v ->
                        val lang = (v as? LangString)?.lang?.takeIf { it.isNotEmpty() }
                        val ok = lang != null && c.langs.any { allowed -> languageTagMatchesLanguageRange(lang, allowed) }
                        if (!ok) add(ConstraintType.LANGUAGE_IN, "languageIn violated for $v", v)
                    }
                is PropertyConstraint.UniqueLang ->
                    if (c.enabled) {
                        val duplicated = values.mapNotNull { v -> (v as? LangString)?.let { "${it.lang.lowercase()} ${it.direction?.token ?: ""}" } }
                            .groupingBy { it }.eachCount().filter { it.value > 1 }.keys
                        duplicated.forEach { _ -> add(ConstraintType.UNIQUE_LANG, "sh:uniqueLang violated") }
                    }
                is PropertyConstraint.EqualsPath -> {
                    val other = distinctShaclTerms(PathEvaluator.evaluate(focus, c.otherPath, data))
                    val otherKeys = fingerprints(other)
                    val valueKeys = fingerprints(values)
                    values.filter { shaclRdfTermFingerprint(it) !in otherKeys }.forEach { add(ConstraintType.EQUALS, "sh:equals: value $it is missing from the referenced path", it) }
                    other.filter { shaclRdfTermFingerprint(it) !in valueKeys }.forEach { add(ConstraintType.EQUALS, "sh:equals: referenced value $it is missing from the path", it) }
                }
                is PropertyConstraint.DisjointPath -> {
                    val otherKeys = fingerprints(PathEvaluator.evaluate(focus, c.otherPath, data))
                    values.filter { shaclRdfTermFingerprint(it) in otherKeys }.forEach { add(ConstraintType.DISJOINT, "sh:disjoint violated: $it is shared with the referenced path", it) }
                }
                is PropertyConstraint.LessThanPath -> {
                    val other = distinctShaclTerms(PathEvaluator.evaluate(focus, c.otherPath, data))
                    values.forEach { v -> other.forEach { w -> if (!literalLess(v, w)) add(ConstraintType.LESS_THAN, "sh:lessThan violated comparing $v and $w", v) } }
                }
                is PropertyConstraint.LessThanOrEqualsPath -> {
                    val other = distinctShaclTerms(PathEvaluator.evaluate(focus, c.otherPath, data))
                    values.forEach { v -> other.forEach { w -> if (!literalLessOrEqual(v, w)) add(ConstraintType.LESS_THAN_OR_EQUALS, "sh:lessThanOrEquals violated comparing $v and $w", v) } }
                }
                is PropertyConstraint.MinInclusive ->
                    values.forEach { v -> if (!satisfiesMinInclusive(v, c.bound)) add(ConstraintType.MIN_INCLUSIVE, "minInclusive violated for $v vs bound ${c.bound}", v) }
                is PropertyConstraint.MaxInclusive ->
                    values.forEach { v -> if (!satisfiesMaxInclusive(v, c.bound)) add(ConstraintType.MAX_INCLUSIVE, "maxInclusive violated for $v vs bound ${c.bound}", v) }
                is PropertyConstraint.MinExclusive ->
                    values.forEach { v -> if (!satisfiesMinExclusive(v, c.bound)) add(ConstraintType.MIN_EXCLUSIVE, "minExclusive violated for $v vs bound ${c.bound}", v) }
                is PropertyConstraint.MaxExclusive ->
                    values.forEach { v -> if (!satisfiesMaxExclusive(v, c.bound)) add(ConstraintType.MAX_EXCLUSIVE, "maxExclusive violated for $v vs bound ${c.bound}", v) }
                is PropertyConstraint.Qualified -> {
                    // SHACL §4.7.3: with sh:qualifiedValueShapesDisjoint, values conforming to a sibling shape don't count.
                    val count = values.count { v ->
                        conformsTo(v, c.shape, ctx, state) && (!c.disjoint || c.siblings.none { s -> conformsTo(v, s, ctx, state) })
                    }
                    if (c.min != null && count < c.min) {
                        add(ConstraintType.QUALIFIED_MIN_COUNT, "qualifiedMinCount violated (required ${c.min}, found $count)", params = mapOf("min" to c.min, "actual" to count))
                    }
                    if (c.max != null && count > c.max) {
                        add(ConstraintType.QUALIFIED_MAX_COUNT, "qualifiedMaxCount violated (max ${c.max}, found $count)", params = mapOf("max" to c.max, "actual" to count))
                    }
                }
                is PropertyConstraint.MinListLength ->
                    values.forEach { v ->
                        val members = data.expandDataList(v)
                        if (members == null || members.size < c.n) add(ConstraintType.MIN_LIST_LENGTH, "sh:minListLength requires at least ${c.n} list members", v)
                    }
                is PropertyConstraint.MaxListLength ->
                    values.forEach { v ->
                        val members = data.expandDataList(v)
                        if (members == null || members.size > c.n) add(ConstraintType.MAX_LIST_LENGTH, "sh:maxListLength allows at most ${c.n} list members", v)
                    }
                is PropertyConstraint.MemberShape ->
                    values.forEach { v ->
                        val members = data.expandDataList(v)
                        when {
                            members == null -> add(ConstraintType.MEMBER_SHAPE, "Value is not a valid SHACL RDF list", v)
                            members.any { m -> !conformsTo(m, c.nestedShape, ctx, state) } ->
                                add(ConstraintType.MEMBER_SHAPE, "sh:memberShape violated for list value", v)
                        }
                    }
                is PropertyConstraint.UniqueMembers ->
                    if (c.enabled) {
                        values.forEach { v ->
                            val members = data.expandDataList(v)
                            if (members == null) {
                                add(ConstraintType.UNIQUE_MEMBERS, "Value is not a valid SHACL RDF list", v)
                            } else if (distinctShaclTerms(members).size != members.size) {
                                add(ConstraintType.UNIQUE_MEMBERS, "sh:uniqueMembers violated", v)
                            }
                        }
                    }
                is PropertyConstraint.SubsetOfPath -> {
                    val otherKeys = fingerprints(PathEvaluator.evaluate(focus, c.otherPath, data))
                    values.forEach { v ->
                        if (shaclRdfTermFingerprint(v) !in otherKeys) add(ConstraintType.SUBSET_OF, "sh:subsetOf violated: value not reachable via referenced path", v)
                    }
                }
                is PropertyConstraint.SingleLine ->
                    if (c.enabled) {
                        values.forEach { v ->
                            val lex = literalLexicalString(v)
                            if (lex != null && singleLineBreakRegex.containsMatchIn(lex)) add(ConstraintType.SINGLE_LINE, "sh:singleLine violated", v)
                        }
                    }
                is PropertyConstraint.SomeValue ->
                    if (values.none { conformsTo(it, c.nestedShape, ctx, state) }) {
                        add(ConstraintType.SOME_VALUE, "sh:someValue requires at least one conforming value")
                    }
                is PropertyConstraint.RootClass ->
                    values.forEach { v ->
                        val cls = v as? Iri
                        if (cls == null) {
                            add(ConstraintType.ROOT_CLASS, "sh:rootClass expects an IRI class value", v)
                        } else if (c.roots.none { it in data.superclassCone(cls) }) {
                            add(ConstraintType.ROOT_CLASS, "sh:rootClass violated for $cls", v)
                        }
                    }
                is PropertyConstraint.Shape ->
                    values.forEach { v ->
                        if (!conformsTo(v, c.nestedShape, ctx, state)) add(ConstraintType.SHAPE, "sh:shape constraint failed for ${c.nestedShape.displayId()}", v)
                    }
                is PropertyConstraint.Node ->
                    values.forEach { v ->
                        if (!conformsTo(v, c.nestedShape, ctx, state)) add(ConstraintType.NODE, "sh:node constraint failed for ${c.nestedShape.displayId()}", v)
                    }
                is PropertyConstraint.Sparql -> vs.addAll(evaluateSparql(focus, tpl, c, ctx, currentShape, path))
                is PropertyConstraint.ReifierShape,
                is PropertyConstraint.ReificationRequired,
                -> Unit
            }
        }
        return vs
    }

    /** SHACL-SPARQL: one result per solution; `?value`, `?path`, `?message` and `?failure` are honoured. */
    private fun evaluateSparql(
        focus: RdfTerm,
        tpl: ResultTemplate,
        c: PropertyConstraint.Sparql,
        ctx: ValidationContext,
        currentShape: RdfResource,
        path: ShaclPath?,
    ): List<ValidationViolation> {
        val bindings = LinkedHashMap<String, RdfTerm>()
        bindings["this"] = focus
        if ("currentShape" in c.preBound) bindings["currentShape"] = currentShape
        if ("shapesGraph" in c.preBound) bindings["shapesGraph"] = SparqlConstraintEvaluator.SHAPES_GRAPH_IRI
        val out = mutableListOf<ValidationViolation>()
        for (row in ctx.select(c.query, bindings)) {
            val failure = row.get("failure")
            if (failure != null && isLexicallyTrue(failure)) {
                throw ShaclValidationException("SPARQL constraint ${c.constraintNode.displayId()} reported ?failure for focus node $focus")
            }
            val value = row.get("value") ?: if (path == null) focus else null
            val rowPath = (row.get("path") as? Iri)?.let { ReportPath(listOf(it), null, emptyList(), it) } ?: tpl.path
            fun substitute(lit: Literal): Literal {
                val text = messagePlaceholder.replace(lit.lexical) { m ->
                    val term = row.get(m.groupValues[1]) ?: bindings[m.groupValues[1]]
                    term?.let { displayTerm(it) } ?: m.value
                }
                return if (lit is LangString) lit.copy(lexical = text) else TypedLiteral(text, lit.datatype)
            }
            val messages =
                c.messages.ifEmpty { listOfNotNull(row.get("message") as? Literal) }.ifEmpty { tpl.messages }.map { substitute(it) }
            out.add(
                violation(
                    focus = focus,
                    tpl = tpl,
                    constraint = constraintStub(ConstraintType.SPARQL_CONSTRAINT, rowPath?.predicate),
                    message = "SPARQL constraint ${c.constraintNode.displayId()} returned a solution",
                    value = value,
                    severity = c.severity ?: tpl.severity,
                    severityIri = if (c.severity != null) c.severityCustomIri else tpl.severityCustomIri,
                    messages = messages,
                    sourceConstraint = c.constraintNode,
                    path = rowPath,
                ),
            )
        }
        return out
    }

    private fun displayTerm(term: RdfTerm): String =
        when (term) {
            is Literal -> term.lexical
            is Iri -> term.value
            is BlankNode -> "_:${term.id}"
            else -> term.toString()
        }

    private fun reportPath(ps: CompiledPropertyShape, ctx: ValidationContext): ReportPath =
        ctx.reportPaths.getOrPut(ps.shapeNode) {
            ReportPath(pathToTerms(ps.path), ps.pathNode.takeIf { it is BlankNode }, ps.pathTriples, (ps.path as? ShaclPath.Predicate)?.iri)
        }

    private fun validatePropertyShape(
        focus: RdfTerm,
        ps: CompiledPropertyShape,
        ctx: ValidationContext,
        state: DepthState,
    ): List<ValidationViolation> {
        if (ps.deactivated) return emptyList()
        // Value nodes are a set (SHACL §2.3.2): alternative / zero-or-one paths may otherwise yield duplicates.
        val values = distinctShaclTerms(PathEvaluator.evaluate(focus, ps.path, ctx.data))
        val tpl = ResultTemplate(ps.shapeNode, ps.severity, ps.severityCustomIri, ps.messages, reportPath(ps, ctx))
        val vs = evaluateConstraintsForValues(focus, tpl, values, ps.constraints, ctx, state, ps.shapeNode, ps.path).toMutableList()
        fun done() = state.conformsOnly && vs.isNotEmpty()
        if (done()) return vs
        for (part in ps.logicalParts) {
            for (v in values) {
                vs.addAll(evalLogical(focus, v, tpl, part, ctx, state))
                if (done()) return vs
            }
        }
        for (nested in ps.nestedPropertyShapes) {
            for (v in values) {
                vs.addAll(validatePropertyShape(v, nested, ctx, state))
                if (done()) return vs
            }
        }
        if (focus is RdfResource &&
            ps.constraints.any { it is PropertyConstraint.ReifierShape || it is PropertyConstraint.ReificationRequired }
        ) {
            vs.addAll(
                validateReifierPropertyConstraints(
                    focus = focus,
                    tpl = tpl,
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
        tpl: ResultTemplate,
        claims: List<RdfTriple>,
        constraints: List<PropertyConstraint>,
        ctx: ValidationContext,
        state: DepthState,
    ): List<ValidationViolation> {
        ctx.checkDeadline()
        val vs = mutableListOf<ValidationViolation>()
        val shapeRefs = constraints.filterIsInstance<PropertyConstraint.ReifierShape>().map { it.nestedShape }
        val reifReq = constraints.filterIsInstance<PropertyConstraint.ReificationRequired>().any { it.required }
        val pathPredicate = tpl.path?.predicate
        for (claim in claims) {
            val reifiers = ctx.data.reifiersForClaim(claim)
            if (reifiers.isEmpty()) {
                when {
                    shapeRefs.isNotEmpty() ->
                        vs.add(violation(focus, tpl, constraintStub(ConstraintType.REIFIER_SHAPE, pathPredicate), "sh:reifierShape: no reifier for triple $claim", value = claim.obj))
                    reifReq ->
                        vs.add(violation(focus, tpl, constraintStub(ConstraintType.REIFICATION_REQUIRED, pathPredicate), "sh:reificationRequired: missing reifier for triple $claim", value = claim.obj))
                }
                continue
            }
            for (ref in shapeRefs) {
                for (r in reifiers) {
                    if (!conformsTo(r, ref, ctx, state)) {
                        vs.add(violation(focus, tpl, constraintStub(ConstraintType.REIFIER_SHAPE, pathPredicate), "sh:reifierShape constraint failed for reifier $r", value = claim.obj))
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
        tpl: ResultTemplate,
        constraint: ShaclConstraint,
        message: String,
        value: RdfTerm? = null,
        severity: ViolationSeverity = tpl.severity,
        severityIri: Iri? = tpl.severityCustomIri,
        messages: List<Literal> = tpl.messages,
        sourceConstraint: RdfTerm? = null,
        path: ReportPath? = tpl.path,
    ): ValidationViolation =
        ValidationViolation(
            severity = severity,
            constraint = constraint,
            focusNode = focus,
            message = messages.firstOrNull()?.lexical ?: message,
            path = path?.terms,
            shapeUri = tpl.shape.displayId(),
            value = value,
            resultSeverityIri = severityIri?.value,
            resultPathNode = path?.node,
            resultPathTriples = path?.triples ?: emptyList(),
            resultMessages = messages,
            sourceConstraint = sourceConstraint,
        )

    private fun buildStatistics(
        totalResources: Int,
        violations: List<ValidationViolation>,
        warnings: List<ValidationWarning>,
        compiled: CompiledShapeGraph,
        validatedConstraintSlots: Int,
    ): ValidationStatistics {
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
            totalResources = totalResources,
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

    private fun pathToTerms(path: ShaclPath): List<RdfTerm>? =
        when (path) {
            is ShaclPath.Predicate -> listOf(path.iri)
            is ShaclPath.Sequence -> path.segments.map { seg -> (seg as? ShaclPath.Predicate)?.iri ?: return null }
            else -> null
        }

    private fun literalMatchesShaclDatatypes(term: RdfTerm, allowed: List<Iri>): Boolean =
        when (term) {
            is LangString -> term.datatype in allowed
            is Literal -> term.datatype in allowed && literalLexicallyValid(term)
            else -> false
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
}
