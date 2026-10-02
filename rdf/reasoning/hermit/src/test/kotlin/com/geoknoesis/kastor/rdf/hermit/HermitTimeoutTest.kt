package com.geoknoesis.kastor.rdf.hermit

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.semanticweb.HermiT.Configuration
import org.semanticweb.HermiT.ReasonerFactory
import org.semanticweb.owlapi.model.OWLOntology
import org.semanticweb.owlapi.reasoner.OWLReasoner
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException
import org.semanticweb.owlapi.reasoner.impl.OWLClassNodeSet
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * [com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig.timeout] is enforced at every stage of a HermiT call.
 *
 * Nothing here depends on how fast the host is: time is an injected clock that moves only when a scenario says so,
 * and the watchdog is either run by the test itself ([ManualWatchdog]) or, where the shared scheduler is the subject,
 * gated by a latch.
 */
class HermitTimeoutTest {
    private val header = """
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix ex: <http://example.org/ns#> .
    """.trimIndent()

    /** Many individuals linked through a cyclic sub-property hierarchy with domains: expensive to realise. */
    private fun propertyHeavy(individuals: Int, properties: Int): RdfGraph = Rdf.parse(
        buildString {
            appendLine(header)
            for (p in 0 until properties) appendLine("ex:p$p a owl:ObjectProperty ; rdfs:subPropertyOf ex:p${(p + 1) % properties} ; rdfs:domain ex:A$p .")
            appendLine("ex:p0 a owl:TransitiveProperty .")
            for (i in 0 until individuals) appendLine("ex:i$i a owl:NamedIndividual ; ex:p${i % properties} ex:i${(i + 1) % individuals} .")
        },
        RdfFormat.TURTLE,
    )

    private fun fakeEngine(ontology: OWLOntology, answer: (String) -> Any?): OWLReasoner =
        Proxy.newProxyInstance(OWLReasoner::class.java.classLoader, arrayOf(OWLReasoner::class.java)) { proxy, method, _ ->
            when (method.name) {
                "dispose" -> null
                "getRootOntology" -> ontology
                "toString" -> "fake-reasoner"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> false
                else -> answer(method.name)
            }
        } as OWLReasoner

    /** A watchdog scheduler that only records what it is asked to schedule; the test runs the task itself. */
    private class ManualWatchdog {
        class Scheduled(val delayMillis: Long, val repeatMillis: Long, val task: Runnable) {
            val future = CompletableFuture<Unit>()
        }

        val scheduled = CopyOnWriteArrayList<Scheduled>()
        val schedule: (Long, Long, Runnable) -> Future<*> = { delay, repeat, task -> Scheduled(delay, repeat, task).also { scheduled.add(it) }.future }

        /** One run of the watchdog of the call in progress, as the scheduler would make it after the deadline. */
        fun fire() {
            scheduled.last().task.run()
        }
    }

    private val realEngine = { ontology: OWLOntology, options: Configuration -> ReasonerFactory().createReasoner(ontology, options) }
    private val loaderThreads = ThreadFactory { task -> Thread(task, "kastor-hermit-loader") }

    private fun reasoner(
        timeout: Duration,
        clock: () -> Long,
        watchdog: (Long, Long, Runnable) -> Future<*>,
        config: ReasonerConfig = ReasonerConfig.hermit(),
        threads: ThreadFactory = loaderThreads,
        engines: (OWLOntology, Configuration) -> OWLReasoner,
    ) = HermitRdfReasoner(config.copy(timeout = timeout), engines, Semaphore(4), threads, clock, watchdog)

    private fun assertTimedOut(error: Throwable) {
        assertTrue(error.message!!.contains("timed out"), error.message)
    }

