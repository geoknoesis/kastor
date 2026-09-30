package com.geoknoesis.kastor.rdf.jena.reasoning

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig
import com.geoknoesis.kastor.rdf.reasoning.ReasonerType
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Axiom filtering, timeout coverage of rule preparation and early threshold checks of the Jena reasoner. */
class JenaReasonerBoundsTest {
    private val ex = "http://example.org/"
    private val rdfs = "http://www.w3.org/2000/01/rdf-schema#"
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private fun iri(local: String) = Iri(ex + local)
    private fun turtle(text: String): RdfGraph = JenaProvider().parseGraph(text.byteInputStream(), "TURTLE")

    private fun chain(classes: Int, instances: Int): RdfGraph = turtle(buildString {
        appendLine("@prefix rdfs: <$rdfs> . @prefix ex: <$ex> .")
        for (c in 0 until classes) appendLine("ex:C$c rdfs:subClassOf ex:C${c + 1} .")
        for (i in 0 until instances) appendLine("ex:i$i a ex:C0 .")
    })

    private val seeAlso = Iri(rdfs + "seeAlso")
    private val subPropertyOf = Iri(rdfs + "subPropertyOf")
    private val vocabularyData = turtle("""
        @prefix rdfs: <$rdfs> . @prefix ex: <$ex> .
        rdfs:seeAlso rdfs:subPropertyOf ex:link .
        ex:link rdfs:subPropertyOf ex:related .
        ex:a rdfs:seeAlso ex:b .
    """.trimIndent())

    @Test
    fun `inferences about vocabulary terms are kept and only exact axioms are dropped`() {
        for (config in listOf(ReasonerConfig.rdfs(), ReasonerConfig(reasonerType = ReasonerType.OWL_MICRO))) {
            val inferred = JenaReasoner(config).getInferredTriples(vocabularyData).toSet()
            assertTrue(RdfTriple(seeAlso, subPropertyOf, iri("related")) in inferred, "${config.reasonerType}: $inferred")
            assertTrue(RdfTriple(iri("a"), iri("link"), iri("b")) in inferred, "${config.reasonerType}")
            assertTrue(RdfTriple(iri("a"), iri("related"), iri("b")) in inferred, "${config.reasonerType}")

            // Exactly the closure of the empty graph is excluded.
            val axioms = JenaReasoner(config.copy(parameters = mapOf("includeAxiomaticTriples" to true)))
                .getInferredTriples(turtle("")).toSet()
            assertTrue(axioms.isNotEmpty(), "${config.reasonerType} has axioms")
            assertTrue(inferred.none { it in axioms }, "${config.reasonerType}: no axiom reported")
            val withAxioms = JenaReasoner(config.copy(parameters = mapOf("includeAxiomaticTriples" to true))).getInferredTriples(vocabularyData).toSet()
            assertEquals(inferred + axioms.filter { it in withAxioms }, withAxioms, "${config.reasonerType}: the axiom filter removes nothing else")
        }
    }

