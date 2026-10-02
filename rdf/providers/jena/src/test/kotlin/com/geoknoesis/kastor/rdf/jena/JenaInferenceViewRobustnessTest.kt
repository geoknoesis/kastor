package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfErrorCode
import com.geoknoesis.kastor.rdf.RdfInferenceException
import com.geoknoesis.kastor.rdf.RdfRepositoryException
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.UpdateQuery
import org.apache.jena.query.QueryCancelledException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Delayed
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Shared inference views under cancellation, poisoning, close and idle release. Every scenario is driven by latches
 * and the seams of [JenaInferenceHooks] instead of timing.
 */
class JenaInferenceViewRobustnessTest {
    private val ex = "http://example.org/"
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    private fun cls(n: Int) = Iri(ex + "C$n")
    private fun instance(n: Int) = Iri(ex + "i$n")
    private val named = Iri(ex + "g1")

    private val instances = 3_000

    private fun schema() = (0 until 5).map { RdfTriple(cls(it), subClassOf, cls(it + 1)) }

    private fun repository(): JenaRepository = JenaRepository.MemoryRepositoryWithInference(Duration.ofMinutes(10))

    private fun load(repo: JenaRepository) {
        repo.transaction {
            editDefaultGraph().addTriples(schema() + (0 until instances).map { RdfTriple(instance(it), type, cls(0)) })
            editGraph(named).addTriples(schema() + RdfTriple(instance(0), type, cls(0)))
        }
    }

