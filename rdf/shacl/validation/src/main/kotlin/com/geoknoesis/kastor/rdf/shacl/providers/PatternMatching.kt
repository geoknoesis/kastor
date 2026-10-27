package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.shacl.native.ValidationBudget

// Regular-expression evaluation support of [NativeShaclValidator]: step budget and counting CharSequence.

/** Thrown out of a regex match exceeding [com.geoknoesis.kastor.rdf.shacl.ValidationConfig.patternTimeout]. */
internal class PatternBudgetExceeded : RuntimeException(null, null, false, false)

/** After this many evaluations of a run have used up their whole step budget, the budget of further values shrinks. */
internal const val PATTERN_MAX_FULL_BUDGET_EXHAUSTIONS = 16

/** The reduced step budget of a value is the full one divided by this, ... */
internal const val PATTERN_DEGRADED_STEP_DIVISOR = 1024L

/** ... but at least this many steps, ... */
internal const val PATTERN_MIN_DEGRADED_STEPS = 1024L

/**
 * ... and at least this many steps per character of the value (never more than the full budget). The engine reads
 * each character about three times, at a cost of up to one step per instruction of the compiled pattern, so an
 * ordinary value of any length stays decidable for a pattern of up to about 20 instructions, while a value of a
 * pattern with a large program is undecided at a cost linear in its length.
 */
internal const val PATTERN_DEGRADED_STEPS_PER_CHAR = 64L

/** The run deadline and the wall-clock backstop are consulted every this many steps. */
internal const val PATTERN_CHECK_STEPS = 1024L

/**
 * The budget of one pattern evaluation: [limit] steps of the regular expression engine, each character read costing
 * [weight] steps (the size of the compiled program), and a wall-clock backstop of [backstopNanos] nanoseconds from
 * [startNanos] (an instant of the pattern clock). The elapsed time is compared, never an instant: instants of
 * `System.nanoTime` may be negative and are only meaningful as differences.
 */
internal class PatternBudget(val limit: Long, val weight: Long, val startNanos: Long, val backstopNanos: Long) {
    var steps = 0L

    /** The step count at which the budget, the run deadline and the backstop are consulted next. */
    var nextCheck = PATTERN_CHECK_STEPS
}

/**
 * A CharSequence for regex matching that counts the work of the regular expression engine in **steps** and stops
 * the match with [PatternBudgetExceeded] when the evaluation has used up its [PatternBudget]. Each character read
 * costs [PatternBudget.weight] steps: RE2/J, a linear-time engine, advances up to one thread per instruction of the
 * compiled program for each character it reads, so reads alone would not measure its work. The steps are what is
 * counted, so whether an evaluation is stopped does not depend on the speed or the load of the machine. Every
 * [PATTERN_CHECK_STEPS] steps it also consults the run deadline and the wall-clock backstop of the evaluation (an
 * instant of [clock]).
 */
internal class DeadlineCharSequence(
    private val value: String,
    private val budget: ValidationBudget,
    private val clock: () -> Long,
    private val evaluation: PatternBudget,
) : CharSequence {
    override val length: Int get() = value.length
    override fun get(index: Int): Char {
        val steps = evaluation.steps + evaluation.weight
        evaluation.steps = steps
        if (steps >= evaluation.nextCheck) {
            evaluation.nextCheck = steps + PATTERN_CHECK_STEPS
            if (steps >= evaluation.limit) throw PatternBudgetExceeded()
            budget.check("pattern matching")
            if (clock() - evaluation.startNanos >= evaluation.backstopNanos) throw PatternBudgetExceeded()
        }
        return value[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        DeadlineCharSequence(value.substring(startIndex, endIndex), budget, clock, evaluation)
    override fun toString(): String = value
}

/** Result of running one pattern against one value. */
internal enum class PatternRun { MATCH, NO_MATCH, OUT_OF_BUDGET }
