package com.geoknoesis.kastor.rdf.hermit

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.semanticweb.HermiT.Configuration
import org.semanticweb.owlapi.model.OWLOntology
import org.semanticweb.owlapi.reasoner.OWLReasoner
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException
import org.semanticweb.owlapi.reasoner.impl.OWLClassNodeSet
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** [com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig.timeout] is enforced at every stage of a HermiT call. */
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

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val value = assertTimeoutPreemptively(Duration.ofSeconds(25), org.junit.jupiter.api.function.ThrowingSupplier { block() })
        return value to (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun `watchdog keeps interrupting when HermiT drops an interrupt at a task start`() {
        // Warm up OWL API loading so the budget below is spent in the (fake) reasoning task, not in class loading.
        HermitRdfReasoner(ReasonerConfig.hermit()).isConsistent(propertyHeavy(3, 2))
        val interrupts = AtomicInteger()
        val reasoner = HermitRdfReasoner(ReasonerConfig.hermit().copy(timeout = Duration.ofMillis(1_500))) { ontology, _: Configuration ->
            fakeEngine(ontology) { name ->
                when (name) {
                    "interrupt" -> interrupts.incrementAndGet().let { null }
                    // Emulates HermiT resetting the interrupt flag when a new internal task starts:
                    // the first interrupt is lost, only a later one stops the task.
                    "isConsistent" -> {
                        while (interrupts.get() < 2) Thread.sleep(5)
                        throw ReasonerInterruptedException("interrupted")
                    }
                    else -> error("unexpected reasoner call: $name")
                }
            }
        }
        val graph = propertyHeavy(3, 2)
        val (error, elapsed) = timed { assertThrows(IllegalStateException::class.java) { reasoner.isConsistent(graph) } }
        assertTrue(error.message!!.contains("timed out"), error.message)
        assertTrue(error.cause is ReasonerInterruptedException, "timeout must come from the interrupted task: ${error.cause}")
        assertTrue(interrupts.get() >= 2, "interrupt must be re-issued, got ${interrupts.get()}")
        assertTrue(elapsed < 8_000, "took $elapsed ms")
    }

    @Test
    fun `budget is checked on every reasoner call made inside inferred-axiom generators`() {
        val calls = AtomicInteger()
        val reasoner = HermitRdfReasoner(ReasonerConfig.hermit().copy(timeout = Duration.ofMillis(400), includeAxioms = false)) { ontology, _: Configuration ->
            fakeEngine(ontology) { name ->
                when (name) {
                    "interrupt" -> null
                    "isConsistent" -> true
                    // Each call is slow and ignores interrupts; only the per-call budget check can stop the generator.
                    "getTypes", "getSuperClasses", "getSuperObjectProperties", "getSuperDataProperties" -> {
                        calls.incrementAndGet()
                        Thread.sleep(20)
                        OWLClassNodeSet()
                    }
                    else -> error("unexpected reasoner call: $name")
                }
            }
        }
        val graph = propertyHeavy(400, 4)
        val (error, elapsed) = timed { assertThrows(IllegalStateException::class.java) { reasoner.reason(graph) } }
        assertTrue(error.message!!.contains("timed out"), error.message)
        assertTrue(calls.get() < 400, "generator must stop early, made ${calls.get()} calls")
        assertTrue(elapsed < 4_000, "took $elapsed ms")
    }

    @Test
    fun `ontology loading is bounded by the deadline`() {
        val graph = propertyHeavy(1500, 40) // OWL API needs several seconds to load this
        val reasoner = HermitRdfReasoner(ReasonerConfig.hermit().copy(timeout = Duration.ofMillis(300)))
        val (error, elapsed) = timed { assertThrows(IllegalStateException::class.java) { reasoner.reason(graph) } }
        assertTrue(error.message!!.contains("timed out"), error.message)
        assertTrue(elapsed < 2_500, "took $elapsed ms")
    }

    @Test
    fun `an expensive real ontology stops close to the timeout`() {
        val graph = propertyHeavy(500, 40) // loads in well under the budget; full reasoning takes far longer
        val reasoner = HermitRdfReasoner(ReasonerConfig.hermit().copy(timeout = Duration.ofMillis(4_000)))
        val (error, elapsed) = timed { assertThrows(IllegalStateException::class.java) { reasoner.reason(graph) } }
        assertTrue(error.message!!.contains("timed out"), error.message)
        assertTrue(elapsed < 9_000, "took $elapsed ms")
        Thread.sleep(100)
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "kastor-hermit-watchdog" && it.isAlive })
    }
}
