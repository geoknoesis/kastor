package com.geoknoesis.kastor.rdf

import java.io.InputStream
import java.util.concurrent.TimeUnit

/** The helper pool shared by all URL loads of the JVM, and how it is configured. */
internal object UrlLoadHelpers {
    const val THREAD_NAME_PREFIX = "kastor-url-helper-"

    /** System property: the largest number of helper threads (a positive integer). */
    const val MAX_THREADS_PROPERTY = "kastor.url.helperThreads"

    /** System property: milliseconds after which a helper whose abandoned work has not returned is replaced. */
    const val ABANDON_GRACE_PROPERTY = "kastor.url.helperAbandonGraceMillis"

    /** The default bound is never lower than this. */
    const val MIN_DEFAULT_THREADS = 32

    /** Helper threads per available processor of the default bound: the threads wait for the network. */
    const val DEFAULT_THREADS_PER_PROCESSOR = 4

    const val DEFAULT_ABANDON_GRACE_MILLIS = 60_000L

    /** The bound for the value [configured] of [MAX_THREADS_PROPERTY] (null or unusable: the default). */
    fun maxThreads(configured: String?, processors: Int): Int =
        configured?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            ?: maxOf(MIN_DEFAULT_THREADS, DEFAULT_THREADS_PER_PROCESSOR * processors)

    /** The grace for the value [configured] of [ABANDON_GRACE_PROPERTY] (null or unusable: the default). */
    fun abandonGraceMillis(configured: String?): Long =
        configured?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: DEFAULT_ABANDON_GRACE_MILLIS

    /** The pool of every load that does not bring its own [UrlLoadRuntime]. */
    val shared: UrlLoadHelperPool by lazy {
        UrlLoadHelperPool(
            maxThreads(System.getProperty(MAX_THREADS_PROPERTY), Runtime.getRuntime().availableProcessors()),
            abandonGraceMillis(System.getProperty(ABANDON_GRACE_PROPERTY)),
        )
    }
}

/**
 * Bounded pool of daemon threads for URL-loading work that may block past the overall deadline. A load uses at most
 * one helper at a time, and the pool never has more than [maxThreads] threads - except for the ones it gave up on:
 *
 * A helper whose work was abandoned ([Handle.abandon]) is interrupted and stays busy until that work returns, which
 * the capped socket timeouts bound for requests and reads. Work that does not return - a host name lookup of the
 * system resolver, a custom policy that hangs - would hold its helper forever: once [abandonGraceMillis] have passed
 * since it was abandoned, its helper stops counting as busy and the pool may start one thread more in its place. The
 * hung thread itself cannot be stopped; when its work does return, the pool goes back to its bound.
 *
 * [nanoTime] is the clock of the grace period (the waits of [execute] use the system clock).
 */
