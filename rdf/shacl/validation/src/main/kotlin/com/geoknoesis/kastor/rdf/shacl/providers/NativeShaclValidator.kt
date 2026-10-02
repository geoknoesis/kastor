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
import com.geoknoesis.kastor.rdf.shacl.native.OwlImportsExpander
import com.geoknoesis.kastor.rdf.shacl.native.PathEvaluator
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint
import com.geoknoesis.kastor.rdf.shacl.native.graphFromTriples
import com.geoknoesis.kastor.rdf.shacl.native.orderViolations
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
import com.geoknoesis.kastor.rdf.shacl.native.shaclRdfTermHash
import com.geoknoesis.kastor.rdf.shacl.native.distinctShaclTerms
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
 *   depends on an undefined answer. Results therefore never depend on operand, constraint or target order.
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
 */
internal class NativeShaclValidator(
    private val config: ValidationConfig,
    private val sparqlRepositoryFactory: () -> com.geoknoesis.kastor.rdf.RdfRepository = SparqlConstraintEvaluator.defaultRepositoryFactory,
) : ShaclValidator, com.geoknoesis.kastor.rdf.shacl.ShapeCacheControl {

    private companion object {
        val singleLineBreakRegex = Regex("[\\f\\r\\n\\u000B]")
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

        /** Stack size of the pattern evaluation thread of a run (see [matchPattern]). */
        const val PATTERN_WORKER_STACK_BYTES = 16L * 1024 * 1024

        /** Values at least this long (UTF-16 code units) are matched on the pattern evaluation thread. */
        const val PATTERN_INLINE_MAX_LENGTH = 1024
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
     * (O(n) `equals`, no canonicalization/sort/SHA-256). Any mutation of the shapes graph changes the snapshot and
     * therefore misses — a stale digest can never be reused. Snapshots whose triple order differs simply miss. There is
     * deliberately no identity fast path (same graph object): without a modification stamp in the RdfGraph API it
     * could return the digest of a graph mutated since.
     * Bounded to [DIGEST_MEMO_CAPACITY] entries (each retains a copy of the triple list, not the graph) and emptied
     * by [clearCache].
     */
    private val digestMemo = object : LinkedHashMap<List<RdfTriple>, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<RdfTriple>, String>?): Boolean = size > DIGEST_MEMO_CAPACITY
    }
    @Volatile internal var digestMemoHits = 0L
        private set

    /** Test instrumentation: node and property shape evaluations performed by this validator. */
    @Volatile internal var shapeEvaluations = 0L

    /** Test instrumentation: restarts of the recursion solver caused by a read its recording had not registered. */
    @Volatile internal var solverRestarts = 0L

    /**
     * Test seam simulating an incomplete dependency recording: a recursive read of (node, shape) for which this
     * returns true is answered by the recording pass but not registered. Never set in production code.
     */
    @Volatile internal var dropRecordedRead: ((RdfTerm, RdfResource) -> Boolean)? = null

    /**
     * Test seam: the clock of the wall-clock backstop of the per-pattern budget ([ValidationConfig.patternTimeout]),
     * a `System.nanoTime`-like source. Tests inject a deterministic clock so that they do not depend on wall-clock
     * time. Never set in production code.
     */
    @Volatile internal var patternClock: () -> Long = System::nanoTime

    /**
     * Test seam: the clock of the run budget ([ValidationConfig.timeout]). A test that injects a clock which advances
     * by one each time it is consulted turns the timeout into a bound on the work of a run (the engine consults the
     * budget in every loop), independent of the machine. Never set in production code.
     */
    @Volatile internal var budgetClock: () -> Long = System::nanoTime

    /** Test instrumentation: `sh:pattern` evaluations actually run against the regular expression engine. */
    @Volatile internal var patternEvaluations = 0L

    /** Test instrumentation: `sh:pattern` evaluations run on the pattern evaluation thread. */
    @Volatile internal var patternWorkerEvaluations = 0L

    /** Test seam: stack size of the pattern evaluation thread. Never set in production code. */
    @Volatile internal var patternWorkerStackBytes: Long = PATTERN_WORKER_STACK_BYTES

    /**
     * Test seam: values shorter than this are first matched on the validating thread. Never set in production code.
     */
    @Volatile internal var patternInlineMaxLength: Int = PATTERN_INLINE_MAX_LENGTH

    private fun digestOf(triples: List<RdfTriple>, budget: ValidationBudget): String {
        synchronized(digestMemo) {
            digestMemo[triples]?.let { digestMemoHits++; return it }
        }
        val digest = ShapesStructuralDigest.digest(triples, config, budget)
        synchronized(digestMemo) { digestMemo[ArrayList(triples)] = digest }
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

    /**
     * How a shape is evaluated: [REPORT] materializes every result; [CONFORMS] only needs the three-valued answer and
     * stops at the first definite failure; [EXHAUSTIVE] also only needs the answer but evaluates every constraint and
     * operand, so that the recursive questions it reads do not depend on the answers it receives.
     */
    private enum class Mode { REPORT, CONFORMS, EXHAUSTIVE }

    /** [depth] counts nested checks of non-recursive shapes. */
    private data class DepthState(val depth: Int, val mode: Mode) {
        fun nested() = DepthState(depth + 1, Mode.CONFORMS)
    }

    /** Three-valued conformance, declared in truth order (FAILS < UNDEFINED < CONFORMS). */
    private enum class Conformance { FAILS, UNDEFINED, CONFORMS }

    /**
     * Why a constraint could not be decided for a reason other than undefined recursion: a `sh:pattern` evaluation
     * that used up its budget or exhausted the stack. [code] is the [ValidationViolation.violationCode] of the results
     * it causes and [reason] names the pattern.
     */
    private class Undecided(val code: String, val reason: String)

    /**
     * Why a conformance answer is undefined: undefined [recursion], an undecided [pattern], or both. A question that
     * is undefined without an entry in [ValidationContext.undefined] is undefined by recursion only.
     */
    private class UndefinedCause(val recursion: Boolean, val pattern: Undecided?)

    /** Outcome of one `sh:pattern` evaluation: an answer, or the reason why there is none. */
    private class PatternOutcome(val matches: Boolean, val undecided: Undecided?) {
        companion object {
            val MATCH = PatternOutcome(true, null)
            val NO_MATCH = PatternOutcome(false, null)
        }
    }

    /** Outcome of one shape evaluation: results in [Mode.REPORT], otherwise only the three-valued answer. */
    private class Sink(val mode: Mode) {
        val results = ArrayList<ValidationViolation>()
        var failed = false
        var undefined = false
        /** First cause of [undefined] that is not undefined recursion (an undecided pattern), if any. */
        var cause: Undecided? = null
        /** Whether [undefined] is (also) caused by undefined recursion. */
        var recursion = false
        /** Only [Mode.CONFORMS] stops early, and only on a definite failure (Kleene conjunction). */
        val stop: Boolean get() = failed && mode == Mode.CONFORMS
        val exhaustive: Boolean get() = mode == Mode.EXHAUSTIVE
        fun conformance(): Conformance =
            when {
                failed -> Conformance.FAILS
                undefined -> Conformance.UNDEFINED
                else -> Conformance.CONFORMS
            }
    }

    /** RDF term under SHACL term equality: a structured memo key (no fingerprint string per conformance check). */
    private class TermKey(val term: RdfTerm) {
        private val hash = shaclRdfTermHash(term)
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = other is TermKey && other.hash == hash && shaclRdfTermEquals(term, other.term)
    }

    /** A conformance question: (value node, shape). */
    private data class AtomKey(val node: TermKey, val shape: RdfResource)

    private class Dependency(val key: AtomKey, val node: RdfTerm, val shape: RdfResource, val negative: Boolean)

    private class Atom(val node: RdfTerm, val shape: RdfResource) {
        /** Questions of the same component read by the recorded evaluation (observed, not predicted). */
        var dependencies: List<Dependency> = emptyList()
        /** Answer of the recorded evaluation, in which every recursive read was answered "conforms". */
        var optimistic = Conformance.CONFORMS
        var value = Conformance.CONFORMS
        var resolved = false
        /** Set while the question belongs to the group being evaluated (reads return [value]). */
        var inGroup = false
        /** While a group with negative dependencies is refined, reads of its members answer undefined. */
        var readUndefined = false
        /** [RecursionSolver.epoch] at which the question was settled (meaningful once [resolved]). */
        var settledAt = 0L
        /** [RecursionSolver.epoch] of the last refinement evaluation that answered undefined; -1 before the first. */
        var refinedAt = -1L
        /**
         * Reads of registered but unsettled questions that the recording of this question missed. They are kept across
         * solver restarts and added to the recorded dependencies, so the next dependency graph orders them correctly.
         */
        val missedReads = ArrayList<Dependency>()
    }

    /** State of one recursive-component solve (see class KDoc). */
    private class RecursionSolver(val component: Int) {
        val atoms = HashMap<AtomKey, Atom>()
        /** Non-null while a question is recorded: reads answer "conforms" and are appended here. */
        var recording: ArrayList<Dependency>? = null
        /** Set when an evaluation reads a question the recording did not register (the solve restarts). */
        var restart = false
        /** New questions or missed dependencies found since the last restart (each restart makes progress). */
        var discovered = 0
        /** Question whose evaluation is under way in step 2 (receives the dependencies its recording missed). */
        var evaluating: Atom? = null
        /** Advanced each time a question is settled, so refinement re-evaluates only questions whose inputs changed. */
        var epoch = 0L

        fun settle(atom: Atom, value: Conformance) {
            atom.value = value
            atom.resolved = true
            atom.inGroup = false
            atom.readUndefined = false
            atom.settledAt = ++epoch
        }
    }

    /** Thrown out of a regex match whose per-evaluation budget ([ValidationConfig.patternTimeout]) is used up. */
    private class PatternBudgetExceeded : RuntimeException(null, null, false, false)

    /**
     * The budget of one pattern evaluation: [limit] steps of the regular expression engine, and the instant
     * [deadlineNanos] of the wall-clock backstop.
     */
    private class PatternBudget(val limit: Long, val deadlineNanos: Long) {
        var steps = 0L
    }

    /**
     * A CharSequence for regex matching that counts the character reads of the regular expression engine (its
     * **steps**: a backtracking match reads characters all the time) and stops the match with
     * [PatternBudgetExceeded] when the evaluation has used up its [PatternBudget]. The steps are what is counted, so
     * whether an evaluation is stopped does not depend on the speed or the load of the machine. Every 1024 steps it
     * also consults the run deadline and the wall-clock backstop of the evaluation (an instant of [clock]).
     */
    private class DeadlineCharSequence(
        private val value: String,
        private val budget: ValidationBudget,
        private val clock: () -> Long,
        private val evaluation: PatternBudget,
    ) : CharSequence {
        override val length: Int get() = value.length
        override fun get(index: Int): Char {
            val steps = ++evaluation.steps
            if ((steps and 1023L) == 0L) {
                if (steps >= evaluation.limit) throw PatternBudgetExceeded()
                budget.check("pattern matching")
                if (clock() - evaluation.deadlineNanos >= 0) throw PatternBudgetExceeded()
            }
            return value[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            DeadlineCharSequence(value.substring(startIndex, endIndex), budget, clock, evaluation)
        override fun toString(): String = value
    }

    /**
     * The pattern evaluation thread of one validation run: a single daemon thread with a fixed stack size, created
     * when the first evaluation needs it and stopped when the run ends. `java.util.regex` matches an alternation
     * under a quantifier recursively, so whether such a match completes depends on the stack that is left; on this
     * thread that is always the same amount, whatever the stack size of the validating thread and however deeply
     * nested the shape that asks.
     */
    private class PatternWorker(stackBytes: Long) : AutoCloseable {
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(null, task, "kastor-shacl-pattern", stackBytes).apply { isDaemon = true }
        }

        /** Runs [task] on the thread and waits for it; its exceptions are rethrown here. */
        fun <T> run(task: () -> T): T {
            val future = executor.submit(java.util.concurrent.Callable { task() })
            try {
                return future.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            } catch (e: InterruptedException) {
                // The validating thread was cancelled: stop the match (it checks its interrupt flag) and fail the run.
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw ShaclValidationException("SHACL pattern matching timed out or was cancelled")
            }
        }

        override fun close() {
            executor.shutdownNow()
        }
    }

    /** Result of running one pattern against one value. */
    private enum class PatternRun { MATCH, NO_MATCH, OUT_OF_BUDGET, OUT_OF_STACK }

    private class ValidationContext(
        val compiled: CompiledShapeGraph,
        val data: DataGraphIndex,
        val budget: ValidationBudget,
        private val repositoryFactory: () -> com.geoknoesis.kastor.rdf.RdfRepository,
        private val sparqlInPlace: SparqlQueryable?,
        /** Clock of the wall-clock backstop of the per-pattern budget (`System.nanoTime` unless a test injects one). */
        private val patternClock: () -> Long,
        private val patternWorkerStackBytes: Long,
    ) : AutoCloseable {
        val memo = HashMap<AtomKey, Conformance>()
        /**
         * Why a conformance question was last answered "undefined", when an undecided pattern is (one of) the
         * cause(s): the result of the constraint that reads the answer reports the cause and its status. The entry is
         * rewritten by every evaluation of the question, so it describes the evaluation that gave the final answer; a
         * question that is undefined without an entry is undefined by recursion only.
         */
        val undefined = HashMap<AtomKey, UndefinedCause>()
        /**
         * Pattern evaluations of this run that are remembered, per (pattern constraint, lexical form): every
         * undecided one, and every one that ran on the pattern evaluation thread. The answer for such a value is the
         * same every time it is asked, and its budget is spent once per value rather than once per focus node or
         * solver pass.
         */
        val patternOutcomes = HashMap<Pair<PropertyConstraint.Pattern, String>, PatternOutcome>()
        private var patternWorker: PatternWorker? = null
        fun patternWorker(): PatternWorker = patternWorker ?: PatternWorker(patternWorkerStackBytes).also { patternWorker = it }
        /** Solver of the recursive component currently being evaluated (routes references into that component). */
        var solver: RecursionSolver? = null
        val reportPaths = HashMap<RdfResource, ReportPath>()
        private val session = lazy {
            SparqlConstraintEvaluator.Session(
                data.graph,
                if (compiled.sparqlUsesShapesGraph) compiled.index.triples else emptyList(),
                repositoryFactory,
                sparqlInPlace,
            )
        }
        fun checkDeadline() {
            budget.check()
        }
        fun select(query: String, bindings: Map<String, RdfTerm>) =
            session.value.select(query, bindings, Duration.ofNanos(budget.remainingNanos()))
        /** [value] for one pattern evaluation of at most [steps] steps that may last at most [backstopNanos]. */
        fun text(value: String, steps: Long, backstopNanos: Long): CharSequence {
            val now = patternClock()
            // Saturating: a huge budget must not overflow into the past.
            val deadline = if (backstopNanos >= Long.MAX_VALUE - now) Long.MAX_VALUE else now + backstopNanos
            return DeadlineCharSequence(value, budget, patternClock, PatternBudget(steps, deadline))
        }
        override fun close() {
            try {
                patternWorker?.close()
            } finally {
                if (session.isInitialized()) session.value.close()
            }
        }
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
    ): ValidationReport {
        val budget = ValidationBudget(config.timeout, budgetClock)
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
                prepareMergedShapesTriples(graph, shapes, datasetForDiscovery, budget, importWarnings)
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
        ValidationContext(compiled, dataIndex, budget, sparqlRepositoryFactory, sparqlInPlace, patternClock, patternWorkerStackBytes).use { ctx ->
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
                totalResults += results.size
                blockingResults += results.count { blocking(it.severity, it.resultSeverityIri?.let { iri -> Iri(iri) }) }
                for (result in results) {
                    if (violations.size >= config.maxViolations) break
                    violations.add(result)
                }
            }

            for (shape in compiled.orderedNodeShapes) {
                ctx.checkDeadline()
                val undecidedTargets = ArrayList<ValidationViolation>()
                val allFocusNodes = computeFocusNodes(shape, ctx, undecidedTargets)
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
                // The solver answers every question of a recursive shape's component at once; a focus node it found
                // conforming has no results, so its report evaluation is skipped. A focus node it found failing or
                // undefined is evaluated once more, in report mode: the solver's own evaluations cannot produce the
                // results, because they run before the answers they read are final (the recording pass assumes
                // "conforms") and stop at the first failure afterwards. The report pass re-runs only this shape's own
                // constraints; every nested conformance check is a memo hit.
                val recursive = shape.shapeNode in compiled.recursiveComponents &&
                    compiled.referencedNodeShapes[shape.shapeNode] === shape && shape.shapeNode !in compiled.referencedPropertyShapes
                for (focus in focusNodes) {
                    ctx.checkDeadline()
                    if (recursive && conformance(focus, shape.shapeNode, ctx, DepthState(0, Mode.CONFORMS)) == Conformance.CONFORMS) continue
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

    private fun prepareMergedShapesTriples(
        data: RdfGraph,
        shapesArg: RdfGraph,
        datasetForDiscovery: Dataset?,
        budget: ValidationBudget,
        importWarnings: MutableCollection<String>,
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
                    OwlImportsExpander.expand(
                        graphFromTriples(primary, budget), config.imports, aux, config.maxCombinedGraphTriples, budget, importWarnings,
                    ),
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

    private fun computeFocusNodes(shape: CompiledNodeShape, ctx: ValidationContext, undecidedTargets: MutableList<ValidationViolation>): List<RdfTerm> {
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
    private fun targetWhereCandidates(tw: RdfResource, ctx: ValidationContext): Collection<RdfTerm> {
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

    // --- conformance checks and recursion --------------------------------------------------------------------------

    /**
     * Whether [value] conforms to the shape [ref] (no validation results of any severity), three-valued. Deactivated
     * and constraint-free shapes are conformed to by every node. [negative] marks a read through a non-monotone
     * operator (recorded as the polarity of a recursive dependency).
     */
    private fun conformance(value: RdfTerm, ref: RdfResource, ctx: ValidationContext, state: DepthState, negative: Boolean = false): Conformance {
        ctx.checkDeadline()
        val compiled = ctx.compiled
        val propertyShape = compiled.referencedPropertyShapes[ref]
        val nodeShape = if (propertyShape == null) compiled.referencedNodeShapes[ref] else null
        if (propertyShape == null && nodeShape == null) return Conformance.CONFORMS
        if (propertyShape?.deactivated == true || nodeShape?.deactivated == true) return Conformance.CONFORMS
        val key = AtomKey(TermKey(value), ref)
        ctx.memo[key]?.let { return it }
        val component = compiled.recursiveComponents[ref]
        if (component != null) {
            val active = ctx.solver
            if (active != null && active.component == component) return readInSolver(active, key, value, ref, negative)
            return solve(key, value, ref, component, ctx, state)
        }
        val next = state.nested()
        if (next.depth > config.maxRecursionDepth) {
            throw ShaclValidationException(
                "SHACL shape nesting exceeded ValidationConfig.maxRecursionDepth=${config.maxRecursionDepth} at shape ${ref.displayId()}",
            )
        }
        val result = evaluateConformance(value, ref, ctx, next)
        ctx.memo[key] = result
        return result
    }

    /** Evaluates every constraint of [ref] for [value] in the (non-report) mode of [state]. */
    private fun evaluateConformance(value: RdfTerm, ref: RdfResource, ctx: ValidationContext, state: DepthState): Conformance {
        val sink = Sink(state.mode)
        val propertyShape = ctx.compiled.referencedPropertyShapes[ref]
        if (propertyShape != null) {
            validatePropertyShape(value, propertyShape, ctx, state, sink)
        } else {
            ctx.compiled.referencedNodeShapes[ref]?.let { validateNodeShape(value, it, ctx, state, sink) }
        }
        val answer = sink.conformance()
        rememberCause(AtomKey(TermKey(value), ref), sink, answer, ctx)
        return answer
    }

    /**
     * Records why the question [key] was just answered [answer] by the evaluation in [sink]: when the answer is
     * undefined and an undecided pattern is among the causes, the pattern and whether undefined recursion is a cause
     * too. Any other answer clears the entry of an earlier evaluation (a solver pass under other assumptions), so a
     * cause never outlives the answer it explains.
     */
    private fun rememberCause(key: AtomKey, sink: Sink, answer: Conformance, ctx: ValidationContext) {
        val pattern = sink.cause
        if (answer == Conformance.UNDEFINED && pattern != null) {
            ctx.undefined[key] = UndefinedCause(sink.recursion, pattern)
        } else if (ctx.undefined.isNotEmpty()) {
            ctx.undefined.remove(key)
        }
    }

    /**
     * Whether the undefined answer of ([node], [shape]) should be reported instead of the one of [current] (null:
     * none yet) by a constraint that depends on several undefined answers. Undefined recursion is preferred to an
     * undecided pattern, and the first one in evaluation order among equals, so the reported status does not depend
     * on the order of the operands.
     */
    private fun prefersUndefined(ctx: ValidationContext, current: Pair<RdfTerm, RdfResource>?, node: RdfTerm, shape: RdfResource): Boolean {
        if (current == null) return true
        if (ctx.undefined.isEmpty()) return false
        fun byRecursion(n: RdfTerm, s: RdfResource) = ctx.undefined[AtomKey(TermKey(n), s)]?.recursion ?: true
        return !byRecursion(current.first, current.second) && byRecursion(node, shape)
    }

    private fun readInSolver(solver: RecursionSolver, key: AtomKey, value: RdfTerm, ref: RdfResource, negative: Boolean): Conformance {
        solver.recording?.let { reads ->
            if (dropRecordedRead?.invoke(value, ref) != true) reads.add(Dependency(key, value, ref, negative))
            return Conformance.CONFORMS
        }
        val atom = solver.atoms[key]
        if (atom == null || (!atom.resolved && !atom.inGroup)) {
            // Fail safe: a question the recording did not register, or a dependency it did not record (so the
            // question may not be settled yet), is never answered by default; the solve restarts with it recorded.
            solver.restart = true
            if (atom == null) {
                solver.atoms[key] = Atom(value, ref)
                solver.discovered++
            } else {
                val reader = solver.evaluating
                if (reader != null && reader.missedReads.none { it.key == key && it.negative == negative }) {
                    reader.missedReads.add(Dependency(key, value, ref, negative))
                    solver.discovered++
                }
            }
            return Conformance.UNDEFINED
        }
        return if (atom.readUndefined) Conformance.UNDEFINED else atom.value
    }

    /** Answers the question [rootKey] on a shape of the recursive [component]; see the class KDoc. */
    private fun solve(rootKey: AtomKey, rootNode: RdfTerm, rootShape: RdfResource, component: Int, ctx: ValidationContext, state: DepthState): Conformance {
        val solver = RecursionSolver(component)
        val savedSolver = ctx.solver
        try {
            ctx.solver = solver
            solver.atoms[rootKey] = Atom(rootNode, rootShape)
            var toRecord: Collection<AtomKey> = listOf(rootKey)
            while (true) {
                recordDependencies(solver, toRecord, ctx, state)
                evaluateGroups(solver, rootKey, ctx, state)
                if (!solver.restart) break
                if (solver.discovered == 0) {
                    // Unreachable: every restart registers a new question or a new missed dependency.
                    throw ShaclValidationException(
                        "Internal error in the SHACL recursion solver: an unsettled question was read again after its " +
                            "dependency had been recorded (shape ${rootShape.displayId()}); please report this shapes graph",
                    )
                }
                // The recorded dependency graph was incomplete: record every question again and re-evaluate.
                solverRestarts++
                solver.restart = false
                solver.discovered = 0
                for (atom in solver.atoms.values) {
                    atom.resolved = false
                    atom.inGroup = false
                    atom.readUndefined = false
                    atom.refinedAt = -1L
                    atom.dependencies = emptyList()
                }
                toRecord = solver.atoms.keys.toList()
            }
            for ((key, atom) in solver.atoms) if (atom.resolved) ctx.memo[key] = atom.value
            return solver.atoms.getValue(rootKey).value
        } finally {
            ctx.solver = savedSolver
        }
    }

    /** Step 1: evaluates each question once with recursive reads answered "conforms", recording those reads. */
    private fun recordDependencies(solver: RecursionSolver, start: Collection<AtomKey>, ctx: ValidationContext, state: DepthState) {
        val recordingState = DepthState(state.depth, Mode.EXHAUSTIVE)
        val recorded = HashSet<AtomKey>()
        val pending = ArrayDeque(start)
        while (pending.isNotEmpty()) {
            ctx.checkDeadline()
            val key = pending.removeLast()
            if (!recorded.add(key)) continue
            val atom = solver.atoms.getValue(key)
            val reads = ArrayList<Dependency>()
            val saved = solver.recording
            solver.recording = reads
            val result =
                try {
                    evaluateConformance(atom.node, atom.shape, ctx, recordingState)
                } finally {
                    solver.recording = saved
                }
            atom.optimistic = result
            reads.addAll(atom.missedReads)
            if (reads.isEmpty() || (result == Conformance.FAILS && reads.none { it.negative })) {
                // No recursive read, or failing although every (positive) read conformed: decided by monotonicity.
                solver.settle(atom, result)
                continue
            }
            atom.dependencies = reads
            for (dependency in reads) {
                if (dependency.key in recorded) continue
                if (dependency.key !in solver.atoms) solver.atoms[dependency.key] = Atom(dependency.node, dependency.shape)
                pending.add(dependency.key)
            }
        }
    }

    /** Step 2: evaluates groups of mutually dependent questions, dependencies first. */
    private fun evaluateGroups(solver: RecursionSolver, rootKey: AtomKey, ctx: ValidationContext, state: DepthState) {
        val evaluationState = DepthState(state.depth, Mode.CONFORMS)
        fun successors(key: AtomKey, within: Set<AtomKey>?): List<AtomKey> =
            solver.atoms.getValue(key).dependencies.mapNotNull { d ->
                d.key.takeIf { k -> !solver.atoms.getValue(k).resolved && (within == null || k in within) }
            }
        val work = ArrayDeque(stronglyConnectedComponents(listOf(rootKey), ctx.budget) { successors(it, null) })
        while (work.isNotEmpty()) {
            ctx.checkDeadline()
            val members = work.removeFirst().filter { !solver.atoms.getValue(it).resolved }
            if (members.isEmpty()) continue
            val memberSet = members.toHashSet()
            val atoms = members.map { solver.atoms.getValue(it) }
            if (atoms.none { atom -> atom.dependencies.any { it.negative && it.key in memberSet } }) {
                greatestFixpoint(solver, members, memberSet, ctx, evaluationState)
                if (solver.restart) return
                continue
            }
            if (!refine(solver, members, memberSet, atoms, ctx, evaluationState)) {
                if (solver.restart) return
                // Every remaining question lies on a cycle through a negative dependency: undefined by recursion,
                // whatever else (an undecided pattern) its last evaluation found.
                for ((index, atom) in atoms.withIndex()) {
                    solver.settle(atom, Conformance.UNDEFINED)
                    if (ctx.undefined.isNotEmpty()) ctx.undefined[members[index]]?.let { ctx.undefined[members[index]] = UndefinedCause(true, it.pattern) }
                }
                continue
            }
            val rest = members.filter { !solver.atoms.getValue(it).resolved }
            val restSet = rest.toHashSet()
            val split = stronglyConnectedComponents(rest, ctx.budget) { successors(it, restSet) }
            for (group in split.asReversed()) work.addFirst(group)
        }
    }

    /**
     * Settles the questions of a group with negative dependencies whose answer does not depend on the group's
     * unsettled answers (every read of an unsettled member answers undefined; by Kleene monotonicity a definite answer
     * stays valid whatever those members turn out to be). Incremental: a question is evaluated again only when one of
     * its recorded dependencies was settled to a definite answer since its last evaluation, so a group costs its
     * members plus its dependency edges rather than one pass over every member per settled question.
     * Returns whether any question was settled (false also on a solver restart).
     */
    private fun refine(
        solver: RecursionSolver,
        members: List<AtomKey>,
        memberSet: Set<AtomKey>,
        atoms: List<Atom>,
        ctx: ValidationContext,
        state: DepthState,
    ): Boolean {
        fun changedSinceRefined(atom: Atom): Boolean =
            atom.refinedAt < 0 || atom.dependencies.any { d ->
                // A dependency settled to undefined changes nothing: it was read as undefined while unsettled.
                solver.atoms.getValue(d.key).let { it.resolved && it.value != Conformance.UNDEFINED && it.settledAt > atom.refinedAt }
            }
        val dependents = HashMap<AtomKey, MutableList<AtomKey>>()
        for ((index, atom) in atoms.withIndex()) {
            atom.inGroup = true
            atom.readUndefined = true
            for (d in atom.dependencies) if (d.key in memberSet) dependents.getOrPut(d.key) { ArrayList() }.add(members[index])
        }
        val work = ArrayDeque<AtomKey>()
        val queued = HashSet<AtomKey>()
        for ((index, atom) in atoms.withIndex()) if (changedSinceRefined(atom) && queued.add(members[index])) work.add(members[index])
        var settled = false
        while (work.isNotEmpty()) {
            ctx.checkDeadline()
            val key = work.removeFirst()
            queued.remove(key)
            val atom = solver.atoms.getValue(key)
            if (atom.resolved) continue
            val result = evaluateAtom(solver, atom, ctx, state)
            if (solver.restart) return false
            atom.refinedAt = solver.epoch
            if (result == Conformance.UNDEFINED) continue
            solver.settle(atom, result)
            settled = true
            dependents[key]?.forEach { reader ->
                if (!solver.atoms.getValue(reader).resolved && queued.add(reader)) work.add(reader)
            }
        }
        for (atom in atoms) if (!atom.resolved) { atom.inGroup = false; atom.readUndefined = false }
        return settled
    }

    /** Evaluates a solver question in step 2, attributing reads the recording missed to it. */
    private fun evaluateAtom(solver: RecursionSolver, atom: Atom, ctx: ValidationContext, state: DepthState): Conformance {
        val saved = solver.evaluating
        solver.evaluating = atom
        try {
            return evaluateConformance(atom.node, atom.shape, ctx, state)
        } finally {
            solver.evaluating = saved
        }
    }

    /** Greatest fixpoint of a group without negative dependencies: assume conformance, lower answers until stable. */
    private fun greatestFixpoint(solver: RecursionSolver, members: List<AtomKey>, memberSet: Set<AtomKey>, ctx: ValidationContext, state: DepthState) {
        val dependents = HashMap<AtomKey, MutableList<AtomKey>>()
        for (key in members) {
            val atom = solver.atoms.getValue(key)
            atom.inGroup = true
            atom.value = Conformance.CONFORMS
            for (d in atom.dependencies) if (d.key in memberSet) dependents.getOrPut(d.key) { ArrayList() }.add(key)
        }
        val work = ArrayDeque<AtomKey>()
        val queued = HashSet<AtomKey>()
        fun lowered(key: AtomKey) {
            dependents[key]?.forEach { if (solver.atoms.getValue(it).value != Conformance.FAILS && queued.add(it)) work.add(it) }
        }
        for (key in members) {
            val atom = solver.atoms.getValue(key)
            // The recorded answer is the first iteration when every read outside the group conforms.
            val reusable = atom.dependencies.all { d -> d.key in memberSet || solver.atoms.getValue(d.key).value == Conformance.CONFORMS }
            val value = if (reusable) atom.optimistic else evaluateAtom(solver, atom, ctx, state)
            if (solver.restart) return
            if (value < atom.value) {
                atom.value = value
                lowered(key)
            }
        }
        while (work.isNotEmpty()) {
            ctx.checkDeadline()
            val key = work.removeFirst()
            queued.remove(key)
            val atom = solver.atoms.getValue(key)
            val result = evaluateAtom(solver, atom, ctx, state)
            if (solver.restart) return
            if (result < atom.value) {
                atom.value = result
                lowered(key)
            }
        }
        for (key in members) solver.atoms.getValue(key).let { solver.settle(it, it.value) }
    }

    // --- three-valued outcomes -----------------------------------------------------------------------------------------

    private inline fun Sink.fail(build: () -> ValidationViolation) {
        failed = true
        if (mode == Mode.REPORT) results.add(build())
    }

    private fun Sink.failAll(list: List<ValidationViolation>) {
        if (list.isEmpty()) return
        failed = true
        if (mode == Mode.REPORT) results.addAll(list)
    }

    /**
     * An outcome that depends on the undefined answer "does [node] conform to [shape]". The answer is undefined
     * because of undefined recursion, because of an undecided pattern, or both ([ValidationContext.undefined]). The
     * result has the status of **one** cause, chosen deterministically: undefined recursion when it is a cause
     * (`ksh:UndefinedRecursion`; its message also names the pattern that could not be evaluated, if any), otherwise
     * the pattern's status. [context] is added to the result's [ValidationViolation.context].
     */
    private fun Sink.undefinedAnswer(
        ctx: ValidationContext,
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
        if (mode != Mode.REPORT) return
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
    private fun Sink.undecidedPattern(focus: RdfTerm, tpl: ResultTemplate, value: RdfTerm, because: Undecided) {
        undefined = true
        if (cause == null) cause = because
        if (mode != Mode.REPORT) return
        // The engine's explanation, never the shape's sh:message: the constraint was not found violated.
        results.add(
            violation(focus, tpl, constraintStub(ConstraintType.PATTERN, tpl.path?.predicate), because.reason, value, messages = emptyList())
                .copy(violationCode = because.code),
        )
    }

    private inline fun Sink.outcome(
        ctx: ValidationContext,
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
        if (config.strictMode) throw ShaclValidationException(message)
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
        ctx: ValidationContext,
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

    private fun validateNodeShape(
        focus: RdfTerm,
        shape: CompiledNodeShape,
        ctx: ValidationContext,
        state: DepthState,
        sink: Sink,
    ) {
        ctx.checkDeadline()
        if (shape.deactivated) return
        shapeEvaluations++
        val tpl = ResultTemplate(shape.shapeNode, shape.severity, shape.severityCustomIri, shape.messages, null)

        for (nr in shape.nodeRefs) {
            sink.outcome(ctx, conformance(focus, nr, ctx, state), focus, tpl, ConstraintType.NODE, focus, focus, nr) {
                violation(focus, tpl, constraintStub(ConstraintType.NODE), "sh:node constraint failed for ${nr.displayId()}", value = focus)
            }
            if (sink.stop) return
        }

        for (exprRef in shape.nodeByExpressionRefs) {
            sink.outcome(ctx, conformance(focus, exprRef, ctx, state), focus, tpl, ConstraintType.NODE_BY_EXPRESSION, focus, focus, exprRef) {
                violation(
                    focus, tpl, constraintStub(ConstraintType.NODE_BY_EXPRESSION),
                    "sh:nodeByExpression constraint failed", value = focus, sourceConstraint = exprRef,
                )
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
            evalLogical(focus, focus, tpl, part, ctx, state, sink)
            if (sink.stop) return
        }

        if (config.validateClosedShapes && shape.closed != ClosedMode.NONE && focus is RdfResource) {
            sink.failAll(validateClosed(focus, shape, tpl, ctx))
        }
    }

    /** Logical constraints under Kleene logic; operands of `sh:xone` and `sh:not` are negative dependencies. */
    private fun evalLogical(
        reportFocus: RdfTerm,
        logicalTarget: RdfTerm,
        tpl: ResultTemplate,
        part: NodeLogicalPart,
        ctx: ValidationContext,
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

    private fun validateClosed(focus: RdfResource, shape: CompiledNodeShape, tpl: ResultTemplate, ctx: ValidationContext): List<ValidationViolation> {
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
    private fun collectClosedByTypesProperties(focus: RdfResource, ctx: ValidationContext): Set<Iri> {
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
        ctx: ValidationContext,
        state: DepthState,
        currentShape: RdfResource,
        path: ShaclPath?,
        sink: Sink,
    ) {
        val data = ctx.data
        val pathPredicate = tpl.path?.predicate

        fun add(
            type: ConstraintType,
            message: String,
            value: RdfTerm? = null,
            params: Map<String, Any> = emptyMap(),
            severity: ViolationSeverity = tpl.severity,
            severityIri: Iri? = tpl.severityCustomIri,
            messages: List<Literal> = tpl.messages,
            sourceConstraint: RdfTerm? = null,
        ) {
            sink.failed = true
            if (sink.mode == Mode.REPORT) {
                sink.results.add(violation(focus, tpl, constraintStub(type, pathPredicate, params), message, value, severity, severityIri, messages, sourceConstraint))
            }
        }

        fun fingerprints(terms: List<RdfTerm>): Set<String> = terms.mapTo(HashSet()) { shaclRdfTermFingerprint(it) }

        for (c in constraints) {
            if (sink.stop) return
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
                        val outcome = if (lex == null) PatternOutcome.NO_MATCH else matchPattern(c, lex, currentShape, ctx)
                        when {
                            outcome.undecided != null -> sink.undecidedPattern(focus, tpl, v, outcome.undecided)
                            !outcome.matches -> add(ConstraintType.PATTERN, "Pattern ${c.pattern} violated for value $v", v)
                        }
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
                        // Language tags are compared case-insensitively.
                        val duplicated = values.mapNotNull { v -> (v as? LangString)?.let { "${it.lang.lowercase()}\u0000${it.direction?.token ?: ""}" } }
                            .groupingBy { it }.eachCount().filter { it.value > 1 }.keys
                        duplicated.forEach { _ -> add(ConstraintType.UNIQUE_LANG, "sh:uniqueLang violated") }
                    }
                is PropertyConstraint.EqualsPath -> {
                    val other = PathEvaluator.evaluate(focus, c.otherPath, data)
                    val otherKeys = fingerprints(other)
                    val valueKeys = fingerprints(values)
                    values.filter { shaclRdfTermFingerprint(it) !in otherKeys }.forEach { add(ConstraintType.EQUALS, "sh:equals: value $it is missing from the referenced path", it) }
                    other.filter { shaclRdfTermFingerprint(it) !in valueKeys }.forEach { add(ConstraintType.EQUALS, "sh:equals: referenced value $it is missing from the path", it) }
                }
                is PropertyConstraint.DisjointPath -> {
                    val otherKeys = fingerprints(PathEvaluator.evaluate(focus, c.otherPath, data))
                    values.filter { shaclRdfTermFingerprint(it) in otherKeys }.forEach { add(ConstraintType.DISJOINT, "sh:disjoint violated: $it is shared with the referenced path", it) }
                }
                // One result per (value, other value) pair that is not ordered; orderViolations sorts once when it can.
                is PropertyConstraint.LessThanPath ->
                    for ((v, w) in orderViolations(values, PathEvaluator.evaluate(focus, c.otherPath, data), strict = true)) {
                        add(ConstraintType.LESS_THAN, "sh:lessThan violated comparing $v and $w", v)
                        if (sink.stop) break
                    }
                is PropertyConstraint.LessThanOrEqualsPath ->
                    for ((v, w) in orderViolations(values, PathEvaluator.evaluate(focus, c.otherPath, data), strict = false)) {
                        add(ConstraintType.LESS_THAN_OR_EQUALS, "sh:lessThanOrEquals violated comparing $v and $w", v)
                        if (sink.stop) break
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
                    // The count lies in [definite, possible]; only an outcome that depends on undefined answers is undefined.
                    // A maximum makes the shape a negative dependency; sibling exclusion always is one.
                    var definite = 0
                    var possible = 0
                    var undecided: Pair<RdfTerm, RdfResource>? = null
                    for (v in values) {
                        val own = conformance(v, c.shape, ctx, state, negative = c.max != null)
                        var counted = own
                        var question: RdfResource? = if (own == Conformance.UNDEFINED) c.shape else null
                        if (c.disjoint && (own != Conformance.FAILS || sink.exhaustive)) {
                            for (s in c.siblings) {
                                when (conformance(v, s, ctx, state, negative = true)) {
                                    Conformance.CONFORMS -> counted = Conformance.FAILS
                                    Conformance.UNDEFINED -> if (counted != Conformance.FAILS) {
                                        counted = Conformance.UNDEFINED
                                        if (question == null || prefersUndefined(ctx, v to question, v, s)) question = s
                                    }
                                    Conformance.FAILS -> Unit
                                }
                                if (counted == Conformance.FAILS && !sink.exhaustive) break
                            }
                        }
                        when (counted) {
                            Conformance.CONFORMS -> { definite++; possible++ }
                            Conformance.UNDEFINED -> {
                                possible++
                                val asked = question ?: c.shape
                                if (prefersUndefined(ctx, undecided, v, asked)) undecided = v to asked
                            }
                            Conformance.FAILS -> Unit
                        }
                    }
                    val u = undecided
                    if (c.min != null) {
                        if (possible < c.min) {
                            add(ConstraintType.QUALIFIED_MIN_COUNT, "qualifiedMinCount violated (required ${c.min}, found $definite)", params = mapOf("min" to c.min, "actual" to definite))
                        } else if (definite < c.min && u != null) {
                            sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.QUALIFIED_MIN_COUNT, null, u.first, u.second)
                        }
                    }
                    if (c.max != null) {
                        if (definite > c.max) {
                            add(ConstraintType.QUALIFIED_MAX_COUNT, "qualifiedMaxCount violated (max ${c.max}, found $definite)", params = mapOf("max" to c.max, "actual" to definite))
                        } else if (possible > c.max && u != null) {
                            sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.QUALIFIED_MAX_COUNT, null, u.first, u.second)
                        }
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
                        if (members == null) {
                            add(ConstraintType.MEMBER_SHAPE, "Value is not a valid SHACL RDF list", v)
                        } else {
                            var failing = false
                            var undecided: Pair<RdfTerm, RdfResource>? = null
                            for (m in members) {
                                when (conformance(m, c.nestedShape, ctx, state)) {
                                    Conformance.FAILS -> { failing = true; if (!sink.exhaustive) break }
                                    Conformance.UNDEFINED -> if (prefersUndefined(ctx, undecided, m, c.nestedShape)) undecided = m to c.nestedShape
                                    Conformance.CONFORMS -> Unit
                                }
                            }
                            val u = undecided
                            if (failing) {
                                add(ConstraintType.MEMBER_SHAPE, "sh:memberShape violated for list value", v)
                            } else if (u != null) {
                                sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.MEMBER_SHAPE, v, u.first, c.nestedShape)
                            }
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
                is PropertyConstraint.SomeValue -> {
                    var matched = false
                    var undecided: Pair<RdfTerm, RdfResource>? = null
                    for (v in values) {
                        when (conformance(v, c.nestedShape, ctx, state)) {
                            Conformance.CONFORMS -> { matched = true; if (!sink.exhaustive) break }
                            Conformance.UNDEFINED -> if (prefersUndefined(ctx, undecided, v, c.nestedShape)) undecided = v to c.nestedShape
                            Conformance.FAILS -> Unit
                        }
                    }
                    val u = undecided
                    if (!matched) {
                        if (u != null) {
                            sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.SOME_VALUE, null, u.first, c.nestedShape)
                        } else {
                            add(ConstraintType.SOME_VALUE, "sh:someValue requires at least one conforming value")
                        }
                    }
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
                        when (conformance(v, c.nestedShape, ctx, state)) {
                            Conformance.FAILS -> add(ConstraintType.SHAPE, "sh:shape constraint failed for ${c.nestedShape.displayId()}", v)
                            Conformance.UNDEFINED -> sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.SHAPE, v, v, c.nestedShape)
                            Conformance.CONFORMS -> Unit
                        }
                    }
                is PropertyConstraint.Node ->
                    values.forEach { v ->
                        when (conformance(v, c.nestedShape, ctx, state)) {
                            Conformance.FAILS -> add(ConstraintType.NODE, "sh:node constraint failed for ${c.nestedShape.displayId()}", v)
                            Conformance.UNDEFINED -> sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.NODE, v, v, c.nestedShape)
                            Conformance.CONFORMS -> Unit
                        }
                    }
                is PropertyConstraint.NodeByExpression ->
                    values.forEach { v ->
                        when (conformance(v, c.nestedShape, ctx, state)) {
                            Conformance.FAILS ->
                                add(ConstraintType.NODE_BY_EXPRESSION, "sh:nodeByExpression constraint failed for ${c.nestedShape.displayId()}", v, sourceConstraint = c.nestedShape)
                            Conformance.UNDEFINED -> sink.undefinedAnswer(ctx, focus, tpl, ConstraintType.NODE_BY_EXPRESSION, v, v, c.nestedShape)
                            Conformance.CONFORMS -> Unit
                        }
                    }
                is PropertyConstraint.Sparql -> sink.failAll(evaluateSparql(focus, tpl, c, ctx, currentShape, path))
                is PropertyConstraint.ReifierShape,
                is PropertyConstraint.ReificationRequired,
                -> Unit
            }
        }
    }

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
     * so it is still the fixed stack that decides. Every outcome of the evaluation thread is remembered for the run:
     * a value decided once stays decided.
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
     */
    private fun matchPattern(c: PropertyConstraint.Pattern, lexical: String, shape: RdfResource, ctx: ValidationContext): PatternOutcome {
        if (ctx.patternOutcomes.isNotEmpty()) ctx.patternOutcomes[c to lexical]?.let { return it }
        patternEvaluations++
        var result = if (lexical.length < patternInlineMaxLength) runPattern(c, lexical, ctx) else null
        val onWorker = result == null || result == PatternRun.OUT_OF_STACK
        if (onWorker) {
            patternWorkerEvaluations++
            result = ctx.patternWorker().run { runPattern(c, lexical, ctx) }
        }
        val outcome =
            when (result!!) {
                PatternRun.MATCH -> PatternOutcome.MATCH
                PatternRun.NO_MATCH -> PatternOutcome.NO_MATCH
                PatternRun.OUT_OF_BUDGET -> PatternOutcome(
                    false,
                    Undecided(
                        ValidationViolation.PATTERN_TIMEOUT_CODE,
                        "${describe(c, shape)} used up its evaluation budget (ValidationConfig.patternTimeout = " +
                            "${config.patternTimeout}: $patternStepBudget regular expression engine steps, or " +
                            "$PATTERN_WALL_CLOCK_FACTOR times that duration) on a value of ${lexical.length} characters, so " +
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
        if (undecided != null || onWorker) ctx.patternOutcomes[c to lexical] = outcome
        return outcome
    }

    /**
     * Matches [c] against [lexical] on the current thread. A stack overflow is caught here: the stack is unwound up
     * to this frame, so it is safe to go on; only the match is abandoned.
     */
    private fun runPattern(c: PropertyConstraint.Pattern, lexical: String, ctx: ValidationContext): PatternRun =
        try {
            if (c.regex.containsMatchIn(ctx.text(lexical, patternStepBudget, patternBackstopNanos))) PatternRun.MATCH else PatternRun.NO_MATCH
        } catch (e: PatternBudgetExceeded) {
            PatternRun.OUT_OF_BUDGET
        } catch (e: StackOverflowError) {
            PatternRun.OUT_OF_STACK
        }

    private fun describe(c: PropertyConstraint.Pattern, shape: RdfResource): String {
        val flags = c.flags?.let { " (sh:flags \"$it\")" }.orEmpty()
        return "sh:pattern \"${c.pattern}\"$flags of shape ${shape.displayId()}"
    }

    /**
     * SHACL-SPARQL: one result per solution; `?value`, `?path`, `?message` and `?failure` are honoured. Pre-bound
     * variables are substituted by the engine ([com.geoknoesis.kastor.rdf.shacl.native.SparqlQueryTemplate]), so
     * results do not depend on the SPARQL provider. A `?message` binding takes precedence over the constraint's
     * `sh:message`, which takes precedence over the shape's (SHACL-SPARQL result mapping).
     */
    private fun evaluateSparql(
        focus: RdfTerm,
        tpl: ResultTemplate,
        c: PropertyConstraint.Sparql,
        ctx: ValidationContext,
        currentShape: RdfResource,
        path: ShaclPath?,
    ): List<ValidationViolation> {
        val preBound = LinkedHashMap<String, RdfTerm>()
        preBound["this"] = focus
        if ("currentShape" in c.preBound) preBound["currentShape"] = currentShape
        if ("shapesGraph" in c.preBound) preBound["shapesGraph"] = SparqlConstraintEvaluator.SHAPES_GRAPH_IRI
        val (query, providerBindings) = c.template.bind(preBound)
        val out = mutableListOf<ValidationViolation>()
        for (row in ctx.select(query, providerBindings)) {
            val failure = row.get("failure")
            if (failure != null && isLexicallyTrue(failure)) {
                throw ShaclValidationException("SPARQL constraint ${c.constraintNode.displayId()} reported ?failure for focus node $focus")
            }
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
        }
        return out
    }

    private fun displayTerm(term: RdfTerm): String =
        when (term) {
            is Literal -> term.lexical
            is Iri -> term.value
            is BlankNode -> term.toString()
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
        sink: Sink,
    ) {
        if (ps.deactivated) return
        shapeEvaluations++
        // Value nodes are a set (SHACL §2.3.2); the path evaluator returns distinct nodes.
        val values = PathEvaluator.evaluate(focus, ps.path, ctx.data)
        val tpl = ResultTemplate(ps.shapeNode, ps.severity, ps.severityCustomIri, ps.messages, reportPath(ps, ctx))
        evaluateConstraintsForValues(focus, tpl, values, ps.constraints, ctx, state, ps.shapeNode, ps.path, sink)
        if (sink.stop) return
        for (part in ps.logicalParts) {
            for (v in values) {
                evalLogical(focus, v, tpl, part, ctx, state, sink)
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
            validateReifierPropertyConstraints(
                focus = focus,
                tpl = tpl,
                claims = tripleClaimsMatchingSimplePath(focus, ps.path, ctx.data),
                constraints = ps.constraints,
                ctx = ctx,
                state = state,
                sink = sink,
            )
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
        ctx: ValidationContext,
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
                            if (sink.mode == Mode.REPORT) {
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

    /** BCP47-style prefix match (`en` ⊇ `en-NZ`), case-insensitive. Supports trailing `-*` wildcard ranges. */
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

    /**
     * [validatedFocusNodes] is the number of distinct focus nodes that were validated against at least one active
     * shape, whether they have results or not; the average time is the elapsed time of the run divided by it.
     */
    private fun buildStatistics(
        totalResources: Int,
        validatedFocusNodes: Int,
        elapsed: Duration,
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
            validatedResources = validatedFocusNodes,
            totalConstraints = propConstraints,
            validatedConstraints = validatedConstraintSlots.coerceAtLeast(violations.size),
            shapesProcessed = compiled.orderedNodeShapes.size,
            constraintsByType = constraintsByType,
            violationsByType = violationsByType,
            warningsByType = warningsByType,
            averageValidationTimePerResource = if (validatedFocusNodes == 0) Duration.ZERO else elapsed.dividedBy(validatedFocusNodes.toLong()),
        )
    }

    private fun RdfResource.displayId(): String = when (this) {
        is Iri -> value
        is BlankNode -> toString()
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