    @Test
    @Timeout(120)
    fun `the watchdog is a repeating task and each of its runs interrupts the engine again`() {
        val watchdog = ManualWatchdog()
        val interrupts = AtomicInteger()
        val reasoner = reasoner(Duration.ofHours(1), clock = { 0L }, watchdog = watchdog.schedule) { ontology, _ ->
            fakeEngine(ontology) { name ->
                when (name) {
                    "interrupt" -> interrupts.incrementAndGet().let { null }
                    // Emulates HermiT resetting the interrupt flag when a new internal task starts:
                    // the first interrupt is lost, only a later one stops the task.
                    "isConsistent" -> {
                        watchdog.fire()
                        assertEquals(1, interrupts.get())
                        watchdog.fire()
                        throw ReasonerInterruptedException("interrupted")
                    }
                    else -> error("unexpected reasoner call: $name")
                }
            }
        }
        val error = assertThrows(IllegalStateException::class.java) { reasoner.isConsistent(propertyHeavy(3, 2)) }
        assertTimedOut(error)
        assertTrue(error.cause is ReasonerInterruptedException, "timeout must come from the interrupted task: ${error.cause}")
        assertEquals(2, interrupts.get(), "every run of the watchdog interrupts the engine")
        val scheduled = watchdog.scheduled.single()
        assertEquals(Duration.ofHours(1).toMillis(), scheduled.delayMillis, "the first run is due at the deadline")
        assertTrue(scheduled.repeatMillis > 0, "the watchdog must repeat: HermiT drops an interrupt at a task start")
        assertTrue(scheduled.future.isCancelled, "the watchdog is cancelled when the call ends")
        // A run that was already due when the call ended must not touch the disposed engine.
        scheduled.task.run()
        assertEquals(2, interrupts.get())
    }

    @Test
    @Timeout(120)
    fun `every call's watchdog runs on the one shared daemon scheduler thread until the reasoner call returns`() {
        val interruptedBy = CopyOnWriteArrayList<Thread>()
        repeat(3) {
            val inReasoner = AtomicBoolean()
            val twoInterrupts = CountDownLatch(2)
            // The shared scheduler, gated so that the watchdog only acts once the reasoner call has begun.
            val gated: (Long, Long, Runnable) -> Future<*> = { _, repeat, task ->
                HermitRdfReasoner.Watchdog.schedule(1, repeat, Runnable { if (inReasoner.get()) task.run() })
            }
            val reasoner = reasoner(Duration.ofHours(1), clock = { 0L }, watchdog = gated) { ontology, _ ->
                fakeEngine(ontology) { name ->
                    when (name) {
                        "interrupt" -> {
                            interruptedBy.add(Thread.currentThread())
                            twoInterrupts.countDown()
                            null
                        }
                        "isConsistent" -> {
                            inReasoner.set(true)
                            twoInterrupts.await() // the first interrupt is "lost": only the re-issued one ends the task
                            throw ReasonerInterruptedException("interrupted")
                        }
                        else -> error("unexpected reasoner call: $name")
                    }
                }
            }
            val error = assertThrows(IllegalStateException::class.java) { reasoner.isConsistent(propertyHeavy(3, 2)) }
            assertTimedOut(error)
            assertTrue(error.cause is ReasonerInterruptedException, "${error.cause}")
        }
        assertTrue(interruptedBy.size >= 6, "two interrupts per call at least, got ${interruptedBy.size}")
        val thread = interruptedBy.first()
        assertTrue(interruptedBy.all { it === thread }, "one scheduler thread serves every call: ${interruptedBy.map { it.name + "@" + System.identityHashCode(it) }.toSet()}")
        assertEquals("kastor-hermit-watchdog", thread.name)
        assertTrue(thread.isDaemon)
    }

    @Test
    @Timeout(120)
    fun `budget is checked on every reasoner call made inside inferred-axiom generators`() {
        val now = AtomicLong()
        val calls = AtomicInteger()
        val watchdog = ManualWatchdog()
        val callCost = Duration.ofMillis(20)
        val timeout = Duration.ofMillis(400)
        val reasoner = reasoner(timeout, now::get, watchdog.schedule, ReasonerConfig.hermit().copy(includeAxioms = false)) { ontology, _ ->
            fakeEngine(ontology) { name ->
                when (name) {
                    "interrupt" -> null
                    "isConsistent" -> true
                    // Each call takes time and ignores interrupts; only the per-call budget check can stop the generator.
                    "getTypes", "getSuperClasses", "getSuperObjectProperties", "getSuperDataProperties" -> {
                        calls.incrementAndGet()
                        now.addAndGet(callCost.toNanos())
                        OWLClassNodeSet()
                    }
                    else -> error("unexpected reasoner call: $name")
                }
            }
        }
        val error = assertThrows(IllegalStateException::class.java) { reasoner.reason(propertyHeavy(400, 4)) }
        assertTimedOut(error)
        assertEquals((timeout.toMillis() / callCost.toMillis()).toInt(), calls.get(), "the generator must stop at the first call after the deadline")
        assertTrue(watchdog.scheduled.single().future.isCancelled)
    }