internal class UrlLoadHelperPool(
    val maxThreads: Int,
    abandonGraceMillis: Long = UrlLoadHelpers.DEFAULT_ABANDON_GRACE_MILLIS,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    init {
        require(maxThreads > 0) { "maxThreads must be positive, got $maxThreads" }
        require(abandonGraceMillis >= 0) { "abandonGraceMillis must not be negative, got $abandonGraceMillis" }
    }

    private val graceNanos = TimeUnit.MILLISECONDS.toNanos(abandonGraceMillis)
    private val threadCount = java.util.concurrent.atomic.AtomicInteger()

    /** No queue: a task is handed to a thread that is free, or to a new one while there is room, or not at all. */
    private val threads = java.util.concurrent.ThreadPoolExecutor(
        0, maxThreads, 10, TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
        { runnable ->
            Thread(runnable, UrlLoadHelpers.THREAD_NAME_PREFIX + threadCount.incrementAndGet()).apply { isDaemon = true }
        },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
    )

    /** Abandoned work that has not returned yet. */
    private val abandoned = java.util.concurrent.ConcurrentLinkedQueue<Handle>()

    /** Tasks that were handed to a thread and count as busy: not finished, and not given up on. */
    private val busyCount = java.util.concurrent.atomic.AtomicInteger()
    private val idleLock = Object()

    /** Helpers given up on whose work is still running; guarded by [resizeLock]. */
    private var hung = 0
    private val resizeLock = Any()

    /** Number of tasks handed to a helper thread so far. */
    val tasksStarted = java.util.concurrent.atomic.AtomicLong()

    /** Number of helpers that stopped counting as busy because their abandoned work outlived the grace period. */
    val helpersReplaced = java.util.concurrent.atomic.AtomicLong()

    /** Helpers that count as busy right now. */
    val busy: Int get() = busyCount.get()

    /** The largest number of threads this pool has had at one time. */
    val largestThreadCount: Int get() = threads.largestPoolSize

    /** The number of threads the pool may have right now: [maxThreads], and one for every helper it gave up on. */
    val threadLimit: Int get() = threads.maximumPoolSize

    /** A task running (or about to run) on a helper thread. */
    inner class Handle internal constructor(private val task: Runnable) : Runnable {
        // Guarded by this.
        private var thread: Thread? = null
        private var done = false
        private var interrupt = false
        private var replaced = false
        private val finished = java.util.concurrent.CountDownLatch(1)

        /** When the task was abandoned, by the clock of the pool. */
        @Volatile internal var abandonedAt = 0L

        internal val isDone: Boolean get() = synchronized(this) { done }

        override fun run() {
            synchronized(this) {
                thread = Thread.currentThread()
                if (interrupt) Thread.currentThread().interrupt()
            }
            try {
                task.run()
            } finally {
                val wasAbandoned: Boolean
                val wasReplaced: Boolean
                synchronized(this) {
                    done = true
                    thread = null
                    // An interrupt meant for this task must not reach the next task of the thread.
                    Thread.interrupted()
                    wasAbandoned = interrupt
                    wasReplaced = replaced
                }
                if (wasAbandoned) abandoned.remove(this)
                if (wasReplaced) resize(-1) else uncount()
                finished.countDown()
            }
        }

        /** Gives up on the task if it is still running: it stops counting as busy; true if this call did that. */
        internal fun replace(): Boolean {
            synchronized(this) {
                if (done || replaced) return false
                replaced = true
            }
            resize(+1)
            uncount()
            return true
        }

        /**
         * The caller no longer waits for the task: interrupts it if it has not returned (once), and lets the pool
         * replace its helper if it has still not returned when the grace period is over.
         */
        fun abandon() {
            synchronized(this) {
                if (done || interrupt) return
                interrupt = true
                abandonedAt = nanoTime()
                abandoned.add(this)
                thread?.interrupt()
            }
        }

        /** Waits up to [millis] until the task has returned and the pool has taken note; true if it has. */
        fun awaitDone(millis: Long): Boolean = finished.await(millis, TimeUnit.MILLISECONDS)
    }

    /** One thread more ([delta] = 1) while a helper that was given up on still runs, one less when it has returned. */
    private fun resize(delta: Int) {
        synchronized(resizeLock) {
            hung += delta
            threads.maximumPoolSize = maxThreads + hung
        }
    }

    private fun uncount() {
        if (busyCount.decrementAndGet() <= 0) synchronized(idleLock) { idleLock.notifyAll() }
    }

    /** Gives up on the helpers whose abandoned work has outlived the grace period; true if there was one. */
    private fun reclaim(): Boolean {
        if (abandoned.isEmpty()) return false
        val now = nanoTime()
        var freed = false
        val each = abandoned.iterator()
        while (each.hasNext()) {
            val handle = each.next()
            if (handle.isDone) {
                each.remove()
            } else if (now - handle.abandonedAt >= graceNanos) {
                each.remove()
                if (handle.replace()) {
                    helpersReplaced.incrementAndGet()
                    freed = true
                }
            }
        }
        return freed
    }

    /** Hands [handle] to a thread that is free, or to a new one if there is room; false if there is neither. */
    private fun start(handle: Handle): Boolean {
        busyCount.incrementAndGet()
        try {
            threads.execute(handle)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            uncount()
            return false
        }
        tasksStarted.incrementAndGet()
        return true
    }

    /** Starts [task] on a helper thread; null (without running it) if all [maxThreads] helpers are busy. */
    fun tryExecute(task: Runnable): Handle? {
        val handle = Handle(task)
        return if (start(handle) || (reclaim() && start(handle))) handle else null
    }

    /**
     * Starts [task] on a helper thread, waiting up to [waitNanos] for one to become free; null (without running it)
     * if none did.
     *
     * @throws InterruptedException if the calling thread is interrupted while it waits (the task then never runs)
     */
    fun execute(task: Runnable, waitNanos: Long): Handle? {
        val handle = Handle(task)
        val deadline = System.nanoTime() + waitNanos
        while (!threads.isShutdown) {
            if (start(handle) || (reclaim() && start(handle))) return handle
            val left = deadline - System.nanoTime()
            if (left <= 0) return null
            // Every thread is busy: hand the task to the first one that asks for work. The wait is sliced because
            // a pool whose threads all retired meanwhile has nobody asking, but room for a new thread - and so has
            // one whose hung helper outlives its grace period.
            busyCount.incrementAndGet()
            val taken = try {
                threads.queue.offer(handle, minOf(left, HAND_OFF_SLICE_NANOS), TimeUnit.NANOSECONDS)
            } catch (e: InterruptedException) {
                uncount()
                throw e
            }
            if (taken) {
                tasksStarted.incrementAndGet()
                return handle
            }
            uncount()
        }
        return null
    }

    /** Waits up to [millis] until no helper counts as busy; true if that happened. */
    fun awaitIdle(millis: Long): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        synchronized(idleLock) {
            while (busyCount.get() > 0) {
                val left = end - System.nanoTime()
                if (left <= 0) return false
                TimeUnit.NANOSECONDS.timedWait(idleLock, left)
            }
            return true
        }
    }

    /** Interrupts every helper and waits a moment for the threads to end; no task is accepted afterwards. */
    override fun close() {
        threads.shutdownNow()
        runCatching { threads.awaitTermination(10, TimeUnit.SECONDS) }
    }

    private companion object {
        /** Longest single wait for a thread to take a task before the pool is asked again. */
        val HAND_OFF_SLICE_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(250)
    }
}

