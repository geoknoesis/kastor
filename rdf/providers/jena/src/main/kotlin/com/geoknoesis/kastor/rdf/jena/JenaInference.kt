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

    /** Runs [block] cancelled once [deadlineNanos] (a value of [clock]) has passed or the thread is interrupted. */
    fun <T> withDeadline(deadlineNanos: Long, clock: () -> Long = System::nanoTime, block: () -> T): T =
        withCheck({ Thread.currentThread().isInterrupted || clock() - deadlineNanos > 0 }, block)

    /**
     * A **clean** cancellation point of a step running on an inference worker: a place where the reasoner is not in
     * the middle of anything (the step has not called it yet, or it is between two results of an iterator), so that
     * stopping here leaves its state as consistent as it is between two steps. Throws [CleanStepCancellation] when
     * the step was cancelled.
     */
    fun checkpoint() {
        if (cancelled()) throw CleanStepCancellation()
    }
}

/**
 * A step stopped at a clean cancellation point ([InferenceCancellation.checkpoint]): the state it shares with other
 * steps is intact. A `QueryCancelledException` raised anywhere else (inside a store read made by the reasoner) means
 * the reasoner was interrupted half-way.
 */
internal class CleanStepCancellation : QueryCancelledException()

/**
 * A step failed in work that no other step can see (it was building something it had not published yet): the view
 * stays healthy and the reader gets [failure].
 */
internal class CleanStepFailure(val failure: Throwable) : RuntimeException(null, failure, false, false)

/**
 * Marks (as its cause) the [com.geoknoesis.kastor.rdf.RdfInferenceException] of a step that **never ran** because
 * the view it was meant for had been invalidated by another reader's step. Nothing of that step happened, so the
 * operation it belonged to can be repeated on a fresh view if the caller has not received any of its results yet.
 */
internal class InferenceViewInvalidated : RuntimeException(null, null, false, false)

/** The failure of a step that never ran because its view was invalidated (see [InferenceViewInvalidated]). */
internal fun inferenceViewInvalidated(): com.geoknoesis.kastor.rdf.RdfInferenceException =
    com.geoknoesis.kastor.rdf.RdfInferenceException(
        "The shared inference view of this snapshot was invalidated because a reasoning step of one of " +
            "its readers was cancelled or failed half-way; results read from it could be incomplete. " +
            "Retry the read: it is served by a fresh view.",
        cause = InferenceViewInvalidated(),
    )

/**
 * The store graph as seen by a reasoner: reads check [InferenceCancellation] and are counted in [reads]
 * (a diagnostic used to verify that inference views stream instead of draining the store).
 */
