package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import java.util.concurrent.atomic.LongAdder

// Test counters and seams of [NativeShaclValidator]. Production code never configures them.

/** Counts of one validation run. Plain fields: a run is confined to one thread, so the hot path takes no fence. */
internal class RunCounters {
    var shapeEvaluations = 0L
    var solverRestarts = 0L
    var patternEvaluations = 0L
    var patternWorkerEvaluations = 0L
    var patternSteps = 0L
    var recordedReports = 0L
    var separateReportEvaluations = 0L
}

/**
 * The instrumentation of one [NativeShaclValidator]: counters that a run adds to when it ends (so concurrent runs
 * on a shared validator lose no counts and the hot path of a run touches no shared field), and the seams tests use
 * to make a run deterministic. A run reads each seam once, when it starts.
 */
internal class ShaclInstrumentation {
    val shapeEvaluations = LongAdder()
    val solverRestarts = LongAdder()
    val patternEvaluations = LongAdder()
    val patternWorkerEvaluations = LongAdder()
    val patternSteps = LongAdder()
    val recordedReports = LongAdder()
    val separateReportEvaluations = LongAdder()
    val digestMemoHits = LongAdder()

    /**
     * Simulates an incomplete dependency recording: a recursive read of (node, shape) for which this returns true is
     * answered by the recording pass but not registered.
     */
    @Volatile var dropRecordedRead: ((RdfTerm, RdfResource) -> Boolean)? = null

    /** Clock of the wall-clock backstop of the per-pattern budget, a `System.nanoTime`-like source. */
    @Volatile var patternClock: () -> Long = System::nanoTime

    /**
     * Clock of the run budget. A clock that advances by one each time it is consulted turns the timeout into a bound
     * on the work of a run, independent of the machine.
     */
    @Volatile var budgetClock: () -> Long = System::nanoTime

    /** Bound on what the recorded evaluations of recursive focus nodes may retain; `0` disables recording. */
    @Volatile var reportRecordingCapacity: Long = 200_000L

    /** Stack size of the pattern evaluation thread. */
    @Volatile var patternWorkerStackBytes: Long = PATTERN_WORKER_STACK_BYTES

    /** Values shorter than this are first matched on the validating thread. */
    @Volatile var patternInlineMaxLength: Int = PATTERN_INLINE_MAX_LENGTH

    fun absorb(c: RunCounters) {
        shapeEvaluations.add(c.shapeEvaluations)
        solverRestarts.add(c.solverRestarts)
        patternEvaluations.add(c.patternEvaluations)
        patternWorkerEvaluations.add(c.patternWorkerEvaluations)
        patternSteps.add(c.patternSteps)
        recordedReports.add(c.recordedReports)
        separateReportEvaluations.add(c.separateReportEvaluations)
    }
}