/**
 * One blocking call run on a thread of [pool]. If the caller stops waiting, [abandon] interrupts the call and hands
 * clean-up to the helper, which runs it once the call returns: the JDK's HTTP connections and streams cannot be
 * closed from another thread while such a call blocks (closing waits for it), so this releases them without
 * occupying a second thread.
 */
internal class HelperCall<T>(private val pool: UrlLoadHelperPool, action: () -> T) {
    private val lock = Any()
    private var started = false
    private var returned = false
    private var cleanup: (() -> Unit)? = null
    private var handle: UrlLoadHelperPool.Handle? = null
    private val task = java.util.concurrent.FutureTask {
        try {
            action()
        } finally {
            val pending = synchronized(lock) { returned = true; cleanup }
            if (pending != null) {
                // The interrupt that came with the abandonment was for the call, not for the clean-up after it.
                Thread.interrupted()
                runCatching(pending)
            }
        }
    }

    /**
     * Starts the call, waiting up to [waitNanos] for a free helper thread; false if none became free (the call then
     * never runs).
     */
    fun start(waitNanos: Long = 0): Boolean {
        val accepted = (if (waitNanos <= 0) pool.tryExecute(task) else pool.execute(task, waitNanos)) ?: return false
        synchronized(lock) { started = true; handle = accepted }
        return true
    }

    /** Waits up to [nanos] for the result, rethrowing the call's own failure. */
    fun await(nanos: Long): T = try {
        task.get(nanos.coerceAtLeast(1), TimeUnit.NANOSECONDS)
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    }

    /**
     * Runs [release] once the call has returned: now if it already has (or never started); otherwise the call is
     * interrupted and [release] runs on the helper thread when it returns.
     */
    fun abandon(release: () -> Unit) {
        var running: UrlLoadHelperPool.Handle? = null
        val now = synchronized(lock) {
            val pending = started && !returned
            if (pending) {
                cleanup = release
                running = handle
            }
            !pending
        }
        if (now) runCatching(release) else running?.abandon()
    }
}

/**
 * The long-lived helper of one [DeadlineInputStream]: a loop on a [UrlLoadHelperPool] thread that performs the reads
 * of [input] the stream asks for, one at a time, so that the stream can stop waiting for a read at its deadline. One
 * helper serves all such reads of a stream; there is no task, future or thread hand-over per read.
 *
 * The helper reads into its own buffer and the bytes are copied to the caller once the read has returned, so a read
 * the caller gave up on never writes into the caller's array. It leaves its thread when the stream is closed, when a
 * read was given up (after that read returns, running the clean-up handed to [finish]), or when it has not been
 * asked for [idleNanos]; the stream then starts another helper for its next read.
 */