internal class CancellableGraph(
    base: Graph,
    private val reads: AtomicLong,
    /** Called before every store read (a test seam, see [JenaInferenceHooks.onStoreRead]). */
    private val beforeRead: () -> Unit = {},
) : org.apache.jena.sparql.graph.GraphWrapper(base) {
    override fun find(triple: Triple): ExtendedIterator<Triple> = guarded { super.find(triple) }
    override fun find(s: Node?, p: Node?, o: Node?): ExtendedIterator<Triple> = guarded { super.find(s, p, o) }

    override fun contains(triple: Triple): Boolean {
        beforeRead()
        InferenceCancellation.checkNotCancelled()
        return super.contains(triple)
    }

    override fun contains(s: Node?, p: Node?, o: Node?): Boolean {
        beforeRead()
        InferenceCancellation.checkNotCancelled()
        return super.contains(s, p, o)
    }

    private inline fun guarded(open: () -> ExtendedIterator<Triple>): ExtendedIterator<Triple> {
        beforeRead()
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

    /**
     * Called by a running step wherever it can stop cleanly (see [InferenceCancellation.checkpoint]), in particular
     * between two results it takes from the reasoner, so that a cancelled step ends even when it needs no store read.
     */
    fun checkpoint() = InferenceCancellation.checkpoint()

    /** Called by a running step each time it has taken a result from the reasoner (a test seam). */
    fun resultTaken() {}
}

/**
 * Seams of a [JenaRepository]'s inference views. Production code uses the defaults; tests replace single hooks to
 * make concurrency scenarios deterministic (block a step inside a store read, count scheduled idle checks, ...).
 */
internal class JenaInferenceHooks {
    /** Runs on the thread doing a store read for an inference view, before the read and its cancellation check. */
    @Volatile var onStoreRead: () -> Unit = {}

    /**
     * Runs on the worker at every point where a step can be cancelled cleanly (when it starts, and between two
     * results it takes from the reasoner), before the cancellation check.
     */
    @Volatile var onStepCheckpoint: () -> Unit = {}

    /** Runs on the worker each time a step has taken a result from the reasoner (the next checkpoint is "between two results"). */
    @Volatile var onStepResult: () -> Unit = {}

    /** Runs on a reader's thread right after it handed a step to the worker of a shared view (the step is queued or running). */
    @Volatile var onStepSubmitted: () -> Unit = {}

    /**
     * How long a reader that cancels a step which is already running waits for that step to reach a cancellation
     * point, before it declares the step stuck and gives the view up (see "Poisoned views" in [JenaRepository]).
     */
    @Volatile var cancelGraceNanos: Long = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100)

    /** Runs on the reader's thread while it opens a shared view (never under the repository's view lock). */
    @Volatile var onOpenView: () -> Unit = {}

    /** Schedules an idle check after the given delay in nanoseconds. */
    @Volatile var schedule: (Long, Runnable) -> java.util.concurrent.ScheduledFuture<*> =
        { nanos, task -> IDLE_TIMER.schedule(task, nanos, java.util.concurrent.TimeUnit.NANOSECONDS) }

    /** Monotonic clock in nanoseconds used for idle bookkeeping and for the deadline of timed queries. */
    @Volatile var clock: () -> Long = System::nanoTime

    /** Longest time `close()` waits for readers to release their inference views (set from the repository's options). */
    @Volatile var closeWaitNanos: Long = java.util.concurrent.TimeUnit.SECONDS.toNanos(10)

    /** Longest time `close()` then waits for workers it stopped forcibly to end their read transactions (likewise). */
    @Volatile var closeGraceNanos: Long = java.util.concurrent.TimeUnit.SECONDS.toNanos(2)

    /** Runs in `close()` right after the store was closed. */
    @Volatile var onStoreClosed: () -> Unit = {}

    /** Runs in `close()` after the views were retired, before it waits for them. */
    @Volatile var onCloseWaiting: () -> Unit = {}

    /** Receives the warnings of `close()`. */
    @Volatile var warn: (String) -> Unit = { org.slf4j.LoggerFactory.getLogger(JenaRepository::class.java).warn(it) }
}

/**
 * Timer releasing idle inference views. Each view keeps at most one pending check, and cancelled checks leave the
 * queue at once, so the queue is bounded by the number of live views and never pins a closed repository.
 */
internal val IDLE_TIMER: java.util.concurrent.ScheduledThreadPoolExecutor =
    java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "kastor-jena-inference-idle").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

/**
 * One step handed to an [InferenceWorker], with the cancellation token its store reads and checkpoints check.
 *
 * The step tracks its own progress (`FutureTask.cancel(false)` cannot tell a queued task from a running one):
 * [cancel] reports whether the step ever started, and [awaitEnd] lets the reader that cancelled a started step wait
 * for it to end.
 */
internal class InferenceStep<T>(private val block: () -> T) : java.util.concurrent.Callable<T> {
    private val state = java.util.concurrent.atomic.AtomicInteger(QUEUED)
    private val token = java.util.concurrent.atomic.AtomicBoolean()
    private val ended = java.util.concurrent.CountDownLatch(1)

    override fun call(): T {
        // Cancelled while queued: never touch the shared state.
        if (!state.compareAndSet(QUEUED, STARTED)) throw java.util.concurrent.CancellationException()
        try {
            return InferenceCancellation.withCheck({ token.get() }) { block() }
        } finally {
            ended.countDown()
        }
    }

    /**
     * Cancels the step for its reader. Returns false when the step never started (and now never will): it left no
     * trace. Returns true when it has started; it is then running, or has ended (see [awaitEnd]).
     */
    fun cancel(): Boolean {
        token.set(true)
        return !state.compareAndSet(QUEUED, CANCELLED)
    }

