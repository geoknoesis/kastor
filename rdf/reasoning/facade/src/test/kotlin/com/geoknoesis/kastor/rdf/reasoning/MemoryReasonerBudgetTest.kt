package com.geoknoesis.kastor.rdf.reasoning

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.reasoning.providers.MemoryReasoner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** The time budget and the materialization cap are enforced while a round produces triples, on an injected clock. */
class MemoryReasonerBudgetTest {
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")

    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")

    // One round already yields 600 x 600 entailed rdf:type triples (rdfs9), far more than any limit below.
    private val chain = (0 until 600).map {
        RdfTriple(Iri("http://example.org/C0"), subClassOf, Iri("http://example.org/K$it"))
    } + (0 until 600).map { RdfTriple(Iri("http://example.org/i$it"), type, Iri("http://example.org/C0")) }

    private fun ticking(): () -> Long {
        var now = 0L
        return { now += 1_000_000L; now } // every poll advances the clock by one millisecond
    }

    @Test
    fun `the time budget is polled inside a round`() {
        val reasoner = MemoryReasoner(
            ReasonerConfig.rdfs().copy(timeout = Duration.ofMillis(5), materializationThreshold = 5_000),
        )
        // Cap 5000 is exceeded only after the first round completes; the clock expires long before that.
        val error = assertThrows(IllegalStateException::class.java) { reasoner.closure(chain, ticking()) }
        assertTrue(error.message!!.contains("timed out"), error.message)
    }

    @Test
    fun `the cap is enforced with the entailed count it reports`() {
        val reasoner = MemoryReasoner(
            ReasonerConfig.rdfs().copy(timeout = Duration.ofDays(1), materializationThreshold = 100),
        )
        val error = assertThrows(IllegalArgumentException::class.java) { reasoner.closure(chain, ticking()) }
        assertTrue(error.message!!.contains("materializationThreshold (100)"), error.message)
    }

    @Test
    fun `classification keeps insertion order per subject`() {
        val s = Iri("http://example.org/s")
        val classes = (0 until 50).map { Iri("http://example.org/K$it") }
        val graph = com.geoknoesis.kastor.rdf.provider.MemoryGraph(classes.map { RdfTriple(s, type, it) })
        val result = MemoryReasoner(ReasonerConfig.rdfs().copy(enabledRules = emptySet())).classify(graph)
        assertEquals(classes, result.instanceClassifications[s])
    }
}
