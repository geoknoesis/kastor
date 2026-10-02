package com.geoknoesis.kastor.rdf.hermit

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.reasoning.RdfReasoning
import com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig
import com.geoknoesis.kastor.rdf.reasoning.ReasonerRegistry
import com.geoknoesis.kastor.rdf.reasoning.ReasonerType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

class HermitLifecycleTest {

    private val ontology = """
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix ex: <http://example.org/ns#> .
        ex:A a owl:Class .
        ex:B a owl:Class .
        ex:C a owl:Class ; owl:equivalentClass [ a owl:Class ; owl:intersectionOf ( ex:A ex:B ) ] .
        ex:D a owl:Class ; rdfs:subClassOf ex:A .
        ex:i a owl:NamedIndividual , ex:C .
    """.trimIndent()

    @Test
    fun `HermiT is constructible through the registry by type`() {
        val byType = RdfReasoning.reasoner(ReasonerType.HERMIT)
        assertTrue(byType is HermitRdfReasoner)
        assertTrue(RdfReasoning.reasoner(ReasonerConfig(reasonerType = ReasonerType.HERMIT)) is HermitRdfReasoner)
        assertTrue(RdfReasoning.reasoner(ReasonerConfig.owlDl()) is HermitRdfReasoner, "OWL_DL must have a provider")
        assertEquals("hermit", ReasonerRegistry.findProviderForType(ReasonerType.OWL_DL)?.getType())
    }

    @Test
    fun `OWL Micro is rejected with a clear error`() {
        val provider = HermitReasonerProvider()
        assertFalse(provider.isSupported(ReasonerType.OWL_MICRO))
        val error = assertThrows(IllegalArgumentException::class.java) {
            provider.createReasoner(ReasonerConfig(reasonerType = ReasonerType.OWL_MICRO))
        }
        assertTrue(error.message!!.contains("OWL_MICRO"), error.message)
    }

    @Test
    fun `inferred triples exclude asserted axioms, headers, declarations and blank nodes`() {
        val graph = Rdf.parse(ontology, RdfFormat.TURTLE)
        val inferred = HermitRdfReasoner(ReasonerConfig.hermit()).reason(graph).inferredTriples
        val ns = "http://example.org/ns#"
        val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        assertTrue(RdfTriple(Iri(ns + "i"), type, Iri(ns + "A")) in inferred, "$inferred")
        assertTrue(RdfTriple(Iri(ns + "i"), type, Iri(ns + "B")) in inferred, "$inferred")
        assertTrue(inferred.none { it.subject is BlankNode || it.obj is BlankNode }, "no blank nodes: $inferred")
        assertTrue(inferred.none { it.obj == Iri("http://www.w3.org/2002/07/owl#Ontology") }, "no ontology header: $inferred")
        assertTrue(inferred.none { it.obj == Iri("http://www.w3.org/2002/07/owl#Class") }, "no declaration triples: $inferred")
        val asserted = graph.getTriples().toSet()
        assertTrue(inferred.none { it in asserted }, "asserted triples are not inferred")
        assertTrue(inferred.none { it.subject == it.obj }, "no reflexive subclass axioms")
    }

    /** Records the watchdogs a reasoner schedules (see [HermitRdfReasoner.Watchdog] for the shared default). */
    private class RecordingWatchdog {
        val futures = java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.CompletableFuture<Unit>>()
        val schedule: (Long, Long, Runnable) -> java.util.concurrent.Future<*> =
            { _, _, _ -> java.util.concurrent.CompletableFuture<Unit>().also { futures.add(it) } }
    }

    private fun reasoner(timeout: Duration, clock: () -> Long, watchdog: RecordingWatchdog) = HermitRdfReasoner(
        ReasonerConfig.hermit().copy(timeout = timeout),
        { o, c -> org.semanticweb.HermiT.ReasonerFactory().createReasoner(o, c) },
        java.util.concurrent.Semaphore(2),
        java.util.concurrent.ThreadFactory { task -> Thread(task, "kastor-hermit-loader") },
        clock,
        watchdog.schedule,
    )

    @Test
    fun `an exhausted timeout fails and cancels its watchdog`() {
        val graph = Rdf.parse(ontology, RdfFormat.TURTLE)
        // Each look at the clock is one millisecond later: a budget of one millisecond is used up at the first check.
        val now = java.util.concurrent.atomic.AtomicLong()
        val watchdog = RecordingWatchdog()
        val reasoner = reasoner(Duration.ofMillis(1), { now.getAndAdd(1_000_000) }, watchdog)
        val error = assertThrows(IllegalStateException::class.java) { reasoner.reason(graph) }
        assertTrue(error.message!!.contains("timed out"), error.message)
        assertTrue(watchdog.futures.single().isCancelled, "the watchdog is cancelled in all cases")
    }

    @Test
    fun `successful runs cancel their watchdogs`() {
        val graph = Rdf.parse(ontology, RdfFormat.TURTLE)
        val watchdog = RecordingWatchdog()
        repeat(3) { assertTrue(reasoner(Duration.ofHours(1), { 0L }, watchdog).isConsistent(graph)) }
        assertEquals(3, watchdog.futures.size, "one watchdog per call")
        assertTrue(watchdog.futures.all { it.isCancelled }, "every call cancels its watchdog when it ends")
    }
}