    @Test
    @Timeout(120)
    fun `a load that outlasts the deadline is abandoned and cleans up when it finishes`() {
        val now = AtomicLong()
        val timeout = Duration.ofSeconds(5)
        val release = CountDownLatch(1)
        val disposed = AtomicInteger()
        val loaders = CopyOnWriteArrayList<Thread>()
        val threads = ThreadFactory { task -> Thread(task, "kastor-hermit-loader").also { loaders.add(it) } }
        val watchdog = ManualWatchdog()
        val reasoner = reasoner(timeout, now::get, watchdog.schedule, threads = threads) { ontology, options ->
            // Engine creation (HermiT preprocessing) outlasts the caller's budget and ignores interrupts until released.
            now.addAndGet(timeout.toNanos())
            while (true) {
                try {
                    release.await()
                    break
                } catch (_: InterruptedException) {
                    // uninterruptible, like HermiT's preprocessing
                }
            }
            val engine = realEngine(ontology, options)
            Proxy.newProxyInstance(engine.javaClass.classLoader, arrayOf(OWLReasoner::class.java)) { _, method, args ->
                if (method.name == "dispose") disposed.incrementAndGet()
                method.invoke(engine, *(args ?: emptyArray()))
            } as OWLReasoner
        }
        try {
            val error = assertThrows(IllegalStateException::class.java) { reasoner.reason(propertyHeavy(3, 2)) }
            assertTimedOut(error)
            assertTrue(loaders.single().isAlive, "the caller returned at the deadline although the load is still running")
            assertEquals(0, disposed.get())
            assertTrue(watchdog.scheduled.single().future.isCancelled)
        } finally {
            release.countDown()
        }
        loaders.single().join()
        assertEquals(1, disposed.get(), "the abandoned load disposes the engine it created")
    }

