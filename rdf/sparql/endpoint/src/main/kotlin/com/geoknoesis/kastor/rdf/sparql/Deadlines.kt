package com.geoknoesis.kastor.rdf.sparql

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
 * A read only publishes its deadline in an atomic field of its stream; it takes the watchdog's lock
 * only when the watching thread would sleep past that deadline (or does not run). One daemon thread
 * sleeps until the earliest published deadline and is woken when a read publishes an earlier one.
 *
 * Wake-ups are not addressed to a thread: a read raises a flag under the lock and signals its
 * condition, and the watching thread looks at the flag, under the same lock, before it sleeps. A
 * deadline is therefore seen whichever thread is watching, also while one thread replaces another.
 *
 * The thread outlives anything a stream does: a stream whose check throws is reported through
 * [report] and no longer watched, and the others still are. Should the thread end all the same (the
 * report itself failing, for instance), it starts its replacement, which scans every registered
 * stream before it first sleeps.
 *
 * The thread runs while the watchdog has users ([retain], [release]: the open repositories) or a
 * read is pending. With no user and nothing pending it ends, so that nothing of this adapter keeps
 * running (and keeps its class loader alive) after the last repository was closed; the next
 * published deadline starts it again, for streams that are read after their repository was closed.
 *
 * @param newThread creates the watching thread for the given body; it is started by the watchdog.
 *   Tests pass their own to count and observe the threads.
 */
internal class ReadWatchdog(
    private val report: (Throwable) -> Unit = ::logFailure,
    private val newThread: (Runnable) -> Thread = ::daemonThread,
) {
    interface Watched {
        /** Expire the pending read if its deadline is at or before [now]; returns the pending deadline, or [Long.MAX_VALUE]. */
        fun expireIfDue(now: Long): Long
    }

    private val streams: MutableSet<Watched> = ConcurrentHashMap.newKeySet()

    /**
     * When the watching thread wakes up next; [Long.MAX_VALUE] while it scans, has nothing to wait
     * for, or does not run. A read that publishes an earlier deadline must wake it.
     */
    @Volatile private var nextWakeNanos = Long.MAX_VALUE

    private val lock = ReentrantLock()
    private val wake = lock.newCondition()

    /** The watching thread, or `null` when none runs. Guarded by [lock]; assigned before the thread is started. */
    private var thread: Thread? = null

    /** A wake-up the watching thread has not acted on yet. Guarded by [lock]. */
    private var signalled = false

    /** Open repositories. Guarded by [lock]. */
    private var users = 0

    fun register(stream: Watched) {
        streams.add(stream)
    }

    fun unregister(stream: Watched) {
        streams.remove(stream)
    }

    /** Keeps the watching thread, once started, running until the matching [release]. */
    fun retain() = lock.withLock {
        users++
    }

    /** Ends the watching thread when this was the last user and no read is pending. */
    fun release() = lock.withLock {
        check(users > 0) { "release() without retain()" }
        if (--users == 0 && thread != null) {
            // It rescans, and ends if nothing is pending.
            signalled = true
            wake.signal()
        }
    }

    /** Called after a read published [deadline]; wakes (or starts) the watching thread if it would sleep past it. */
    fun published(deadline: Long) {
        // A deadline at or after the next wake-up is found by the scan that follows that wake-up.
        if (deadline < nextWakeNanos) lock.withLock {
            signalled = true
            if (thread == null) start() else wake.signal()
        }
    }

    /** Starts a watching thread; it scans every registered stream before it first sleeps. Called with [lock] held. */
    private fun start() {
        val created = newThread(Runnable(::watch))
        thread = created
        try {
            created.start()
        } catch (failure: Throwable) {
            // No thread runs: the next published deadline tries again.
            thread = null
            throw failure
        }
    }

    private fun watch() {
        var ended = false
        try {
            while (true) {
                // A read publishing from here on sees MAX_VALUE and raises the flag, so the wait below is skipped.
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
                lock.withLock {
                    if (!signalled) {
                        if (next == Long.MAX_VALUE) {
                            if (users == 0) {
                                // Nothing to watch and nobody to watch for; nextWakeNanos is MAX_VALUE, so the next read starts a thread.
                                thread = null
                                ended = true
                                return
                            }
                            wake.await()
                        } else {
                            nextWakeNanos = next
                            wake.awaitNanos(next - System.nanoTime())
                        }
                    }
                    signalled = false
                }
            }
        } finally {
            // Reached without `ended` only on a failure that could not even be reported (or an interrupt).
            if (!ended) lock.withLock {
                thread = null
                nextWakeNanos = Long.MAX_VALUE
                // Deadlines published while this thread was ending are found by the scan the replacement starts with.
                start()
            }
        }
    }

    companion object {
        /** The watchdog of every response stream of this adapter. */
        val shared = ReadWatchdog()

        private fun daemonThread(body: Runnable): Thread = Thread(body, "kastor-sparql-deadline").apply { isDaemon = true }

        private fun logFailure(failure: Throwable) {
            System.getLogger(SparqlRepository::class.java.name).log(
                System.Logger.Level.WARNING,
                "A SPARQL response stream failed while its read deadline was checked; it is no longer watched",
                failure,
            )
        }
    }
}
