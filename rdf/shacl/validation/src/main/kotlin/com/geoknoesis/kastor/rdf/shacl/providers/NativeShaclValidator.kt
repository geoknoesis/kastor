package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.Dataset
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlQueryable
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.ShapesGraphNotFoundException
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ShaclShape
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclOperationException
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationReport
import com.geoknoesis.kastor.rdf.shacl.ValidationStatistics
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ValidationWarning
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import java.time.Duration
import com.geoknoesis.kastor.rdf.shacl.native.ValidationBudget
import com.geoknoesis.kastor.rdf.shacl.native.CompiledNodeShape
import com.geoknoesis.kastor.rdf.shacl.native.CompiledShapeGraph
import com.geoknoesis.kastor.rdf.shacl.native.DataGraphIndex
import com.geoknoesis.kastor.rdf.shacl.native.NativeCompileCache
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler
import com.geoknoesis.kastor.rdf.shacl.native.ShapeGraphIndex
import com.geoknoesis.kastor.rdf.shacl.native.ShapesStructuralDigest
import com.geoknoesis.kastor.rdf.shacl.native.SparqlConstraintEvaluator

/**
 * Kastor native SHACL Core validator (compile → plan → execute → report).
 *
 * Semantics notes:
 * - A report conforms (`isValid`) only when it has no result of severity sh:Violation, sh:Warning, sh:Info or a
 *   custom severity; SHACL 1.2 sh:Debug / sh:Trace results do not affect conformance.
 * - Value nodes are sets; nested shape checks (`sh:node`, logical constraints, qualified shapes, `sh:shape`,
 *   `sh:someValue`, `sh:memberShape`, `sh:reifierShape`, `sh:nodeByExpression`, `sh:targetWhere`) are conformance
 *   checks memoized per (value node, shape) within a run.
 * - Conformance checks are three-valued (conforms / fails / undefined) and combined with Kleene logic: a shape and
 *   `sh:and` fail as soon as one part definitely fails, `sh:or` / `sh:someValue` conform as soon as one part definitely
 *   conforms, qualified counts are bounded by the definite answers, and an answer is undefined only when it really
 *   depends on an undefined answer. Results therefore never depend on operand, constraint or target order, with one
 *   bounded exception: once a run has met many hostile `sh:pattern` values, whether a value that is expensive to
 *   match (but not hostile) is decided can depend on whether it is first matched before or after that (see below).
 * - Recursion. SHACL leaves recursive shapes undefined; the engine applies a sound, evaluation-order independent
 *   interpretation. The compiler finds the shapes that can reach themselves (strongly connected components of the
 *   static shape dependency graph); only those can recurse over data. Nested checks of any other shape recurse at
 *   most as deep as the static shape nesting ([ValidationConfig.maxRecursionDepth]). A conformance question on a
 *   recursive shape is answered by an explicit-worklist solver that never uses JVM stack proportional to the data:
 *   1. it evaluates each question once, answering the recursive questions it reads with "conforms" and **recording**
 *      every such read with its polarity (the evaluation visits every constraint and operand, so what it reads does
 *      not depend on the answers). Reads through monotone operators (`sh:node`, `sh:and`, `sh:or`, `sh:property`,
 *      `sh:shape`, `sh:someValue`, `sh:memberShape`, `sh:reifierShape`, `sh:nodeByExpression`,
 *      `sh:qualifiedMinCount`) are positive; reads through `sh:not`, `sh:xone`, `sh:qualifiedMaxCount` and the sibling
 *      exclusion of `sh:qualifiedValueShapesDisjoint` are negative. A question without recursive reads, or failing
 *      although it only has positive reads, is decided and its reads are not explored;
 *   2. it evaluates the recorded dependency graph dependencies-first. A group of mutually dependent questions without
 *      a negative dependency among them gets its greatest fixpoint (assumed to conform until a constraint fails; the
 *      recorded answers are its first iteration). In a group with a negative dependency, the questions whose answer
 *      is the same whatever the group's answers are (evaluated with them undefined) are settled and the remainder is
 *      split again; questions still on a cycle through a negative dependency are **undefined**.
 *   A read the recording did not register is never answered by default: it is registered and the solve restarts.
 *   A top-level constraint whose outcome depends on an undefined answer produces a result stating that the recursive
 *   dependency is undefined (in [ValidationConfig.strictMode] validation fails instead), in addition to the definite
 *   results of the other value nodes. An undecidable `sh:targetWhere` membership is reported the same way. Such a
 *   result is marked [ValidationViolation.isUndefinedRecursion] and has the source shape's declared severity and the
 *   constraint's component, so it affects conformance exactly as a failure would: the report conforms only when it
 *   conforms whatever the undefined answers are.
 * - Results of recursive shapes. The focus nodes of a recursive shape are questions of its component, so the solver
 *   answers them. A question that is such a focus node is evaluated in a mode that also materializes its results
 *   and logs, per constraint, the solver answers the results depend on. When the focus node turns out to fail or to
 *   be undefined, its results are taken from that evaluation: the constraints whose logged answers are the final
 *   ones keep their recorded results, and only a constraint that was given another answer (the recording pass
 *   assumes "conforms") is evaluated again, on the value nodes already computed. The focus node itself is not
 *   evaluated a second time. What recordings retain is bounded; beyond the bound a focus node gets a separate report
 *   evaluation, with the same results.
 * - `sh:pattern` evaluations are bounded in regular expression engine steps and, for long values, run on a thread
 *   with a fixed stack size, so their outcome does not depend on the machine or on the caller's stack (see
 *   [ValidationConfig.patternTimeout]). Within a run, a (pattern, value) has a single answer, whoever asks. After
 *   [PATTERN_MAX_FULL_BUDGET_EXHAUSTIONS] values of a run have used up their whole budget, values not matched yet get
 *   a reduced budget that still grows linearly with their length (see [matchPattern]).
 */
