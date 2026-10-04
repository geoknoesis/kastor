package com.geoknoesis.kastor.rdf

import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** A stream that fails once a URL-loading limit is exceeded and remembers the failure it threw. */
internal interface LimitedStream {
    val failure: java.io.IOException?
}

/**
 * Input stream that throws [RdfLoadTimeoutException] once [timeoutMillis] have passed since [startedNanos].
 *
 * - Reaching the end of the stream is never a timeout, even if the final read returns after the deadline.
 * - [blockingReadMillis] is the longest one read of the underlying stream can block (`0` = unbounded), or null if
 *   reads do not block. A read runs on the calling thread when more time than that remains, or when the underlying
 *   stream reports data available (such a read does not block). Any other read is done by the stream's helper
 *   ([StreamHelper]) while the caller waits only until the deadline, so no read can overrun it. A read given up
 *   this way ends the stream: later reads fail with the same timeout, and the underlying stream is closed by the
 *   helper thread once the read returns, because the JDK's HTTP streams only close once a blocked read has
 *   returned. When every helper thread is busy the read waits for one until the deadline.
 * - A read given up because the reading thread was interrupted ends the stream as well, and is reported as what it
 *   is: that read and every later one fail with [java.io.InterruptedIOException], never with a timeout.
 * - An I/O failure that happens after the deadline is reported as a timeout (an interrupt is not).
 * - [helpers] is the pool the stream's helper runs in, and [nanoTime] the clock of the deadline.
 * - [onClose] runs when the stream is closed, before the underlying stream is closed (so an HTTP connection can still
 *   be disconnected: closing its stream first forgets the connection), and after a read in flight returns.
 * - [helperIdleMillis] is how long the helper waits for the next read before it gives its thread back.
 */
