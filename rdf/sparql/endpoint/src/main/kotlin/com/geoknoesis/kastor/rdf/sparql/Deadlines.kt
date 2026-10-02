package com.geoknoesis.kastor.rdf.sparql

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.LockSupport

/**
 * The one place where a timeout becomes a number. `Duration.toNanos()` and `toMillis()` throw
 * [ArithmeticException] for durations beyond about 292 years (or 292 million years), which is how
 * "no timeout" is commonly written (`Duration.ofMillis(Long.MAX_VALUE)`, `ChronoUnit.FOREVER`); here
 * such a duration is [UNBOUNDED], and adding it to a clock reading gives no deadline.
 */
internal object Durations {
    /** A wait without end. */
    const val UNBOUNDED = Long.MAX_VALUE

    /** [duration] in nanoseconds, or [UNBOUNDED] when it is too long to count. Durations are validated positive. */
    fun nanos(duration: Duration): Long = try {
        duration.toNanos()
    } catch (_: ArithmeticException) {
        if (duration.isNegative) Long.MIN_VALUE else UNBOUNDED
    }

    /** [duration] in milliseconds for messages, saturating like [nanos]. */
    fun millis(duration: Duration): Long = try {
        duration.toMillis()
    } catch (_: ArithmeticException) {
        if (duration.isNegative) Long.MIN_VALUE else Long.MAX_VALUE
    }

    fun isUnbounded(duration: Duration): Boolean = nanos(duration) == UNBOUNDED

    /**
     * The `System.nanoTime()` reading [budgetNanos] after [startNanos], or `null` when there is no
     * such deadline: the budget is [UNBOUNDED], or the sum is beyond what a `Long` holds. The result
     * is never `Long.MAX_VALUE` or `Long.MIN_VALUE`, which callers use as markers.
     */
    fun deadline(startNanos: Long, budgetNanos: Long): Long? {
        if (budgetNanos == UNBOUNDED) return null
        val sum = startNanos + budgetNanos
        val overflowed = ((startNanos xor sum) and (budgetNanos xor sum)) < 0
        return if (overflowed || sum == Long.MAX_VALUE || sum == Long.MIN_VALUE) null else sum
    }

    fun requirePositive(duration: Duration, name: String) =
        require(!duration.isNegative && !duration.isZero) { "$name must be positive" }
}

/**
 * Closes response streams whose current read has passed its deadline (the read timeout or the
 * request deadline). Closing a `java.net.http` response stream from another thread unblocks a
 * blocked read immediately (unlike `HttpURLConnection.disconnect()`, which waits for the reading
 * thread's stream lock).
 *
 * A read only publishes its deadline in an atomic field of its stream, so reads take no lock and
 * schedule no task. One daemon thread sleeps until the earliest published deadline and is woken
 * when a read publishes an earlier one.
 *
 * The thread outlives anything a stream does: a stream whose check throws is reported through
 * [report] and no longer watched, and the others still are. Should the thread end all the same (the
 * report itself failing, for instance), it is replaced, at the latest when the next read publishes
 * its deadline.
 */
internal class ReadWatchdog(private val report: (Throwable) -> Unit = ::logFailure) {
    interface Watched {
        /** Expire the pending read if its deadline is at or before [now]; returns the pending deadline, or [Long.MAX_VALUE]. */
        fun expireIfDue(now: Long): Long
    }

    private val streams: MutableSet<Watched> = ConcurrentHashMap.newKeySet()

    /** When the watchdog wakes up next; [Long.MAX_VALUE] while it scans or has nothing to wait for. */
    @Volatile private var nextWakeNanos = Long.MAX_VALUE

    /** The watching thread; started with the first published deadline. */
    @Volatile private var thread: Thread? = null

    /** How many threads have been started; more than one means the thread had to be replaced. */
    @Volatile internal var threadsStarted = 0
        private set

    fun register(stream: Watched) {
        streams.add(stream)
    }

    fun unregister(stream: Watched) {
        streams.remove(stream)
    }

    /** Called after a read published [deadline]; wakes the watchdog if it would sleep past it. */
    fun published(deadline: Long) {
        val current = thread
        if (current == null || !current.isAlive) ensureRunning()
        else if (deadline < nextWakeNanos) LockSupport.unpark(current)
    }

    @Synchronized
    private fun ensureRunning() {
        val current = thread
        // A thread that is alive may be on its way out, so it is woken as well: it then rescans or is replaced.
        if (current != null && current.isAlive) LockSupport.unpark(current) else spawn()
    }

    /** Starts a watching thread; it scans every registered stream before it first sleeps. */
    @Synchronized
    private fun spawn() {
        threadsStarted++
        thread = Thread(::watch, "kastor-sparql-deadline").apply {
            isDaemon = true
            start()
        }
    }

    private fun watch() {
        try {
            while (true) {
                // A read publishing during the scan sees MAX_VALUE and unparks, so the park below returns at once.
                nextWakeNanos = Long.MAX_VALUE
                val now = System.nanoTime()
                var next = Long.MAX_VALUE
                for (stream in streams) {
                    val pending = try {
                        stream.expireIfDue(now)
                    } catch (failure: Throwable) {
                        // Whatever it was, it must not end the watch over the other streams.
                        streams.remove(stream)
                        report(failure)
                        Long.MAX_VALUE
                    }
                    next = minOf(next, pending)
                }
                nextWakeNanos = next
                if (next == Long.MAX_VALUE) LockSupport.park(this) else LockSupport.parkNanos(this, next - now)
            }
        } finally {
            // The loop has no exit: this thread is ending on a failure that could not even be reported.
            if (Thread.currentThread() === thread) spawn()
        }
    }

    companion object {
        /** The watchdog of every response stream of this adapter. */
        val shared = ReadWatchdog()

        private fun logFailure(failure: Throwable) {
            System.getLogger(SparqlRepository::class.java.name).log(
                System.Logger.Level.WARNING,
                "A SPARQL response stream failed while its read deadline was checked; it is no longer watched",
                failure,
            )
        }
    }
}
