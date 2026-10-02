package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.RdfInferenceException
import org.apache.jena.query.QueryCancelledException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * How far the cancellation or failure of one reader's reasoning step reaches: a step that stops at a clean point
 * leaves the shared view healthy, and when a view is given up, readers that have not received anything yet continue
 * on a fresh one. Every scenario is driven by latches and the seams of [JenaInferenceHooks].
 */
class JenaInferencePoisonScopeTest {
    private val ex = "http://example.org/"
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    private fun cls(n: Int) = Iri(ex + "C$n")
    private fun instance(n: Int) = Iri(ex + "i$n")
    private val named = Iri(ex + "g1")

    private val instances = 3_000
    private val waitNanos = TimeUnit.SECONDS.toNanos(60)

    private fun schema() = (0 until 5).map { RdfTriple(cls(it), subClassOf, cls(it + 1)) }

    private fun repository(): JenaRepository = JenaRepository.MemoryRepositoryWithInference(Duration.ofMinutes(10)).also { repo ->
        repo.transaction {
            editDefaultGraph().addTriples(schema() + (0 until instances).map { RdfTriple(instance(it), type, cls(0)) })
            editGraph(named).addTriples(schema() + RdfTriple(instance(0), type, cls(0)))
        }
    }

    /** Prepares the shared view of the current snapshot (default graph) and returns its identity. */
    private fun warmUp(repo: JenaRepository): Any {
        assertTrue(repo.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(5))))
        return assertNotNull(repo.currentInferenceView())
    }

    /** Runs [block] on a new thread, recording what it returned or threw. */
    private class Attempt<T>(block: () -> T) {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        val thread: Thread = thread {
            try { result.set(block()) } catch (t: Throwable) { failure.set(t) }
        }

        fun join() {
            thread.join(120_000)
            assertFalse(thread.isAlive, "the reader must finish")
        }
    }

    /**
     * Called on the worker, inside a step: returns once the step's reader has cancelled it (a wait for that condition,
     * bounded only so that a broken cancellation fails the test instead of hanging it).
     */
    private fun awaitCancelled() {
        val deadline = System.nanoTime() + waitNanos
        while (!InferenceCancellation.cancelled() && System.nanoTime() - deadline < 0) Thread.onSpinWait()
    }

    @Test
    @Timeout(180)
    fun `a step cancelled before it touched the reasoner leaves the view healthy for everybody`() {
        repository().use { repo ->
            val view = warmUp(repo)
            // Another reader is half-way through a result on the same view.
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val other = Attempt {
                repo.withSelectRows(SparqlSelectQuery("SELECT ?s WHERE { ?s a <${cls(4).value}> }")) { rows ->
                    val iterator = rows.iterator()
                    iterator.next()
                    holding.countDown()
                    assertTrue(release.await(120, TimeUnit.SECONDS))
                    var n = 1
                    while (iterator.hasNext()) { iterator.next(); n++ }
                    n
                }
            }
            assertTrue(holding.await(60, TimeUnit.SECONDS))

            // The next step starts and is cancelled by its reader before it does anything.
            repo.hooks.cancelGraceNanos = waitNanos
            val started = CountDownLatch(1)
            val armed = AtomicInteger(1)
            repo.hooks.onStepCheckpoint = {
                if (armed.getAndDecrement() == 1) {
                    started.countDown()
                    awaitCancelled()
                }
            }
            val cancelled = Attempt { repo.defaultGraph.find(null, type, cls(2)).size }
            assertTrue(started.await(60, TimeUnit.SECONDS), "the step must start")
            cancelled.thread.interrupt()
            cancelled.join()
            assertTrue(cancelled.failure.get() is QueryCancelledException, "${cancelled.failure.get()}")

            assertSame(view, repo.currentInferenceView(), "a step that touched nothing must not cost the view")
            release.countDown()
            other.join()
            assertNull(other.failure.get(), "${other.failure.get()}")
            assertEquals(instances, other.result.get(), "the other reader's open iterator is unaffected")
            assertSame(view, repo.currentInferenceView())
        }
    }

    @Test
    @Timeout(180)
    fun `a cancelled step stops between two results without store reads and keeps the view`() {
        repository().use { repo ->
            val view = warmUp(repo)
            repo.hooks.cancelGraceNanos = waitNanos
            val betweenResults = CountDownLatch(1)
            // The checkpoint the step passes right after it took its first result from the reasoner: between two
            // results of one step, however many checkpoints a step has before its first result.
            val results = AtomicInteger()
            repo.hooks.onStepResult = { results.incrementAndGet() }
            repo.hooks.onStepCheckpoint = {
                if (results.get() == 1 && betweenResults.count == 1L) {
                    betweenResults.countDown()
                    awaitCancelled()
                }
            }
            // From here on the step must not need the store to notice its cancellation.
            val readsAfterCancel = AtomicInteger()
            repo.hooks.onStoreRead = { if (betweenResults.count == 0L) readsAfterCancel.incrementAndGet() }
            val cancelled = Attempt { repo.defaultGraph.find(null, type, cls(2)).size }
            assertTrue(betweenResults.await(60, TimeUnit.SECONDS), "the step must produce a first result")
            cancelled.thread.interrupt()
            cancelled.join()
            assertTrue(cancelled.failure.get() is QueryCancelledException, "${cancelled.failure.get()}")
            assertEquals(0, readsAfterCancel.get(), "the step stopped at the next result, not at the next store read")
            repo.hooks.onStoreRead = {}

            assertSame(view, repo.currentInferenceView(), "stopping between two results leaves the reasoner consistent")
            assertEquals(0, repo.queuedInferenceSteps(), "the worker is free again")
            assertEquals(instances, repo.defaultGraph.find(null, type, cls(2)).size)
            assertSame(view, repo.currentInferenceView())
        }
    }

    @Test
    @Timeout(180)
    fun `a reader cancelled while a graph is being prepared does not cost the view`() {
        repository().use { repo ->
            val view = warmUp(repo)
            repo.hooks.cancelGraceNanos = waitNanos
            val inRead = CountDownLatch(1)
            val armed = AtomicInteger(1)
            repo.hooks.onStoreRead = {
                if (armed.getAndDecrement() == 1) {
                    inRead.countDown()
                    awaitCancelled()
                }
            }
            // First read of the named graph: its inference graph is prepared, which reads the store.
            val cancelled = Attempt { repo.getGraph(named).hasTriple(RdfTriple(instance(0), type, cls(5))) }
            assertTrue(inRead.await(60, TimeUnit.SECONDS), "preparation must read the store")
            cancelled.thread.interrupt()
            cancelled.join()
            assertTrue(cancelled.failure.get() is QueryCancelledException, "${cancelled.failure.get()}")

            // The half-prepared graph was never shared: nothing of the view is suspect.
            assertSame(view, repo.currentInferenceView())
            assertTrue(repo.getGraph(named).hasTriple(RdfTriple(instance(0), type, cls(5))))
            assertTrue(repo.defaultGraph.hasTriple(RdfTriple(instance(1), type, cls(5))))
            assertSame(view, repo.currentInferenceView())
        }
    }

    @Test
    @Timeout(180)
    fun `a step that aborts inside the reasoner poisons the view as soon as it ends`() {
        repository().use { repo ->
            val view = warmUp(repo)
            assertFalse(repo.isPoisonedInferenceView(view))
            // Never reached: only the step's own end may poison the view here, not a reader giving up on a stuck step.
            repo.hooks.cancelGraceNanos = waitNanos
            // Another reader is half-way through a result on the same view.
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val delivered = AtomicInteger()
            val other = Attempt {
                repo.withSelectRows(SparqlSelectQuery("SELECT ?s WHERE { ?s a <${cls(4).value}> }")) { rows ->
                    val iterator = rows.iterator()
                    iterator.next()
                    delivered.incrementAndGet()
                    holding.countDown()
                    assertTrue(release.await(120, TimeUnit.SECONDS))
                    while (iterator.hasNext()) {
                        iterator.next()
                        delivered.incrementAndGet()
                    }
                }
            }
            assertTrue(holding.await(60, TimeUnit.SECONDS))

            val inRead = CountDownLatch(1)
            val armed = AtomicInteger(1)
            repo.hooks.onStoreRead = {
                if (armed.getAndDecrement() == 1) {
                    inRead.countDown()
                    awaitCancelled()
                }
            }
            val cancelled = Attempt { repo.defaultGraph.find(null, type, cls(2)).size }
            assertTrue(inRead.await(60, TimeUnit.SECONDS), "the step must reach the store")
            cancelled.thread.interrupt()
            cancelled.join()
            assertTrue(cancelled.failure.get() is QueryCancelledException, "${cancelled.failure.get()}")

            // A backward-chaining step stopped at a store read may leave the goal tables half-updated: the view is
            // poisoned (not merely dropped), and it is no longer handed out.
            assertTrue(repo.isPoisonedInferenceView(view), "the view must be poisoned by the step that aborted inside the reasoner")
            assertNull(repo.currentInferenceView(), "a poisoned view is not the current view any more")

            // The concurrent reader already holds results of that view: it must fail, never end as if complete.
            release.countDown()
            other.join()
            val failure = other.failure.get()
            assertTrue(failure is RdfInferenceException, "the concurrent reader must see the invalidation: $failure")
            assertTrue(failure.cause is InferenceViewInvalidated, "${failure.cause}")
            assertTrue(delivered.get() in 1 until instances, "it got the results fetched before, not all: ${delivered.get()}")

            // Later readers get a fresh, healthy view.
            assertEquals(instances, repo.defaultGraph.find(null, type, cls(2)).size)
            val fresh = assertNotNull(repo.currentInferenceView())
            assertNotSame(view, fresh)
            assertFalse(repo.isPoisonedInferenceView(fresh))
        }
    }

    @Test
    @Timeout(180)
    fun `readers waiting behind a step that poisons the view continue on a fresh view`() {
        repository().use { repo ->
            val view = warmUp(repo)
            // A step is stuck inside a store read ...
            val inRead = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            val armed = AtomicInteger(1)
            repo.hooks.onStoreRead = {
                if (armed.getAndDecrement() == 1) {
                    inRead.countDown()
                    proceed.await(120, TimeUnit.SECONDS)
                }
            }
            val stuck = Attempt { repo.defaultGraph.find(null, type, cls(2)).size }
            assertTrue(inRead.await(60, TimeUnit.SECONDS))
            // ... two other readers hand their steps to the worker, which is busy: a find that has not delivered
            // anything yet, and a lookup ...
            // (The stuck reader passes this hook too, possibly only now: its own hand-over does not count.)
            val waiting = CountDownLatch(2)
            repo.hooks.onStepSubmitted = { if (Thread.currentThread() !== stuck.thread) waiting.countDown() }
            val finding = Attempt { repo.defaultGraph.find(null, type, cls(3)).size }
            val asking = Attempt { repo.defaultGraph.hasTriple(RdfTriple(instance(7), type, cls(4))) }
            assertTrue(waiting.await(60, TimeUnit.SECONDS), "both readers must be waiting behind the stuck step")
            assertEquals(2, repo.queuedInferenceSteps(), "both steps wait behind the stuck one")
            // ... and its reader gives up: the step does not end within the grace, so the view is given up.
            stuck.thread.interrupt()
            stuck.join()
            assertTrue(stuck.failure.get() is QueryCancelledException, "${stuck.failure.get()}")
            assertTrue(repo.isPoisonedInferenceView(view), "a step that outlasts the cancel grace costs the view")
            proceed.countDown()

            finding.join()
            asking.join()
            assertNull(finding.failure.get(), "a find that delivered nothing restarts on a fresh view: ${finding.failure.get()}")
            assertEquals(instances, finding.result.get())
            assertNull(asking.failure.get(), "a lookup is retried on a fresh view: ${asking.failure.get()}")
            assertEquals(true, asking.result.get())
        }
    }
}