internal class StreamHelper(private val input: InputStream, private val idleNanos: Long) : Runnable {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val asked = lock.newCondition()
    private val answered = lock.newCondition()

    // All guarded by lock.
    private var buffer = ByteArray(0)
    private var request = NONE
    private var argument = 0L
    private var reading = false
    private var answer = 0L
    private var failure: Throwable? = null
    private var done = false
    private var finished = false
    private var gone = false
    private var cleanup: (() -> Unit)? = null

    /** The pool's handle of the thread this helper runs on, set once it was started. */
    @Volatile var handle: UrlLoadHelperPool.Handle? = null

    override fun run() {
        while (true) {
            val operation: Int
            val amount: Long
            lock.lock()
            try {
                var idle = idleNanos
                while (request == NONE && !finished && idle > 0) {
                    idle = try {
                        asked.awaitNanos(idle)
                    } catch (_: InterruptedException) {
                        0
                    }
                }
                if (request == NONE || finished) {
                    gone = true
                    return
                }
                operation = request
                amount = argument
                reading = true
                if (operation == READ && buffer.size < amount) buffer = ByteArray(amount.toInt())
            } finally {
                lock.unlock()
            }
            var result = 0L
            var error: Throwable? = null
            try {
                result = if (operation == READ) input.read(buffer, 0, amount.toInt()).toLong() else input.skip(amount)
            } catch (e: Throwable) {
                error = e
            }
            val pending: (() -> Unit)?
            lock.lock()
            try {
                reading = false
                request = NONE
                answer = result
                failure = error
                done = true
                answered.signalAll()
                pending = cleanup
                if (pending != null) gone = true
            } finally {
                lock.unlock()
            }
            if (pending != null) {
                // The interrupt that came with the abandonment was for the read, not for the clean-up after it.
                Thread.interrupted()
                runCatching(pending)
                return
            }
        }
    }

    /**
     * Asks the helper to read up to [length] bytes, waits up to [nanos] for it and copies the bytes read to
     * [target] at [offset]. Returns the number of bytes read, -1 at the end of the stream, or [GONE] if the helper
     * has left its thread (nothing was read; ask a new helper). Rethrows the failure of the read.
     *
     * @throws java.util.concurrent.TimeoutException if the read did not return in time: it is still running, no
     *   other read may be asked for, and [finish] must be called
     * @throws InterruptedException if the calling thread was interrupted while waiting, with the same consequences
     */
    fun read(target: ByteArray, offset: Int, length: Int, nanos: Long): Int {
        lock.lock()
        try {
            val read = perform(READ, length.toLong(), nanos)
            if (read > 0) System.arraycopy(buffer, 0, target, offset, read.toInt())
            return read.toInt()
        } finally {
            lock.unlock()
        }
    }

    /** Like [read], for skipping up to [count] bytes; returns the number of bytes skipped or [GONE]. */
    fun skip(count: Long, nanos: Long): Long {
        lock.lock()
        try {
            return perform(SKIP, count, nanos)
        } finally {
            lock.unlock()
        }
    }

    /** Called with the lock held. */
    private fun perform(operation: Int, amount: Long, nanos: Long): Long {
        if (gone || finished) return GONE.toLong()
        request = operation
        argument = amount
        done = false
        asked.signal()
        var left = nanos
        while (!done) {
            if (left <= 0) throw java.util.concurrent.TimeoutException()
            left = answered.awaitNanos(left)
        }
        failure?.let { throw it }
        return answer
    }

    /**
     * Ends the helper. If a read is in flight, it is interrupted, [release] runs on the helper thread once that read
     * returns and the result is false; otherwise the helper leaves its thread, nothing else happens and the result
     * is true (the caller releases the stream itself).
     */
    fun finish(release: () -> Unit): Boolean {
        lock.lock()
        try {
            finished = true
            if (reading) {
                cleanup = release
                handle?.abandon()
                return false
            }
            // A request the helper has not picked up yet is withdrawn with it.
            request = NONE
            asked.signal()
            return true
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /** Result of [read] and [skip] when the helper has left its thread. */
        const val GONE = Int.MIN_VALUE
        private const val NONE = 0
        private const val READ = 1
        private const val SKIP = 2
    }
}
