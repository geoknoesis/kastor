package com.geoknoesis.kastor.rdf.hermit

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.semanticweb.HermiT.ReasonerFactory
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    private fun liveLoaders() = Thread.getAllStackTraces().keys.count { it.name == "kastor-hermit-loader" && it.isAlive }

    @Test
    fun `repeated timeouts during engine creation do not grow the background work beyond the cap`() {
        val release = CountDownLatch(1)
        val created = AtomicInteger()
        val disposed = AtomicInteger()
        val cap = 2
        val permits = Semaphore(cap)
        val loadersBefore = liveLoaders()
        // Engine creation (HermiT preprocessing) that ignores interrupts until released.
        val reasoner = HermitRdfReasoner(ReasonerConfig.hermit().copy(timeout = Duration.ofMillis(150)), { ontology, options ->
            while (true) {
                try {
                    if (release.await(10, TimeUnit.SECONDS)) break
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
        }, permits)
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(30)) {
                repeat(cap) {
                    val error = assertThrows(IllegalStateException::class.java) { reasoner.isConsistent(graph) }
                    assertTrue(error.message!!.contains("timed out"), error.message)
                }
                repeat(6) {
                    val error = assertThrows(IllegalStateException::class.java) { reasoner.isConsistent(graph) }
                    assertTrue(error.message!!.contains("too many HermiT loads"), error.message)
                    assertTrue(liveLoaders() - loadersBefore <= cap, "abandoned loads must not pile up: ${liveLoaders() - loadersBefore}")
                }
            }
        } finally {
            release.countDown()
        }
        assertTimeoutPreemptively(Duration.ofSeconds(30)) {
            while (permits.availablePermits() < cap || disposed.get() < cap) Thread.onSpinWait()
        }
        assertEquals(cap, created.get(), "rejected calls must not start loads")
        assertEquals(cap, disposed.get(), "engines created by abandoned loads are disposed by the loader")
        assertTrue(HermitRdfReasoner(ReasonerConfig.hermit(), { o, c -> ReasonerFactory().createReasoner(o, c) }, permits).isConsistent(graph))
    }
}