    @Test
    @Timeout(300)
    fun `a real engine in the middle of an expensive ontology is stopped by the watchdog`() {
        val graph = propertyHeavy(500, 40) // realising it takes HermiT far longer than a watchdog period
        val reasoning = AtomicBoolean()
        val disposed = AtomicInteger()
        val watchdogs = CopyOnWriteArrayList<Future<*>>()
        // The shared scheduler, due as soon as the engine has been asked something: the clock never moves, so only the
        // watchdog (flag plus repeated interrupts) can end the call.
        val gated: (Long, Long, Runnable) -> Future<*> = { _, repeat, task ->
            HermitRdfReasoner.Watchdog.schedule(1, repeat, Runnable { if (reasoning.get()) task.run() }).also { watchdogs.add(it) }
        }
        val reasoner = reasoner(Duration.ofHours(1), clock = { 0L }, watchdog = gated) { ontology, options ->
            val engine = realEngine(ontology, options)
            Proxy.newProxyInstance(engine.javaClass.classLoader, arrayOf(OWLReasoner::class.java)) { _, method, args ->
                when (method.name) {
                    "dispose" -> disposed.incrementAndGet()
                    "interrupt" -> Unit
                    else -> reasoning.set(true)
                }
                try {
                    method.invoke(engine, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as OWLReasoner
        }
        val error = assertThrows(IllegalStateException::class.java) { reasoner.reason(graph) }
        assertTimedOut(error)
        assertEquals(1, disposed.get(), "the engine is disposed by the call that timed out")
        assertTrue(watchdogs.single().isCancelled, "the watchdog leaves the shared scheduler when the call ends")
    }

    private class EngineFailure : RuntimeException("the engine failed for a reason of its own")

    @Test
    @Timeout(120)
    fun `a failure that is not a timeout keeps its cause when the deadline has passed`() {
        val watchdog = ManualWatchdog()
        val reasoner = reasoner(Duration.ofHours(1), clock = { 0L }, watchdog = watchdog.schedule) { ontology, _ ->
            fakeEngine(ontology) { name ->
                when (name) {
                    "interrupt" -> null
                    "isConsistent" -> {
                        watchdog.fire() // the deadline passes while the engine is at work ...
                        throw EngineFailure() // ... and the engine then fails for another reason
                    }
                    else -> error("unexpected reasoner call: $name")
                }
            }
        }
        val error = assertThrows(EngineFailure::class.java) { reasoner.isConsistent(propertyHeavy(3, 2)) }
        assertTrue(
            error.suppressed.any { it is IllegalStateException && it.message!!.contains("timed out") },
            "the passed deadline is reported next to the real cause: ${error.suppressed.toList()}",
        )
    }

    @Test
    @Timeout(120)
    fun `the materialization threshold is reported as itself`() {
        val graph = Rdf.parse(
            "$header\nex:A rdfs:subClassOf ex:B . ex:B rdfs:subClassOf ex:C . ex:i a ex:A . ex:j a ex:A .",
            RdfFormat.TURTLE,
        )
        val watchdog = ManualWatchdog()
        val reasoner = reasoner(Duration.ofHours(1), clock = { 0L }, watchdog = watchdog.schedule,
            config = ReasonerConfig.hermit().copy(materializationThreshold = 1), engines = realEngine)
        val error = assertThrows(IllegalArgumentException::class.java) { reasoner.reason(graph) }
        assertTrue(error.message!!.contains("materializationThreshold"), error.message)
        assertTrue(error.suppressed.isEmpty(), "no deadline had passed: ${error.suppressed.toList()}")
    }

    /** A graph whose triples "take" [costNanos] each to hand out, counting how many the serializer took. */
    private class SlowGraph(private val graph: RdfGraph, private val now: AtomicLong, private val costNanos: Long) : RdfGraph by graph {
        val handedOut = AtomicInteger()
        val reads = AtomicInteger()

        override fun getTriples(): List<RdfTriple> {
            reads.incrementAndGet()
            val triples = graph.getTriples()
            return object : AbstractList<RdfTriple>() {
                override val size: Int get() = triples.size
                override fun get(index: Int): RdfTriple {
                    handedOut.incrementAndGet()
                    now.addAndGet(costNanos)
                    return triples[index]
                }
            }
        }

        override fun getTriplesSequence(): Sequence<RdfTriple> = getTriples().asSequence()
    }

    @Test
    @Timeout(120)
    fun `serializing the input stops at the deadline instead of reading the whole graph`() {
        val now = AtomicLong()
        val base = propertyHeavy(3_000, 4)
        val total = base.size()
        val graph = SlowGraph(base, now, Duration.ofMillis(1).toNanos())
        val created = AtomicInteger()
        val watchdog = ManualWatchdog()
        val reasoner = reasoner(Duration.ofSeconds(1), now::get, watchdog.schedule) { ontology, options ->
            created.incrementAndGet()
            realEngine(ontology, options)
        }
        val error = assertThrows(IllegalStateException::class.java) { reasoner.isConsistent(graph) }
        assertTimedOut(error)
        // 1 ms per triple and a budget of 1 s: the deadline passes after 1000 triples; the check comes every 256.
        assertTrue(graph.handedOut.get() in 1_000..1_256, "read ${graph.handedOut.get()} of $total triples")
        assertEquals(0, created.get(), "nothing is loaded after the deadline")
        assertTrue(watchdog.scheduled.single().future.isCancelled)
    }

    @Test
    @Timeout(120)
    fun `the input is not serialized at all when the deadline has already passed`() {
        val now = AtomicLong()
        val base = propertyHeavy(3, 2)
        val timeout = Duration.ofSeconds(1)
        val slow = SlowGraph(base, now, 0)
        val graph = object : RdfGraph by slow {
            // Admission asks for the size, which uses up the whole budget here.
            override fun size(): Int {
                now.addAndGet(timeout.toNanos())
                return base.size()
            }
        }
        val reasoner = reasoner(timeout, now::get, ManualWatchdog().schedule, engines = realEngine)
        val error = assertThrows(IllegalStateException::class.java) { reasoner.isConsistent(graph) }
        assertTimedOut(error)
        assertEquals(0, slow.reads.get(), "the graph must not be read after the deadline")
    }

    @Test
    @Timeout(120)
    fun `a call within its budget is unaffected by the checks`() {
        val now = AtomicLong()
        val watchdog = ManualWatchdog()
        val graph = SlowGraph(propertyHeavy(3, 2), now, Duration.ofMillis(1).toNanos())
        val reasoner = reasoner(Duration.ofHours(1), now::get, watchdog.schedule, engines = realEngine)
        assertTrue(reasoner.isConsistent(graph))
        assertFalse(reasoner.reason(graph).inferredTriples.isEmpty())
        assertEquals(2, watchdog.scheduled.size)
        assertTrue(watchdog.scheduled.all { it.future.isCancelled })
    }
}
