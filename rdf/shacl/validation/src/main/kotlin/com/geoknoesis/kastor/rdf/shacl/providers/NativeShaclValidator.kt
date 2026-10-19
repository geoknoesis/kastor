package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Dataset
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlQueryable
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
import com.geoknoesis.kastor.rdf.shacl.UnsupportedFeatureHandling
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeature
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeatureException
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
import com.geoknoesis.kastor.rdf.shacl.native.ClosedMode
import com.geoknoesis.kastor.rdf.shacl.native.CompiledNodeShape
import com.geoknoesis.kastor.rdf.shacl.native.CompiledPropertyShape
import com.geoknoesis.kastor.rdf.shacl.native.CompiledShapeGraph
import com.geoknoesis.kastor.rdf.shacl.native.DataGraphIndex
import com.geoknoesis.kastor.rdf.shacl.native.NativeCompileCache
import com.geoknoesis.kastor.rdf.shacl.native.NodeLogicalPart
import com.geoknoesis.kastor.rdf.shacl.native.PathEvaluator
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint
import com.geoknoesis.kastor.rdf.shacl.native.ShaclPath
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler
import com.geoknoesis.kastor.rdf.shacl.native.ShapesStructuralDigest
import com.geoknoesis.kastor.rdf.shacl.native.SparqlConstraintEvaluator
import com.geoknoesis.kastor.rdf.shacl.native.isLexicallyTrue
import com.geoknoesis.kastor.rdf.shacl.native.constraintStub
import com.geoknoesis.kastor.rdf.shacl.native.shaclRdfTermFingerprint
import com.geoknoesis.kastor.rdf.shacl.native.stronglyConnectedComponents

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
        const val UNDEFINED_RECURSION =
            "Recursive shape dependency through a non-monotone operator (sh:not, sh:xone, sh:qualifiedMaxCount, disjoint qualified value shapes) is undefined"
        val messagePlaceholder = Regex("\\{[?$]([A-Za-z_][A-Za-z0-9_]*)\\}")

        /**
         * Nanoseconds of [ValidationConfig.patternTimeout] that one step of the regular expression engine (one
         * character read) stands for: 50,000 steps per millisecond. `java.util.regex` reads several times as many
         * characters per second on current hardware, so an evaluation normally reaches its step budget well before
         * the configured duration has passed.
         */
        const val PATTERN_NANOS_PER_STEP = 20L

        /** The wall clock stops a pattern evaluation only at this multiple of [ValidationConfig.patternTimeout]. */
        const val PATTERN_WALL_CLOCK_FACTOR = 10L
    }

    private val compileCache = NativeCompileCache()

    private val patternTimeoutNanos: Long =
        try { config.patternTimeout.toNanos() } catch (e: ArithmeticException) { Long.MAX_VALUE }

    /** Step budget of one pattern evaluation: [ValidationConfig.patternTimeout] at [PATTERN_NANOS_PER_STEP]. */
    private val patternStepBudget: Long = (patternTimeoutNanos / PATTERN_NANOS_PER_STEP).coerceAtLeast(1)

    /** Wall-clock backstop of one pattern evaluation, saturating. */
    private val patternBackstopNanos: Long =
        if (patternTimeoutNanos > Long.MAX_VALUE / PATTERN_WALL_CLOCK_FACTOR) Long.MAX_VALUE else patternTimeoutNanos * PATTERN_WALL_CLOCK_FACTOR

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
        val dataIndex = DataGraphIndex(graph, budget, config.maxPathValueNodes)
        NativeValidationContext(compiled, dataIndex, budget, sparqlRepositoryFactory, sparqlInPlace, instrumentation).use { ctx ->
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
            fun record(results: List<ValidationViolation>) {
                // Strict mode: a constraint that undefined recursion leaves undecided fails the validation. The check
                // is made on the results that are reported, not where they are built: the solver also builds results
                // (recorded evaluations) under answers that are not final yet.
                if (config.strictMode) results.firstOrNull { it.isUndefinedRecursion }?.let { throw ShaclValidationException(it.message) }
                totalResults += results.size
                blockingResults += results.count { blocking(it.severity, it.resultSeverityIri?.let { iri -> Iri(iri) }) }
                for (result in results) {
                    if (violations.size >= config.maxViolations) break
                    violations.add(result)
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

    /**
     * Blank node `sh:targetNode` values. One that is a node of the data graph (the graphs share it: a graph validated
     * against itself, shapes discovered from the data) is an ordinary focus node, whatever its triples look like. One
     * that is not can never be a focus node, and validating it anyway would check the shape against a node without
     * triples (bogus `sh:minCount` results, everything else conforming): in SHACL 1.2 it is a node expression, which
     * the engine does not evaluate. Every such target — plain, list-shaped, described with `sh:` vocabulary or
     * call-shaped — is an unsupported feature, failing or warning per [ValidationConfig.unsupportedFeatures]. Returns
     * the warnings to add to the report.
     */
    private fun checkBlankNodeTargets(compiled: CompiledShapeGraph, data: DataGraphIndex): List<String> {
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

    private fun computeFocusNodes(shape: CompiledNodeShape, ctx: NativeValidationContext, undecidedTargets: MutableList<ValidationViolation>): List<RdfTerm> {
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

    // --- three-valued outcomes ------------------------------------------------------------------------------------

    /**
     * Marks this sink failed, building a validation row only in reporting mode.
     * Conformance-only checks retain the failure without allocating a report row.
     * The caller supplies the row through `build`.
     */
    // Failure rows are lazy in non-reporting modes.

    private inline fun Sink.fail(build: () -> ValidationViolation) {
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

    /** The outcome of a `sh:pattern` constraint on [value] that could not be decided: see [matchPattern]. */
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

    private inline fun Sink.outcome(
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
    private fun undecidableTargetResult(
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

    private fun NodeLogicalPart.constraintType(): ConstraintType =
        when (this) {
            is NodeLogicalPart.And -> ConstraintType.AND
            is NodeLogicalPart.Or -> ConstraintType.OR
            is NodeLogicalPart.Xone -> ConstraintType.XONE
            is NodeLogicalPart.Not -> ConstraintType.NOT
        }

    // --- shape evaluation ---------------------------------------------------------------------------------------------

    internal fun validateNodeShape(
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
    private fun evalLogical(
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

    /** The results of a closed shape; with [firstOnly] (the answer is all that is needed) only the first one. */
    private fun validateClosed(
        focus: RdfResource,
        shape: CompiledNodeShape,
        tpl: ResultTemplate,
        ctx: NativeValidationContext,
        firstOnly: Boolean,
    ): List<ValidationViolation> {
        val allowed = mutableSetOf<Iri>()
        allowed.addAll(shape.ignoredProperties)
        if (shape.closed == ClosedMode.BY_TYPES || RDF.type in shape.ignoredProperties) {
            allowed.add(RDF.type)
        }
        when (shape.closed) {
            // SHACL §4.8.1: the IRIs used as sh:path of every sh:property value (deactivated ones included).
            ClosedMode.TRUE -> allowed.addAll(shape.closedAllowedPredicates)
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
                if (firstOnly) return violations
            }
        }
        return violations
    }

    /**
     * SHACL 1.2 `sh:closed sh:ByTypes` property collection: the predicates declared by the shapes that apply to the
     * types of [focus] — the types themselves when they are shapes (implicit class targets), the shapes that target
     * them with `sh:targetClass`, their `sh:node` references, and the same for every superclass. The walk over the
     * shapes graph uses an explicit worklist and a visited set, so an `rdfs:subClassOf` or `sh:node` chain of any
     * length, or a cycle, cannot exhaust the stack.
     */
    private fun collectClosedByTypesProperties(focus: RdfResource, ctx: NativeValidationContext): Set<Iri> {
        val out = mutableSetOf<Iri>()
        val compiled = ctx.compiled
        val shapesIdx = compiled.index
        val visited = HashSet<RdfResource>()
        val pending = ArrayDeque<RdfResource>()
        fun visit(node: RdfResource) {
            if (visited.add(node)) pending.addLast(node)
        }
        ctx.data.typesOf(focus).forEach(::visit)
        // Shapes by the classes they target, built once per walk (a scan of every shape per class would be quadratic).
        val shapesByTargetClass: Map<Iri, List<RdfResource>> by lazy {
            val byClass = HashMap<Iri, MutableList<RdfResource>>()
            for (cn in compiled.orderedNodeShapes) for (c in cn.targets.targetClasses) byClass.getOrPut(c) { ArrayList() }.add(cn.shapeNode)
            byClass
        }
        while (pending.isNotEmpty()) {
            ctx.budget.tick("closed by types")
            val s = pending.removeFirst()
            compiled.shapesByNode[s]?.let { out.addAll(it.closedAllowedPredicates) }
            val types = shapesIdx.objects(s, RDF.type)
            if (s is Iri && types.contains(RDFS.Class)) {
                for (sup in shapesIdx.objects(s, RDFS.subClassOf)) if (sup is Iri) visit(sup)
                shapesByTargetClass[s]?.forEach(::visit)
            }
            if (types.contains(SHACL.NodeShape)) {
                for (nr in shapesIdx.objects(s, SHACL.node)) if (nr is RdfResource) visit(nr)
            }
        }
        return out
    }

    /**
     * `sh:uniqueValuesFor`: hash-groups targets by their composite key (for each listed property, the set of its values
     * compared as RDF terms; value nodes are sets, so value order is irrelevant). Every
     * target whose key is shared with at least one other target yields exactly one result, without `sh:value`
     * (W3C `uniqueValuesFor-001` … `-005`). The total is counted per group and at most [capacity] result rows are
     * materialized, so large duplicate groups stay linear.
     */
    private fun validateUniqueValuesForShape(
        shape: CompiledNodeShape,
        focusNodes: List<RdfTerm>,
        focusOnly: RdfResource?,
        ctx: NativeValidationContext,
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
            val duplicates = if (focusOnly == null) nodes else nodes.filter { it == focusOnly }
            count += duplicates.size
            for (node in duplicates) {
                ctx.budget.tick("uniqueValuesFor")
                if (rows.size >= capacity) break
                rows.add(
                    violation(
                        node, tpl, constraintStub(ConstraintType.UNIQUE_VALUES_FOR),
                        "sh:uniqueValuesFor: composite key shared with ${nodes.size - 1} other target(s)",
                    ),
                )
            }
        }
        return rows to count
    }

    private fun evaluateConstraintsForValues(
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

    /** Evaluates the constraint [c] on the value nodes [values] of [focus]. */
    /**
     * One `sh:pattern` evaluation, bounded by [ValidationConfig.patternTimeout].
     *
     * **Budget.** The evaluation may take [patternStepBudget] steps of the regular expression engine (character
     * reads, [DeadlineCharSequence]): the configured duration at 50,000 steps per millisecond. Steps are counted, not
     * time, so whether a value is decided does not depend on the machine, its load or a garbage collection pause.
     * The wall clock is only a backstop, at [PATTERN_WALL_CLOCK_FACTOR] times the configured duration.
     *
     * **Stack.** `java.util.regex` matches an alternation under a quantifier recursively (one group of frames per
     * repetition), so a long value can exhaust the stack, and whether it does depends on the stack that is left
     * where the match starts. Values of [PATTERN_INLINE_MAX_LENGTH] characters or more are therefore matched on the
     * run's pattern evaluation thread ([PatternWorker]), whose stack has a fixed size
     * ([PATTERN_WORKER_STACK_BYTES]): the outcome is the same at any shape nesting depth and for any stack size of
     * the validating thread. Shorter values are matched on the validating thread (a thread hand-over per value would
     * dominate the cost of ordinary patterns); if that overflows, the match is repeated on the evaluation thread,
     * so it is still the fixed stack that decides. Every outcome of the evaluation thread is remembered for the run.
     *
     * A value on which the pattern uses up its budget, or exhausts the stack of the evaluation thread, has no
     * answer. That is not an error of the run: one slow or hostile literal must not prevent the validation of
     * everything else. The constraint is **undecided** for that value ([PatternOutcome.undecided]): the caller
     * reports a result naming the pattern, marked `ksh:PatternTimeout` / `ksh:PatternTooComplex`, which blocks
     * conformance as a failure would (the value is not accepted), and a shape that reads the answer through `sh:not`,
     * `sh:or`… gets "undefined", so an undecided pattern can neither satisfy nor silently fail an outer constraint.
     * Validation then goes on. With [ValidationConfig.strictMode] it fails instead, as for undefined recursion. The
     * run-wide [ValidationConfig.timeout] is still enforced during the match and aborts the run.
     *
     * An undecided answer is remembered for the run, so the same value is matched (and the budget spent) once.
     *
     * **Run-wide cap.** The budget above is per value, so N distinct hostile values would cost N budgets. After
     * [PATTERN_MAX_FULL_BUDGET_EXHAUSTIONS] evaluations of a run have used up their whole budget, every value that has
     * not been matched yet gets a reduced budget ([reducedPatternSteps]): 1/[PATTERN_DEGRADED_STEP_DIVISOR] of the full
     * one, but at least [PATTERN_MIN_DEGRADED_STEPS] steps and at least [PATTERN_DEGRADED_STEPS_PER_CHAR] steps per
     * character of the value, and never more than the full budget. A pattern that does not backtrack reads each
     * character a few times, so an ordinary value of any length is still decided; a hostile one is undecided at a cost
     * linear in its length. Data without hostile values never reaches the cap, so it is evaluated as before.
     *
     * **One answer per value.** Within a run, a (pattern, value) has one answer, whoever asks and whenever (a
     * conformance check through `sh:node` or `sh:not`, the report of a focus node, another solver pass): every
     * outcome that could differ under the reduced budget, i.e. every undecided one and every decided one that took
     * at least the reduced budget of its value, is remembered for the run. A cheaper evaluation is not remembered:
     * the engine is deterministic, so it gives the same answer under either budget. The remembered entries refer to
     * the lexical forms of the data and each stands for at least [PATTERN_MIN_DEGRADED_STEPS] steps of work, so
     * their number is bounded by the run's work. Whether an expensive value (more than its reduced budget, within
     * the full one) is decided can still depend on whether it is first matched before or after the cap is reached,
     * i.e. on the order of evaluation, which for a given shapes graph and data graph is fixed.
     */
    internal fun matchPattern(c: PropertyConstraint.Pattern, lexical: String, shape: RdfResource, ctx: NativeValidationContext): PatternOutcome {
        if (ctx.patternOutcomes.isNotEmpty()) ctx.patternOutcomes[c to lexical]?.let { return it }
        ctx.counters.patternEvaluations++
        // Run-wide cap: once PATTERN_MAX_FULL_BUDGET_EXHAUSTIONS evaluations used up their whole budget, a value not
        // matched before only gets a small fraction of it, so many distinct hostile values cost a bounded total.
        val reduced = reducedPatternSteps(lexical.length)
        val steps = if (ctx.patternBudgetExhaustions < PATTERN_MAX_FULL_BUDGET_EXHAUSTIONS) patternStepBudget else reduced
        val stepsBefore = ctx.counters.patternSteps
        var result = if (lexical.length < ctx.patternInlineMaxLength) runPattern(c, lexical, ctx, steps) else null
        val onWorker = result == null || result == PatternRun.OUT_OF_STACK
        if (onWorker) {
            ctx.counters.patternWorkerEvaluations++
            result = ctx.patternWorker().run { runPattern(c, lexical, ctx, steps) }
        }
        if (result == PatternRun.OUT_OF_BUDGET) ctx.patternBudgetExhaustions++
        val outcome =
            when (result) {
                PatternRun.MATCH -> PatternOutcome.MATCH
                PatternRun.NO_MATCH -> PatternOutcome.NO_MATCH
                PatternRun.OUT_OF_BUDGET -> PatternOutcome(
                    false,
                    Undecided(
                        ValidationViolation.PATTERN_TIMEOUT_CODE,
                        "${describe(c, shape)} used up its evaluation budget (ValidationConfig.patternTimeout = " +
                            "${config.patternTimeout}: $patternStepBudget regular expression engine steps, or " +
                            "$PATTERN_WALL_CLOCK_FACTOR times that duration; $reduced steps for this value once " +
                            "${PATTERN_MAX_FULL_BUDGET_EXHAUSTIONS} evaluations of the run have used up the whole budget; " +
                            "this evaluation had $steps) on a value of ${lexical.length} characters, so " +
                            "whether the value matches is unknown and it is not accepted; the pattern probably backtracks " +
                            "catastrophically (nested or overlapping quantifiers)",
                    ),
                )
                PatternRun.OUT_OF_STACK -> PatternOutcome(
                    false,
                    Undecided(
                        ValidationViolation.PATTERN_TOO_COMPLEX_CODE,
                        "${describe(c, shape)} could not be evaluated on a value of ${lexical.length} characters: the regular " +
                            "expression engine ran out of stack, so whether the value matches is unknown and it is not accepted; " +
                            "an alternation under a quantifier such as (a|b)* recurses once per repetition - rewrite it as a " +
                            "character class ([ab]*) or bound the value with sh:maxLength",
                    ),
                )
            }
        val undecided = outcome.undecided
        if (undecided != null && config.strictMode) throw ShaclValidationException(undecided.reason)
        // Remember every outcome another budget could change: one value, one answer for the whole run.
        if (undecided != null || onWorker || ctx.counters.patternSteps - stepsBefore >= reduced) {
            ctx.patternOutcomes[c to lexical] = outcome
        }
        return outcome
    }

    /**
     * The step budget of a value of [length] characters once the run-wide cap is reached: a fraction of the full
     * budget, but never less than a linear allowance for the value, and never more than the full budget.
     */
    private fun reducedPatternSteps(length: Int): Long {
        val linear = if (length > Long.MAX_VALUE / PATTERN_DEGRADED_STEPS_PER_CHAR) Long.MAX_VALUE else length * PATTERN_DEGRADED_STEPS_PER_CHAR
        return maxOf(patternStepBudget / PATTERN_DEGRADED_STEP_DIVISOR, PATTERN_MIN_DEGRADED_STEPS, linear).coerceAtMost(patternStepBudget)
    }

    /**
     * Matches [c] against [lexical] on the current thread. A stack overflow is caught here: the stack is unwound up
     * to this frame, so it is safe to go on; only the match is abandoned.
     */
    private fun runPattern(c: PropertyConstraint.Pattern, lexical: String, ctx: NativeValidationContext, steps: Long): PatternRun {
        val evaluation = ctx.patternBudget(steps, patternBackstopNanos)
        try {
            return if (c.regex.containsMatchIn(ctx.text(lexical, evaluation))) PatternRun.MATCH else PatternRun.NO_MATCH
        } catch (e: PatternBudgetExceeded) {
            return PatternRun.OUT_OF_BUDGET
        } catch (e: StackOverflowError) {
            return PatternRun.OUT_OF_STACK
        } finally {
            ctx.counters.patternSteps += evaluation.steps
        }
    }

    private fun describe(c: PropertyConstraint.Pattern, shape: RdfResource): String {
        val flags = c.flags?.let { " (sh:flags \"$it\")" }.orEmpty()
        return "sh:pattern \"${c.pattern}\"$flags of shape ${shape.displayId()}"
    }

    /**
     * SHACL-SPARQL: one result per solution; `?value`, `?path`, `?message` and `?failure` are honoured. Pre-bound
     * variables are substituted by the engine ([com.geoknoesis.kastor.rdf.shacl.native.SparqlQueryTemplate]), so
     * results do not depend on the SPARQL provider. A `?message` binding takes precedence over the constraint's
     * `sh:message`, which takes precedence over the shape's (SHACL-SPARQL result mapping). With [firstOnly] (a
     * conformance check, which only needs to know whether there is a solution) only the first result is built, and
     * only the first solution is read unless the query can bind `?failure`.
     */
    internal fun evaluateSparql(
        focus: RdfTerm,
        tpl: ResultTemplate,
        c: PropertyConstraint.Sparql,
        ctx: NativeValidationContext,
        currentShape: RdfResource,
        path: ShaclPath?,
        firstOnly: Boolean,
    ): List<ValidationViolation> {
        val preBound = LinkedHashMap<String, RdfTerm>()
        preBound["this"] = focus
        if ("currentShape" in c.preBound) preBound["currentShape"] = currentShape
        if ("shapesGraph" in c.preBound) preBound["shapesGraph"] = SparqlConstraintEvaluator.SHAPES_GRAPH_IRI
        val (query, providerBindings) = c.template.bind(preBound)
        val out = mutableListOf<ValidationViolation>()
        // The solutions are streamed, never collected. When only the answer is needed, the first solution gives it;
        // a query that can bind ?failure is still read to the end (a ?failure solution anywhere makes the check a
        // failure, nested or not), without building results; one that cannot stops at the first solution.
        ctx.select(query, providerBindings) { rows ->
            for (row in rows) {
                val failure = row.get("failure")
                if (failure != null && isLexicallyTrue(failure)) {
                    throw ShaclValidationException("SPARQL constraint ${c.constraintNode.displayId()} reported ?failure for focus node $focus")
                }
                // Only a query that can bind ?failure gets here after its first result: it is scanned, not built.
                if (firstOnly && out.isNotEmpty()) continue
                val value = row.get("value") ?: if (path == null) focus else null
                val rowPath = (row.get("path") as? Iri)?.let { ReportPath(listOf(it), null, emptyList(), it) } ?: tpl.path
                fun substitute(lit: Literal): Literal {
                    val text = messagePlaceholder.replace(lit.lexical) { m ->
                        val term = row.get(m.groupValues[1]) ?: preBound[m.groupValues[1]]
                        term?.let { displayTerm(it) } ?: m.value
                    }
                    return if (lit is LangString) lit.copy(lexical = text) else TypedLiteral(text, lit.datatype)
                }
                val messages =
                    listOfNotNull(row.get("message") as? Literal).ifEmpty { c.messages }.ifEmpty { tpl.messages }.map { substitute(it) }
                out.add(
                    violation(
                        focus = focus,
                        tpl = tpl,
                        constraint = constraintStub(ConstraintType.SPARQL_CONSTRAINT, rowPath?.predicate),
                        message = "SPARQL constraint ${c.constraintNode.displayId()} returned a solution",
                        value = value,
                        // SHACL-SPARQL: a sh:severity on the SPARQL-based constraint is the result severity.
                        severity = c.severity ?: tpl.severity,
                        severityIri = if (c.severity != null) c.severityCustomIri else tpl.severityCustomIri,
                        messages = messages,
                        sourceConstraint = c.constraintNode,
                        path = rowPath,
                    ),
                )
                if (firstOnly && !c.template.mayBindFailure) break
            }
        }
        return out
    }

    private fun reportPath(ps: CompiledPropertyShape, ctx: NativeValidationContext): ReportPath =
        ctx.reportPaths.getOrPut(ps.shapeNode) {
            ReportPath(pathToTerms(ps.path), ps.pathNode.takeIf { it is BlankNode }, ps.pathTriples, (ps.path as? ShaclPath.Predicate)?.iri)
        }

    internal fun validatePropertyShape(
        focus: RdfTerm,
        ps: CompiledPropertyShape,
        ctx: NativeValidationContext,
        state: DepthState,
        sink: Sink,
    ) {
        if (ps.deactivated) return
        ctx.counters.shapeEvaluations++
        // Value nodes are a set (SHACL §2.3.2); the path evaluator returns distinct nodes.
        val values = PathEvaluator.evaluate(focus, ps.path, ctx.data)
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

    private fun validateReifierPropertyConstraints(
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


}