internal class DeadlineInputStream(
    input: InputStream,
    private val startedNanos: Long,
    private val timeoutMillis: Long,
    blockingReadMillis: Long? = null,
    private val helperIdleMillis: Long = HELPER_IDLE_MILLIS,
    private val helpers: UrlLoadHelperPool = UrlLoadHelpers.shared,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onClose: () -> Unit = {},
) : FilterInputStream(input), LimitedStream {
    private val limitNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    private val blockingReadNanos: Long? = blockingReadMillis?.let {
        if (it <= 0) Long.MAX_VALUE else TimeUnit.MILLISECONDS.toNanos(it)
    }
    private var ended = false

    /**
     * Set when a read was given up - at the deadline, or because the reading thread was interrupted: that read may
     * still be running, so no other read may start. Says which of the two it was.
     */
    @Volatile private var abandonment: Abandonment? = null
    @Volatile private var helper: StreamHelper? = null
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private var single: ByteArray? = null

    override var failure: java.io.IOException? = null
        private set

    private fun remainingNanos() = limitNanos - (nanoTime() - startedNanos)

    private fun interrupted(cause: Throwable?): java.io.InterruptedIOException =
        java.io.InterruptedIOException(
            if (cause == null) "Loading RDF from a URL was abandoned when the reading thread was interrupted"
            else "Interrupted while loading RDF from a URL",
        ).apply { if (cause != null) initCause(cause) }

    private fun timeout(): java.io.IOException = failure ?: RdfLoadTimeoutException(timeoutMillis).also { failure = it }

    fun checkDeadline() {
        if (remainingNanos() < 0) throw timeout()
    }

    /** True if a read may block without outlasting the deadline, so it can run on the calling thread. */
    private fun readsDirectly(remaining: Long): Boolean = blockingReadNanos == null || remaining > blockingReadNanos

    /** True if the underlying stream has data at hand, so a read of it returns without blocking. */
    private fun dataAvailable(): Boolean = try {
        `in`.available() > 0
    } catch (_: java.io.IOException) {
        false
    }

    /**
     * Runs one read of the underlying stream without letting it outlast the deadline: [direct] on the calling thread
     * if it cannot, otherwise [viaHelper], which is given the time left.
     */
    private inline fun timed(direct: () -> Long, viaHelper: (remaining: Long) -> Long): Long {
        when (abandonment) {
            Abandonment.DEADLINE -> throw timeout()
            Abandonment.INTERRUPT -> throw interrupted(null)
            null -> Unit
        }
        val remaining = remainingNanos()
        val mayOverrun = !readsDirectly(remaining)
        try {
            return if (!mayOverrun || dataAvailable()) direct() else viaHelper(remaining)
        } catch (e: java.io.IOException) {
            // A read that was allowed to block past the deadline has a socket timeout no shorter than the time that was
            // left, so its socket timeout is the deadline expiring (even if the clock says a moment is left).
            val socketTimeout = e is java.net.SocketTimeoutException
            val deadlinePassed = remainingNanos() < 0 || (socketTimeout && mayOverrun)
            if (e is RdfInputTooLargeException || e is RdfLoadTimeoutException || !deadlinePassed ||
                (e is java.io.InterruptedIOException && !socketTimeout)) throw e
            throw timeout().also { if (it !== e) it.addSuppressed(e) }
        }
    }

    /**
     * Has the helper do one read ([ask] returns [StreamHelper.GONE] when the helper it was given has left its
     * thread; another one is started then). Gives the stream up if the read does not return in time.
     */
    private inline fun helped(remaining: Long, ask: (StreamHelper) -> Long): Long {
        while (true) {
            if (closed.get()) throw java.io.IOException("Stream closed")
            try {
                val result = ask(helper ?: startHelper(remaining))
                if (result != StreamHelper.GONE.toLong()) return result
                helper = null
            } catch (_: java.util.concurrent.TimeoutException) {
                abandonment = Abandonment.DEADLINE
                throw timeout()
            } catch (e: InterruptedException) {
                abandonment = Abandonment.INTERRUPT
                Thread.currentThread().interrupt()
                throw interrupted(e)
            }
        }
    }

    /** Starts the helper of this stream, waiting for a free helper thread only as long as the deadline allows. */
    private fun startHelper(remaining: Long): StreamHelper {
        val created = StreamHelper(`in`, TimeUnit.MILLISECONDS.toNanos(helperIdleMillis))
        // Interrupted while waiting for a thread: no read is in flight, the stream stays usable.
        val accepted = try {
            helpers.execute(created, remaining)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted(e)
        }
        created.handle = accepted ?: throw timeout()
        helper = created
        // Closed meanwhile by another thread (a cancelled load): the helper must not outlive the stream.
        if (closed.get()) created.finish {}
        return created
    }

    override fun read(): Int {
        if (ended) return -1
        checkDeadline()
        val one = single ?: ByteArray(1).also { single = it }
        val n = timed(
            direct = { val b = `in`.read(); if (b < 0) -1L else { one[0] = b.toByte(); 1L } },
            viaHelper = { remaining -> helped(remaining) { it.read(one, 0, 1, remainingNanos()).toLong() } },
        )
        if (n < 0) { ended = true; return -1 }
        checkDeadline()
        return one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (ended) return -1
        if (len == 0) return 0
        checkDeadline()
        // A read on the calling thread fills the caller's array directly. A helper read fills the helper's buffer,
        // which is copied once the read has returned, so a read that was given up never writes into the caller's array.
        val n = timed(
            direct = { `in`.read(b, off, len).toLong() },
            viaHelper = { remaining -> helped(remaining) { it.read(b, off, len, remainingNanos()).toLong() } },
        )
        if (n < 0) { ended = true; return -1 }
        checkDeadline()
        return n.toInt()
    }

    override fun skip(n: Long): Long {
        checkDeadline()
        return timed(
            direct = { `in`.skip(n) },
            viaHelper = { remaining -> helped(remaining) { it.skip(n, remainingNanos()) } },
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val release = {
            runCatching { onClose() }
            runCatching { `in`.close() }
            Unit
        }
        // With a read in flight (given up at the deadline, or still running when another thread closes the stream)
        // the helper releases the stream when that read returns.
        if (helper?.finish(release) == false) return
        try {
            onClose()
        } finally {
            super.close()
        }
    }

    override fun markSupported(): Boolean = false

    /** Why a read was given up. */
    private enum class Abandonment { DEADLINE, INTERRUPT }

    companion object {
        /** How long a stream's helper waits for the next read before it gives its thread back to the pool. */
        const val HELPER_IDLE_MILLIS = 2_000L
    }
}

/** Input stream that throws [RdfInputTooLargeException] once more than [limit] bytes have been read. */
internal class BoundedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input), LimitedStream {
    private var count = 0L

    override var failure: java.io.IOException? = null
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) advance(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) advance(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) advance(skipped)
        return skipped
    }

    override fun markSupported(): Boolean = false

    private fun advance(n: Long) {
        count += n
        if (count > limit) throw (failure ?: RdfInputTooLargeException(limit).also { failure = it })
    }
}

/**
 * Counts bytes read, so a parse can tell whether a provider consumed input before declining it.
 *
 * [close] does **not** close the underlying stream: this is the stream the `parseFromInputStream`, `parseStreaming`
 * and `parseDataset` entry points of [Rdf] hand to a provider, and the stream they were given stays the caller's to
 * close, whatever the provider's parser does when it is done (Jena's parsers close their input).
 */
internal class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun close() = Unit

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count += n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) count += skipped
        return skipped
    }

    override fun markSupported(): Boolean = false
}

/** Finds a URL-loading limit failure (body size or overall deadline) wrapped by a provider's own parser exceptions. */
internal fun Throwable.inputLimitCause(): java.io.IOException? =
    generateSequence(this) { it.cause }.take(16)
        .firstOrNull { it is RdfInputTooLargeException || it is RdfLoadTimeoutException } as java.io.IOException?