    @Test
    fun `timeout uses the injected clock`() {
        val now = AtomicLong()
        val clock = { now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1)) }
        val reasoner = JenaReasoner(ReasonerConfig.rdfs().copy(timeout = Duration.ofMillis(50), materializationThreshold = Long.MAX_VALUE), clock, { it.prepare() }, Semaphore(4))
        val error = assertThrows(IllegalStateException::class.java) { reasoner.reason(chain(300, 300)) }
        assertTrue(error.message!!.contains("timed out"), error.message)
    }

    @Test
    fun `an uninterruptible rule preparation is abandoned at the deadline and abandoned work is capped`() {
        val release = CountDownLatch(1)
        val started = AtomicInteger()
        // Emulates Jena's prepare(): ignores interrupts until released.
        val stuckPrepare: (org.apache.jena.rdf.model.InfModel) -> Unit = {
            started.incrementAndGet()
            while (true) {
                try {
                    if (release.await(10, TimeUnit.SECONDS)) break
                } catch (_: InterruptedException) {
                    // keep going, like uninterruptible preparation
                }
            }
        }
        val cap = 2
        val permits = Semaphore(cap)
        val reasoner = JenaReasoner(ReasonerConfig.rdfs().copy(timeout = Duration.ofMillis(200)), System::nanoTime, stuckPrepare, permits)
        fun liveWorkers() = Thread.getAllStackTraces().keys.count { it.name == "kastor-jena-prepare" && it.isAlive }
        val graph = chain(3, 3)
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(30)) {
                repeat(cap) {
                    val error = assertThrows(IllegalStateException::class.java) { reasoner.getInferredTriples(graph) }
                    assertTrue(error.message!!.contains("timed out"), error.message)
                }
                repeat(5) {
                    val error = assertThrows(IllegalStateException::class.java) { reasoner.getInferredTriples(graph) }
                    assertTrue(error.message!!.contains("too many rule preparations"), error.message)
                    assertTrue(liveWorkers() <= cap, "abandoned preparations must not pile up: ${liveWorkers()}")
                }
            }
            assertEquals(cap, started.get(), "rejected calls must not start more preparations")
        } finally {
            release.countDown()
        }
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            while (permits.availablePermits() < cap) Thread.onSpinWait()
        }
        assertFalse(JenaReasoner(ReasonerConfig.rdfs(), System::nanoTime, { it.prepare() }, permits).getInferredTriples(graph).isEmpty(), "permits are released")
    }

    @Test
    fun `an abandoned preparation releases its permit only after closing its models`() {
        val now = AtomicLong()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val model = java.util.concurrent.atomic.AtomicReference<org.apache.jena.rdf.model.InfModel>()
        val blockingPrepare: (org.apache.jena.rdf.model.InfModel) -> Unit = {
            model.set(it)
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            it.prepare()
        }
        // Records, at the moment the permit is returned, whether the abandoned worker has already closed its models.
        val openAtRelease = java.util.concurrent.CompletableFuture<Boolean>()
        val acquired = java.util.concurrent.atomic.AtomicBoolean()
        val permits = object : Semaphore(1) {
            override fun tryAcquire(timeout: Long, unit: TimeUnit): Boolean =
                super.tryAcquire(timeout, unit).also { if (it) acquired.set(true) }

            override fun release() {
                model.get()?.let { openAtRelease.complete(!it.isClosed) }
                super.release()
            }
        }
        // The injected clock passes the deadline once the preparation holds its permit, so the caller abandons it
        // deterministically instead of waiting.
        val clock = { if (acquired.get()) now.get() + Duration.ofHours(2).toNanos() else now.get() }
        val reasoner = JenaReasoner(ReasonerConfig.rdfs().copy(timeout = Duration.ofHours(1)), clock, blockingPrepare, permits)
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            assertThrows(IllegalStateException::class.java) { reasoner.getInferredTriples(chain(3, 3)) }
        }
        release.countDown()
        assertFalse(openAtRelease.get(10, TimeUnit.SECONDS), "permit released before the models were closed")
    }

    @Test
    fun `materialization threshold fails right after preparation when forward deductions exceed it`() {
        val prepared = AtomicInteger()
        // A forward (`->`) transitive rule: preparation itself builds the whole closure (~20,000 triples for 200 links).
        // (Jena's RDFS reasoner derives subclass transitivity by backward chaining, so its forward deductions stay small.)
        val transitive = ReasonerConfig(
            reasonerType = ReasonerType.CUSTOM,
            customRules = listOf(com.geoknoesis.kastor.rdf.reasoning.CustomRule("trans", "(?a <${ex}next> ?b), (?b <${ex}next> ?c)", "(?a <${ex}next> ?c)")),
            materializationThreshold = 50,
        )
        val reasoner = JenaReasoner(transitive, System::nanoTime, { prepared.incrementAndGet(); it.prepare() }, Semaphore(4))
        val links = turtle(buildString {
            appendLine("@prefix ex: <$ex> .")
            for (i in 0 until 200) appendLine("ex:n$i ex:next ex:n${i + 1} .")
        })
        val error = assertThrows(IllegalArgumentException::class.java) { reasoner.reason(links) }
        assertTrue(error.message!!.contains("materializationThreshold"), error.message)
        // The early check reports the forward-closure lower bound, i.e. it fails before the closure is read.
        assertTrue(error.message!!.contains("forward closure"), "fails before reading the closure: ${error.message}")
        assertEquals(1, prepared.get())
    }
}
