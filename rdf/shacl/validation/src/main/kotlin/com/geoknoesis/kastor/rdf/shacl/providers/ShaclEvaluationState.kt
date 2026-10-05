package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import com.geoknoesis.kastor.rdf.shacl.native.shaclRdfTermEquals
import com.geoknoesis.kastor.rdf.shacl.native.shaclRdfTermHash

// Result, mode and recursion-solver types of [NativeShaclValidator]'s evaluation. They hold state and read no
// member of the validator.

/** `sh:resultPath` information for results of one property shape. */
internal class ReportPath(val terms: List<RdfTerm>?, val node: RdfTerm?, val triples: List<RdfTriple>, val predicate: Iri?)

/** Source shape, severity, messages and path shared by the results of one shape. */
internal class ResultTemplate(
    val shape: RdfResource,
    val severity: ViolationSeverity,
    val severityCustomIri: Iri?,
    val messages: List<Literal>,
    val path: ReportPath?,
)

/**
 * How a shape is evaluated: [REPORT] materializes every result; [CONFORMS] only needs the three-valued answer and
 * stops at the first definite failure; [EXHAUSTIVE] also only needs the answer but evaluates every constraint and
 * operand, so that the recursive questions it reads do not depend on the answers it receives; [RECORD] is
 * exhaustive too and materializes every result, together with the solver answers each result depends on
 * ([Recording]).
 */
internal enum class Mode { REPORT, CONFORMS, EXHAUSTIVE, RECORD }

/** [depth] counts nested checks of non-recursive shapes. */
internal data class DepthState(val depth: Int, val mode: Mode) {
    fun nested() = DepthState(depth + 1, Mode.CONFORMS)
}

/** Three-valued conformance, declared in truth order (FAILS < UNDEFINED < CONFORMS). */
internal enum class Conformance { FAILS, UNDEFINED, CONFORMS }

/**
 * Why a constraint could not be decided for a reason other than undefined recursion: a `sh:pattern` evaluation
 * that used up its budget or exhausted the stack. [code] is the [ValidationViolation.violationCode] of the results
 * it causes and [reason] names the pattern.
 */
internal class Undecided(val code: String, val reason: String)

/**
 * Why a conformance answer is undefined: undefined [recursion], an undecided [pattern], or both. A question that
 * is undefined without an entry in [ValidationContext.undefined] is undefined by recursion only.
 */
internal class UndefinedCause(val recursion: Boolean, val pattern: Undecided?)

/** Outcome of one `sh:pattern` evaluation: an answer, or the reason why there is none. */
internal class PatternOutcome(val matches: Boolean, val undecided: Undecided?) {
    companion object {
        val MATCH = PatternOutcome(true, null)
        val NO_MATCH = PatternOutcome(false, null)
    }
}

/** A read of the recursion solver by a recorded evaluation: the question and the answer the evaluation was given. */
internal class SolverRead(val node: RdfTerm, val shape: RdfResource, val answer: Conformance)

/**
 * A part of a recorded evaluation that read the recursion solver: one constraint (or logical constraint, or
 * `sh:node` reference) applied to one set of value nodes. Its results are `results[resultsFrom, resultsTo)` of
 * the [Recording], it made the reads `reads[readsFrom, readsTo)`, and [replay] evaluates it again into another
 * sink. Units do not overlap and are in evaluation order.
 */
internal class ResultUnit(val resultsFrom: Int, val resultsTo: Int, val readsFrom: Int, val readsTo: Int, val replay: (Sink) -> Unit)

/**
 * The results of one solver evaluation of a conformance question ([Mode.RECORD]), kept so that the report of a
 * failing or undefined focus node of a recursive shape does not need another evaluation: see
 * [recordedResults].
 */
internal class Recording(val results: List<ValidationViolation>, val reads: List<SolverRead>, val units: List<ResultUnit>) {
    /** What the recording retains, for the bound on retained recordings. */
    val size: Int get() = 1 + results.size + reads.size + units.size
}

/** Outcome of one shape evaluation: results in [Mode.REPORT] and [Mode.RECORD], otherwise only the three-valued answer. */
internal class Sink(val mode: Mode) {
    val results = ArrayList<ValidationViolation>()
    /** Whether results are materialized. */
    val reports: Boolean get() = mode == Mode.REPORT || mode == Mode.RECORD
    /** [Mode.RECORD]: the reads of the recursion solver by this evaluation, and its [ResultUnit]s. */
    var reads: ArrayList<SolverRead>? = null
    var units: ArrayList<ResultUnit>? = null
    var failed = false
    var undefined = false
    /** First cause of [undefined] that is not undefined recursion (an undecided pattern), if any. */
    var cause: Undecided? = null
    /** Whether [undefined] is (also) caused by undefined recursion. */
    var recursion = false
    /** Only [Mode.CONFORMS] stops early, and only on a definite failure (Kleene conjunction). */
    val stop: Boolean get() = failed && mode == Mode.CONFORMS
    val exhaustive: Boolean get() = mode == Mode.EXHAUSTIVE || mode == Mode.RECORD
    fun conformance(): Conformance =
        when {
            failed -> Conformance.FAILS
            undefined -> Conformance.UNDEFINED
            else -> Conformance.CONFORMS
        }
}

/** RDF term under SHACL term equality: a structured memo key (no fingerprint string per conformance check). */
internal class TermKey(val term: RdfTerm) {
    private val hash = shaclRdfTermHash(term)
    override fun hashCode(): Int = hash
    override fun equals(other: Any?): Boolean = other is TermKey && other.hash == hash && shaclRdfTermEquals(term, other.term)
}

/** A conformance question: (value node, shape). */
internal data class AtomKey(val node: TermKey, val shape: RdfResource)

internal class Dependency(val key: AtomKey, val node: RdfTerm, val shape: RdfResource, val negative: Boolean)

internal class Atom(val node: RdfTerm, val shape: RdfResource) {
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
internal class RecursionSolver(val component: Int) {
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