    /** Prepares the shared view of the current snapshot and returns its identity. */
    private fun warmUp(repo: JenaRepository): Any {
        assertTrue(repo.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(5))))
        return assertNotNull(repo.currentInferenceView())
    }

    /** Blocks the next store read of an inference step until [proceed] is released. */
    private class BlockedRead(repo: JenaRepository) {
        val inRead = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        private val armed = AtomicBoolean(true)

        init {
            repo.hooks.onStoreRead = {
                if (armed.compareAndSet(true, false)) {
                    inRead.countDown()
                    proceed.await(60, TimeUnit.SECONDS)
                }
            }
        }
    }

    /** Runs [block] on a new thread, recording what it returned or threw. */
    private class Attempt<T>(block: () -> T) {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        val thread: Thread = thread {
            try { result.set(block()) } catch (t: Throwable) { failure.set(t) }
        }

        fun join() {
            thread.join(60_000)
            assertFalse(thread.isAlive, "the reader must finish")
        }
    }

    /** A reader of the default graph is cancelled while its step is blocked inside a store read: the view is poisoned. */
    private fun poisonCurrentView(repo: JenaRepository) {
        val blocked = BlockedRead(repo)
        val cancelled = Attempt { repo.defaultGraph.find(null, type, cls(2)).size }
        assertTrue(blocked.inRead.await(30, TimeUnit.SECONDS), "the step must reach the store")
        cancelled.thread.interrupt()
        cancelled.join()
        assertTrue(cancelled.failure.get() is QueryCancelledException, "${cancelled.failure.get()}")
        blocked.proceed.countDown()
    }

    @Test
    @Timeout(120)
    fun `a step cancelled inside a store read poisons the view and later readers get a fresh one`() {
        repository().use { repo ->
            load(repo)
            val view = warmUp(repo)
            poisonCurrentView(repo)
            assertNotSame(view, repo.currentInferenceView(), "a view whose step was stopped half-way must not be shared any more")

            assertEquals(instances, repo.defaultGraph.find(null, type, cls(3)).size)
            val fresh = assertNotNull(repo.currentInferenceView())
            assertNotSame(view, fresh)
        }
    }

    @Test
    @Timeout(120)
    fun `a queued step cancelled before it started does not poison the view`() {
        repository().use { repo ->
            load(repo)
            val view = warmUp(repo)
            val blocked = BlockedRead(repo)
            val running = Attempt { repo.defaultGraph.find(null, type, cls(3)).size }
            assertTrue(blocked.inRead.await(30, TimeUnit.SECONDS))

            val submitted = CountDownLatch(1)
            repo.hooks.onStepSubmitted = { submitted.countDown() }
            val queued = Attempt { repo.defaultGraph.hasTriple(RdfTriple(instance(1), type, cls(4))) }
            assertTrue(submitted.await(30, TimeUnit.SECONDS), "the second reader must hand its step to the worker")
            assertEquals(1, repo.queuedInferenceSteps(), "the second reader's step waits behind the blocked one")
            queued.thread.interrupt()
            queued.join()
            assertTrue(queued.failure.get() is QueryCancelledException, "${queued.failure.get()}")
            assertSame(view, repo.currentInferenceView(), "a step that never started leaves the view intact")

            blocked.proceed.countDown()
            running.join()
            assertNull(running.failure.get(), "${running.failure.get()}")
            assertEquals(instances, running.result.get())
            assertSame(view, repo.currentInferenceView())
        }
    }

    @Test
    fun `a cancelled step reports whether it ever started and when it has ended`() {
        val step = InferenceStep { 42 }
        assertFalse(step.awaitEnd(0), "a step that has not run has not ended")
        assertEquals(42, step.call())
        assertTrue(step.cancel(), "the step had started")
        assertTrue(step.awaitEnd(0), "and it has ended: what it left behind was decided where it ran")

        val never = InferenceStep { 1 }
        assertFalse(never.cancel(), "a step that never started left no trace")
        assertFailsWith<java.util.concurrent.CancellationException> { never.call() }

        val failed = InferenceStep<Int> { error("boom") }
        assertFailsWith<IllegalStateException> { failed.call() }
        assertTrue(failed.cancel())
        assertTrue(failed.awaitEnd(0), "a failed step has ended too")

        // The token of a started step is visible to the step itself, at its checkpoints.
        val cancelledInside = InferenceStep {
            InferenceCancellation.checkpoint()
            "not cancelled yet"
        }
        assertEquals("not cancelled yet", cancelledInside.call())
        lateinit var self: InferenceStep<String>
        self = InferenceStep {
            self.cancel()
            InferenceCancellation.checkpoint()
            "unreachable"
        }
        assertFailsWith<CleanStepCancellation> { self.call() }

        // Waiting for a step to end is not cut short by the reader's own interrupt, which is kept.
        val running = InferenceStep { 1 }
        Thread.currentThread().interrupt()
        assertFalse(running.awaitEnd(TimeUnit.MILLISECONDS.toNanos(20)))
        assertTrue(Thread.interrupted(), "the interrupt is kept for the caller")
    }

    @Test
    @Timeout(120)
    fun `an open iterator of a poisoned view fails with a typed exception instead of partial results`() {
        repository().use { repo ->
            load(repo)
            warmUp(repo)
            repo.withSelectRows(SparqlSelectQuery("SELECT ?s WHERE { ?s a <${cls(4).value}> }")) { rows ->
                val iterator = rows.iterator()
                iterator.next()
                poisonCurrentView(repo)
                val error = assertFailsWith<RdfInferenceException> {
                    var n = 1
                    while (iterator.hasNext()) { iterator.next(); n++ }
                    n
                }
                assertTrue(error.message!!.contains("invalidated"), error.message)
            }
            // The repository stays usable.
            assertEquals(instances, repo.defaultGraph.find(null, type, cls(4)).size)
        }
    }

    @Test
    @Timeout(120)
    fun `a reader of a poisoned view reads other graphs from a fresh view while its open iterator fails loudly`() {
        repository().use { repo ->
            load(repo)
            val view = warmUp(repo)
            repo.withSelectRows(SparqlSelectQuery("SELECT ?s WHERE { ?s a <${cls(4).value}> }")) { rows ->
                val iterator = rows.iterator()
                iterator.next()
                poisonCurrentView(repo)
                // A new read in the same transaction (here: of a second graph) is served by a fresh view of the snapshot.
                assertTrue(repo.getGraph(named).hasTriple(RdfTriple(instance(0), type, cls(5))))
                val fresh = assertNotNull(repo.currentInferenceView())
                assertNotSame(view, fresh)
                // The iterator opened on the poisoned view neither continues silently nor reports a closed view.
                assertFailsWith<RdfInferenceException> {
                    var n = 1
                    while (iterator.hasNext()) { iterator.next(); n++ }
                    n
                }
            }
        }
    }

    @Test
    @Timeout(120)
    fun `close waits for readers holding an inference view`() {
        val repo = repository()
        load(repo)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val reader = Attempt {
            repo.withSelectRows(SparqlSelectQuery("SELECT ?s WHERE { ?s a <${cls(4).value}> }")) { rows ->
                val iterator = rows.iterator()
                iterator.next()
                holding.countDown()
                assertTrue(release.await(60, TimeUnit.SECONDS))
                var n = 1
                while (iterator.hasNext()) { iterator.next(); n++ }
                events.add("reader finished")
                n
            }
        }
        assertTrue(holding.await(30, TimeUnit.SECONDS))
        val waiting = CountDownLatch(1)
        val warnings = Collections.synchronizedList(mutableListOf<String>())
        repo.hooks.onCloseWaiting = { waiting.countDown() }
        repo.hooks.warn = { warnings.add(it) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val closing = executor.submit { repo.close(); events.add("closed") }
            assertTrue(waiting.await(30, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException>("close() must not return while a reader holds the view") {
                closing.get(300, TimeUnit.MILLISECONDS)
            }
            release.countDown()
            closing.get(60, TimeUnit.SECONDS)
            reader.join()
            assertNull(reader.failure.get(), "${reader.failure.get()}")
            assertEquals(instances, reader.result.get(), "the reader finishes on the view it held")
            assertEquals(listOf("reader finished", "closed"), events.toList())
            assertTrue(warnings.isEmpty(), "$warnings")
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
        assertTrue(repo.isClosed())
        val error = assertFailsWith<IllegalStateException> { repo.defaultGraph.getTriples() }
        assertTrue(error.message!!.contains("closed"), error.message)
    }

    @Test
    @Timeout(120)
    fun `close warns about views still in use at its time limit and their readers then fail clearly`() {
        val repo = repository()
        load(repo)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reader = Attempt {
            repo.withSelectRows(SparqlSelectQuery("SELECT ?s WHERE { ?s a <${cls(4).value}> }")) { rows ->
                val iterator = rows.iterator()
                iterator.next()
                holding.countDown()
                assertTrue(release.await(60, TimeUnit.SECONDS))
                var n = 1
                while (iterator.hasNext()) { iterator.next(); n++ }
                n
            }
        }
        try {
            assertTrue(holding.await(30, TimeUnit.SECONDS))
            val warnings = Collections.synchronizedList(mutableListOf<String>())
            repo.hooks.warn = { warnings.add(it) }
            repo.hooks.closeWaitNanos = TimeUnit.MILLISECONDS.toNanos(100)
            repo.close()
            assertEquals(1, warnings.size, "$warnings")
            assertTrue(warnings.single().contains("1 reader"), warnings.single())
        } finally {
            release.countDown()
        }
        reader.join()
        val failure = reader.failure.get()
        assertTrue(failure is RdfRepositoryException && failure.errorCode == RdfErrorCode.REPOSITORY_CLOSED, "$failure")
    }

    /** Timer whose tasks run only when the test fires them. */
    private class ManualTimer {
        inner class Task(val delayNanos: Long, val task: Runnable) : ScheduledFuture<Any?> {
            private var cancelled = false
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                cancelled = true
                return pending.remove(this)
            }
            override fun isCancelled(): Boolean = cancelled
            override fun isDone(): Boolean = cancelled || this !in pending
            override fun get(): Any? = null
            override fun get(timeout: Long, unit: TimeUnit): Any? = null
            override fun getDelay(unit: TimeUnit): Long = unit.convert(delayNanos, TimeUnit.NANOSECONDS)
            override fun compareTo(other: Delayed): Int = getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
        }

        val pending: MutableList<Task> = Collections.synchronizedList(mutableListOf<Task>())
        var scheduled = 0

        fun schedule(delayNanos: Long, task: Runnable): ScheduledFuture<*> {
            scheduled++
            return Task(delayNanos, task).also { pending.add(it) }
        }

        fun fire() {
            val task = pending.removeAt(0)
            task.task.run()
        }
    }

    @Test
    @Timeout(120)
    fun `a view keeps at most one pending idle check however many reads it serves`() {
        val timer = ManualTimer()
        var now = 0L
        val idle = Duration.ofSeconds(30)
        val repo = JenaRepository.MemoryRepositoryWithInference(idle)
        repo.hooks.schedule = timer::schedule
        repo.hooks.clock = { now }
        load(repo)
        val view = warmUp(repo)
        repeat(50) { assertTrue(repo.defaultGraph.hasTriple(RdfTriple(instance(it), type, cls(3)))) }
        assertEquals(1, timer.pending.size, "one idle check per view, not one per read (${timer.scheduled} scheduled)")

        // The check fires while the view was used recently: it is rescheduled for the remaining idle time.
        now = idle.toNanos() / 2
        assertTrue(repo.defaultGraph.hasTriple(RdfTriple(instance(1), type, cls(3))))
        now = idle.toNanos()
        timer.fire()
        assertSame(view, repo.currentInferenceView())
        assertEquals(1, timer.pending.size)
        assertEquals(idle.toNanos() / 2, timer.pending.single().delayNanos)

        // Idle for the whole timeout: the view is released and nothing stays scheduled.
        now = idle.toNanos() * 2
        timer.fire()
        assertNull(repo.currentInferenceView())
        assertTrue(timer.pending.isEmpty())

        // Closing cancels the pending check of a live view, so the timer does not pin the closed repository.
        warmUp(repo)
        assertEquals(1, timer.pending.size)
        repo.close()
        assertTrue(timer.pending.isEmpty(), "close() must cancel the idle check")
    }

    @Test
    @Timeout(120)
    fun `inside a write transaction the inference model is reused until the next write`() {
        repository().use { repo ->
            val extra = Iri(ex + "Extra")
            repo.transaction {
                editDefaultGraph().addTriples(schema() + RdfTriple(instance(0), type, cls(0)))
                editGraph(named).addTriples(schema() + RdfTriple(instance(1), type, cls(0)))
                val start = repo.privateInferenceViewsBuilt()
                repeat(5) { assertTrue(defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(5)))) }
                assertEquals(6, withSelectRows(SparqlSelectQuery("SELECT ?c WHERE { <${instance(0).value}> a ?c }")) { it.count() })
                assertEquals(1, repo.privateInferenceViewsBuilt() - start, "reads between writes share one model per graph")
                assertTrue(getGraph(named).hasTriple(RdfTriple(instance(1), type, cls(5))))
                assertTrue(getGraph(named).hasTriple(RdfTriple(instance(1), type, cls(4))))
                assertEquals(2, repo.privateInferenceViewsBuilt() - start)

                // A write invalidates the cached models: uncommitted changes stay visible.
                editDefaultGraph().addTriple(RdfTriple(cls(5), subClassOf, extra))
                assertTrue(defaultGraph.hasTriple(RdfTriple(instance(0), type, extra)))
                assertTrue(defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(5))))
                assertEquals(3, repo.privateInferenceViewsBuilt() - start)

                update(UpdateQuery("INSERT DATA { <${ex}Extra> <${subClassOf.value}> <${ex}Top> }"))
                assertTrue(defaultGraph.hasTriple(RdfTriple(instance(0), type, Iri(ex + "Top"))))
                assertEquals(4, repo.privateInferenceViewsBuilt() - start)
            }
            assertTrue(repo.defaultGraph.hasTriple(RdfTriple(instance(0), type, Iri(ex + "Top"))))
        }
    }

    @Test
    @Timeout(120)
    fun `opening a view does not hold the view lock`() {
        repository().use { repo ->
            load(repo)
            val opening = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            val armed = AtomicBoolean(true)
            repo.hooks.onOpenView = {
                if (armed.compareAndSet(true, false)) {
                    opening.countDown()
                    proceed.await(60, TimeUnit.SECONDS)
                }
            }
            val reader = Attempt { repo.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(5))) }
            val executor = Executors.newSingleThreadExecutor()
            try {
                assertTrue(opening.await(30, TimeUnit.SECONDS))
                // A commit retires the current view under the view lock: it must not wait for the reader's open.
                val commit = executor.submit { repo.editDefaultGraph().addTriple(RdfTriple(cls(5), subClassOf, Iri(ex + "Top"))) }
                commit.get(20, TimeUnit.SECONDS)
            } finally {
                proceed.countDown()
                executor.shutdownNow()
            }
            reader.join()
            assertNull(reader.failure.get(), "${reader.failure.get()}")
            assertEquals(true, reader.result.get())
            assertTrue(repo.defaultGraph.hasTriple(RdfTriple(instance(0), type, Iri(ex + "Top"))))
        }
    }
}
