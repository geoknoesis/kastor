package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.SparqlFunctionLibrary
import com.geoknoesis.kastor.rdf.SparqlQueryable
import java.time.Duration
import com.geoknoesis.kastor.rdf.shacl.native.ValidationBudget
import com.geoknoesis.kastor.rdf.shacl.native.CompiledShapeGraph
import com.geoknoesis.kastor.rdf.shacl.native.DataGraphIndex
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpression
import com.geoknoesis.kastor.rdf.shacl.native.SparqlFunctionDeclarationSet
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint
import com.geoknoesis.kastor.rdf.shacl.native.SparqlConstraintEvaluator

internal class NativeValidationContext(
    private val validator: NativeShaclValidator,
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
    /** The constraints instantiated from the run's [PropertyConstraint.Dynamic] constraints (SHACL 1.2 Node Expressions §8). */
    val dynamicInstances = dynamicInstanceCacheFor(data.maxPathValueNodes)
    private val session = lazy {
        SparqlConstraintEvaluator.Session(
            data.graph,
            repositoryFactory = repositoryFactory,
            inPlace = sparqlInPlace,
            functions = compiled.functions.library(::runFunctionBody),
            declaresFunctions = !compiled.functions.isEmpty,
        )
    }

    /**
     * The node expression nesting depth at which the query or conformance check under way was started by the node
     * expression evaluator (0 outside node expressions). A function body that the query calls continues one level
     * deeper ([runFunctionBody]), so nesting through SPARQL is capped by [MAX_NODE_EXPRESSION_DEPTH] as a whole.
     */
    var nodeExpressionDepth = 0

    /**
     * The shape nesting depth ([DepthState.depth]) of the shape evaluation whose node expression is being evaluated:
     * the shape-based functions validate one level deeper ([evalNodeExpression]), so nesting through node
     * expressions is bounded by [com.geoknoesis.kastor.rdf.shacl.ValidationConfig.maxRecursionDepth] like any other.
     */
    var expressionShapeDepth = 0

    /** Provisional answers read by function bodies during the queries under way ([ProvisionalStash]). */
    private val provisionalInQuery = ProvisionalStash()

    /**
     * Whether "undefined" is only a provisional answer to "does [node] conform to [shape]": the recursion solver of
     * the shape's component is under way and has not settled the question (or restarts). Its final answer may be
     * definite, so a reader must report it as undefined ([Sink.undefinedAnswer]) and let the solver evaluate it
     * again, not fail.
     */
    fun isProvisional(node: RdfTerm, shape: RdfResource): Boolean {
        val active = solver ?: return false
        if (compiled.recursiveComponents[shape] != active.component) return false
        if (active.restart) return true
        return active.atoms[AtomKey(TermKey(node), shape)]?.resolved != true
    }

    /**
     * Evaluates the node expression [body] of the SHACL SPARQL function [function] called with [arguments] (SHACL 1.2
     * SPARQL §7.3, Node Expressions §6.2): the focus node is the function IRI and the arguments are `shnex:arg 0`,
     * `shnex:arg 1`, ...; the result is the single output node, or null (an error) for none, several or an
     * evaluation failure. The body continues the nesting depth of the node expression whose query calls it; the
     * number of nested function calls is limited by the library ([SparqlFunctionDeclarationSet.library]).
     */
    private fun runFunctionBody(body: NodeExpression, function: Iri, arguments: List<RdfTerm?>): RdfTerm? {
        val outcome = try {
            validator.evaluate(body, this, NodeExprScope(function, emptyMap(), arguments, nodeExpressionDepth + 1))
        } catch (e: ProvisionalUndefined) {
            // The query cannot carry it: the whole query's answer is provisional ([select] rethrows it).
            provisionalInQuery.stash(e)
            return null
        }
        if (outcome is NodeExprOutcome.Failure && outcome.undecided) {
            // The body failed on a conformance check the engine could not decide (a final undefined answer, a shape
            // that did not compile or uses an unsupported feature (R8), a validation error), which SPARQL would turn
            // into an ordinary error (a FILTER dropping its row, say) and so into a definite answer: the query fails
            // instead ([select] rethrows it), and so does a node expression that runs the query, whatever surrounds it.
            provisionalInQuery.stashFailure(UndecidedConformanceException("SHACL function ${function.value}: ${outcome.reason}", outcome.cause))
        }
        return (outcome as? NodeExprOutcome.Nodes)?.nodes?.singleOrNull()
    }
    fun checkDeadline() {
        budget.check()
    }
    /**
     * Runs [query] with the shapes graph's function library. The budget is checked when the query returns: a function
     * body that ran out of time is only a SPARQL error of its call (`FILTER (!ex:f(...))` drops the row, say), so the
     * query's answer may be wrong, and no later check might come (the last focus node).
     */
    fun <T> select(query: String, bindings: Map<String, RdfTerm>, consume: (Sequence<com.geoknoesis.kastor.rdf.BindingSet>) -> T): T =
        provisionalInQuery.scoped { session.value.select(query, bindings, Duration.ofNanos(budget.remainingNanos()), consume) }
            .also { checkDeadline() }
    /** As [select], with the function library [library] instead of the shapes graph's. */
    fun <T> select(query: String, bindings: Map<String, RdfTerm>, library: SparqlFunctionLibrary, consume: (Sequence<com.geoknoesis.kastor.rdf.BindingSet>) -> T): T =
        provisionalInQuery.scoped { session.value.select(query, bindings, Duration.ofNanos(budget.remainingNanos()), library, consume) }
            .also { checkDeadline() }
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
