package com.geoknoesis.kastor.rdf.sparql

import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Enforces the per-read timeout and the overall deadline on a response body: every read
 * publishes its deadline and [ReadWatchdog] closes the underlying stream when the read waits
 * past it, which unblocks the reader. Time between reads (the consumer's own work) counts
 * towards the overall deadline, checked on every read and by [checkDeadline].
 */
internal class GuardedInputStream(
    private val raw: InputStream,
    private val watchdog: ReadWatchdog,
    readTimeout: Duration,
    private val deadlineNanos: Long?,
    overall: Duration?,
) : InputStream(), ReadWatchdog.Watched {
    private val readTimeoutNanos = Durations.nanos(readTimeout)
    private val readTimeoutMessage = "SPARQL response read timed out after ${Durations.millis(readTimeout)} ms"
    private val deadlineMessage = "SPARQL request exceeded its ${overall?.let(Durations::millis)} ms deadline"

    /** The deadline of a read, with the message that says what bounds it. */
    private class PendingRead(val deadline: Long, val message: String)

    /**
     * The read in progress, or `null` between reads and once the watchdog claimed it. Whoever
     * replaces it (the read when it ends, the watchdog when it is overdue) wins, so a read is
     * either left alone or expired, never both, and the message that expires it is its own.
     */
    private val pending = AtomicReference<PendingRead?>(null)

    @Volatile var failure: String? = null
        private set

    init {
        watchdog.register(this)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    /** Throws if the stream already failed or the overall deadline has passed. */
    fun checkDeadline() {
        failure?.let { throw IOException(it) }
        if (deadlineNanos != null && deadlineNanos - System.nanoTime() <= 0) {
            failure = deadlineMessage
            closeRaw()
            throw IOException(deadlineMessage)
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkDeadline()
        val now = System.nanoTime()
        val untilDeadline = deadlineNanos?.let { it - now } ?: Durations.UNBOUNDED
        val byDeadline = untilDeadline < readTimeoutNanos
        // Nothing is published for a read that neither the read timeout nor a deadline bounds.
        val deadline = Durations.deadline(now, if (byDeadline) untilDeadline else readTimeoutNanos)
        val mine = deadline?.let { PendingRead(it, if (byDeadline) deadlineMessage else readTimeoutMessage) }
        if (mine != null) {
            pending.set(mine)
            watchdog.published(mine.deadline)
        }
        try {
            return raw.read(b, off, len)
        } catch (e: IOException) {
            failure?.let { throw IOException(it, e) }
            throw e
        } finally {
            // Losing this exchange means the watchdog claimed the read just as it ended: the stream is
            // failed (or about to be) for the read's own reason, so the next read cannot start unnoticed.
            if (mine != null && !pending.compareAndSet(mine, null)) {
                failure = failure ?: mine.message
                closeRaw()
            }
        }
    }

    /** Tests: makes a read with [deadline] pending, as [read] does, without blocking in one. */
    internal fun publishForTest(deadline: Long) {
        pending.set(PendingRead(deadline, readTimeoutMessage))
    }

    override fun expireIfDue(now: Long): Long {
        val read = pending.get() ?: return Long.MAX_VALUE
        if (!Durations.reached(now, read.deadline)) return read.deadline
        if (pending.compareAndSet(read, null)) {
            failure = failure ?: read.message
            closeRaw()
        }
        return Long.MAX_VALUE
    }

    /** Closing is what unblocks the reader; whatever the stream's own close() throws changes nothing about the timeout. */
    private fun closeRaw() {
        try { raw.close() } catch (_: Exception) { }
    }

    override fun close() {
        watchdog.unregister(this)
        raw.close()
    }
}
