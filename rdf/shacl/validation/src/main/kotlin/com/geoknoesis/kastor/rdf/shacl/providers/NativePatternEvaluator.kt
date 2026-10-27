package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint

/** Bounded regular expression evaluation; all mutable per-run state belongs to the validation context. */
internal class NativePatternEvaluator(private val config: ValidationConfig) {
    internal companion object {
        // One engine step (a character read, per instruction of the compiled program) represents 5 ns (200,000
        // steps/ms; RE2/J takes 2.5 to 5 ns per step on 2026 hardware); wall time is a generous backstop only.
        const val PATTERN_NANOS_PER_STEP = 5L
        const val PATTERN_WALL_CLOCK_FACTOR = 10L

        private fun timeoutNanos(config: ValidationConfig): Long =
            try { config.patternTimeout.toNanos() } catch (e: ArithmeticException) { Long.MAX_VALUE }

        /** The step budget of one evaluation under [config] (the full one, before the run-wide cap). */
        fun stepBudget(config: ValidationConfig): Long = (timeoutNanos(config) / PATTERN_NANOS_PER_STEP).coerceAtLeast(1)
    }
    private val patternTimeoutNanos = timeoutNanos(config)
    private val patternStepBudget = stepBudget(config)
    private val patternBackstopNanos =
        if (patternTimeoutNanos > Long.MAX_VALUE / PATTERN_WALL_CLOCK_FACTOR) Long.MAX_VALUE else patternTimeoutNanos * PATTERN_WALL_CLOCK_FACTOR

    /**
     * One `sh:pattern` evaluation, bounded by [ValidationConfig.patternTimeout].
     *
     * **Budget.** The evaluation may take [patternStepBudget] steps of the regular expression engine
     * ([DeadlineCharSequence]): each character read costs as many steps as the compiled pattern has instructions
     * ([com.geoknoesis.kastor.rdf.sparql.internal.XPathPattern.programSize]), the most work the engine can do per
     * read, at 200,000 steps per millisecond of the configured duration. Steps are counted, not time, so whether a
     * value is decided does not depend on the machine, its load or a garbage collection pause. The wall clock is
     * only a backstop, at [PATTERN_WALL_CLOCK_FACTOR] times the configured duration.
     *
     * **Engine.** Patterns run on the linear-time RE2/J engine ([compileShaclPattern]): no pattern backtracks, and a
     * match does not recurse on its input, so it cannot exhaust the stack; the budget bounds the cost of a long value.

     * A value on which the pattern uses up its budget has no answer. That is not an error of the run: one slow or hostile literal must not prevent the validation of
     * everything else. The constraint is **undecided** for that value ([PatternOutcome.undecided]): the caller
     * reports a result naming the pattern, marked `ksh:PatternTimeout`, which blocks
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
     * character of the value, and never more than the full budget. The engine reads each character about three
     * times, so a value of any length is still decided for a pattern of up to about 20 instructions; a value of a
     * larger pattern may be undecided, at a cost linear in its length. Data that never uses up a budget never reaches
     * the cap, so it is evaluated as before.
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
    fun match(c: PropertyConstraint.Pattern, lexical: String, shape: RdfResource, ctx: NativeValidationContext): PatternOutcome {
        if (ctx.patternOutcomes.isNotEmpty()) ctx.patternOutcomes[c to lexical]?.let { return it }
        ctx.counters.patternEvaluations++
        // Run-wide cap: once PATTERN_MAX_FULL_BUDGET_EXHAUSTIONS evaluations used up their whole budget, a value not
        // matched before only gets a small fraction of it, so many distinct hostile values cost a bounded total.
        val reduced = reducedPatternSteps(lexical.length)
        val steps = if (ctx.patternBudgetExhaustions < PATTERN_MAX_FULL_BUDGET_EXHAUSTIONS) patternStepBudget else reduced
        val stepsBefore = ctx.counters.patternSteps
        val result = runPattern(c, lexical, ctx, steps)
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
                            "${config.patternTimeout}: $patternStepBudget regular expression engine steps, a character read costing " +
                            "${c.regex.programSize} steps for this pattern, or " +
                            "$PATTERN_WALL_CLOCK_FACTOR times that duration; $reduced steps for this value once " +
                            "${PATTERN_MAX_FULL_BUDGET_EXHAUSTIONS} evaluations of the run have used up the whole budget; " +
                            "this evaluation had $steps) on a value of ${lexical.length} characters, so " +
                            "whether the value matches is unknown and it is not accepted; bound the value with sh:maxLength",
                    ),
                )
            }
        val undecided = outcome.undecided
        if (undecided != null && config.strictMode) throw ShaclValidationException(undecided.reason)
        // Remember every outcome another budget could change: one value, one answer for the whole run.
        if (undecided != null || ctx.counters.patternSteps - stepsBefore >= reduced) {
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

    /** Matches [c] against [lexical], within [steps] steps of the engine. */
    private fun runPattern(c: PropertyConstraint.Pattern, lexical: String, ctx: NativeValidationContext, steps: Long): PatternRun {
        val evaluation = ctx.patternBudget(steps, c.regex.programSize.toLong().coerceAtLeast(1), patternBackstopNanos)
        try {
            return if (c.regex.containsMatchIn(ctx.text(lexical, evaluation))) PatternRun.MATCH else PatternRun.NO_MATCH
        } catch (e: PatternBudgetExceeded) {
            return PatternRun.OUT_OF_BUDGET
        } finally {
            ctx.counters.patternSteps += evaluation.steps
        }
    }

    private fun describe(c: PropertyConstraint.Pattern, shape: RdfResource): String {
        val flags = c.flags?.let { " (sh:flags \"$it\")" }.orEmpty()
        return "sh:pattern \"${c.pattern}\"$flags of shape ${shape.displayId()}"
    }

}