internal class NativeShaclValidator(
    internal val config: ValidationConfig,
    private val sparqlRepositoryFactory: () -> com.geoknoesis.kastor.rdf.RdfRepository = SparqlConstraintEvaluator.defaultRepositoryFactory,
) : ShaclValidator, com.geoknoesis.kastor.rdf.shacl.ShapeCacheControl {

    private companion object {
        const val DIGEST_MEMO_CAPACITY = 8
    }

    private val compileCache = NativeCompileCache()
    private val patterns = NativePatternEvaluator(config)

    /**
     * Structural digests of recently validated shapes snapshots (finding: avoid re-sorting and re-hashing an
     * unchanged shapes graph on every run). The RdfGraph API exposes no modification counter, so entries are keyed
     * by the **content** of the merged triple snapshot: a hit requires element-wise equality with a stored copy
     * (O(n) `equals`, no canonicalization/sort/SHA-256, outside the memo lock). Any mutation of the shapes graph changes the snapshot and
     * therefore misses — a stale digest can never be reused. Snapshots whose triple order differs simply miss. There is
     * deliberately no identity fast path (same graph object): without a modification stamp in the RdfGraph API it
     * could return the digest of a graph mutated since.
     * Bounded to [DIGEST_MEMO_CAPACITY] entries (each retains a copy of the triple list, not the graph) and emptied
     * by [clearCache].
     */
    private class DigestMemoEntry(val triples: List<RdfTriple>, val digest: String)

    private val digestMemo = object : LinkedHashMap<Int, DigestMemoEntry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, DigestMemoEntry>?): Boolean = size > DIGEST_MEMO_CAPACITY
    }

    /** Counters and test seams (see [ShaclInstrumentation]); a run adds its counts when it ends. */
    internal val instrumentation = ShaclInstrumentation()

    internal val digestMemoHits: Long get() = instrumentation.digestMemoHits.sum()

    /** Test instrumentation: node and property shape evaluations performed by this validator. */
    internal val shapeEvaluations: Long get() = instrumentation.shapeEvaluations.sum()

    /** Test instrumentation: restarts of the recursion solver caused by a read its recording had not registered. */
    internal val solverRestarts: Long get() = instrumentation.solverRestarts.sum()

    /** Test instrumentation: `sh:pattern` evaluations actually run against the regular expression engine. */
    internal val patternEvaluations: Long get() = instrumentation.patternEvaluations.sum()

    /** Test instrumentation: regular expression engine steps used by `sh:pattern` evaluations. */
    internal val patternSteps: Long get() = instrumentation.patternSteps.sum()

    /** Test instrumentation: `sh:pattern` evaluations run on the pattern evaluation thread. */
    internal val patternWorkerEvaluations: Long get() = instrumentation.patternWorkerEvaluations.sum()

    /** Test instrumentation: failing or undefined recursive focus nodes whose results came from a recorded solver evaluation. */
    internal val recordedReports: Long get() = instrumentation.recordedReports.sum()

    /** Test instrumentation: failing or undefined recursive focus nodes that needed a separate report evaluation. */
    internal val separateReportEvaluations: Long get() = instrumentation.separateReportEvaluations.sum()

    /** Test seam: see [ShaclInstrumentation.dropRecordedRead]. Never set in production code. */
    internal var dropRecordedRead: ((RdfTerm, RdfResource) -> Boolean)?
        get() = instrumentation.dropRecordedRead
        set(value) { instrumentation.dropRecordedRead = value }

    /** Test seam: the clock of the wall-clock backstop of the per-pattern budget. Never set in production code. */
    internal var patternClock: () -> Long
        get() = instrumentation.patternClock
        set(value) { instrumentation.patternClock = value }

    /** Test seam: the clock of the run budget ([ValidationConfig.timeout]). Never set in production code. */
    internal var budgetClock: () -> Long
        get() = instrumentation.budgetClock
        set(value) { instrumentation.budgetClock = value }

    /**
     * Bound on what the recorded evaluations of recursive focus nodes may retain at any time (results, solver reads
     * and units, summed over the recordings that are waiting to be reported). While it is exceeded, further focus
     * nodes are not recorded: their results come from a separate report evaluation, as they did before recordings
     * existed. Test seam: `0` disables recording altogether.
     */
    internal var reportRecordingCapacity: Long
        get() = instrumentation.reportRecordingCapacity
        set(value) { instrumentation.reportRecordingCapacity = value }

    /** Test seam: stack size of the pattern evaluation thread. Never set in production code. */
    internal var patternWorkerStackBytes: Long
        get() = instrumentation.patternWorkerStackBytes
        set(value) { instrumentation.patternWorkerStackBytes = value }

    /** Test seam: values shorter than this are first matched on the validating thread. Never set in production code. */
    internal var patternInlineMaxLength: Int
        get() = instrumentation.patternInlineMaxLength
        set(value) { instrumentation.patternInlineMaxLength = value }

    /**
     * The snapshot is hashed and compared **outside** the memo lock (both are O(n)), so concurrent runs are not
     * serialised by them: the lock only guards the map operations. Entries are keyed by the snapshot's hash; the
     * stored copy is immutable, so comparing it needs no lock either.
     */
    internal fun digestOf(triples: List<RdfTriple>, budget: ValidationBudget): String {
        val hash = triples.hashCode()
        val candidate = synchronized(digestMemo) { digestMemo[hash] }
        if (candidate != null && candidate.triples == triples) {
            synchronized(digestMemo) { digestMemo[hash] } // refreshes the entry's recency
            instrumentation.digestMemoHits.increment()
            return candidate.digest
        }
        val digest = ShapesStructuralDigest.digest(triples, config, budget)
        val entry = DigestMemoEntry(ArrayList(triples), digest)
        synchronized(digestMemo) { digestMemo[hash] = entry }
        return digest
    }

    override val cacheStatistics get() = compileCache.statistics()

    /** Clears the compiled shapes cache and the shapes digest memo (releasing the retained triple snapshots). */
    override fun clearCache() {
        compileCache.clear()
        synchronized(digestMemo) { digestMemo.clear() }
    }

    init {
        if (config.parallelValidation) {
            throw UnsupportedShaclOperationException(
                "ValidationConfig.parallelValidation = true is not supported by the Kastor native SHACL engine (provider ids " +
                    "\"kastor\" and \"memory\"): it validates on the calling thread. Leave parallelValidation at false.",
            )
        }
        if (config.streamingMode) {
            throw UnsupportedShaclOperationException(
                "ValidationConfig.streamingMode = true is not supported by the Kastor native SHACL engine (provider ids " +
                    "\"kastor\" and \"memory\"): it validates an in-memory snapshot of the data graph. Leave streamingMode at false.",
            )
        }
        require(config.maxViolations > 0 && !config.timeout.isNegative && !config.timeout.isZero)
        require(config.maxPathValueNodes > 0) { "maxPathValueNodes must be positive" }
        require(!config.patternTimeout.isNegative && !config.patternTimeout.isZero) { "patternTimeout must be positive" }
    }

    override fun validate(graph: RdfGraph, shapes: RdfGraph): ValidationReport =
        runValidation(graph, shapes, config.dataset.validationDataset)

    /** When the dataset is SPARQL-capable, SHACL-SPARQL constraints query it in place instead of copying its default graph. */
    override fun validateDataset(dataset: Dataset, shapes: RdfGraph?): ValidationReport =
        runValidation(dataset.defaultGraph, shapes ?: Rdf.graph { }, dataset, sparqlInPlace = dataset)

    private fun runValidation(
        graph: RdfGraph,
        shapes: RdfGraph,
        datasetForDiscovery: Dataset?,
        focusOnly: RdfResource? = null,
        sparqlInPlace: SparqlQueryable? = null,
    ): ValidationReport =
        try {
            runValidationUnguarded(graph, shapes, datasetForDiscovery, focusOnly, sparqlInPlace)
        } catch (e: StackOverflowError) {
            // Shape nesting (up to ValidationConfig.maxRecursionDepth) recurses on the validating thread: a large
            // limit can exceed its stack. The stack is unwound to here, so the failure is an ordinary one.
            throw ShaclValidationException(
                "SHACL validation exhausted the stack of the validating thread; shape nesting of up to " +
                    "ValidationConfig.maxRecursionDepth=${config.maxRecursionDepth} needs more stack than is available " +
                    "(lower maxRecursionDepth or validate on a thread with a larger stack)",
                e,
            )
        }

    private fun runValidationUnguarded(
        graph: RdfGraph,
        shapes: RdfGraph,
        datasetForDiscovery: Dataset?,
        focusOnly: RdfResource?,
        sparqlInPlace: SparqlQueryable?,
    ): ValidationReport {
        val budget = ValidationBudget(config.timeout, instrumentation.budgetClock)
        budget.check("admission")
        val start = System.nanoTime()
        val combinedEstimate = graph.size().toLong() + shapes.size().toLong()
        if (combinedEstimate > config.maxCombinedGraphTriples) {
            throw ShaclValidationException(
                "Combined data + shapes triple count ($combinedEstimate) exceeds ValidationConfig.maxCombinedGraphTriples (${config.maxCombinedGraphTriples})",
            )
        }
        val importWarnings = ArrayList<String>()
        val mergedShapesTriples =
            try {
                NativeShapesPreparation(config).prepare(graph, shapes, datasetForDiscovery, budget, importWarnings)
            } catch (e: ShapesGraphNotFoundException) {
                throw ShaclValidationException(e.message ?: "Referenced shapes graph not found", e)
            }
        val digest =
            try {
                digestOf(mergedShapesTriples, budget)
            } catch (e: ShapeCompileException) {
                throw ShaclValidationException("SHACL shapes digest failed: ${e.message}", e)
            }
        config.cache.shapesGraphVersion?.let { compileCache.assertTagOrRecord(it, digest, budget) }
        // The unsupported-feature policy changes the compile outcome, so it is part of the key.
        val cacheKey = ShapesStructuralDigest.compileCacheKey(digest, config) + "|" + config.unsupportedFeatures.name
        if (graph.size().toLong() + mergedShapesTriples.size > config.maxCombinedGraphTriples) {
            throw ShaclValidationException("Expanded data and shapes exceed maxCombinedGraphTriples")
        }
        val compiled = try {
            compileCache.getOrCompile(cacheKey, budget) { ShapesCompiler.compile(mergedShapesTriples, config, budget) }
        } catch (e: ShapeCompileException) { throw ShaclValidationException("SHACL compile failed: ${e.message}", e) }
        newContext(compiled, graph, budget, sparqlInPlace).use { ctx ->
            val dataIndex = ctx.data
            ctx.checkDeadline()
            val targetWarnings = checkBlankNodeTargets(compiled, dataIndex)

            val violations = mutableListOf<ValidationViolation>()
            var totalResults = 0L
            // SHACL 1.2 sh:conformanceDisallows (by default, sh:Debug and sh:Trace results do not affect conformance).
            var blockingResults = 0L
            fun blocking(severity: ViolationSeverity, customIri: Iri?) = disallowsConformance(severity, customIri)
            var validatedConstraintSlots = 0L
            // Distinct focus nodes validated against at least one shape (ValidationStatistics.validatedResources).
            val validatedFocusNodes = HashSet<TermKey>()
            // Deep copies of blank-node sh:sourceConstraint values, by node (shared by the results that name one).
            val sourceConstraintCopies = SourceConstraintCopies(compiled.index.triples.size)
            fun record(results: List<ValidationViolation>) {
                // Strict mode: a constraint that undefined recursion leaves undecided fails the validation. The check
                // is made on the results that are reported, not where they are built: the solver also builds results
                // (recorded evaluations) under answers that are not final yet.
                if (config.strictMode) results.firstOrNull { it.isUndefinedRecursion }?.let { throw ShaclValidationException(it.message) }
                totalResults += results.size
                blockingResults += results.count { blocking(it.severity, it.resultSeverityIri?.let { iri -> Iri(iri) }) }
                for (result in results) {
                    if (violations.size >= config.maxViolations) break
                    violations.add(withSourceConstraintCopy(result, ctx, sourceConstraintCopies))
                }
            }

            // A shape whose focus nodes are answered by the recursion solver: see the loop below.
            fun answeredBySolver(shape: CompiledNodeShape) = shape.shapeNode in compiled.recursiveComponents &&
                compiled.referencedNodeShapes[shape.shapeNode] === shape && shape.shapeNode !in compiled.referencedPropertyShapes
            // The focus nodes of the recursive shapes are computed before anything is validated, so that the solver
            // knows which of its questions will be reported, whichever shape's validation asks them first.
            val recordingCapacity = instrumentation.reportRecordingCapacity
            ctx.recordingCapacity = recordingCapacity
            val targetsOfRecursiveShapes = java.util.IdentityHashMap<CompiledNodeShape, Pair<List<RdfTerm>, List<ValidationViolation>>>()
            if (recordingCapacity > 0) {
                for (shape in compiled.orderedNodeShapes) {
                    if (shape.deactivated || !answeredBySolver(shape)) continue
                    ctx.checkDeadline()
                    val undecided = ArrayList<ValidationViolation>()
                    val all = computeFocusNodes(shape, ctx, undecided)
                    targetsOfRecursiveShapes[shape] = all to undecided
                    ctx.reportedFocusNodes[shape.shapeNode] =
                        (if (focusOnly == null) all else all.filter { it == focusOnly }).mapTo(HashSet()) { TermKey(it) }
                }
            }

            for (shape in compiled.orderedNodeShapes) {
                ctx.checkDeadline()
                val known = targetsOfRecursiveShapes[shape]
                val undecidedTargets: List<ValidationViolation>
                val allFocusNodes: List<RdfTerm>
                if (known != null) {
                    allFocusNodes = known.first
                    undecidedTargets = known.second
                } else {
                    val undecided = ArrayList<ValidationViolation>()
                    allFocusNodes = computeFocusNodes(shape, ctx, undecided)
                    undecidedTargets = undecided
                }
                record(if (focusOnly == null) undecidedTargets else undecidedTargets.filter { it.focusNode == focusOnly })
                if (shape.uniqueValuesForProps.isNotEmpty()) {
                    val (rows, count) = validateUniqueValuesForShape(shape, allFocusNodes, focusOnly, ctx, config.maxViolations - violations.size)
                    violations.addAll(rows)
                    totalResults += count
                    if (blocking(shape.severity, shape.severityCustomIri)) blockingResults += count
                }
                val focusNodes = if (focusOnly == null) allFocusNodes else allFocusNodes.filter { it == focusOnly }
                validatedConstraintSlots += countConstraintEvaluationSlots(shape, focusNodes.size)
                if (!shape.deactivated) for (focus in focusNodes) validatedFocusNodes.add(TermKey(focus))
                // The solver answers every question of a recursive shape's component at once. A focus node it found
                // conforming has no results. For one it found failing or undefined, the results come from the
                // evaluation by which the solver settled the question (recordedResults): the focus node is not
                // evaluated again. Only when no recording is available (recording is bounded, see
                // reportRecordingCapacity) is the focus node evaluated once more, in report mode; that pass re-runs
                // only this shape's own constraints, every nested conformance check being a memo hit.
                val recursive = answeredBySolver(shape)
                for (focus in focusNodes) {
                    ctx.checkDeadline()
                    if (recursive) {
                        if (conformance(focus, shape.shapeNode, ctx, DepthState(0, Mode.CONFORMS)) == Conformance.CONFORMS) continue
                        val key = AtomKey(TermKey(focus), shape.shapeNode)
                        val recording = ctx.recordings[key]
                        if (recording != null) {
                            ctx.keepRecording(key, null)
                            val results = recordedResults(recording, ctx)
                            if (results != null) {
                                ctx.counters.recordedReports++
                                record(results)
                                continue
                            }
                        }
                        ctx.counters.separateReportEvaluations++
                    }
                    val sink = Sink(Mode.REPORT)
                    validateNodeShape(focus, shape, ctx, DepthState(0, Mode.REPORT), sink)
                    record(sink.results)
                }
            }
            val violationsTruncated = totalResults > violations.size
            val slots = validatedConstraintSlots.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            // ValidationConfig.includeWarnings = false leaves the report-level warnings out; results are unaffected.
            val warnings =
                if (config.includeWarnings) {
                    (importWarnings + compiled.unsupportedFeatureWarnings + targetWarnings).map { ValidationWarning(it) }
                } else {
                    emptyList()
                }

            val elapsed = Duration.ofNanos(System.nanoTime() - start)
            val statistics =
                buildStatistics(ctx.data.distinctResourceSubjects().size, validatedFocusNodes.size, elapsed, violations, warnings, compiled, slots)

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

    /** The context of one run that validates data [graph] against [compiled] shapes. */
    private fun newContext(
        compiled: CompiledShapeGraph,
        graph: RdfGraph,
        budget: ValidationBudget,
        sparqlInPlace: SparqlQueryable? = null,
    ): NativeValidationContext =
        NativeValidationContext(
            this, compiled, DataGraphIndex(graph, budget, config.maxPathValueNodes), budget, sparqlRepositoryFactory, sparqlInPlace, instrumentation,
        )

    /**
     * Runs [body] in a context over [graph] as data graph and as shapes graph (compiled from its triples alone, without
     * `owl:imports`), as node expression evaluation outside validation needs it (Node Expressions §3.4). The shapes
     * [expressionShapes] names over the shapes index are compiled as well (see [ShapesCompiler.compile]). Ill-formed
     * shapes throw [ShapeCompileException].
     */
    internal fun <T> withNodeExpressionContext(
        graph: RdfGraph,
        expressionShapes: (ShapeGraphIndex) -> Collection<RdfResource> = { emptyList() },
        body: (NativeValidationContext) -> T,
    ): T {
        val budget = ValidationBudget(config.timeout, instrumentation.budgetClock)
        val compiled = ShapesCompiler.compile(graph.getTriples(), config, budget, expressionShapes)
        return newContext(compiled, graph, budget).use(body)
    }

    override fun validate(graph: RdfGraph, shapes: List<ShaclShape>): ValidationReport {
        if (shapes.isNotEmpty()) {
            throw UnsupportedShaclOperationException(
                "The Kastor native SHACL engine does not support validate(graph, shapes: List<ShaclShape>). " +
                    "Pass the shapes as an RdfGraph via validate(graph, shapesGraph).",
            )
        }
        return validate(graph, Rdf.graph { })
    }

    override fun validateResource(graph: RdfGraph, shapes: RdfGraph, resource: RdfResource): ValidationReport =
        runValidation(graph, shapes, config.dataset.validationDataset, resource)

    override fun validateConstraints(graph: RdfGraph, constraints: List<com.geoknoesis.kastor.rdf.shacl.ShaclConstraint>): ValidationReport {
        if (constraints.isNotEmpty()) {
            throw UnsupportedShaclOperationException(
                "The Kastor native SHACL engine does not support validateConstraints(graph, constraints); declare the " +
                    "constraints in a shapes RdfGraph and call validate(graph, shapesGraph).",
            )
        }
        return validate(graph, Rdf.graph { })
    }

    override fun conforms(graph: RdfGraph, shapes: RdfGraph): Boolean = validate(graph, shapes).isValid

    override fun getValidationStatistics(graph: RdfGraph, shapes: RdfGraph): ValidationStatistics =
        validate(graph, shapes).statistics

    /** Whether a result of this severity makes the report non-conforming ([ValidationConfig.conformanceDisallows]). */
    private fun disallowsConformance(severity: ViolationSeverity, customIri: Iri?): Boolean =
        config.disallowsSeverity(severity, customIri)


    internal fun matchPattern(c: PropertyConstraint.Pattern, lexical: String, shape: RdfResource, ctx: NativeValidationContext): PatternOutcome =
        patterns.match(c, lexical, shape, ctx)
}