    /**
     * Waits up to [nanos] for a started step to end and returns whether it has. An interrupt of the calling thread
     * does not cut the wait short (the caller is typically an interrupted reader) and is kept for the caller.
     */
    fun awaitEnd(nanos: Long): Boolean {
        val deadline = System.nanoTime() + nanos
        var interrupted = Thread.interrupted()
        try {
            while (true) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return ended.count == 0L
                try {
                    return ended.await(left, java.util.concurrent.TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val QUEUED = 0
        const val STARTED = 1
        const val CANCELLED = 2
    }
}

/**
 * A single worker thread that owns inference state (and, for shared views, the read transaction it was built in).
 *
 * [call] hands a step to the worker and waits for it while watching the *caller's* cancellation
 * ([InferenceCancellation]). When the caller's query times out or its thread is interrupted:
 * - a step that is still **queued** is withdrawn: it never runs and leaves no trace;
 * - a step that has **started** gets its token set, so its next store read or checkpoint stops it, and the caller
 *   waits up to the cancel grace for it to end. What the step leaves behind is judged where it ends, on the worker
 *   (the block handed to [call] knows whether it stopped at a clean point);
 * - a step that is **still running after the grace** is stuck (a blocking store read, a long computation without
 *   reads): `onStuck` is invoked, because whatever it shares with other steps stays unusable for as long as it runs.
 *
 * In every case the caller then gets a `QueryCancelledException`. A step that fails delivers its exception to the
 * caller unchanged.
 */
internal class InferenceWorker(
    name: String,
    /**
     * The owner's registry of its running worker threads, if it keeps one: a thread is added when it is created and
     * removes itself when its work is done, so the owner can tell which of **its** workers are alive.
     */
    private val registry: MutableSet<Thread>? = null,
) {
    /** Threads started by [executor] (at most one at a time), so [awaitTermination] can wait for them to exit. */
    private val threads = java.util.concurrent.CopyOnWriteArrayList<Thread>()

    private val executor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue(),
    ) { task ->
        Thread({ try { task.run() } finally { registry?.remove(Thread.currentThread()) } }, name).apply {
            isDaemon = true
            threads.add(this)
            registry?.add(this)
        }
    }

    fun <T> call(cancelGraceNanos: Long, onStuck: () -> Unit, onSubmitted: () -> Unit = {}, block: () -> T): T {
        InferenceCancellation.checkNotCancelled()
        val step = InferenceStep(block)
        val future = try {
            executor.submit(step)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            throw IllegalStateException("Inference view is closed", e)
        }
        onSubmitted()
        while (true) {
            try {
                return future.get(POLL_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                if (InferenceCancellation.cancelled()) cancel(step, cancelGraceNanos, onStuck)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                cancel(step, cancelGraceNanos, onStuck)
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    private fun cancel(step: InferenceStep<*>, cancelGraceNanos: Long, onStuck: () -> Unit): Nothing {
        if (step.cancel() && !step.awaitEnd(cancelGraceNanos)) onStuck()
        throw QueryCancelledException()
    }

    /** Steps handed to the worker that it has not started yet. */
    fun queuedSteps(): Int = executor.queue.size

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
 * the owner; finds stream in chunks, so nothing is materialised beyond one chunk per open iterator, and other
 * readers' steps interleave between chunks.
 *
 * **Chunk size.** A find starts with [FIRST_CHUNK] results and quadruples the size of every further step up to
 * [MAX_CHUNK]: a consumer that stops early (`LIMIT`, `ASK`, an existence check) costs the reasoner little more than
 * what it reads, and a long scan needs about one step per [MAX_CHUNK] results, which bounds both the hand-over
 * overhead per result and the time other readers of the same view wait for their turn (one step).
 *
 * Every loop over the reasoner's results passes a checkpoint ([InferenceExecutor.checkpoint]) per result, so a
 * cancelled step ends at the next result even when it needs no store read to produce it.
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
            while (true) {
                owner.checkpoint()
                if (!iterator.hasNext()) break
                iterator.next()
                owner.resultTaken()
                count++
            }
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

        /** No further step will be made: the results ended, the iterator was closed, or a step failed. */
        private var exhausted = false

        /**
         * What ended the find before its results did (a failed or cancelled step). The iterator **stays failed**:
         * once the results fetched before are consumed, every further `hasNext()` / `next()` throws it again, so a
         * caller that catches the failure and goes on can never take the truncated result for a complete one.
         */
        private var failure: Throwable? = null
        private var closed = false
        private var chunkSize = FIRST_CHUNK

        override fun hasNext(): Boolean {
            if (closed) return false
            if (buffer.isNotEmpty()) return true
            failure?.let { throw it }
            if (exhausted) return false
            val size = chunkSize
            val chunk = try {
                owner.call {
                    val iterator = source ?: inf.find(pattern).also { source = it }
                    val out = ArrayList<Triple>(size)
                    while (out.size < size) {
                        owner.checkpoint()
                        if (!iterator.hasNext()) break
                        out.add(iterator.next())
                        owner.resultTaken()
                    }
                    if (out.size < size) {
                        iterator.close()
                        source = null
                    }
                    out
                }
            } catch (e: Throwable) {
                // The step failed, or its reader stopped waiting for it (cancellation): the step may still have
                // opened the reasoner's iterator, which only the owner can close.
                exhausted = true
                failure = e
                releaseSource()
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
            closed = true
            if (exhausted) return
            exhausted = true
            releaseSource()
        }

        /** Closes the reasoner's iterator on the owner, after any step still running for this find. */
        private fun releaseSource() = owner.submitQuietly { source?.close(); source = null }
    }

    private companion object {
        const val FIRST_CHUNK = 16
        const val MAX_CHUNK = 1_024
    }
}

/**
 * One graph of a shared inference view as a read transaction sees it. [resolve] returns the graph to read: the
 * [SharedInferenceGraph] of a view the transaction holds, or a transaction-private inference graph.
 *
 * When a step fails because **another** reader's step invalidated the view (the failing step itself never ran, see
 * [InferenceViewInvalidated]), the operation is repeated on what [resolve] returns next, which is a fresh view:
 * - lookups and counts (`contains`, `isEmpty`, `size`) are always repeated;
 * - a `find` is restarted only while it has **not handed out any result**. Once the caller holds results of the
 *   invalidated view, the remaining ones cannot be told apart from those of a fresh run (the order of results is not
 *   stable), so the iterator fails with the [com.geoknoesis.kastor.rdf.RdfInferenceException] instead of risking
 *   duplicates or gaps.
 *
 * At most [MAX_RESTARTS] repetitions are made per operation; a view that keeps being invalidated then surfaces the
 * failure. A step that failed or was cancelled itself is never repeated here.
 */
internal class RestartableInferenceGraph(private val resolve: () -> Graph) : org.apache.jena.graph.impl.GraphBase() {
    private var target: Graph? = null

    /** Runs [operation] on the current target, repeating it on a fresh one while [mayRestart] allows. */
    private inline fun <T> restarting(mayRestart: () -> Boolean, onRestart: () -> Unit, operation: (Graph) -> T): T {
        var restarts = 0
        while (true) {
            try {
                return operation(target ?: resolve().also { target = it })
            } catch (e: com.geoknoesis.kastor.rdf.RdfInferenceException) {
                if (e.cause !is InferenceViewInvalidated || restarts >= MAX_RESTARTS || !mayRestart()) throw e
                restarts++
                target = null
                onRestart()
            }
        }
    }

    private inline fun <T> restarting(operation: (Graph) -> T): T = restarting({ true }, {}, operation)

    override fun graphBaseFind(triplePattern: Triple): ExtendedIterator<Triple> = RestartableFind(triplePattern)

    override fun graphBaseContains(t: Triple): Boolean = restarting { it.contains(t) }

    /** Counts what [find] exposes (an inference graph's own size does not count every entailment). */
    override fun graphBaseSize(): Int = restarting { graph ->
        if (graph is SharedInferenceGraph) {
            graph.size()
        } else {
            val iterator = graph.find()
            try {
                var count = 0
                while (iterator.hasNext()) { iterator.next(); count++ }
                count
            } finally {
                iterator.close()
            }
        }
    }

    override fun isEmpty(): Boolean = restarting { it.isEmpty }

    override fun createPrefixMapping(): org.apache.jena.shared.PrefixMapping = restarting { it.prefixMapping }

    private inner class RestartableFind(private val pattern: Triple) : NiceIterator<Triple>() {
        private var source: ExtendedIterator<Triple>? = null

        /** A result was handed to the caller: from now on the find cannot be restarted. */
        private var delivered = false
        private var closed = false

        override fun hasNext(): Boolean {
            if (closed) return false
            return restarting({ !delivered }, { source = null }) { graph ->
                (source ?: graph.find(pattern).also { source = it }).hasNext()
            }
        }

        override fun next(): Triple {
            if (!hasNext()) throw NoSuchElementException()
            delivered = true
            return source!!.next()
        }

        override fun close() {
            closed = true
            source?.close()
            source = null
        }
    }

    private companion object {
        const val MAX_RESTARTS = 3
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
