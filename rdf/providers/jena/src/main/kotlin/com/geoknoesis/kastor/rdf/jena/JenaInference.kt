package com.geoknoesis.kastor.rdf.jena

import org.apache.jena.graph.Graph
import org.apache.jena.graph.Node
import org.apache.jena.graph.Triple
import org.apache.jena.query.QueryCancelledException
import org.apache.jena.util.iterator.ExtendedIterator
import org.apache.jena.util.iterator.NiceIterator
import java.util.concurrent.atomic.AtomicLong

/**
 * Cooperative cancellation of inference work.
 *
 * A thread may install a cancellation check (a query deadline, or the token of a task running on an inference
 * worker); without one, an interrupted thread counts as cancelled. Store reads made by the reasoner call
 * [checkNotCancelled], so a long backward-chaining step stops soon after its query is cancelled.
 */
internal object InferenceCancellation {
    private val current = ThreadLocal<() -> Boolean>()

    fun cancelled(): Boolean = current.get()?.invoke() ?: Thread.currentThread().isInterrupted

    fun checkNotCancelled() {
        if (cancelled()) throw QueryCancelledException()
    }

    /** Runs [block] with [check] as this thread's cancellation check. */
    fun <T> withCheck(check: () -> Boolean, block: () -> T): T {
        val previous = current.get()
        current.set(check)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    /** Runs [block] cancelled once [deadlineNanos] (a [System.nanoTime] value) has passed or the thread is interrupted. */
    fun <T> withDeadline(deadlineNanos: Long, block: () -> T): T =
        withCheck({ Thread.currentThread().isInterrupted || System.nanoTime() - deadlineNanos > 0 }, block)
}

/**
 * The store graph as seen by a reasoner: reads check [InferenceCancellation] and are counted in [reads]
 * (a diagnostic used to verify that inference views stream instead of draining the store).
 */
internal class CancellableGraph(base: Graph, private val reads: AtomicLong) : org.apache.jena.sparql.graph.GraphWrapper(base) {
    override fun find(triple: Triple): ExtendedIterator<Triple> = guarded { super.find(triple) }
    override fun find(s: Node?, p: Node?, o: Node?): ExtendedIterator<Triple> = guarded { super.find(s, p, o) }

    override fun contains(triple: Triple): Boolean {
        InferenceCancellation.checkNotCancelled()
        return super.contains(triple)
    }

    override fun contains(s: Node?, p: Node?, o: Node?): Boolean {
        InferenceCancellation.checkNotCancelled()
        return super.contains(s, p, o)
    }

    private inline fun guarded(open: () -> ExtendedIterator<Triple>): ExtendedIterator<Triple> {
        InferenceCancellation.checkNotCancelled()
        val source = open()
        return object : NiceIterator<Triple>() {
            override fun hasNext(): Boolean {
                InferenceCancellation.checkNotCancelled()
                return source.hasNext()
            }

            override fun next(): Triple = source.next().also { reads.incrementAndGet() }

            override fun close() = source.close()
        }
    }
}

/** Runs the steps of a shared inference view on the thread that owns it. */
internal interface InferenceExecutor {
    /** Runs [block] on the owning thread and returns its result; cancellable by the calling reader. */
    fun <T> call(block: () -> T): T

    /** Schedules [block] on the owning thread without waiting (ignored once the owner has stopped). */
    fun submitQuietly(block: () -> Unit)
}

/** Timer releasing idle inference views. */
internal val IDLE_TIMER: java.util.concurrent.ScheduledExecutorService =
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "kastor-jena-inference-idle").apply { isDaemon = true }
    }

/**
 * A single worker thread that owns inference state (and, for shared views, the read transaction it was built in).
 *
 * [call] hands a step to the worker and waits for it while watching the *caller's* cancellation
 * ([InferenceCancellation]): when the caller's query times out or its thread is interrupted, the step's token is
 * set (store reads made by the step then throw), the caller gets a `QueryCancelledException` without waiting, and
 * `onBroken` is invoked if the step had already started, because its shared state may now be inconsistent.
 */
internal class InferenceWorker(name: String) {
    /** Threads started by [executor] (at most one at a time), so [awaitTermination] can wait for them to exit. */
    private val threads = java.util.concurrent.CopyOnWriteArrayList<Thread>()

