package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.native.ValidationBudget

// Regular-expression evaluation support of [NativeShaclValidator]: step budget, counting CharSequence, worker thread.

/** Thrown out of a regex match whose per-evaluation budget ([ValidationConfig.patternTimeout]) is used up. */
internal class PatternBudgetExceeded : RuntimeException(null, null, false, false)

/** Stack size of the pattern evaluation thread of a run (see `matchPattern`). */
internal const val PATTERN_WORKER_STACK_BYTES = 16L * 1024 * 1024

/** Values at least this long (UTF-16 code units) are matched on the pattern evaluation thread. */
internal const val PATTERN_INLINE_MAX_LENGTH = 1024

/** After this many evaluations of a run have used up their whole step budget, the budget of further values shrinks. */
internal const val PATTERN_MAX_FULL_BUDGET_EXHAUSTIONS = 16

/** The reduced step budget of a value is the full one divided by this, ... */
internal const val PATTERN_DEGRADED_STEP_DIVISOR = 1024L

/** ... but at least this many steps. */
internal const val PATTERN_MIN_DEGRADED_STEPS = 1024L

/**
 * The budget of one pattern evaluation: [limit] steps of the regular expression engine, and a wall-clock backstop
 * of [backstopNanos] nanoseconds from [startNanos] (an instant of the pattern clock). The elapsed time is compared,
 * never an instant: instants of `System.nanoTime` may be negative and are only meaningful as differences.
 */
internal class PatternBudget(val limit: Long, val startNanos: Long, val backstopNanos: Long) {
    var steps = 0L
}

/**
 * A CharSequence for regex matching that counts the character reads of the regular expression engine (its
 * **steps**: a backtracking match reads characters all the time) and stops the match with
 * [PatternBudgetExceeded] when the evaluation has used up its [PatternBudget]. The steps are what is counted, so
 * whether an evaluation is stopped does not depend on the speed or the load of the machine. Every 1024 steps it
 * also consults the run deadline and the wall-clock backstop of the evaluation (an instant of [clock]).
 */
internal class DeadlineCharSequence(
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
            if (clock() - evaluation.startNanos >= evaluation.backstopNanos) throw PatternBudgetExceeded()
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
internal class PatternWorker(stackBytes: Long) : AutoCloseable {
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
internal enum class PatternRun { MATCH, NO_MATCH, OUT_OF_BUDGET, OUT_OF_STACK }
