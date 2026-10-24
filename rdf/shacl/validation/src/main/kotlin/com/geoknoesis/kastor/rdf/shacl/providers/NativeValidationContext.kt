package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.SparqlQueryable
import java.time.Duration
import com.geoknoesis.kastor.rdf.shacl.native.ValidationBudget
import com.geoknoesis.kastor.rdf.shacl.native.CompiledShapeGraph
import com.geoknoesis.kastor.rdf.shacl.native.DataGraphIndex
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint
import com.geoknoesis.kastor.rdf.shacl.native.SparqlConstraintEvaluator

internal class NativeValidationContext(
    val compiled: CompiledShapeGraph,
    val data: DataGraphIndex,
    val budget: ValidationBudget,
    private val repositoryFactory: () -> com.geoknoesis.kastor.rdf.RdfRepository,
    private val sparqlInPlace: SparqlQueryable?,
    private val instrumentation: ShaclInstrumentation,
) : AutoCloseable {
    /** The counts of this run, added to [instrumentation] when the run ends. */
    val counters = RunCounters()
    // The seams are read once per run: the hot path reads no shared field.
    /** Clock of the wall-clock backstop of the per-pattern budget (`System.nanoTime` unless a test injects one). */
    private val patternClock: () -> Long = instrumentation.patternClock
    private val patternWorkerStackBytes: Long = instrumentation.patternWorkerStackBytes
    val patternInlineMaxLength: Int = instrumentation.patternInlineMaxLength
    val dropRecordedRead: ((RdfTerm, RdfResource) -> Boolean)? = instrumentation.dropRecordedRead
    /**
     * Pattern evaluations of this run that used up their whole step budget. Past the full-budget exhaustion limit
     * of them, a value that has not been matched before gets a reduced budget (see [NativePatternEvaluator]).
     */
    var patternBudgetExhaustions = 0
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
     * undecided one, every one that ran on the pattern evaluation thread, and every one that took at least the
     * reduced budget of its value (see [NativePatternEvaluator]). The answer for a value is the same every
     * time it is asked, and an expensive budget is spent once per value rather than once per focus node or solver pass.
     */
    val patternOutcomes = HashMap<Pair<PropertyConstraint.Pattern, String>, PatternOutcome>()
    private var patternWorker: PatternWorker? = null
    fun patternWorker(): PatternWorker = patternWorker ?: PatternWorker(patternWorkerStackBytes).also { patternWorker = it }
    /** Solver of the recursive component currently being evaluated (routes references into that component). */
    var solver: RecursionSolver? = null
    /**
     * The focus nodes of the recursive shapes that are validated as targets, per shape: the conformance
     * questions whose results will be reported, so the solver records them ([Mode.RECORD]). A shape whose focus
     * nodes are not known yet is absent (its focus nodes get an ordinary report evaluation).
     */
    val reportedFocusNodes = HashMap<RdfResource, Set<TermKey>>()
    /** The latest recorded evaluation of each such question, until it is reported or found to conform. */
    val recordings = HashMap<AtomKey, Recording>()
    /** Sum of [Recording.size] over [recordings]: recording stops while it exceeds [recordingCapacity]. */
    var recordedSize = 0L
    var recordingCapacity = 0L
    /** The sink of the [Mode.RECORD] evaluation under way, which logs the solver reads; null otherwise. */
    var recordingSink: Sink? = null

    fun records(node: RdfTerm, shape: RdfResource): Boolean =
        recordedSize < recordingCapacity && reportedFocusNodes[shape]?.contains(TermKey(node)) == true

    fun keepRecording(key: AtomKey, recording: Recording?) {
        val previous = if (recording == null) recordings.remove(key) else recordings.put(key, recording)
        recordedSize += (recording?.size ?: 0) - (previous?.size ?: 0)
    }
    val reportPaths = HashMap<RdfResource, ReportPath>()
    private val session = lazy {
        SparqlConstraintEvaluator.Session(
            data.graph,
            repositoryFactory = repositoryFactory,
            inPlace = sparqlInPlace,
        )
    }
    fun checkDeadline() {
        budget.check()
    }
    fun <T> select(query: String, bindings: Map<String, RdfTerm>, consume: (Sequence<com.geoknoesis.kastor.rdf.BindingSet>) -> T): T =
        session.value.select(query, bindings, Duration.ofNanos(budget.remainingNanos()), consume)
    /** The budget of one pattern evaluation of at most [steps] steps that may last at most [backstopNanos]. */
    fun patternBudget(steps: Long, backstopNanos: Long) = PatternBudget(steps, patternClock(), backstopNanos)
    /** [value] for the pattern evaluation [evaluation]. */
    fun text(value: String, evaluation: PatternBudget): CharSequence = DeadlineCharSequence(value, budget, patternClock, evaluation)
    override fun close() {
        try {
            patternWorker?.close()
        } finally {
            try {
                if (session.isInitialized()) session.value.close()
            } finally {
                instrumentation.absorb(counters)
            }
        }
    }
}
