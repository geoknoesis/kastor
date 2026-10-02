package com.geoknoesis.kastor.rdf.hermit

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.semanticweb.HermiT.ReasonerFactory
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Abandoned background loads (OWL API loading plus engine creation) are capped and clean up after themselves. */
class HermitLoadCapTest {
    private val graph = Rdf.parse(
        """
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix ex: <http://example.org/ns#> .
        ex:A rdfs:subClassOf ex:B .
        ex:a a ex:A .
        """.trimIndent(),
        RdfFormat.TURTLE,
    )

    @Test
    fun `a loader thread that cannot be started returns its permit and leaves the reasoner usable`() {
        val permits = Semaphore(2)
        val engines = { o: org.semanticweb.owlapi.model.OWLOntology, c: org.semanticweb.HermiT.Configuration -> ReasonerFactory().createReasoner(o, c) }
        val failing = java.util.concurrent.ThreadFactory { throw OutOfMemoryError("unable to create native thread") }
        val reasoner = HermitRdfReasoner(ReasonerConfig.hermit(), engines, permits, failing)
        repeat(5) {
            assertThrows(OutOfMemoryError::class.java) { reasoner.isConsistent(graph) }
            assertEquals(2, permits.availablePermits(), "the permit of a loader that never ran must be returned")
        }
        val unstartable = java.util.concurrent.ThreadFactory { task -> object : Thread(task) { override fun start() { throw IllegalThreadStateException("no start") } } }
        assertThrows(IllegalThreadStateException::class.java) { HermitRdfReasoner(ReasonerConfig.hermit(), engines, permits, unstartable).isConsistent(graph) }
        assertEquals(2, permits.availablePermits())
        assertTrue(HermitRdfReasoner(ReasonerConfig.hermit(), engines, permits).isConsistent(graph))
    }

    /**
     * Deterministic: time is an injected clock that only moves when the scenario says so (engine creation "takes" the
     * whole budget; waiting for a permit that is not free "takes" the rest of it), and the loader threads are joined
     * instead of being polled, so neither a slow host nor a busy one changes the outcome.
     */
    @Test
    @Timeout(600)
    fun `repeated timeouts during engine creation do not grow the background work beyond the cap`() {
        val release = CountDownLatch(1)
        val created = AtomicInteger()
        val disposed = AtomicInteger()
        val cap = 2
        val now = AtomicLong()
        val timeout = Duration.ofHours(1)
        // A timed acquire never waits: when no permit is free, the caller's whole remaining budget elapses at once.
        val permits = object : Semaphore(cap) {
            override fun tryAcquire(timeout: Long, unit: TimeUnit): Boolean {
                if (tryAcquire()) return true
                now.addAndGet(unit.toNanos(timeout))
                return false
            }
        }
        val loaders = CopyOnWriteArrayList<Thread>()
        val threads = ThreadFactory { task -> Thread(task, "kastor-hermit-loader").also { loaders.add(it) } }
        val reasoner = HermitRdfReasoner(ReasonerConfig.hermit().copy(timeout = timeout), { ontology, options ->
            // Engine creation (HermiT preprocessing) outlasts the caller's budget ...
            now.addAndGet(timeout.toNanos())
            // ... and ignores interrupts until released.
            while (true) {
                try {
                    release.await()
                    break
                } catch (_: InterruptedException) {
                    // uninterruptible, like HermiT's preprocessing
                }
            }
            created.incrementAndGet()
            val engine = ReasonerFactory().createReasoner(ontology, options)
            java.lang.reflect.Proxy.newProxyInstance(engine.javaClass.classLoader, arrayOf(org.semanticweb.owlapi.reasoner.OWLReasoner::class.java)) { _, method, args ->
                if (method.name == "dispose") disposed.incrementAndGet()
                method.invoke(engine, *(args ?: emptyArray()))
            } as org.semanticweb.owlapi.reasoner.OWLReasoner
        }, permits, threads, now::get)
        try {
            repeat(cap) {
                val error = assertThrows(IllegalStateException::class.java) { reasoner.isConsistent(graph) }
                assertTrue(error.message!!.contains("timed out"), error.message)
                assertFalse(error.message!!.contains("too many HermiT loads"), error.message)
            }
            assertEquals(cap, loaders.size, "each timed-out call abandoned one load")
            assertEquals(0, permits.availablePermits(), "abandoned loads keep their permits while they run")
            repeat(6) {
                val error = assertThrows(IllegalStateException::class.java) { reasoner.isConsistent(graph) }
                assertTrue(error.message!!.contains("too many HermiT loads"), error.message)
                assertEquals(cap, loaders.size, "abandoned loads must not pile up")
            }
        } finally {
            release.countDown()
        }
        loaders.forEach { it.join() }
        assertEquals(cap, permits.availablePermits(), "every abandoned load returns its permit when it finishes")
        assertEquals(cap, created.get(), "rejected calls must not start loads")
        assertEquals(cap, disposed.get(), "engines created by abandoned loads are disposed by the loader")
        assertTrue(HermitRdfReasoner(ReasonerConfig.hermit(), { o, c -> ReasonerFactory().createReasoner(o, c) }, permits).isConsistent(graph))
    }
}