    private val executor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue(),
    ) { task -> Thread(task, name).apply { isDaemon = true; threads.add(this) } }

    fun <T> call(onBroken: () -> Unit, block: () -> T): T {
        InferenceCancellation.checkNotCancelled()
        val token = java.util.concurrent.atomic.AtomicBoolean()
        val future = try {
            executor.submit(java.util.concurrent.Callable { InferenceCancellation.withCheck({ token.get() }) { block() } })
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            throw IllegalStateException("Inference view is closed", e)
        }
        while (true) {
            try {
                return future.get(POLL_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                if (InferenceCancellation.cancelled()) cancel(token, future, onBroken)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                cancel(token, future, onBroken)
            } catch (e: java.util.concurrent.ExecutionException) {
                onBroken()
                throw e.cause ?: e
            }
        }
    }

    private fun cancel(token: java.util.concurrent.atomic.AtomicBoolean, future: java.util.concurrent.Future<*>, onBroken: () -> Unit): Nothing {
        token.set(true)
        // A step that never started left no trace; one that did may have been stopped half-way.
        if (!future.cancel(false)) onBroken()
        throw QueryCancelledException()
    }

    fun submitQuietly(block: () -> Unit) {
        try {
            executor.execute { runCatching(block) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    /** Runs [last] after every queued step, then stops the thread. */
    fun shutdown(last: () -> Unit) {
        submitQuietly(last)
        executor.shutdown()
    }

    /** Waits up to [millis] for the stopped worker's thread to exit; true when it has. */
    fun awaitTermination(millis: Long): Boolean {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis)
        if (!executor.awaitTermination(millis, java.util.concurrent.TimeUnit.MILLISECONDS)) return false
        for (thread in threads) {
            val left = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left <= 0) return !thread.isAlive
            thread.join(left)
        }
        return threads.none { it.isAlive }
    }

    private companion object {
        const val POLL_MILLIS = 20L
    }
}

/**
 * Read-only facade over a prepared inference graph owned by an [InferenceExecutor]. Every operation on [inf] runs on
 * the owner; finds stream in chunks (small at first, growing up to [MAX_CHUNK]), so nothing is materialised beyond
 * one chunk per open iterator, and other readers' steps interleave between chunks.
 */
internal class SharedInferenceGraph(val inf: org.apache.jena.reasoner.InfGraph, private val owner: InferenceExecutor) :
    org.apache.jena.graph.impl.GraphBase() {

    override fun graphBaseFind(triplePattern: Triple): ExtendedIterator<Triple> = StreamingFind(triplePattern)

    override fun graphBaseContains(t: Triple): Boolean = owner.call { inf.contains(t) }

    /** Counts what [find] exposes (an inference graph's own size does not count every entailment). */
    override fun graphBaseSize(): Int = owner.call {
        val iterator = inf.find()
        try {
            var count = 0
            while (iterator.hasNext()) { iterator.next(); count++ }
            count
        } finally {
            iterator.close()
        }
    }

    override fun isEmpty(): Boolean = owner.call {
        val iterator = inf.find()
        try { !iterator.hasNext() } finally { iterator.close() }
    }

    override fun createPrefixMapping(): org.apache.jena.shared.PrefixMapping = inf.prefixMapping

    private inner class StreamingFind(private val pattern: Triple) : NiceIterator<Triple>() {
        /** The reasoner's iterator; only ever touched on the owner's thread. */
        private var source: ExtendedIterator<Triple>? = null
        private val buffer = ArrayDeque<Triple>()
        private var exhausted = false
        private var chunkSize = FIRST_CHUNK

        override fun hasNext(): Boolean {
            if (buffer.isNotEmpty()) return true
            if (exhausted) return false
            val size = chunkSize
            val chunk = try {
                owner.call {
                    val iterator = source ?: inf.find(pattern).also { source = it }
                    val out = ArrayList<Triple>(size)
                    while (out.size < size && iterator.hasNext()) out.add(iterator.next())
                    if (out.size < size) {
                        iterator.close()
                        source = null
                    }
                    out
                }
            } catch (e: Throwable) {
                exhausted = true
                throw e
            }
            if (chunk.size < size) exhausted = true
            chunkSize = minOf(size * 4, MAX_CHUNK)
            buffer.addAll(chunk)
            return buffer.isNotEmpty()
        }

        override fun next(): Triple {
            if (!hasNext()) throw NoSuchElementException()
            return buffer.removeFirst()
        }

        override fun close() {
            buffer.clear()
            if (exhausted) return
            exhausted = true
            owner.submitQuietly { source?.close(); source = null }
        }
    }

    private companion object {
        const val FIRST_CHUNK = 16
        const val MAX_CHUNK = 1_024
    }
}

/** A graph created on first read (so that merely listing a dataset's graphs never prepares an inference view). */
internal class LazyGraph(create: () -> Graph) : org.apache.jena.graph.impl.GraphBase() {
    private val target by lazy(LazyThreadSafetyMode.NONE, create)

    override fun graphBaseFind(triplePattern: Triple): ExtendedIterator<Triple> = target.find(triplePattern)
    override fun graphBaseContains(t: Triple): Boolean = target.contains(t)
    override fun graphBaseSize(): Int = target.size()
    override fun isEmpty(): Boolean = target.isEmpty
    override fun createPrefixMapping(): org.apache.jena.shared.PrefixMapping = target.prefixMapping
}
