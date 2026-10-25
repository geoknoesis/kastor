package com.geoknoesis.kastor.rdf

import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cancellation of one [Rdf.parseFromUrlAsync] load. [cancel] never blocks the cancelling thread: disconnecting a JDK
 * HTTP connection or closing its stream waits for a connect or read in progress on another thread (up to the
 * connect or read timeout), so the release is handed to a thread of [helpers]. When all of them are busy it is
 * queued on [overflow], which has a fixed, small number of threads, so a burst of cancellations never starts more
 * threads than the helper pool and [overflow] allow.
 */
internal class UrlLoadCancellation(
    private val helpers: UrlLoadHelperPool,
    private val overflow: Executor = sharedOverflow,
) {
    private val lock = Any()
    private var cancelled = false
    private var connection: java.net.URLConnection? = null
    private var input: java.io.InputStream? = null

    /**
     * Called on the connecting thread right before [connection] connects: fails with
     * [java.util.concurrent.CancellationException] if the load was cancelled, so the request is never sent.
     */
    fun connecting(connection: java.net.URLConnection) = synchronized(lock) {
        if (cancelled) throw java.util.concurrent.CancellationException("Cancelled")
        this.connection = connection
    }

    /** Called with the body before it is parsed; fails like [connecting] if the load was cancelled. */
    fun reading(input: java.io.InputStream) = synchronized(lock) {
        if (cancelled) throw java.util.concurrent.CancellationException("Cancelled")
        this.input = input
    }

    fun cancel() {
        val (connection, input) = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            connection to input
        }
        if (connection == null && input == null) return
        val release = Runnable {
            (connection as? java.net.HttpURLConnection)?.let { runCatching { it.disconnect() } }
            runCatching { input?.close() }
        }
        if (helpers.tryExecute(release) == null) overflow.execute(release)
    }

    companion object {
        /** Threads of the shared overflow releaser. */
        const val OVERFLOW_THREADS = 2

        /** Name prefix of the overflow releaser threads. */
        const val OVERFLOW_THREAD_PREFIX = "kastor-url-cancel-"

        /**
         * A releaser with at most [threads] daemon threads and an unbounded queue (a queued release is two small
         * objects). Its threads end after 10 s without work.
         */
        fun overflowExecutor(threads: Int): ThreadPoolExecutor {
            val count = AtomicInteger()
            return ThreadPoolExecutor(threads, threads, 10, TimeUnit.SECONDS, LinkedBlockingQueue()) { runnable ->
                Thread(runnable, OVERFLOW_THREAD_PREFIX + count.incrementAndGet()).apply { isDaemon = true }
            }.apply { allowCoreThreadTimeOut(true) }
        }

        private val sharedOverflow: Executor by lazy { overflowExecutor(OVERFLOW_THREADS) }
    }
}
